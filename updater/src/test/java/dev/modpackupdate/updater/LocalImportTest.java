package dev.modpackupdate.updater;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class LocalImportTest {
    @TempDir Path root;
    private final byte[] payload = "updated value".getBytes(StandardCharsets.UTF_8);
    private Models.Config config() { var config = new Models.Config(); config.packId = "pack"; return config; }
    private Models.Installed installed() { var state = new Models.Installed(); state.packId = "pack"; state.version = "1.0"; state.releaseId = 1; return state; }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes)); }
    private byte[] json(Object value) { return FilesSupport.JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    private Path zip(String name, Map<String, byte[]> entries) throws Exception {
        Path path = root.resolve("ModpackUpdate/inbox/" + name);
        Files.createDirectories(path.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return path;
    }
    private Map<String, byte[]> entries(boolean bundled, boolean optional, String url) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("modrinth.index.json", json(Map.of("formatVersion", 1, "game", "minecraft", "name", "Local pack", "versionId", "2.0",
                "dependencies", Map.of("minecraft", "1.20.1", "fabric-loader", "0.16.10"), "files", List.of(Map.of(
                        "path", "config/value.txt", "hashes", Map.of("sha512", hash(payload)), "fileSize", payload.length,
                        "downloads", List.of(url), "env", Map.of("client", optional ? "optional" : "required"))))));
        entries.put("modpackupdate.release.json", json(new Models.PackRelease(1, "pack", 2, "2.0")));
        if (bundled) entries.put("overrides/config/value.txt", payload);
        return entries;
    }
    private Protocol protocol() { return new Protocol(new Downloads(2), root.resolve("ModpackUpdate/downloads")); }

    @Test void offlinePackUsesFallbackFormatPreservesOptionalAndProtectsSettings() throws Exception {
        var entries = entries(true, true, "http://127.0.0.1:1/unavailable");
        entries.put("overrides/options.txt", "server options".getBytes(StandardCharsets.UTF_8));
        entries.put("server-overrides/config/server.txt", payload);
        Path source = zip("offline.mrpack", entries);
        var prepared = LocalImports.prepare(root, source);
        var protocol = protocol();
        var release = protocol.local(prepared.archive(), source.getFileName().toString(), config(), installed(), root, "1.20.1", "0.16.10", "fabric-loader");
        var targets = protocol.targets(release, root, "1.20.1", "0.16.10");
        assertEquals(1, targets.size());
        assertTrue(targets.get("config/value.txt").optional());
        assertArrayEquals(payload, Files.readAllBytes(protocol.stage(targets.get("config/value.txt"), release)));
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(root.resolve("config/value.txt")));
    }

    @Test void ordinaryPackDownloadsOnlyMissingPayloadAndReusesCache() throws Exception {
        var count = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payload", exchange -> {
            count.incrementAndGet(); exchange.sendResponseHeaders(200, payload.length); exchange.getResponseBody().write(payload); exchange.close();
        });
        server.start();
        try {
            Path source = zip("ordinary.mrpack", entries(false, false, "http://127.0.0.1:" + server.getAddress().getPort() + "/payload"));
            var prepared = LocalImports.prepare(root, source);
            var protocol = protocol();
            var release = protocol.local(prepared.archive(), "ordinary.mrpack", config(), installed(), root, "1.20.1", "0.16.10", "fabric-loader");
            assertEquals(0, count.get());
            var target = protocol.targets(release, root, "1.20.1", "0.16.10").get("config/value.txt");
            assertArrayEquals(payload, Files.readAllBytes(protocol.stage(target, release)));
            protocol.stage(target, release);
            assertEquals(1, count.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"identity", "downgrade", "loader", "path", "broken"})
    void invalidPackageIsRetainedWithoutInstallation(String problem) throws Exception {
        var entries = entries(true, false, "https://example.com/payload");
        if (problem.equals("identity")) entries.put("modpackupdate.release.json", json(new Models.PackRelease(1, "wrong", 2, "2.0")));
        if (problem.equals("downgrade")) entries.put("modpackupdate.release.json", json(new Models.PackRelease(1, "pack", 0, "2.0")));
        if (problem.equals("path")) entries.put("overrides/../escape.txt", payload);
        Path source = zip("invalid.mrpack", entries);
        if (problem.equals("broken")) Files.writeString(source, "unfinished zip");
        var prepared = LocalImports.prepare(root, source);
        assertThrows(Exception.class, () -> protocol().local(prepared.archive(), "invalid.mrpack", config(), installed(), root,
                "1.20.1", problem.equals("loader") ? "wrong" : "0.16.10", "fabric-loader"));
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(root.resolve("config/value.txt")));
        assertFalse(Files.exists(root.resolve("ModpackUpdate/transaction.json")));
    }

    @Test void missingPayloadLeavesGameAndInboxUntouched() throws Exception {
        Path source = zip("missing.mrpack", entries(false, false, "http://127.0.0.1:1/unavailable"));
        var prepared = LocalImports.prepare(root, source);
        var protocol = protocol();
        var release = protocol.local(prepared.archive(), "missing.mrpack", config(), installed(), root, "1.20.1", "0.16.10", "fabric-loader");
        var target = protocol.targets(release, root, "1.20.1", "0.16.10").get("config/value.txt");
        assertThrows(Exception.class, () -> protocol.stage(target, release));
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(root.resolve("config/value.txt")));
        assertFalse(new Installer(root).pending());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recoveryArchivesOnlyACommittedImport(boolean committed) throws Exception {
        Path source = zip("recover.mrpack", entries(true, false, "https://example.com/payload"));
        Path destination = root.resolve("config/value.txt");
        Files.createDirectories(destination.getParent()); Files.writeString(destination, "old");
        var previous = installed();
        previous.files.put("config/value.txt", new Models.Baseline(FilesSupport.hash(destination), FilesSupport.hash(destination), false));
        InstalledStore.write(root, previous);
        var prepared = LocalImports.prepare(root, source);
        var protocol = protocol();
        var release = protocol.local(prepared.archive(), "recover.mrpack", config(), previous, root, "1.20.1", "0.16.10", "fabric-loader");
        var targets = protocol.targets(release, root, "1.20.1", "0.16.10");
        var changes = Planner.plan(root, config(), previous, targets, true);
        var next = Planner.next(previous, release, targets, changes, changes, config());
        next.localImport = prepared.receipt();
        var installer = new Installer(root, "fabric-loader", step -> {
            if (step.equals(committed ? "committed" : "installed")) throw new AssertionError("crash");
        });
        Path staged = protocol.stage(targets.get("config/value.txt"), release);
        assertThrows(AssertionError.class, () -> installer.install(previous, next, changes, Map.of("config/value.txt", staged), false));
        assertTrue(Files.exists(source));
        installer.recover();
        assertNull(LocalImports.finish(root, InstalledStore.header(root)));
        assertEquals(!committed, Files.exists(source));
        assertEquals(committed, Files.exists(root.resolve(prepared.receipt().archivedPath())));
        assertEquals(committed ? "updated value" : "old", Files.readString(destination));
        assertNull(LocalImports.finish(root, InstalledStore.header(root)));
        Storage.cleanup(root, 0, 1);
        assertEquals(committed, Files.exists(root.resolve(prepared.receipt().archivedPath())));
    }

    @Test void discoveryAndArchivingDoNotConsumeChangedOrPartialFiles() throws Exception {
        Path source = zip("one.MRPACK", entries(true, false, "https://example.com/payload"));
        zip("two.mrpack.part", Map.of());
        Files.createDirectories(root.resolve("ModpackUpdate/inbox/directory.mrpack"));
        assertEquals(List.of(source), LocalImports.list(root));
        var prepared = LocalImports.prepare(root, source);
        Files.writeString(source, "replacement still copying");
        var state = installed(); state.localImport = prepared.receipt();
        assertNotNull(LocalImports.finish(root, state));
        assertEquals("replacement still copying", Files.readString(source));
        assertFalse(Files.exists(root.resolve(prepared.receipt().archivedPath())));
    }

    @Test void unnumberedPackNeedsConfirmationAndSameVersionCanBeRepaired() throws Exception {
        var entries = entries(true, false, "https://example.com/payload");
        entries.remove("modpackupdate.release.json");
        Path source = zip("plain.mrpack", entries);
        var prepared = LocalImports.prepare(root, source);
        var protocol = protocol();
        var previous = installed(); previous.version = "2.0";
        var release = protocol.local(prepared.archive(), "plain.mrpack", config(), previous, root, "1.20.1", "0.16.10", "fabric-loader");
        assertTrue(protocol.requiresConfirmation());
        assertEquals(previous.releaseId, release.releaseId());
        assertFalse(Planner.plan(root, config(), previous, protocol.targets(release, root, "1.20.1", "0.16.10"), true).isEmpty());
    }
}
