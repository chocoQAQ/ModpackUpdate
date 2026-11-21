package dev.modpackupdate.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class UpdateBootstrapTest {
    @TempDir Path root;

    @Test void checkDeadlineDoesNotKillInteractiveConfirmationButBoundsAStalledCheck() throws Exception {
        Path phase = root.resolve("phase");
        assertFalse(UpdateBootstrap.checkExpired(phase, 100, 99));
        assertTrue(UpdateBootstrap.checkExpired(phase, 100, 101));
        Files.writeString(phase, "1000");
        assertFalse(UpdateBootstrap.checkExpired(phase, 100, 5999));
        assertTrue(UpdateBootstrap.checkExpired(phase, 100, 6001));
        Files.writeString(phase, "interactive");
        assertFalse(UpdateBootstrap.checkExpired(phase, 100, Long.MAX_VALUE));
        Files.writeString(phase, "broken");
        assertTrue(UpdateBootstrap.checkExpired(phase, 100, 101));
    }

    @BeforeEach void prepareInstance() throws Exception {
        root = root.toRealPath();
        Files.createDirectories(root.resolve("ModpackUpdate"));
    }

    private void pendingTransaction() throws Exception {
        Files.writeString(root.resolve("ModpackUpdate/transaction.json"), "{\"id\":\"interrupted\",\"operations\":[]}");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void helperFailureBlocksOnlyAnInstanceWithPendingRecovery(boolean pending) throws Exception {
        Files.createDirectory(root.resolve("ModpackUpdate/update.lock"));
        if (pending) pendingTransaction();
        if (pending) assertThrows(IllegalStateException.class, () -> UpdateBootstrap.check(root, "1.20.1", "0.16.10", "fabric-loader"));
        else assertDoesNotThrow(() -> UpdateBootstrap.check(root, "1.20.1", "0.16.10", "fabric-loader"));
        assertEquals(pending, Files.exists(root.resolve("ModpackUpdate/transaction.json")));
        assertFalse(Files.exists(root.resolve("ModpackUpdate/config/config.json")));
        Path runtime = root.resolve("ModpackUpdate/runtime");
        try (var logs = Files.list(runtime)) {
            Path log = logs.filter(path -> path.getFileName().toString().startsWith("check-")).findFirst().orElseThrow();
            assertTrue(Files.readString(log).contains("Exception"), "The helper must actually fail before recovery");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparationFailureStillChecksForPendingRecovery(boolean pending) throws Exception {
        Files.writeString(root.resolve("ModpackUpdate/runtime"), "not-a-directory");
        if (pending) pendingTransaction();
        if (pending) assertThrows(IllegalStateException.class, () -> UpdateBootstrap.check(root, "1.20.1", "0.16.10", "fabric-loader"));
        else assertDoesNotThrow(() -> UpdateBootstrap.check(root, "1.20.1", "0.16.10", "fabric-loader"));
    }

    private Path extract(Path runtime) throws Exception {
        var extract = UpdateBootstrap.class.getDeclaredMethod("extractHelper", Path.class);
        extract.setAccessible(true);
        return (Path) extract.invoke(null, runtime);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void corruptHelperIsRebuiltFromEmbeddedBytesIncludingSameLengthCorruption(boolean sameLength) throws Exception {
        Path runtime = Files.createDirectories(root.resolve("ModpackUpdate/runtime"));
        Path helper = extract(runtime);
        byte[] expected;
        try (var input = UpdateBootstrap.class.getResourceAsStream("/modpackupdate/updater.jar")) {
            assertNotNull(input);
            expected = input.readAllBytes();
        }
        byte[] corrupt = sameLength ? expected.clone() : new byte[]{0};
        corrupt[corrupt.length / 2] ^= 1;
        Files.write(helper, corrupt);
        assertEquals(helper, extract(runtime));
        assertTrue(Files.isRegularFile(helper));
        assertArrayEquals(expected, Files.readAllBytes(helper));
        try (var files = Files.list(runtime)) { assertEquals(1, files.count(), "Extraction must clean temporary files"); }
    }

    @Test void validCachedHelperIsReusedWithoutRewriting() throws Exception {
        Path runtime = Files.createDirectories(root.resolve("ModpackUpdate/runtime"));
        Path helper = extract(runtime);
        FileTime timestamp = FileTime.fromMillis(1_500_000_000_000L);
        Files.setLastModifiedTime(helper, timestamp);
        assertEquals(helper, extract(runtime));
        assertEquals(timestamp, Files.getLastModifiedTime(helper));
    }
}
