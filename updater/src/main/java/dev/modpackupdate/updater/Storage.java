package dev.modpackupdate.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class Storage {
    private static final long RESERVE = 64L * 1024 * 1024;

    static void requireSpace(Path directory, long bytes) throws IOException {
        Files.createDirectories(directory);
        long available = Files.getFileStore(directory).getUsableSpace();
        if (bytes < 0 || available < RESERVE || bytes > available - RESERVE) {
            throw new IOException("磁盘空间不足，需要额外空间约 " + (Math.max(0, bytes) / 1024 / 1024 + 64) + " MiB");
        }
    }

    // Called only while holding update.lock and with no pending transaction. The default retains all backups.
    static void cleanup(Path root, int cacheMaxMib) throws IOException {
        cleanup(root, cacheMaxMib, 0);
    }
    static void cleanup(Path root, int cacheMaxMib, int backupRetentionCount) throws IOException {
        if (Files.exists(FilesSupport.resolve(root, "ModpackUpdate/transaction.json"))) return;
        if (backupRetentionCount > 0) pruneBackups(root, backupRetentionCount);
        Path cache = FilesSupport.resolve(root, "ModpackUpdate/downloads");
        if (Files.isDirectory(cache)) {
            List<Path> files;
            try (var stream = Files.list(cache)) {
                files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> cacheFile(path.getFileName().toString()))
                        .sorted(Comparator.comparingLong(Storage::modified)).toList();
            }
            long total = 0;
            for (Path file : files) total += Files.size(file);
            long budget = Math.max(0, cacheMaxMib) * 1024L * 1024;
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (total <= budget && name.matches("[0-9a-f]{128}")) continue;
                long size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
            }
        }
        Path runtime = FilesSupport.resolve(root, "ModpackUpdate/runtime");
        if (!Files.isDirectory(runtime)) return;
        Path running = runningJar();
        long cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000;
        try (var stream = Files.list(runtime)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !file.toAbsolutePath().normalize().equals(running)
                        && modified(file) < cutoff && (name.matches("updater-[0-9a-f]{64}\\.jar")
                        || name.matches("check-[0-9a-f-]+\\.log") || name.matches("handoff-[0-9a-f-]+(?:\\.phase)?"))) {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) { /* In-use files can wait. */ }
                }
            }
        }
    }

    private static void pruneBackups(Path root, int keep) throws IOException {
        Path work = FilesSupport.resolve(root, "ModpackUpdate");
        var backups = new java.util.ArrayList<Path>();
        try (var versions = Files.list(work)) {
            for (Path version : versions.toList()) {
                if (!Files.isDirectory(version, LinkOption.NOFOLLOW_LINKS)
                        || List.of("config", "runtime", "downloads").contains(version.getFileName().toString().toLowerCase(java.util.Locale.ROOT))) continue;
                FilesSupport.resolve(root, FilesSupport.portable(root.relativize(version)));
                try (var transactions = Files.list(version)) {
                    for (Path directory : transactions.toList()) {
                        String id = directory.getFileName().toString();
                        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                        Path marker = directory.resolve(".modpackupdate-backup.json");
                        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) continue;
                        try {
                            var data = FilesSupport.read(marker, com.google.gson.JsonObject.class);
                            if (id.equals(data.get("transaction-id").getAsString())) backups.add(directory);
                        } catch (Exception ignored) { /* Unrecognized backups require manual management. */ }
                    }
                }
            }
        }
        backups.sort(Comparator.comparingLong((Path p) -> modified(p.resolve(".modpackupdate-backup.json"))).reversed());
        for (Path directory : backups.subList(Math.min(keep, backups.size()), backups.size())) {
            List<Path> tree;
            try (var stream = Files.walk(directory)) { tree = stream.sorted(Comparator.reverseOrder()).toList(); }
            // Validate the entire tree before deleting anything; never follow links or junctions outside the instance.
            for (Path path : tree) {
                FilesSupport.resolve(root, FilesSupport.portable(root.relativize(path)));
                if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("备份目录包含路径别名，保留该备份 " + directory);
            }
            for (Path path : tree) Files.delete(path);
        }
    }

    private static boolean cacheFile(String name) {
        return name.matches("[0-9a-f]{128}(?:\\.part|\\.extract)?") || name.equals("fallback.mrpack.part")
                || name.equals("fallback-index.json") || name.matches("install-.*\\.tmp");
    }
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException e) { return Long.MAX_VALUE; }
    }
    private static Path runningJar() {
        try { return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize(); }
        catch (Exception e) { return null; }
    }
    private Storage() {}
}
