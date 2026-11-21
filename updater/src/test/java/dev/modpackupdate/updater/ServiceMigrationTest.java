package dev.modpackupdate.updater;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServiceMigrationTest {
    @TempDir Path root;
    private Path configPath() { return root.resolve("ModpackUpdate/config/config.json"); }
    private Models.Config config(String url) {
        var config = new Models.Config();
        config.enable = true;
        config.packId = "pack";
        config.updateServiceUrl = url;
        return config;
    }
    private Models.Release unchangedRelease() {
        var unused = new Models.Artifact("https://example.com/unused", "a".repeat(128), 1);
        return new Models.Release(1, "pack", 1, "1.0", "", unused, new Models.Overrides(false, null, List.of()), unused);
    }
    private int check() throws Exception {
        Files.createDirectories(root.resolve("ModpackUpdate/runtime"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-jar", System.getProperty("updater.jar"), "check", root.toString(),
                Long.toString(ProcessHandle.current().pid()), root.resolve("ModpackUpdate/runtime/handoff-test").toString(),
                "1.20.1", "0.16.10").redirectErrorStream(true).redirectOutput(root.resolve("migration.log").toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Migration must finish without a dialog");
            return process.exitValue();
        } finally { process.destroyForcibly(); }
    }

    @Test void savesOnlyUrlAndUsesNewServiceOnNextStartupWithoutHandoffOrFallback() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String nextUrl = base + "/new?channel=stable";
        var oldRequests = new AtomicInteger();
        var newRequests = new AtomicInteger();
        var fallbackRequests = new AtomicInteger();
        server.createContext("/", exchange -> {
            byte[] body;
            switch (exchange.getRequestURI().getPath()) {
                case "/old" -> { oldRequests.incrementAndGet(); body = FilesSupport.JSON.toJson(Map.of("changed", nextUrl)).getBytes(StandardCharsets.UTF_8); }
                case "/new" -> { newRequests.incrementAndGet(); body = FilesSupport.JSON.toJson(unchangedRelease()).getBytes(StandardCharsets.UTF_8); }
                default -> { fallbackRequests.incrementAndGet(); body = new byte[]{0}; }
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var config = config(base + "/old");
            config.fullPackUrl = base + "/fallback";
            JsonObject original = FilesSupport.JSON.toJsonTree(config).getAsJsonObject();
            original.add("future-setting", FilesSupport.JSON.toJsonTree(Map.of("keep", true)));
            FilesSupport.write(configPath(), original);
            var installed = new Models.Installed();
            installed.packId = "pack";
            installed.releaseId = 1;
            installed.version = "1.0";
            InstalledStore.write(root, installed);
            Path state = root.resolve("ModpackUpdate/config/installed.json");
            byte[] before = Files.readAllBytes(state);
            assertEquals(0, check(), Files.readString(root.resolve("migration.log")));
            assertEquals(1, oldRequests.get());
            assertEquals(0, newRequests.get());
            assertEquals(0, fallbackRequests.get());
            JsonObject saved = FilesSupport.read(configPath(), JsonObject.class);
            original.addProperty("update-service-url", nextUrl);
            assertEquals(original, saved);
            assertArrayEquals(before, Files.readAllBytes(state));
            assertFalse(Files.exists(root.resolve("ModpackUpdate/runtime/handoff-test")));
            assertFalse(Files.exists(root.resolve("ModpackUpdate/transaction.json")));
            assertEquals(0, check(), Files.readString(root.resolve("migration.log")));
            assertEquals(1, oldRequests.get());
            assertEquals(1, newRequests.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "12", "false", "{}", "[]", "\"\"", "\" \"",
            "\"http://example.com/latest\"", "\"/relative\"", "\"file:///tmp/latest.json\"",
            "\"https://user:password@example.com/latest\"", "\"https://example.com/latest#fragment\""})
    void invalidMigrationDoesNotTriggerFullPackFallback(String value) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var fallbacks = new AtomicInteger();
        server.createContext("/old", exchange -> {
            byte[] body = ("{\"changed\":" + value + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/fallback", exchange -> {
            fallbacks.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var config = config(base + "/old");
            config.fullPackUrl = base + "/fallback";
            FilesSupport.write(configPath(), config);
            byte[] before = Files.readAllBytes(configPath());
            var protocol = new Protocol(new Downloads(2), root.resolve("cache"));
            var failure = assertThrows(java.io.IOException.class, () -> protocol.findUpdate(config, new Models.Installed(),
                    root, "1.20.1", "0.16.10", "fabric-loader"));
            assertFalse(failure instanceof Protocol.ServiceChangedException);
            assertEquals(0, fallbacks.get());
            assertArrayEquals(before, Files.readAllBytes(configPath()));
        } finally { server.stop(0); }
    }

    @Test void preservesSettingsEditedDuringCheckButDoesNotOverwriteChangedUrl() throws Exception {
        var expected = config("https://old.example/latest.json");
        var edited = FilesSupport.JSON.toJsonTree(expected).getAsJsonObject();
        edited.addProperty("silent-update", true);
        FilesSupport.write(configPath(), edited);
        ServiceMigration.save(root, expected, "https://new.example/latest.json");
        assertTrue(FilesSupport.read(configPath(), Models.Config.class).silentUpdate);
        byte[] saved = Files.readAllBytes(configPath());
        assertThrows(java.io.IOException.class, () -> ServiceMigration.save(root, expected, "https://third.example/latest.json"));
        assertArrayEquals(saved, Files.readAllBytes(configPath()));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void migrationTakesPriorityWhenAddedToACompleteVersionEntry(int version) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var artifact = new Models.Artifact("https://example.com/manifest", "a".repeat(128), 100);
        var release = version == 1 ? unchangedRelease()
                : new Models.Release(2, "pack", 1, "1.0", null, null, null, null, artifact);
        JsonObject json = FilesSupport.JSON.toJsonTree(release).getAsJsonObject();
        json.addProperty("changed", "https://new.example/latest.json");
        // Existing v1 clients ignore the extension and can still parse the upgrade release.
        if (version == 1) Protocol.validateRelease(FilesSupport.JSON.fromJson(json, Models.Release.class), "pack");
        server.createContext("/", exchange -> {
            byte[] body = FilesSupport.JSON.toJson(json).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var protocol = new Protocol(new Downloads(2), root.resolve("cache"));
            var changed = assertThrows(Protocol.ServiceChangedException.class,
                    () -> protocol.release("http://127.0.0.1:" + server.getAddress().getPort() + "/", "pack"));
            assertEquals("https://new.example/latest.json", changed.url);
        } finally { server.stop(0); }
    }
}
