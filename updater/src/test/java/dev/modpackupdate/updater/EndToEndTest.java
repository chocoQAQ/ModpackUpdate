package dev.modpackupdate.updater;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class EndToEndTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"fabric-loader,1.20.1,0.16.10", "neoforge,1.21.1,21.1.266"})
    void independentUpdaterWaitsForLockedGameJarThenUpdatesItself(String loaderType, String minecraft, String loaderVersion) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        root = root.toRealPath();
        Path original = root.resolve("mods/ModpackUpdate.jar");
        Files.createDirectories(original.getParent());
        Files.write(original, jar("1", loaderType));
        String oldHash = FilesSupport.hash(original);
        Path payload = root.resolve("new.jar");
        Files.write(payload, jar("2", loaderType));
        String newHash = FilesSupport.hash(payload);
        Map<String, byte[]> responses = new HashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = responses.get(exchange.getRequestURI().getPath());
            if (body == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String index = """
                {"formatVersion":1,"game":"minecraft","versionId":"1.1.0","name":"Test",
                 "dependencies":{"minecraft":"%s","%s":"%s"},
                 "files":[{"path":"mods/ModpackUpdate.jar","hashes":{"sha512":"%s"},
                           "fileSize":%d,"downloads":["%s/mod"]}]}
                """.formatted(minecraft, loaderType, loaderVersion, newHash, Files.size(payload), base);
        Path indexFile = root.resolve("index.json");
        Files.writeString(indexFile, index);
        Models.Artifact indexArtifact = new Models.Artifact(base + "/index", FilesSupport.hash(indexFile), Files.size(indexFile));
        Models.Release release = new Models.Release(1, "test", 2, "1.1.0", "", indexArtifact,
                new Models.Overrides(false, null, List.of()), indexArtifact);
        responses.put("/latest", FilesSupport.JSON.toJson(release).getBytes(StandardCharsets.UTF_8));
        responses.put("/index", Files.readAllBytes(indexFile));
        responses.put("/mod", Files.readAllBytes(payload));
        Models.Config config = new Models.Config();
        config.enable = true;
        config.silentUpdate = true;
        config.packId = "test";
        config.updateServiceUrl = base + "/latest";
        FilesSupport.write(root.resolve("ModpackUpdate/config/config.json"), config);
        Models.Installed installed = new Models.Installed();
        installed.packId = "test";
        installed.releaseId = 1;
        installed.version = "1.0.0";
        installed.files.put("mods/ModpackUpdate.jar", new Models.Baseline(oldHash, oldHash, false));
        FilesSupport.write(root.resolve("ModpackUpdate/config/installed.json"), installed);
        Path signal = root.resolve("ModpackUpdate/runtime/handoff-test");
        Files.createDirectories(signal.getParent());
        String classpath = Path.of(EndToEndTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + System.getProperty("updater.jar");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process game = new ProcessBuilder(java, "-cp", classpath, HoldingGame.class.getName(),
                original.toString(), signal.toString()).redirectErrorStream(true).redirectOutput(root.resolve("game.log").toFile()).start();
        Process updater = null;
        server.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(root.resolve("game-ready")) && System.nanoTime() < deadline) Thread.sleep(30);
            assertTrue(Files.exists(root.resolve("game-ready")));
            updater = new ProcessBuilder(java, "-cp", classpath, AutoUpdater.class.getName(),
                    "check", root.toString(), Long.toString(game.pid()), signal.toString(), minecraft, loaderVersion, loaderType)
                    .redirectErrorStream(true).redirectOutput(root.resolve("updater.log").toFile()).start();
            assertTrue(updater.waitFor(25, TimeUnit.SECONDS), "Updater must finish after the simulated game exits");
            assertEquals(0, updater.exitValue(), Files.readString(root.resolve("updater.log")));
            assertEquals(0, game.exitValue(), Files.readString(root.resolve("game.log")));
            assertEquals(newHash, FilesSupport.hash(original));
            Models.Installed next = FilesSupport.read(root.resolve("ModpackUpdate/config/installed.json"), Models.Installed.class);
            assertEquals(2, next.releaseId);
            assertEquals(oldHash, FilesSupport.hash(root.resolve("ModpackUpdate/1.0.0/" + next.transactionId + "/mods/ModpackUpdate.jar")));
        } finally {
            game.destroyForcibly();
            if (updater != null) updater.destroyForcibly();
            server.stop(0);
        }
    }
    private byte[] jar(String version, String loaderType) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            String metadata = "{\"schemaVersion\":1,\"id\":\"modpackupdate\",\"version\":\"" + version + "\"}";
            String path = "fabric.mod.json";
            if (loaderType.equals("neoforge")) {
                path = "META-INF/neoforge.mods.toml";
                metadata = "[[mods]]\nmodId=\"modpackupdate\"\nversion=\"" + version + "\"\n";
            }
            zip.putNextEntry(new ZipEntry(path));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
    public static class HoldingGame {
        public static void main(String[] args) throws Exception {
            Path original = Path.of(args[0]);
            String before = FilesSupport.hash(original);
            try (ZipFile ignored = new ZipFile(original.toFile())) {
                Files.writeString(original.getParent().getParent().resolve("game-ready"), "ready");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(Path.of(args[1])) && System.nanoTime() < deadline) Thread.sleep(30);
                if (!Files.exists(Path.of(args[1]))) throw new IllegalStateException("Missing handoff");
                Thread.sleep(500);
                if (!FilesSupport.hash(original).equals(before)) throw new IllegalStateException("Jar changed before game exit");
            }
        }
    }
    public static class AutoUpdater {
        public static void main(String[] args) {
            new Timer(100, event -> {
                for (Window window : Window.getWindows()) {
                    if (window.isShowing()) dismiss(window);
                }
            }).start();
            Main.main(args);
        }
        private static void dismiss(Container container) {
            for (Component component : container.getComponents()) {
                if (component instanceof JOptionPane pane) pane.setValue(JOptionPane.OK_OPTION);
                else if (component instanceof javax.swing.JButton button && "关闭".equals(button.getText())) button.doClick();
                else if (component instanceof Container child) dismiss(child);
            }
        }
    }
}
