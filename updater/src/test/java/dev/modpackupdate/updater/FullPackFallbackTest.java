package dev.modpackupdate.updater;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class FullPackFallbackTest {
    @TempDir Path root;
    private static final String INDEX = """
        {"formatVersion":1,"game":"minecraft","versionId":"2.0","name":"Test",
         "dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.10"},"files":[]}
        """;

    @Test void fallbackUsesClientOverlayPreservesSettingsAndDoesNotInventReleaseId() throws Exception {
        try (Fixture f = new Fixture(null, Map.of("modrinth.index.json", INDEX,
                "overrides/config/test.txt", "base", "client-overrides/config/test.txt", "client",
                "overrides/options.txt", "author options", "server-overrides/server.txt", "server"))) {
            Protocol protocol = f.protocol();
            Models.Release release = f.check(protocol);
            assertEquals(41, release.releaseId());
            assertEquals("2.0", release.version());
            assertTrue(protocol.usingFallback());
            assertTrue(protocol.requiresConfirmation());
            assertFalse(protocol.sequenceKnown());
            var targets = protocol.targets(release, root, "1.20.1", "0.16.10");
            assertEquals(1, targets.size());
            assertEquals("client", Files.readString(protocol.stage(targets.get("config/test.txt"), release)));
            assertEquals(1, f.packRequests.get());
            f.installed.version = "2.0";
            assertNull(f.check(f.protocol()));
            assertEquals(2, f.packRequests.get());
            assertFalse(Files.exists(root.resolve("config/test.txt")));
        }
    }

    @Test void numberedPackSharesTheServiceSequenceAndRejectsDowngrades() throws Exception {
        String metadata = FilesSupport.JSON.toJson(new Models.PackRelease(1, "test", 42, "2.0"));
        try (Fixture f = new Fixture(null, Map.of("modrinth.index.json", INDEX, "modpackupdate.release.json", metadata))) {
            Protocol protocol = f.protocol();
            assertEquals(42, f.check(protocol).releaseId());
            assertTrue(protocol.sequenceKnown());
            assertFalse(protocol.requiresConfirmation());
            f.installed.releaseId = 43;
            f.installed.version = "3.0";
            f.installed.fullPackFallback = true;
            f.installed.fullPackSequenceKnown = true;
            assertNull(f.check(f.protocol()));
        }
        var artifact = new Models.Artifact("https://example.invalid/unused", "a".repeat(128), 1);
        var release = new Models.Release(1, "test", 42, "2.0", "", artifact,
                new Models.Overrides(false, null, List.of()), artifact);
        try (Fixture f = new Fixture(FilesSupport.JSON.toJson(release), Map.of())) {
            f.installed.releaseId = 43;
            f.installed.version = "3.0";
            f.installed.fullPackFallback = true;
            f.installed.fullPackSequenceKnown = true;
            assertNull(f.check(f.protocol()));
            // A previous unnumbered fallback cannot establish ordering: never accept silently.
            f.installed.releaseId = 41;
            f.installed.fullPackSequenceKnown = false;
            Protocol protocol = f.protocol();
            assertNotNull(f.check(protocol));
            assertTrue(protocol.requiresConfirmation());
        }
    }

    @Test void fallbackMetadataMustMatchPackAndIndexAndCannotBeIgnoredWhenInvalid() throws Exception {
        for (var metadata : List.of(new Models.PackRelease(1, "other", 42, "2.0"),
                new Models.PackRelease(1, "test", 42, "wrong"), new Models.PackRelease(1, "test", -1, "2.0"))) {
            try (Fixture f = new Fixture(null, Map.of("modrinth.index.json", INDEX,
                    "modpackupdate.release.json", FilesSupport.JSON.toJson(metadata)))) {
                assertThrows(Exception.class, () -> f.check(f.protocol()));
            }
        }
    }

    @Test void explicitRepairReopensTheSameReleaseButDoesNotAcceptAnOlderNumber() throws Exception {
        var artifact = new Models.Artifact("https://example.invalid/unused", "a".repeat(128), 1);
        var release = new Models.Release(1, "test", 41, "1.0", "", artifact,
                new Models.Overrides(false, null, List.of()), artifact);
        try (Fixture f = new Fixture(FilesSupport.JSON.toJson(release), Map.of())) {
            assertNull(f.check(f.protocol()));
            assertNotNull(f.protocol().findUpdate(f.config, f.installed, root, "1.20.1", "0.16.10", "fabric-loader", true));
            f.installed.releaseId = 42;
            assertNull(f.protocol().findUpdate(f.config, f.installed, root, "1.20.1", "0.16.10", "fabric-loader", true));
        }
    }

    @Test void availableManifestNeverFetchesFallbackEvenWhenNoUpdateOrValidationFails() throws Exception {
        var artifact = new Models.Artifact("https://example.invalid/unused", "a".repeat(128), 1);
        var release = new Models.Release(1, "test", 41, "1.0", "", artifact,
                new Models.Overrides(false, null, List.of()), artifact);
        for (String body : List.of(FilesSupport.JSON.toJson(release), "not-json", FilesSupport.JSON.toJson(release).replace("test", "other"))) {
            try (Fixture f = new Fixture(body, Map.of("modrinth.index.json", INDEX))) {
                if (body.equals(FilesSupport.JSON.toJson(release))) assertNull(f.check(f.protocol()));
                else assertThrows(Exception.class, () -> f.check(f.protocol()));
                assertEquals(0, f.packRequests.get());
            }
        }
    }

    @Test void restoredServiceDoesNotReinstallSameFallbackVersion() throws Exception {
        var artifact = new Models.Artifact("https://example.invalid/unused", "a".repeat(128), 1);
        var release = new Models.Release(1, "test", 42, "2.0", "", artifact,
                new Models.Overrides(false, null, List.of()), artifact);
        try (Fixture f = new Fixture(FilesSupport.JSON.toJson(release), Map.of())) {
            f.installed.version = "2.0";
            f.installed.fullPackFallback = true;
            assertNull(f.check(f.protocol()));
            assertEquals(0, f.packRequests.get());
        }
    }

    @Test void invalidPackPathsAndPlatformFailBeforeInstallation() throws Exception {
        for (Map<String, String> contents : List.of(Map.of("modrinth.index.json", INDEX, "overrides/../escape", "bad"),
                Map.of("modrinth.index.json", INDEX.replace("1.20.1", "1.21.1")),
                Map.of("modrinth.index.json", INDEX, "overrides/config/A", "a", "client-overrides/config/a", "b"),
                Map.of("modrinth.index.json", INDEX, "overrides/config//test.txt", "bad"),
                Map.of("modrinth.index.json", INDEX, "overrides/config/tree", "file", "client-overrides/config/tree/child", "child"),
                Map.of("other", "not a modpack"))) {
            try (Fixture f = new Fixture(null, contents)) {
                assertThrows(Exception.class, () -> f.check(f.protocol()));
                assertFalse(Files.exists(root.resolve("config")));
            }
        }
    }

    @Test void missingFallbackKeepsOriginalFailureAndFailedDownloadIsNotReused() throws Exception {
        try (Fixture f = new Fixture(null, Map.of("modrinth.index.json", INDEX))) {
            f.config.fullPackUrl = "";
            assertThrows(Exception.class, () -> f.check(f.protocol()));
            assertEquals(0, f.packRequests.get());
            f.config.fullPackUrl = f.base + "/pack";
            assertNotNull(f.check(f.protocol()));
            f.config.fullPackUrl = f.base + "/missing";
            assertThrows(Exception.class, () -> f.check(f.protocol()));
            assertFalse(Files.exists(root.resolve("ModpackUpdate/downloads/fallback.mrpack.part")));
        }
    }

    private final class Fixture implements AutoCloseable {
        final HttpServer server;
        final String base;
        final Models.Config config = new Models.Config();
        final Models.Installed installed = new Models.Installed();
        final AtomicInteger packRequests = new AtomicInteger();
        Fixture(String manifest, Map<String, String> contents) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                for (var entry : contents.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            server.createContext("/pack", exchange -> {
                packRequests.incrementAndGet();
                byte[] body = bytes.toByteArray();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            if (manifest != null) server.createContext("/latest", exchange -> {
                byte[] body = manifest.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            config.packId = "test";
            config.updateServiceUrl = base + "/latest";
            config.fullPackUrl = base + "/pack";
            installed.releaseId = 41;
            installed.version = "1.0";
        }
        Protocol protocol() { return new Protocol(new Downloads(2), root.resolve("ModpackUpdate/downloads")); }
        Models.Release check(Protocol protocol) throws Exception { return protocol.findUpdate(config, installed, root, "1.20.1", "0.16.10", "fabric-loader"); }
        public void close() { server.stop(0); }
    }
}
