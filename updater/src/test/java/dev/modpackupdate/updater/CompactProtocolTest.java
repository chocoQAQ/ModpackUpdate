package dev.modpackupdate.updater;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompactProtocolTest {
    @TempDir Path root;
    private Models.Artifact artifact(String url, byte[] bytes) throws Exception {
        return new Models.Artifact(url, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes)), bytes.length);
    }
    private byte[] json(Object value) { return FilesSupport.JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }

    @Test void unchangedReleaseFetchesOnlyEntryAndNewReleaseLoadsVerifiedDetailsOnDemand() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        byte[] index = """
                {"formatVersion":1,"game":"minecraft","versionId":"2.0","name":"Test",
                 "dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.10"},"files":[]}
                """.getBytes(StandardCharsets.UTF_8);
        var detail = new Models.Release(1, "pack", 2, "2.0", "announcement", artifact(base + "/index", index),
                new Models.Overrides(false, null, List.of()), artifact(base + "/pack", new byte[]{1}));
        byte[] manifest = json(detail);
        var entry = new Models.Release(2, "pack", 2, "2.0", null, null, null, null, artifact(base + "/manifest", manifest));
        var requests = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        var responses = Map.of("/latest", json(entry), "/manifest", manifest, "/index", index);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            byte[] body = responses.getOrDefault(path, new byte[]{0});
            exchange.sendResponseHeaders(responses.containsKey(path) ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var protocol = new Protocol(new Downloads(2), root.resolve("ModpackUpdate/downloads"));
            var config = new Models.Config();
            config.packId = "pack";
            config.updateServiceUrl = base + "/latest";
            config.fullPackUrl = base + "/pack";
            var installed = new Models.Installed();
            installed.releaseId = 2;
            installed.version = "2.0";
            assertNull(protocol.findUpdate(config, installed, root, "1.20.1", "0.16.10", "fabric-loader"));
            assertEquals(java.util.Set.of("/latest"), requests.keySet());
            installed.releaseId = 1;
            installed.version = "1.0";
            var found = protocol.findUpdate(config, installed, root, "1.20.1", "0.16.10", "fabric-loader");
            assertNotNull(found);
            assertFalse(requests.containsKey("/manifest"));
            assertEquals("announcement", protocol.details(found).announcement());
            assertFalse(requests.containsKey("/index"));
            assertTrue(protocol.targets(found, root, "1.20.1", "0.16.10").isEmpty());
            assertEquals(1, requests.get("/manifest").get());
            assertEquals(1, requests.get("/index").get());
            assertFalse(requests.containsKey("/pack"));
            // The authoring command must consume the same v2 entry and package both state files.
            Path instance = root.resolve("seed-instance");
            Files.createDirectories(instance);
            Process seed = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", System.getProperty("updater.jar"), "seed", instance.toString(), base + "/latest",
                    "pack", "1.20.1", "0.16.10").redirectErrorStream(true).redirectOutput(root.resolve("seed.log").toFile()).start();
            try {
                assertTrue(seed.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, seed.exitValue(), Files.readString(root.resolve("seed.log")));
                assertEquals(2, InstalledStore.read(instance).schemaVersion);
                assertEquals(2, InstalledStore.read(instance).releaseId);
            } finally { seed.destroyForcibly(); }
        } finally { server.stop(0); }
    }

    @Test void rejectsMismatchedOrRecursiveManifestEvenWhenHashIsValid() throws Exception {
        Path cache = root.resolve("ModpackUpdate/downloads");
        Files.createDirectories(cache);
        var unused = new Models.Artifact("https://example.com/file", "a".repeat(128), 1);
        for (var invalid : List.of(
                new Models.Release(1, "pack", 9, "9.0", "", unused, new Models.Overrides(false, null, List.of()), unused),
                new Models.Release(2, "pack", 2, "2.0", null, null, null, null, unused))) {
            byte[] bytes = json(invalid);
            var artifact = artifact("http://127.0.0.1:1/manifest", bytes);
            Files.write(cache.resolve(artifact.sha512()), bytes);
            var entry = new Models.Release(2, "pack", 2, "2.0", null, null, null, null, artifact);
            assertThrows(Exception.class, () -> new Protocol(new Downloads(2), cache).details(entry));
        }
    }

    @Test void rejectsBulkyOrInvalidVersionEntry() throws Exception {
        var huge = new Models.Artifact("https://example.com/manifest", "a".repeat(128), 16 * 1024 * 1024 + 1);
        assertThrows(Exception.class, () -> Protocol.validateRelease(new Models.Release(2, "pack", 2, "2.0",
                null, null, null, null, huge), "pack"));
        var valid = new Models.Artifact("https://example.com/manifest", "a".repeat(128), 100);
        assertThrows(Exception.class, () -> Protocol.validateRelease(new Models.Release(2, "pack", -1, "2.0",
                null, null, null, null, valid), "pack"));
        assertThrows(Exception.class, () -> Protocol.validateRelease(new Models.Release(2, "pack", 2, "../bad",
                null, null, null, null, valid), "pack"));
        assertThrows(Exception.class, () -> Protocol.validateRelease(new Models.Release(2, "pack", 2, "2.0",
                "move announcement to manifest", null, null, null, valid), "pack"));
    }
}
