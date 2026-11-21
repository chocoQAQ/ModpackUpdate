package dev.modpackupdate.updater;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReviewRegressionTest {
    @TempDir Path root;
    private final Models.Config config = new Models.Config();

    @BeforeEach void prepareInstance() throws Exception {
        root = root.toRealPath();
        Files.createDirectories(root.resolve("ModpackUpdate/downloads"));
    }

    private Path file(String relative, String contents) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
        return path;
    }

    private Path mod(String relative, String version) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"example\",\"version\":\"" + version + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private Models.Installed installed(String relative, Path original) throws Exception {
        Models.Installed state = new Models.Installed();
        state.packId = "review";
        state.releaseId = 1;
        state.version = "1.0.0";
        String hash = FilesSupport.hash(original);
        state.files.put(relative, new Models.Baseline(hash, hash, false, false));
        FilesSupport.write(root.resolve("ModpackUpdate/config/installed.json"), state);
        return state;
    }

    private Models.Target target(String relative, Path source, boolean optional) throws Exception {
        return new Models.Target(relative, FilesSupport.hash(source), Files.size(source), List.of(), false, optional);
    }

    private Models.Release release() {
        return new Models.Release(1, "review", 2, "1.1.0", "", null,
                new Models.Overrides(false, null, List.of()), null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void caseOnlyRenameKeepsTheRequestedModActiveAndOneBaseline(boolean contentChanges) throws Exception {
        Path original = mod("mods/Example.jar", "1");
        Models.Installed previous = installed("mods/Example.jar", original);
        Path source = root.resolve("ModpackUpdate/downloads/new.jar");
        if (contentChanges) mod("ModpackUpdate/downloads/new.jar", "2");
        else Files.copy(original, source);
        Models.Target updated = target("mods/example.jar", source, false);
        var targets = Map.of(updated.path(), updated);
        var changes = Planner.plan(root, config, previous, targets);
        assertTrue(changes.stream().allMatch(change -> change.conflict() == null));
        if (Path.of("mods/Example.jar").equals(Path.of("mods/example.jar"))) {
            assertTrue(changes.stream().noneMatch(Models.Change::disable));
        }
        Models.Installed next = Planner.next(previous, release(), targets, changes, changes, config);
        new Installer(root).install(previous, next, changes, Map.of(updated.path(), source), false);
        assertEquals(updated.sha512(), FilesSupport.hash(root.resolve(updated.path())));
        assertEquals(Set.of(updated.path()), next.files.keySet());
        try (var files = Files.list(root.resolve("mods"))) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".jar")).count());
        }
        assertEquals(2, FilesSupport.read(root.resolve("ModpackUpdate/config/installed.json"), Models.Installed.class).releaseId);
        assertFalse(new Installer(root).pending());
    }

    @Test void skippedCaseRenamePreservesModifiedLocalModOnWindows() throws Exception {
        assumeTrue(Path.of("mods/Example.jar").equals(Path.of("mods/example.jar")), "Windows path identity");
        Path original = mod("mods/Example.jar", "1");
        Models.Installed previous = installed("mods/Example.jar", original);
        String baselineHash = FilesSupport.hash(original);
        mod("mods/Example.jar", "player");
        String playerHash = FilesSupport.hash(original);
        Path source = mod("ModpackUpdate/downloads/new.jar", "2");
        Models.Target updated = target("mods/example.jar", source, false);
        var targets = Map.of(updated.path(), updated);
        var changes = Planner.plan(root, config, previous, targets);
        assertEquals(1, changes.size());
        assertNotNull(changes.get(0).conflict());
        assertFalse(changes.get(0).disable());
        Models.Installed next = Planner.next(previous, release(), targets, changes, List.of(), config);
        new Installer(root).install(previous, next, List.of(), Map.of(), false);
        assertEquals(playerHash, FilesSupport.hash(original));
        assertFalse(Files.exists(root.resolve("mods/Example.jar.disable")));
        assertEquals(Set.of(updated.path()), next.files.keySet());
        assertEquals(baselineHash, next.files.get(updated.path()).sha512());
        assertTrue(next.files.get(updated.path()).retained());
    }

    @ParameterizedTest
    @ValueSource(strings = {"config//a.json", "options.txt/", "mods/./a.jar", "mods/../a.jar"})
    void nonCanonicalPathsNeverReachPlanningOrInstallation(String path) throws Exception {
        Path settings = file("options.txt", "player-settings");
        Path source = file("ModpackUpdate/downloads/new", "author-settings");
        Models.Target updated = target(path, source, false);
        assertThrows(IOException.class, () -> FilesSupport.resolve(root, path));
        assertThrows(IOException.class, () -> Planner.plan(root, config, new Models.Installed(), Map.of(path, updated)));
        assertThrows(IOException.class, () -> parseTargets(List.of(path), List.of()));
        var change = new Models.Change(path, updated, null, null);
        assertThrows(IOException.class, () -> new Installer(root).install(new Models.Installed(), new Models.Installed(),
                List.of(change), Map.of(path, source), false));
        assertEquals("player-settings", Files.readString(settings));
        assertFalse(new Installer(root).pending());
    }

    @ParameterizedTest
    @CsvSource({"config/a,config/a/child", "config/a/child,config/a", "Config/a,config/A/child", "config/a,CONFIG/A"})
    void indexAndOverrideLayersRejectFileIdentityAndParentConflicts(String first, String second) throws Exception {
        assertThrows(IOException.class, () -> parseTargets(List.of(first, second), List.of()));
        assertThrows(IOException.class, () -> parseTargets(List.of(first), List.of(second)));
        assertFalse(new Installer(root).pending());
    }

    private Map<String, Models.Target> parseTargets(List<String> paths, List<String> overridePaths) throws Exception {
        var files = new ArrayList<Map<String, Object>>();
        for (String path : paths) {
            files.add(Map.of("path", path, "hashes", Map.of("sha512", "a".repeat(128)),
                    "fileSize", 1, "downloads", List.of("https://example.invalid/unused")));
        }
        Path index = file("review-index.json", FilesSupport.JSON.toJson(Map.of(
                "formatVersion", 1, "game", "minecraft", "versionId", "1.1.0", "files", files,
                "dependencies", Map.of("minecraft", "1.20.1", "fabric-loader", "0.16.10"))));
        var overrides = overridePaths.stream().map(path -> new Models.OverrideFile(path, "b".repeat(128), 1)).toList();
        Models.Release release = new Models.Release(1, "review", 2, "1.1.0", "", null,
                new Models.Overrides(!overrides.isEmpty(), null, overrides), null);
        return Protocol.parseTargets(release, root, "1.20.1", "0.16.10", "fabric-loader", index);
    }

    @Test void installerRejectsDuplicateDestinationsBeforeWritingTheJournal() throws Exception {
        Path original = file("config/a.json", "old");
        Models.Installed previous = installed("config/a.json", original);
        Path first = file("ModpackUpdate/downloads/first", "first");
        Path second = file("ModpackUpdate/downloads/second", "second");
        Models.Target a = target("config/a.json", first, false);
        Models.Target b = target("config/A.json", second, false);
        var changes = List.of(new Models.Change(a.path(), a, FilesSupport.hash(original), null),
                new Models.Change(b.path(), b, FilesSupport.hash(original), null));
        assertThrows(IOException.class, () -> new Installer(root).install(previous, new Models.Installed(), changes,
                Map.of(a.path(), first, b.path(), second), false));
        assertEquals("old", Files.readString(original));
        assertFalse(new Installer(root).pending());
    }

    @Test void installerRejectsParentDestinationsBeforeWritingTheJournal() throws Exception {
        Path source = file("ModpackUpdate/downloads/new", "new");
        Models.Target parent = target("config/tree", source, false);
        Models.Target child = target("config/tree/child", source, false);
        var changes = List.of(new Models.Change(parent.path(), parent, null, null), new Models.Change(child.path(), child, null, null));
        assertThrows(IOException.class, () -> new Installer(root).install(new Models.Installed(), new Models.Installed(), changes,
                Map.of(parent.path(), source, child.path(), source), false));
        assertFalse(Files.exists(root.resolve("config/tree")));
        assertFalse(new Installer(root).pending());
    }

    @Test void installerRejectsAnExistingParentFileWithoutLeavingATransaction() throws Exception {
        Path parent = file("config/tree", "old");
        Path source = file("ModpackUpdate/downloads/new", "new");
        Models.Target child = target("config/tree/child", source, false);
        assertThrows(IOException.class, () -> new Installer(root).install(new Models.Installed(), new Models.Installed(),
                List.of(new Models.Change(child.path(), child, null, null)), Map.of(child.path(), source), false));
        assertEquals("old", Files.readString(parent));
        assertFalse(new Installer(root).pending());
    }

    @Test void disabledModDoesNotOverwriteAnotherSelectedDestination() throws Exception {
        Path original = mod("mods/old.jar", "1");
        Models.Installed previous = installed("mods/old.jar", original);
        String oldHash = FilesSupport.hash(original);
        Path source = file("ModpackUpdate/downloads/new", "published-disable-file");
        Models.Target updated = target("mods/old.jar.disable", source, false);
        var targets = Map.of(updated.path(), updated);
        var changes = Planner.plan(root, config, previous, targets);
        Models.Installed next = Planner.next(previous, release(), targets, changes, changes, config);
        new Installer(root).install(previous, next, changes, Map.of(updated.path(), source), false);
        assertEquals("published-disable-file", Files.readString(root.resolve(updated.path())));
        assertFalse(Files.exists(original));
        try (var files = Files.list(root.resolve("mods"))) {
            var disabled = files.filter(path -> !path.getFileName().toString().equals("old.jar.disable")).toList();
            assertEquals(1, disabled.size());
            assertEquals(oldHash, FilesSupport.hash(disabled.get(0)));
        }
    }

    @Test void requiredOptionalRequiredTransitionReconsidersAndRestoresMissingMod() throws Exception {
        Path original = mod("mods/example.jar", "1");
        Models.Installed previous = installed("mods/example.jar", original);
        Path source = root.resolve("ModpackUpdate/downloads/new.jar");
        Files.copy(original, source);
        Models.Target optional = target("mods/example.jar", source, true);
        var optionalTargets = Map.of(optional.path(), optional);
        var changes = Planner.plan(root, config, previous, optionalTargets);
        assertTrue(changes.isEmpty());
        Models.Installed next = Planner.next(previous, release(), optionalTargets, changes, changes, config);
        new Installer(root).install(previous, next, changes, Map.of(), false);
        assertTrue(next.files.get(optional.path()).optional());
        assertEquals(previous.files.get(optional.path()).sha512(), next.files.get(optional.path()).sha512());
        Files.delete(original);
        Models.Target required = target(optional.path(), source, false);
        var requiredTargets = Map.of(required.path(), required);
        var requiredChanges = Planner.plan(root, config, next, requiredTargets);
        assertEquals(1, requiredChanges.size());
        assertNotNull(requiredChanges.get(0).conflict());
        Models.Installed requiredState = Planner.next(next, release(), requiredTargets, requiredChanges, requiredChanges, config);
        new Installer(root).install(next, requiredState, requiredChanges, Map.of(required.path(), source), false);
        assertEquals(required.sha512(), FilesSupport.hash(original));
        assertFalse(requiredState.files.get(required.path()).optional());
    }

    @Test void metadataOnlyTransitionPreservesTheAuthorBaselineAndRetainedChoice() throws Exception {
        Path original = file("config/a.json", "original");
        Models.Installed previous = installed("config/a.json", original);
        String originalHash = FilesSupport.hash(original);
        Files.writeString(original, "player");
        Path source = file("ModpackUpdate/downloads/new", "upstream");
        Models.Target optional = target("config/a.json", source, true);
        previous.files.put(optional.path(), new Models.Baseline(originalHash, optional.sha512(), true, false));
        var targets = Map.of(optional.path(), optional);
        var changes = Planner.plan(root, config, previous, targets);
        assertTrue(changes.isEmpty());
        Models.Installed next = Planner.next(previous, release(), targets, changes, changes, config);
        Models.Baseline baseline = next.files.get(optional.path());
        assertTrue(baseline.optional());
        assertTrue(baseline.retained());
        assertEquals(originalHash, baseline.sha512());
        assertEquals(optional.sha512(), baseline.targetSha512());
        assertEquals("player", Files.readString(original));
    }
}
