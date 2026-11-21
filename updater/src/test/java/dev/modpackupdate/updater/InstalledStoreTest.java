package dev.modpackupdate.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class InstalledStoreTest {
    @TempDir Path root;

    @Test void migratesLegacyRecordToSmallHeaderAndVerifiedLazyIndex() throws Exception {
        var state = new Models.Installed();
        state.packId = "pack";
        state.releaseId = 3;
        for (int i = 0; i < 2000; i++) state.files.put("config/" + i + ".json",
                new Models.Baseline("a".repeat(128), "b".repeat(128), true));
        Path header = root.resolve("ModpackUpdate/config/installed.json");
        FilesSupport.write(header, state);
        assertTrue(InstalledStore.header(root).files.isEmpty());
        assertEquals(state.files, InstalledStore.read(root).files);
        InstalledStore.write(root, state);
        assertTrue(Files.size(header) < 1024);
        assertFalse(Files.readString(header).contains("\"files\""));
        assertEquals(state.files, InstalledStore.read(root).files);
        Path index = root.resolve("ModpackUpdate/config/file-indexes/" + state.filesIndex + ".json");
        Files.writeString(index, "corrupt");
        assertEquals(3, InstalledStore.header(root).releaseId);
        assertThrows(Exception.class, () -> InstalledStore.read(root));
    }

    @Test void abandonedNewIndexCannotAdvanceCommittedHeader() throws Exception {
        var old = new Models.Installed();
        old.releaseId = 1;
        old.transactionId = "committed";
        InstalledStore.write(root, old);
        byte[] committed = Files.readAllBytes(root.resolve("ModpackUpdate/config/installed.json"));
        var next = new Models.Installed();
        next.releaseId = 2;
        next.files.put("config/new.json", new Models.Baseline("a".repeat(128), "a".repeat(128), false));
        InstalledStore.write(root, next);
        Files.write(root.resolve("ModpackUpdate/config/installed.json"), committed);
        assertEquals(1, InstalledStore.read(root).releaseId);
        assertEquals("committed", InstalledStore.header(root).transactionId);
        assertTrue(InstalledStore.read(root).files.isEmpty());
    }

    @Test void recoveryKeepsJournalUntilCommittedIndexIsRestored() throws Exception {
        Path destination = root.resolve("config/example.txt");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "old");
        var previous = new Models.Installed();
        String oldHash = FilesSupport.hash(destination);
        previous.files.put("config/example.txt", new Models.Baseline(oldHash, oldHash, false));
        InstalledStore.write(root, previous);
        Path payload = root.resolve("ModpackUpdate/downloads/new");
        Files.createDirectories(payload.getParent());
        Files.writeString(payload, "new");
        var target = new Models.Target("config/example.txt", FilesSupport.hash(payload), Files.size(payload), java.util.List.of(), true, false);
        var change = new Models.Change(target.path(), target, oldHash, null);
        var next = new Models.Installed();
        next.releaseId = 2;
        next.files.put(target.path(), new Models.Baseline(target.sha512(), target.sha512(), false));
        var installer = new Installer(root, "fabric-loader", step -> {
            if (step.equals("committed")) throw new AssertionError("simulated crash after commit");
        });
        assertThrows(AssertionError.class, () -> installer.install(previous, next, java.util.List.of(change), java.util.Map.of(target.path(), payload), true));
        Path index = root.resolve("ModpackUpdate/config/file-indexes/" + next.filesIndex + ".json");
        byte[] goodIndex = Files.readAllBytes(index);
        Files.delete(index);
        assertThrows(Exception.class, installer::recover);
        assertTrue(installer.pending());
        assertEquals("new", Files.readString(destination));
        Files.write(index, goodIndex);
        installer.recover();
        assertFalse(installer.pending());
        assertEquals(2, InstalledStore.read(root).releaseId);
    }
}
