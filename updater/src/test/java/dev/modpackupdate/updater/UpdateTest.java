package dev.modpackupdate.updater;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UpdateTest {
    @TempDir Path root;

    private Path file(String path, String content) throws Exception {
        Path destination = root.resolve(path);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, content);
        return destination;
    }
    private Path mod(String path, String id, String version) throws Exception {
        Path destination = root.resolve(path);
        Files.createDirectories(destination.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return destination;
    }
    private Models.Target target(String path, Path source) throws Exception {
        return new Models.Target(path, FilesSupport.hash(source), Files.size(source), List.of(), false, false);
    }
    private Models.Installed installed(String path, Path file) throws Exception {
        Models.Installed installed = new Models.Installed();
        installed.packId = "test";
        installed.releaseId = 1;
        installed.version = "1.0.0";
        installed.files.put(path, new Models.Baseline(FilesSupport.hash(file), FilesSupport.hash(file), false));
        FilesSupport.write(root.resolve("ModpackUpdate/config/installed.json"), installed);
        Files.createDirectories(root.resolve("ModpackUpdate/downloads"));
        return installed;
    }
    private Models.Release release() {
        return new Models.Release(1, "test", 2, "1.1.0", "", null, null, null);
    }

    @Test void onlyChangedTargetsAreReadAndProtectedFilesStayOutsidePlan() throws Exception {
        Path configFile = file("config/example.json", "old");
        Models.Installed previous = installed("config/example.json", configFile);
        Models.Target unchanged = target("config/example.json", configFile);
        Files.delete(configFile);
        Files.createDirectory(configFile); // Would fail hashing if the planner scanned unchanged paths.
        Path replacement = file("ModpackUpdate/downloads/new", "new");
        Map<String, Models.Target> targets = new LinkedHashMap<>();
        targets.put(unchanged.path(), unchanged);
        targets.put("options.txt", target("options.txt", replacement));
        targets.put("saves/world/level.dat", target("saves/world/level.dat", replacement));
        targets.put("ModpackUpdate/config/installed.json", target("ModpackUpdate/config/installed.json", replacement));
        assertTrue(Planner.plan(root, new Models.Config(), previous, targets).isEmpty());
    }

    @Test void modifiedFileIsConflictAndRetainedBaselineSurvivesNextRelease() throws Exception {
        Path original = file("config/a.json", "original");
        Models.Installed previous = installed("config/a.json", original);
        Files.writeString(original, "player");
        Models.Target target = target("config/a.json", file("ModpackUpdate/downloads/new", "new"));
        var targets = Map.of(target.path(), target);
        var changes = Planner.plan(root, new Models.Config(), previous, targets);
        assertNotNull(changes.get(0).conflict());
        Models.Installed next = Planner.next(previous, release(), targets, changes, List.of(), new Models.Config());
        assertTrue(next.files.get(target.path()).retained());
        assertEquals(previous.files.get(target.path()).sha512(), next.files.get(target.path()).sha512());
        assertTrue(Planner.plan(root, new Models.Config(), next, targets).isEmpty());
    }

    @Test void missingAndUnknownExistingFilesRequireChoice() throws Exception {
        Path original = file("config/a.json", "old");
        Models.Installed previous = installed("config/a.json", original);
        Files.delete(original);
        Path updated = file("ModpackUpdate/downloads/new", "new");
        file("config/b.json", "user");
        var a = target("config/a.json", updated);
        var b = target("config/b.json", updated);
        var changes = Planner.plan(root, new Models.Config(), previous, Map.of(a.path(), a, b.path(), b));
        assertEquals(2, changes.size());
        assertTrue(changes.stream().allMatch(change -> change.conflict() != null));
    }

    @Test void optionalFileBecomingRequiredIsReconsideredEvenWithSameHash() throws Exception {
        Path source = file("ModpackUpdate/downloads/new", "content");
        Models.Target target = target("config/required.json", source);
        Models.Installed previous = new Models.Installed();
        previous.files.put(target.path(), new Models.Baseline(null, target.sha512(), true, true));
        var changes = Planner.plan(root, new Models.Config(), previous, Map.of(target.path(), target));
        assertEquals(1, changes.size());
        assertNotNull(changes.get(0).conflict());
    }

    @Test void replacesOwnJarAndKeepsVersionedBackup() throws Exception {
        Path original = mod("mods/ModpackUpdate.jar", "modpackupdate", "0.1.0");
        String oldHash = FilesSupport.hash(original);
        Models.Installed previous = installed("mods/ModpackUpdate.jar", original);
        Path source = mod("ModpackUpdate/downloads/new.jar", "modpackupdate", "0.2.0");
        Models.Target target = target("mods/ModpackUpdate.jar", source);
        var targets = Map.of(target.path(), target);
        var changes = Planner.plan(root, new Models.Config(), previous, targets);
        Models.Installed next = Planner.next(previous, release(), targets, changes, changes, new Models.Config());
        new Installer(root).install(previous, next, changes, Map.of(target.path(), source), false);
        assertEquals(target.sha512(), FilesSupport.hash(original));
        assertEquals(oldHash, FilesSupport.hash(root.resolve("ModpackUpdate/1.0.0/" + next.transactionId + "/mods/ModpackUpdate.jar")));
        assertEquals(2, FilesSupport.read(root.resolve("ModpackUpdate/config/installed.json"), Models.Installed.class).releaseId);
        assertFalse(new Installer(root).pending());
    }

    @Test void deletionOptionRemovesReplacedModBackupAfterCommit() throws Exception {
        Path original = mod("mods/test.jar", "test", "1");
        Models.Installed previous = installed("mods/test.jar", original);
        Path source = mod("ModpackUpdate/downloads/new.jar", "test", "2");
        Models.Target target = target("mods/test.jar", source);
        var targets = Map.of(target.path(), target);
        var changes = Planner.plan(root, new Models.Config(), previous, targets);
        Models.Installed next = Planner.next(previous, release(), targets, changes, changes, new Models.Config());
        new Installer(root).install(previous, next, changes, Map.of(target.path(), source), true);
        assertFalse(Files.exists(root.resolve("ModpackUpdate/1.0.0/" + next.transactionId + "/mods/test.jar")));
        assertEquals(target.sha512(), FilesSupport.hash(original));
    }

    @Test void removedManagedModIsDisabledAndUserModIsPreserved() throws Exception {
        Path original = mod("mods/old.jar", "old", "1");
        Models.Installed previous = installed("mods/old.jar", original);
        mod("mods/user.jar", "user", "1");
        file("config/removed.json", "keep");
        previous.files.put("config/removed.json", new Models.Baseline("a".repeat(128), "a".repeat(128), false));
        var changes = Planner.plan(root, new Models.Config(), previous, Map.of());
        assertEquals(1, changes.size());
        Models.Installed next = Planner.next(previous, release(), Map.of(), changes, changes, new Models.Config());
        new Installer(root).install(previous, next, changes, Map.of(), true);
        assertFalse(Files.exists(original));
        assertTrue(Files.exists(root.resolve("mods/old.jar.disable")));
        assertTrue(Files.exists(root.resolve("mods/user.jar")));
        assertTrue(Files.exists(root.resolve("config/removed.json")));
    }

    @Test void duplicateModIdPreventsPartialInstall() throws Exception {
        Path original = mod("mods/old.jar", "same", "1");
        Models.Installed previous = installed("mods/old.jar", original);
        Path replacement = mod("ModpackUpdate/downloads/new.jar", "same", "2");
        Models.Target target = target("mods/new.jar", replacement);
        var changes = List.of(new Models.Change(target.path(), target, null, null));
        assertThrows(IOException.class, () -> new Installer(root).install(previous, new Models.Installed(), changes, Map.of(target.path(), replacement), false));
        assertTrue(Files.exists(original));
        assertFalse(Files.exists(root.resolve(target.path())));
    }

    @Test void changedAfterSelectionAbortsBeforeMutating() throws Exception {
        Path original = file("config/a.json", "old");
        Models.Installed previous = installed("config/a.json", original);
        Path source = file("ModpackUpdate/downloads/new", "new");
        Models.Target target = target("config/a.json", source);
        var changes = Planner.plan(root, new Models.Config(), previous, Map.of(target.path(), target));
        Files.writeString(original, "changed again");
        assertThrows(IOException.class, () -> new Installer(root).install(previous, new Models.Installed(), changes, Map.of(target.path(), source), false));
        assertEquals("changed again", Files.readString(original));
    }

    @Test void crashRecoveryRestoresReplacementAndDisabledModAndRemovesNewFile() throws Exception {
        Path original = file("config/a.json", "old");
        Models.Installed previous = installed("config/a.json", original);
        String oldHash = FilesSupport.hash(original);
        Path backup = file("ModpackUpdate/1.0.0/test/config/a.json", "old");
        Files.writeString(original, "new");
        Path added = file("config/new.json", "added");
        Path disabled = file("mods/old.jar.disable", "old-mod");
        Models.Journal journal = new Models.Journal();
        journal.id = "test";
        journal.operations = List.of(
                new Models.Operation("config/a.json", "1.0.0/test/config/a.json", null, FilesSupport.hash(original), oldHash),
                new Models.Operation("config/new.json", "1.0.0/test/config/new.json", null, FilesSupport.hash(added), null),
                new Models.Operation("mods/old.jar", "1.0.0/test/mods/old.jar", "mods/old.jar.disable", null, FilesSupport.hash(disabled)));
        FilesSupport.write(root.resolve("ModpackUpdate/transaction.json"), journal);
        new Installer(root).recover();
        assertEquals("old", Files.readString(original));
        assertFalse(Files.exists(added));
        assertTrue(Files.exists(root.resolve("mods/old.jar")));
        assertFalse(Files.exists(backup));
        assertEquals(previous.releaseId, FilesSupport.read(root.resolve("ModpackUpdate/config/installed.json"), Models.Installed.class).releaseId);
    }

    @Test void rejectsTraversalAndWindowsAliasPaths() {
        for (String path : List.of("../outside", "/absolute", "C:/escape", "mods/../x", "mods\\x", "mods/a.jar:stream", "mods/CON.jar", "mods/a. ")) {
            assertThrows(IOException.class, () -> FilesSupport.resolve(root, path), path);
        }
    }

    @Test void verifiesDownloadUsesCacheAndRejectsBadHash() throws Exception {
        byte[] body = "valid content".getBytes(StandardCharsets.UTF_8);
        Path expected = file("expected", "valid content");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] requests = {0};
        server.createContext("/file", exchange -> {
            requests[0]++;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            Downloads downloads = new Downloads(2);
            Models.Artifact artifact = new Models.Artifact(url, FilesSupport.hash(expected), body.length);
            Path cache = root.resolve("cache");
            assertEquals(artifact.sha512(), FilesSupport.hash(downloads.artifact(artifact, cache)));
            downloads.artifact(artifact, cache);
            assertEquals(1, requests[0]);
            assertThrows(Exception.class, () -> downloads.artifact(new Models.Artifact(url, "0".repeat(128), body.length), cache));
            assertFalse(Files.exists(cache.resolve("0".repeat(128))));
        } finally { server.stop(0); }
    }
}
