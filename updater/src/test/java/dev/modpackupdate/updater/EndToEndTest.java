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
    @CsvSource({"fabric-loader,1.20.1,0.16.10,false,false,0", "neoforge,1.21.1,21.1.266,false,false,0",
            "forge,1.20.1,47.4.28,false,false,0", "quilt-loader,1.21.1,0.30.2,false,false,0",
            "fabric-loader,1.20.1,0.16.10,true,false,0", "neoforge,1.21.1,21.1.266,true,false,0",
            "fabric-loader,1.20.1,0.16.10,false,true,0", "neoforge,1.21.1,21.1.266,false,true,0",
            "fabric-loader,1.20.1,0.16.10,false,true,1", "neoforge,1.21.1,21.1.266,false,true,1",
            "forge,1.20.1,47.4.28,false,true,1", "quilt-loader,1.21.1,0.30.2,false,true,1",
            "fabric-loader,1.20.1,0.16.10,false,false,2", "fabric-loader,1.20.1,0.16.10,false,false,3",
            "fabric-loader,1.20.1,0.16.10,false,false,4"})
    void independentUpdaterWaitsForLockedGameJarThenUpdatesItself(String loaderType, String minecraft, String loaderVersion,
                                                                 boolean fallback, boolean compactRenamed, int localMode) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        root = root.toRealPath();
        Path original = root.resolve(compactRenamed ? "mods/renamed-local-updater.jar" : "mods/ModpackUpdate.jar");
        Files.createDirectories(original.getParent());
        Files.write(original, jar("1", loaderType));
        String oldHash = FilesSupport.hash(original);
        Path payload = root.resolve("new.jar");
        Files.write(payload, jar("2", loaderType));
        String newHash = FilesSupport.hash(payload);
        Map<String, byte[]> responses = new HashMap<>();
        var metadataRequests = new java.util.concurrent.atomic.AtomicInteger();
        var payloadRequests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/mod")) payloadRequests.incrementAndGet();
            else metadataRequests.incrementAndGet();
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
        if (compactRenamed) {
            Path manifest = root.resolve("manifest.json");
            FilesSupport.write(manifest, release);
            responses.put("/manifest", Files.readAllBytes(manifest));
            var entry = new Models.Release(2, "test", 2, "1.1.0", null, null, null, null,
                    new Models.Artifact(base + "/manifest", FilesSupport.hash(manifest), Files.size(manifest)));
            responses.put("/latest", FilesSupport.JSON.toJson(entry).getBytes(StandardCharsets.UTF_8));
        }
        responses.put("/index", Files.readAllBytes(indexFile));
        responses.put("/mod", Files.readAllBytes(payload));
        Models.Config config = new Models.Config();
        config.enable = localMode == 0;
        config.silentUpdate = true;
        config.packId = "test";
        config.updateServiceUrl = base + (fallback ? "/unavailable" : "/latest");
        if (fallback) {
            config.fullPackUrl = base + "/pack";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("modrinth.index.json"));
                zip.write(Files.readAllBytes(indexFile));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("overrides/mods/ModpackUpdate.jar"));
                zip.write(Files.readAllBytes(payload));
                zip.closeEntry();
            }
            responses.put("/pack", bytes.toByteArray());
        }
        Path localPackage = root.resolve("ModpackUpdate/inbox/01-update.mrpack");
        if (localMode > 0) {
            Files.createDirectories(localPackage.getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(localPackage))) {
                zip.putNextEntry(new ZipEntry("modrinth.index.json"));
                zip.write(Files.readAllBytes(indexFile)); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("modpackupdate.release.json"));
                zip.write(FilesSupport.JSON.toJson(new Models.PackRelease(1, "test", 2, "1.1.0")).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                if (localMode == 1 || localMode == 3) {
                    zip.putNextEntry(new ZipEntry("overrides/mods/ModpackUpdate.jar"));
                    zip.write(Files.readAllBytes(payload)); zip.closeEntry();
                }
            }
            if (loaderType.equals("forge")) Files.copy(localPackage, localPackage.resolveSibling("99-unselected.mrpack"));
            if (localMode == 4) responses.remove("/mod");
        }
        FilesSupport.write(root.resolve("ModpackUpdate/config/config.json"), config);
        Models.Installed installed = new Models.Installed();
        installed.packId = "test";
        installed.releaseId = 1;
        installed.version = "1.0.0";
        if (!compactRenamed) installed.files.put("mods/ModpackUpdate.jar", new Models.Baseline(oldHash, oldHash, false));
        if (compactRenamed) InstalledStore.write(root, installed);
        else FilesSupport.write(root.resolve("ModpackUpdate/config/installed.json"), installed);
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
            updater = new ProcessBuilder(java, "-Dtest.skip-local=" + (localMode == 3), "-cp", classpath, AutoUpdater.class.getName(),
                    "check", root.toString(), Long.toString(game.pid()), signal.toString(), minecraft, loaderVersion, loaderType)
                    .redirectErrorStream(true).redirectOutput(root.resolve("updater.log").toFile()).start();
            assertTrue(updater.waitFor(25, TimeUnit.SECONDS), "Updater must finish after the simulated game exits");
            if (localMode == 3 || localMode == 4) {
                assertEquals(localMode == 3 ? 0 : 1, updater.exitValue(), Files.readString(root.resolve("updater.log")));
                assertTrue(Files.exists(localPackage));
                assertEquals(oldHash, FilesSupport.hash(original));
                assertEquals(1, InstalledStore.read(root).releaseId);
                assertFalse(new Installer(root).pending());
                assertEquals(0, metadataRequests.get());
                if (localMode == 3) {
                    assertTrue(game.isAlive());
                    assertFalse(Files.exists(signal));
                    assertEquals(0, payloadRequests.get());
                }
                return;
            }
            assertEquals(0, updater.exitValue(), Files.readString(root.resolve("updater.log")));
            assertEquals(0, game.exitValue(), Files.readString(root.resolve("game.log")));
            assertEquals(newHash, FilesSupport.hash(root.resolve("mods/ModpackUpdate.jar")));
            Models.Installed next = InstalledStore.read(root);
            assertEquals(fallback ? 1 : 2, next.releaseId);
            assertEquals(fallback || localMode > 0, next.fullPackFallback);
            assertEquals(fallback || localMode > 0, Files.exists(root.resolve("manual-confirm")), "Fallback and local imports must require confirmation as appropriate");
            if (localMode > 0) {
                assertFalse(Files.exists(localPackage));
                assertNotNull(next.localImport);
                assertEquals(next.localImport.sha512(), FilesSupport.hash(root.resolve(next.localImport.archivedPath())));
                assertEquals(0, metadataRequests.get());
                assertEquals(localMode == 1 ? 0 : 1, payloadRequests.get());
                if (loaderType.equals("forge")) assertTrue(Files.exists(localPackage.resolveSibling("99-unselected.mrpack")));
            }
            if (compactRenamed) {
                assertFalse(Files.exists(original));
                assertEquals(oldHash, FilesSupport.hash(original.resolveSibling(original.getFileName() + ".disable")));
            } else assertEquals(oldHash, FilesSupport.hash(root.resolve("ModpackUpdate/1.0.0/" + next.transactionId + "/mods/ModpackUpdate.jar")));
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
            if (loaderType.equals("neoforge") || loaderType.equals("forge")) {
                path = loaderType.equals("forge") ? "META-INF/mods.toml" : "META-INF/neoforge.mods.toml";
                metadata = "[[mods]]\nmodId=\"modpackupdate\"\nversion=\"" + version + "\"\n";
            }
            if (loaderType.equals("quilt-loader")) {
                path = "quilt.mod.json";
                metadata = "{\"schema_version\":1,\"quilt_loader\":{\"id\":\"modpackupdate\",\"version\":\"" + version + "\"}}";
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
        private static Path instance;
        private static boolean repair;
        public static void main(String[] args) {
            instance = Path.of(args[1]);
            repair = args[0].equals("repair");
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
                else if (component instanceof javax.swing.JButton button && "更新并退出游戏".equals(button.getText()) && !Boolean.getBoolean("test.skip-local")) {
                    try { Files.writeString(instance.resolve("manual-confirm"), "confirmed"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    button.doClick();
                }
                else if (component instanceof javax.swing.JButton button && "跳过并继续游戏".equals(button.getText()) && Boolean.getBoolean("test.skip-local")) button.doClick();
                else if (component instanceof javax.swing.JButton button && "检查所选更新包".equals(button.getText())) button.doClick();
                else if (component instanceof javax.swing.JButton button && "开始安装".equals(button.getText())) button.doClick();
                else if (repair && component instanceof javax.swing.JButton button && "全选".equals(button.getText())) button.doClick();
                else if (component instanceof Container child) dismiss(child);
            }
        }
    }
}
