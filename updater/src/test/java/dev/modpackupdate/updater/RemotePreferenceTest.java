package dev.modpackupdate.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RemotePreferenceTest {
    @TempDir Path root;
    private Path mod(String name, String id, String loader) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        String entry = "fabric.mod.json";
        String content = "{\"id\":\"" + id + "\",\"version\":\"1\"}";
        if (loader.equals("quilt-loader")) {
            entry = "quilt.mod.json";
            content = "{\"quilt_loader\":{\"id\":\"" + id + "\"}}";
        } else if (loader.equals("forge") || loader.equals("neoforge")) {
            entry = loader.equals("forge") ? "META-INF/mods.toml" : "META-INF/neoforge.mods.toml";
            content = "[[mods]]\nmodId=\"" + id + "\"\n";
        }
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
    private Models.Target target(String name, Path payload) throws Exception {
        return new Models.Target(name, FilesSupport.hash(payload), Files.size(payload), List.of(), true, false);
    }
    private Models.Release release() {
        return new Models.Release(1, "pack", 2, "2.0", "", null, null, null);
    }

    @ParameterizedTest @ValueSource(strings = {"fabric-loader", "quilt-loader", "forge", "neoforge"})
    void adoptsRemoteSelfUpdateEvenWhenLocalFilenameIsUntracked(String loader) throws Exception {
        Path old = mod("mods/renamed-updater.jar", "modpackupdate", loader);
        Path unrelated = mod("mods/player-extra.jar", "extra", loader);
        String oldHash = FilesSupport.hash(old);
        Path payload = mod("ModpackUpdate/downloads/cloud.jar", "modpackupdate", loader);
        var target = target("mods/ModpackUpdate-2.jar", payload);
        var targets = Map.of(target.path(), target);
        var previous = new Models.Installed();
        var config = new Models.Config();
        var changes = Planner.plan(root, config, previous, targets);
        var installer = new Installer(root, loader);
        var staged = Map.of(target.path(), payload);
        var selected = installer.preferRemoteMods(changes, changes, staged, targets, config);
        for (var change : selected) if (!changes.contains(change)) changes.add(change);
        var next = Planner.next(previous, release(), targets, changes, selected, config);
        installer.install(previous, next, selected, staged, false);
        assertFalse(Files.exists(old));
        assertEquals(oldHash, FilesSupport.hash(root.resolve("mods/renamed-updater.jar.disable")));
        assertEquals(target.sha512(), FilesSupport.hash(root.resolve(target.path())));
        assertTrue(Files.exists(unrelated));
        assertFalse(installer.pending());
        assertEquals("complete", InstalledStore.read(root).installationStatus);
    }

    @Test void skippedRemoteDoesNotDisableLocalAndProtectedLocalCannotBeReplaced() throws Exception {
        Path old = mod("mods/old.jar", "same", "fabric-loader");
        Path payload = mod("ModpackUpdate/downloads/new.jar", "same", "fabric-loader");
        var target = target("mods/new.jar", payload);
        var targets = Map.of(target.path(), target);
        var config = new Models.Config();
        var changes = Planner.plan(root, config, new Models.Installed(), targets);
        var installer = new Installer(root);
        assertTrue(installer.preferRemoteMods(changes, List.of(), Map.of(), targets, config).isEmpty());
        config.protectedPaths.add("mods/old.jar");
        assertThrows(Exception.class, () -> installer.preferRemoteMods(changes, changes, Map.of(target.path(), payload), targets, config));
        assertTrue(Files.exists(old));
        assertFalse(installer.pending());
    }

    @Test void remoteCannotPublishTwoFilesWithOneId() throws Exception {
        Path payload = mod("ModpackUpdate/downloads/new.jar", "same", "fabric-loader");
        var first = target("mods/one.jar", payload);
        var second = target("mods/two.jar", payload);
        var targets = Map.of(first.path(), first, second.path(), second);
        var config = new Models.Config();
        var changes = Planner.plan(root, config, new Models.Installed(), targets);
        assertThrows(Exception.class, () -> new Installer(root).preferRemoteMods(changes, changes,
                Map.of(first.path(), payload, second.path(), payload), targets, config));
        assertFalse(Files.exists(root.resolve(first.path())));
    }

    @Test void renamedModifiedTrackedModUsesExistingObservationAndRollsBackOnFailure() throws Exception {
        Path old = mod("mods/old.jar", "same", "fabric-loader");
        Path payload = mod("ModpackUpdate/downloads/new.jar", "same", "fabric-loader");
        var target = target("mods/new.jar", payload);
        var previous = new Models.Installed();
        previous.files.put("mods/old.jar", new Models.Baseline("a".repeat(128), "a".repeat(128), true));
        FilesSupport.write(root.resolve("ModpackUpdate/config/installed.json"), previous);
        var config = new Models.Config();
        var targets = Map.of(target.path(), target);
        var changes = Planner.plan(root, config, previous, targets);
        var installOnly = changes.stream().filter(change -> !change.disable()).toList();
        var installer = new Installer(root, "fabric-loader", step -> {
            if (step.equals("disabled")) throw new IllegalStateException("simulated failure");
        });
        var staged = Map.of(target.path(), payload);
        var selected = installer.preferRemoteMods(changes, installOnly, staged, targets, config);
        assertEquals(2, selected.size());
        assertTrue(selected.contains(changes.stream().filter(Models.Change::disable).findFirst().orElseThrow()));
        var next = Planner.next(previous, release(), targets, changes, selected, config);
        assertThrows(Exception.class, () -> installer.install(previous, next, selected, staged, false));
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(root.resolve(target.path())));
        assertFalse(installer.pending());
        assertEquals(previous.releaseId, InstalledStore.read(root).releaseId);
    }
}
