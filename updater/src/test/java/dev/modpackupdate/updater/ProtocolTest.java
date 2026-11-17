package dev.modpackupdate.updater;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @TempDir Path root;
    private static String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content));
    }
    private byte[] archive(Map<String, byte[]> contents) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private Models.Artifact artifact(String url, byte[] content) throws Exception {
        return new Models.Artifact(url, hash(content), content.length);
    }

    @Test void fullPackFallbackRespectsClientLayerAndEnvironment() throws Exception {
        byte[] override = "client setting".getBytes(StandardCharsets.UTF_8);
        String indexText = """
                {"formatVersion":1,"game":"minecraft","versionId":"1.2.0","name":"Test",
                 "dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.10"},
                 "files":[
                   {"path":"mods/server.jar","env":{"client":"unsupported","server":"required"}},
                   {"path":"mods/optional.jar","env":{"client":"optional","server":"unsupported"},
                    "hashes":{"sha512":"%s"},"fileSize":1,"downloads":["https://example.com/mod.jar"]}
                 ]}
                """.formatted("a".repeat(128));
        byte[] index = indexText.getBytes(StandardCharsets.UTF_8);
        byte[] pack = archive(Map.of("modrinth.index.json", index,
                "overrides/config/test.json", "base".getBytes(StandardCharsets.UTF_8),
                "client-overrides/config/test.json", override));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] packDownloads = {0};
        server.createContext("/pack", exchange -> {
            packDownloads[0]++;
            exchange.sendResponseHeaders(200, pack.length);
            exchange.getResponseBody().write(pack);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Models.Release release = new Models.Release(1, "test", 2, "1.2.0", "公告",
                    artifact(base + "/missing-index", index),
                    new Models.Overrides(true, artifact(base + "/missing-overrides", override),
                            List.of(new Models.OverrideFile("config/test.json", hash(override), override.length))),
                    artifact(base + "/pack", pack));
            Protocol.validateRelease(release, "test");
            Protocol protocol = new Protocol(new Downloads(2), root.resolve("ModpackUpdate/downloads"));
            var targets = protocol.targets(release, root, "1.20.1", "0.16.10");
            assertFalse(targets.containsKey("mods/server.jar"));
            assertTrue(targets.get("mods/optional.jar").optional());
            assertEquals("client setting", Files.readString(protocol.stage(targets.get("config/test.json"), release)));
            assertEquals(1, packDownloads[0]);
            assertThrows(Exception.class, () -> protocol.targets(release, root, "1.21.1", "0.16.10"));
        } finally { server.stop(0); }
    }

    @Test void helperExitsWithoutUiWhenDisabledOrOffline() throws Exception {
        Path runtime = root.resolve("ModpackUpdate/runtime");
        Files.createDirectories(runtime);
        assertEquals(0, runCheck(root));
        Path configPath = root.resolve("ModpackUpdate/config/config.json");
        assertTrue(Files.isRegularFile(configPath));
        Models.Config config = FilesSupport.read(configPath, Models.Config.class);
        config.enable = true;
        config.packId = "test";
        config.timeoutSeconds = 2;
        config.updateServiceUrl = "http://127.0.0.1:1/unreachable";
        FilesSupport.write(configPath, config);
        assertEquals(1, runCheck(root));
        assertFalse(Files.exists(runtime.resolve("handoff-test")));
    }

    @Test void unchangedReleaseDoesNotFetchIndex() throws Exception {
        byte[] unused = "unused".getBytes(StandardCharsets.UTF_8);
        Models.Release release = new Models.Release(1, "test", 1, "1.0.0", "",
                artifact("https://example.invalid/index", unused), new Models.Overrides(false, null, List.of()),
                artifact("https://example.invalid/pack", unused));
        byte[] body = FilesSupport.JSON.toJson(release).getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Files.createDirectories(root.resolve("ModpackUpdate/runtime"));
            Models.Config config = new Models.Config();
            config.enable = true;
            config.packId = "test";
            config.updateServiceUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/latest";
            FilesSupport.write(root.resolve("ModpackUpdate/config/config.json"), config);
            Models.Installed installed = new Models.Installed();
            installed.packId = "test";
            installed.releaseId = 1;
            installed.version = "1.0.0";
            FilesSupport.write(root.resolve("ModpackUpdate/config/installed.json"), installed);
            assertEquals(0, runCheck(root));
        } finally { server.stop(0); }
    }

    private int runCheck(Path root) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Djava.awt.headless=true", "-jar", System.getProperty("updater.jar"),
                "check", root.toString(), Long.toString(ProcessHandle.current().pid()),
                root.resolve("ModpackUpdate/runtime/handoff-test").toString(), "1.20.1", "0.16.10")
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Helper must return promptly");
            if (process.exitValue() != 0) System.out.println(Files.readString(root.resolve("child.log")));
            return process.exitValue();
        } finally { process.destroyForcibly(); }
    }
}
