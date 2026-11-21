package dev.modpackupdate.updater;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class RiskFixTest {
    @TempDir Path root;
    private Path file(String path, String value) throws Exception {
        Path destination = root.resolve(path);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, value);
        return destination;
    }
    private Path mod(String path, String id) throws Exception {
        Path destination = root.resolve(path);
        Files.createDirectories(destination.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return destination;
    }
    private Models.Installed installed(String path) throws Exception {
        var state = new Models.Installed();
        state.packId = "test";
        state.releaseId = 1;
        state.version = "1.0";
        String hash = FilesSupport.hash(root.resolve(path));
        state.files.put(path, new Models.Baseline(hash, hash, false));
        FilesSupport.write(root.resolve("ModpackUpdate/config/installed.json"), state);
        return state;
    }
    private Models.Target target(String path, Path source) throws Exception {
        return new Models.Target(path, FilesSupport.hash(source), Files.size(source), List.of(), true, false);
    }
    private Models.Release release() {
        return new Models.Release(1, "test", 2, "2.0", "", null, new Models.Overrides(false, null, List.of()), null);
    }

    @Test void generatedDisablePathCannotCollideWithANewDirectory() throws Exception {
        mod("mods/old.jar", "example");
        var previous = installed("mods/old.jar");
        Path payload = file("ModpackUpdate/downloads/payload", "new");
        var updated = target("mods/old.jar.disable/note.txt", payload);
        var targets = Map.of(updated.path(), updated);
        var config = new Models.Config();
        var changes = Planner.plan(root, config, previous, targets);
        var next = Planner.next(previous, release(), targets, changes, changes, config);
        var installer = new Installer(root);
        installer.install(previous, next, changes, Map.of(updated.path(), payload), false);
        assertFalse(installer.pending());
        assertEquals("new", Files.readString(root.resolve(updated.path())));
        assertFalse(Files.exists(root.resolve("mods/old.jar")));
        assertTrue(Files.isRegularFile(root.resolve("mods/old.jar." + next.transactionId + ".disable")));
    }

    @Test void legacyFailedDisableDirectoryTransactionCanBeRecovered() throws Exception {
        mod("mods/old.jar", "example");
        var previous = installed("mods/old.jar");
        Path payload = file("mods/old.jar.disable/note.txt", "new");
        var journal = new Models.Journal();
        journal.id = "legacy";
        journal.backupDirectory = "1.0/legacy";
        journal.operations.add(new Models.Operation("mods/old.jar.disable/note.txt", "1.0/legacy/note.txt", null, FilesSupport.hash(payload), null));
        journal.operations.add(new Models.Operation("mods/old.jar", "1.0/legacy/old.jar", "mods/old.jar.disable", null,
                previous.files.get("mods/old.jar").sha512()));
        FilesSupport.write(root.resolve("ModpackUpdate/transaction.json"), journal);
        new Installer(root).recover();
        assertFalse(new Installer(root).pending());
        assertTrue(Files.isRegularFile(root.resolve("mods/old.jar")));
        assertFalse(Files.exists(payload));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void renamedModIsDisabledOnlyWhenItsReplacementIsAdopted(boolean adopt) throws Exception {
        mod("mods/old.jar", "example");
        var previous = installed("mods/old.jar");
        mod("mods/new.jar", "player_other_mod");
        Path payload = mod("ModpackUpdate/downloads/new.jar", "example");
        var updated = target("mods/new.jar", payload);
        var targets = Map.of(updated.path(), updated);
        var config = new Models.Config();
        var changes = Planner.plan(root, config, previous, targets);
        var selected = adopt ? changes : changes.stream().filter(change -> change.conflict() == null).toList();
        var staged = adopt ? Map.of(updated.path(), payload) : Map.<String, Path>of();
        var installer = new Installer(root);
        selected = installer.reconcileSelection(changes, selected, staged);
        var next = Planner.next(previous, release(), targets, changes, selected, config);
        installer.install(previous, next, selected, staged, false);
        assertEquals(!adopt, Files.exists(root.resolve("mods/old.jar")));
        assertEquals(adopt ? "complete" : "modified", next.installationStatus);
        if (!adopt) assertTrue(next.files.get("mods/old.jar").retained());
    }

    @Test void acceptedReplacementStillDisablesItsOldModWhenAnotherModWasSkipped() throws Exception {
        mod("mods/old.jar", "example");
        var previous = installed("mods/old.jar");
        Path payload = mod("ModpackUpdate/downloads/new.jar", "example");
        var updated = target("mods/new.jar", payload);
        var skipped = target("mods/optional.jar", mod("ModpackUpdate/downloads/optional.jar", "optional"));
        var disable = new Models.Change("mods/old.jar", null, previous.files.get("mods/old.jar").sha512(), null);
        var install = new Models.Change(updated.path(), updated, null, null);
        var ignore = new Models.Change(skipped.path(), skipped, null, "choice");
        var selected = new Installer(root).reconcileSelection(List.of(install, ignore, disable), List.of(install, disable), Map.of(updated.path(), payload));
        assertEquals(List.of(install, disable), selected);
    }

    @ParameterizedTest @ValueSource(strings = {"journal", "starting", "backup", "installed", "committed"})
    void interruptedInstallRestoresOldStateOrCompletesCommittedState(String point) throws Exception {
        file("config/a", "old");
        var previous = installed("config/a");
        Path payload = file("ModpackUpdate/downloads/payload", "new");
        var updated = target("config/a", payload);
        var config = new Models.Config();
        var targets = Map.of(updated.path(), updated);
        var changes = Planner.plan(root, config, previous, targets);
        var next = Planner.next(previous, release(), targets, changes, changes, config);
        var installer = new Installer(root, "fabric-loader", step -> { if (step.equals(point)) throw new SimulatedCrash(); });
        assertThrows(SimulatedCrash.class, () -> installer.install(previous, next, changes, Map.of(updated.path(), payload), false));
        assertTrue(installer.pending());
        new Installer(root).recover();
        new Installer(root).recover(); // Recovery is repeatable.
        assertFalse(installer.pending());
        assertEquals(point.equals("committed") ? "new" : "old", Files.readString(root.resolve("config/a")));
        var state = FilesSupport.read(root.resolve("ModpackUpdate/config/installed.json"), Models.Installed.class);
        assertEquals(point.equals("committed") ? 2 : 1, state.releaseId);
    }

    @Test void rollbackDoesNotTouchAnOperationThatNeverStarted() throws Exception {
        file("config/a", "old");
        var previous = installed("config/a");
        Path payload = file("ModpackUpdate/downloads/payload", "new");
        var first = target("config/a", payload);
        var later = target("config/later", payload);
        var targets = new LinkedHashMap<String, Models.Target>();
        targets.put(first.path(), first);
        targets.put(later.path(), later);
        var config = new Models.Config();
        var changes = Planner.plan(root, config, previous, targets);
        var next = Planner.next(previous, release(), targets, changes, changes, config);
        var installer = new Installer(root, "fabric-loader", step -> { if (step.equals("backup")) throw new SimulatedCrash(); });
        assertThrows(SimulatedCrash.class, () -> installer.install(previous, next, changes, Map.of(first.path(), payload, later.path(), payload), false));
        file(later.path(), "external change after crash");
        new Installer(root).recover();
        assertEquals("old", Files.readString(root.resolve(first.path())));
        assertEquals("external change after crash", Files.readString(root.resolve(later.path())));
    }

    @Test void repairCanReconsiderPreviouslyRetainedContentWithoutChangingVersionManually() throws Exception {
        file("config/a", "old");
        var previous = installed("config/a");
        Path payload = file("ModpackUpdate/downloads/payload", "new");
        var updated = target("config/a", payload);
        var targets = Map.of(updated.path(), updated);
        var config = new Models.Config();
        file("config/a", "player");
        var changes = Planner.plan(root, config, previous, targets);
        var retained = Planner.next(previous, release(), targets, changes, List.of(), config);
        assertEquals("modified", retained.installationStatus);
        assertTrue(Planner.plan(root, config, retained, targets).isEmpty());
        var repair = Planner.plan(root, config, retained, targets, true);
        assertEquals(1, repair.size());
        assertNotNull(repair.get(0).conflict());
        var next = Planner.next(retained, release(), targets, repair, repair, config);
        new Installer(root).install(retained, next, repair, Map.of(updated.path(), payload), false);
        assertEquals("complete", next.installationStatus);
        assertEquals("new", Files.readString(root.resolve("config/a")));
    }

    @Test void cleanupHonorsBudgetPreservesBackupsAndNeverRunsDuringRecovery() throws Exception {
        Path cached = file("ModpackUpdate/downloads/" + "a".repeat(128), "cache");
        Path temporary = file("ModpackUpdate/downloads/" + "b".repeat(128) + ".part", "partial");
        Path unknown = file("ModpackUpdate/downloads/user-file", "user");
        Path backup = file("ModpackUpdate/1.0/backup/mods/a.jar", "backup");
        Path journal = file("ModpackUpdate/transaction.json", "pending");
        Storage.cleanup(root, 0);
        assertTrue(Files.exists(cached));
        assertTrue(Files.exists(temporary));
        Files.delete(journal);
        Storage.cleanup(root, 0);
        assertFalse(Files.exists(cached));
        assertFalse(Files.exists(temporary));
        assertTrue(Files.exists(unknown));
        assertTrue(Files.exists(backup));
        assertThrows(IOException.class, () -> Storage.requireSpace(root, Long.MAX_VALUE));
    }

    @Test void backupRetentionRequiresOptInAndOnlyRemovesMarkedCompletedBackups() throws Exception {
        String first = "00000000-0000-0000-0000-000000000001";
        String second = "00000000-0000-0000-0000-000000000002";
        Path old = file("ModpackUpdate/1.0/" + first + "/mods/old.jar", "old");
        Path recent = file("ModpackUpdate/2.0/" + second + "/mods/new.jar", "new");
        Path unmarked = file("ModpackUpdate/legacy/00000000-0000-0000-0000-000000000003/file", "keep");
        Path firstMarker = old.getParent().getParent().resolve(".modpackupdate-backup.json");
        Path secondMarker = recent.getParent().getParent().resolve(".modpackupdate-backup.json");
        FilesSupport.write(firstMarker, Map.of("transaction-id", first));
        FilesSupport.write(secondMarker, Map.of("transaction-id", second));
        Files.setLastModifiedTime(firstMarker, java.nio.file.attribute.FileTime.fromMillis(1000));
        Files.setLastModifiedTime(secondMarker, java.nio.file.attribute.FileTime.fromMillis(2000));
        Storage.cleanup(root, 0, 0);
        assertTrue(Files.exists(old));
        Storage.cleanup(root, 0, 1);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(unmarked));
    }

    @Test void startupBudgetInterruptsAStalledLargeDownloadAndClearsItsOwnInterrupt() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        long start = System.nanoTime();
        try {
            var downloads = new Downloads(10);
            try (var check = new CheckSession(downloads, 2)) {
                assertThrows(Exception.class, () -> downloads.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/stall",
                        root.resolve("downloads/pack"), 20L * 1024 * 1024 * 1024));
                assertTrue(check.cancelled());
            }
            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 8);
        } finally { release.countDown(); server.stop(0); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void standaloneRepairRechecksSameVersionAndRefusesAnActiveGame(boolean gameRunning) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        file("config/a", "old");
        var previous = installed("config/a");
        file("config/a", "player");
        Path payload = file("payload", "new");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var responses = new java.util.HashMap<String, byte[]>();
        server.createContext("/", exchange -> {
            byte[] body = responses.get(exchange.getRequestURI().getPath());
            if (body == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        Path index = file("index.json", FilesSupport.JSON.toJson(Map.of("formatVersion", 1, "game", "minecraft", "versionId", "1.0",
                "dependencies", Map.of("minecraft", "1.20.1", "fabric-loader", "0.16.10"),
                "files", List.of(Map.of("path", "config/a", "hashes", Map.of("sha512", FilesSupport.hash(payload)),
                        "fileSize", Files.size(payload), "downloads", List.of(base + "/payload"))))));
        var artifact = new Models.Artifact(base + "/index", FilesSupport.hash(index), Files.size(index));
        var release = new Models.Release(1, "test", 1, "1.0", "", artifact, new Models.Overrides(false, null, List.of()), artifact);
        responses.put("/latest", FilesSupport.JSON.toJson(release).getBytes(StandardCharsets.UTF_8));
        responses.put("/index", Files.readAllBytes(index));
        responses.put("/payload", Files.readAllBytes(payload));
        var config = new Models.Config();
        config.packId = "test";
        config.updateServiceUrl = base + "/latest";
        config.silentUpdate = true;
        FilesSupport.write(root.resolve("ModpackUpdate/config/config.json"), config); // Startup checks disabled; explicit repair still works.
        if (gameRunning) file("ModpackUpdate/runtime/games/" + ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElseThrow().toString());
        String classpath = Path.of(EndToEndTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator + System.getProperty("updater.jar");
        server.start();
        Process child = null;
        try {
            child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
                    EndToEndTest.AutoUpdater.class.getName(), "repair", root.toString(), "1.20.1", "0.16.10")
                    .redirectErrorStream(true).redirectOutput(root.resolve("repair.log").toFile()).start();
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), Files.readString(root.resolve("repair.log")));
            assertEquals(gameRunning ? "player" : "new", Files.readString(root.resolve("config/a")));
            assertFalse(new Installer(root).pending());
            var next = FilesSupport.read(root.resolve("ModpackUpdate/config/installed.json"), Models.Installed.class);
            assertEquals(previous.releaseId, next.releaseId);
            assertEquals(gameRunning ? "" : "complete", gameRunning ? next.transactionId : next.installationStatus);
        } finally { if (child != null) child.destroyForcibly(); server.stop(0); }
    }

    private static final class SimulatedCrash extends Error {}
}
