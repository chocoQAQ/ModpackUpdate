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
}
