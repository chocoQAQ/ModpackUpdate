package dev.modpackupdate.updater;

import dev.modpackupdate.bootstrap.UpdateBootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Reproduces observed defects in main at fb58e0a; not a production test suite. */
public final class MainReviewProbe {
    private static Path output;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected an existing output directory");
        output = Path.of(args[0]).toRealPath();
        caseOnlyRename();
        nonCanonicalPaths();
        protectedPathAlias();
        optionalTransition();
        failedHelperWithPendingTransaction();
        corruptHelperCache();
        System.out.println("All six probes reproduced the reviewed behavior.");
    }

    private static Path instance(String name) throws IOException {
        Path root = Files.createTempDirectory(output, name + "-").toRealPath();
        Files.createDirectories(root.resolve("ModpackUpdate/downloads"));
        return root;
    }

    private static Path text(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static Path mod(Path root, String relative, String version) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"example\",\"version\":\"" + version + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static Models.Installed installed(Path root, String relative, Path original) throws Exception {
        Models.Installed state = new Models.Installed();
        state.packId = "review";
        state.releaseId = 1;
        state.version = "1.0.0";
        String hash = FilesSupport.hash(original);
        state.files.put(relative, new Models.Baseline(hash, hash, false, false));
        FilesSupport.write(root.resolve("ModpackUpdate/config/installed.json"), state);
        return state;
    }

    private static Models.Target target(String relative, Path source, boolean optional) throws Exception {
        return new Models.Target(relative, FilesSupport.hash(source), Files.size(source),
                List.of("https://example.invalid/unused"), false, optional);
    }

    private static Models.Release release() {
        return new Models.Release(1, "review", 2, "1.1.0", "", null, null, null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void caseOnlyRename() throws Exception {
        Path root = instance("case-rename");
        Path original = mod(root, "mods/Example.jar", "1");
        if (!Files.exists(root.resolve("mods/example.jar"))) {
            throw new IllegalStateException("The case rename probe requires a case-insensitive filesystem");
        }
        Models.Installed previous = installed(root, "mods/Example.jar", original);
        Path source = mod(root, "ModpackUpdate/downloads/new.jar", "2");
        Models.Target updated = target("mods/example.jar", source, false);
        Map<String, Models.Target> targets = Map.of(updated.path(), updated);
        Models.Config config = new Models.Config();
        var changes = Planner.plan(root, config, previous, targets);
        require(changes.size() == 2, "Expected install and disable operations for the same file");
        Models.Installed next = Planner.next(previous, release(), targets, changes, changes, config);
        new Installer(root).install(previous, next, changes, Map.of(updated.path(), source), false);
        require(!Files.exists(root.resolve(updated.path())), "Expected the new mod to be disabled");
        require(FilesSupport.hash(root.resolve("mods/Example.jar.disable")).equals(updated.sha512()),
                "Expected the disabled file to contain the new version");
        require(FilesSupport.read(root.resolve("ModpackUpdate/config/installed.json"),
                Models.Installed.class).releaseId == 2, "Expected a committed release");
        System.out.println("CASE_RENAME: release=2, activeMod=false, disabledContent=new; " + root);
    }

    private static Map<String, Models.Target> parsedTargets(Path root, Path first, Path second) throws Exception {
        String indexText = """
                {"formatVersion":1,"game":"minecraft","versionId":"1.1.0","name":"Review",
                 "dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.10"},
                 "files":[
                   {"path":"config/a.json","hashes":{"sha512":"%s"},"fileSize":%d,
                    "downloads":["https://example.invalid/a"]},
                   {"path":"config//a.json","hashes":{"sha512":"%s"},"fileSize":%d,
                    "downloads":["https://example.invalid/b"]}
                 ]}
                """.formatted(FilesSupport.hash(first), Files.size(first), FilesSupport.hash(second), Files.size(second));
        Path index = text(root, "index.json", indexText);
        String hash = FilesSupport.hash(index);
        Path cache = root.resolve("ModpackUpdate/downloads");
        Files.copy(index, cache.resolve(hash));
        Models.Artifact artifact = new Models.Artifact("https://example.invalid/index", hash, Files.size(index));
        Models.Release release = new Models.Release(1, "review", 2, "1.1.0", "", artifact,
                new Models.Overrides(false, null, List.of()), artifact);
        return new Protocol(new Downloads(2), cache).targets(release, root, "1.20.1", "0.16.10");
    }

    private static void nonCanonicalPaths() throws Exception {
        Path root = instance("path-alias");
        Path original = text(root, "config/a.json", "old");
        Models.Installed previous = installed(root, "config/a.json", original);
        Path first = text(root, "ModpackUpdate/downloads/first", "first-new");
        Path second = text(root, "ModpackUpdate/downloads/second", "second-new");
        var targets = parsedTargets(root, first, second);
        require(targets.size() == 2, "Expected both aliases to pass protocol validation");
        Models.Config config = new Models.Config();
        var changes = Planner.plan(root, config, previous, targets);
        Models.Installed next = Planner.next(previous, release(), targets, changes, changes, config);
        Installer installer = new Installer(root);
        Exception failure = null;
        try {
            installer.install(previous, next, changes, Map.of("config/a.json", first, "config//a.json", second), false);
        } catch (Exception expected) { failure = expected; }
        require(failure != null && installer.pending(), "Expected installation and automatic recovery to fail");
        boolean recoveryFailed = false;
        try { installer.recover(); } catch (IOException expected) { recoveryFailed = true; }
        require(recoveryFailed && Files.readString(original).equals("first-new"),
                "Expected the alias collision to leave a partial installation");
        System.out.println("PATH_ALIAS: acceptedPaths=2, pending=true, retryRecovery=failed, content=first-new; " + root);
    }

    private static void protectedPathAlias() throws Exception {
        Path root = instance("protected-alias");
        Path original = text(root, "options.txt", "player-settings");
        Models.Config config = new Models.Config();
        Path source = text(root, "ModpackUpdate/downloads/settings", "author-settings");
        Models.Target updated = target("options.txt/", source, false);
        require(FilesSupport.resolve(root, updated.path()).equals(original), "Expected a filesystem alias");
        require(!FilesSupport.protectedPath(updated.path(), config), "Expected protection to miss the alias");
        var targets = Map.of(updated.path(), updated);
        var changes = Planner.plan(root, config, new Models.Installed(), targets);
        require(changes.size() == 1, "Expected a protected file to enter conflict selection");
        Models.Installed next = Planner.next(new Models.Installed(), release(), targets, changes, changes, config);
        new Installer(root).install(new Models.Installed(), next, changes, Map.of(updated.path(), source), false);
        require(Files.readString(original).equals("author-settings"), "Expected selected alias to overwrite options.txt");
        System.out.println("PROTECTED_ALIAS: options.txt/ accepted, protected=false, options.txt overwritten; " + root);
    }

    private static void optionalTransition() throws Exception {
        Path root = instance("optional-transition");
        Path original = mod(root, "mods/example.jar", "1");
        Models.Installed previous = installed(root, "mods/example.jar", original);
        Models.Config config = new Models.Config();
        Models.Target optional = target("mods/example.jar", original, true);
        var targets = Map.of(optional.path(), optional);
        var changes = Planner.plan(root, config, previous, targets);
        Models.Installed next = Planner.next(previous, release(), targets, changes, changes, config);
        require(!next.files.get(optional.path()).optional(), "Expected the optional attribute to stay stale");
        Models.Target required = target("mods/example.jar", original, false);
        Files.delete(original);
        var nextChanges = Planner.plan(root, config, next, Map.of(required.path(), required));
        require(nextChanges.isEmpty(), "Expected the missing required mod to be skipped");
        System.out.println("OPTIONAL_TRANSITION: required->optional recorded=false, optional->required changes=0, modMissing=true; " + root);
    }

    private static void pendingTransaction(Path root) throws Exception {
        Path original = text(root, "config/example.json", "old");
        String oldHash = FilesSupport.hash(original);
        installed(root, "config/example.json", original);
        text(root, "ModpackUpdate/1.0.0/interrupted/config/example.json", "old");
        Files.writeString(original, "partially-installed");
        Models.Journal journal = new Models.Journal();
        journal.id = "interrupted";
        journal.operations = List.of(new Models.Operation("config/example.json",
                "1.0.0/interrupted/config/example.json", null, FilesSupport.hash(original), oldHash));
        FilesSupport.write(root.resolve("ModpackUpdate/transaction.json"), journal);
    }

    private static void failedHelperWithPendingTransaction() throws Exception {
        Path root = instance("pending-helper-failure");
        pendingTransaction(root);
        Files.createDirectory(root.resolve("ModpackUpdate/update.lock"));
        UpdateBootstrap.check(root, "1.20.1", "0.16.10", "fabric-loader");
        require(new Installer(root).pending(), "Expected an unrecovered transaction");
        require(Files.readString(root.resolve("config/example.json")).equals("partially-installed"),
                "Expected recovery not to run");
        System.out.println("PENDING_HELPER_FAILURE: bootstrapReturned=true, pending=true, content=partially-installed; " + root);
    }

    private static void corruptHelperCache() throws Exception {
        Path root = instance("corrupt-helper");
        Path runtime = root.resolve("ModpackUpdate/runtime");
        Files.createDirectories(runtime);
        byte[] embedded;
        try (var input = UpdateBootstrap.class.getResourceAsStream("/modpackupdate/updater.jar")) {
            require(input != null, "Expected the packaged updater resource on the classpath");
            embedded = input.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(embedded));
        Path cached = runtime.resolve("updater-" + hash + ".jar");
        byte[] corrupt = "not-a-jar".getBytes(StandardCharsets.UTF_8);
        Files.write(cached, corrupt);
        var extract = UpdateBootstrap.class.getDeclaredMethod("extractHelper", Path.class);
        extract.setAccessible(true);
        for (int attempt = 0; attempt < 2; attempt++) {
            require(cached.equals(extract.invoke(null, runtime)), "Expected the existing cache path");
            require(Arrays.equals(corrupt, Files.readAllBytes(cached)), "Expected the corrupt cache to be reused");
        }
        System.out.println("CORRUPT_HELPER: extractAttempts=2, cacheRepaired=false, cachedBytes=not-a-jar; " + root);
    }

    private MainReviewProbe() {}
}
