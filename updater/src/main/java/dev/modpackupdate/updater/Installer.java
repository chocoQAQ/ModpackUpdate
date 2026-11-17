package dev.modpackupdate.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipFile;

final class Installer {
    private final Path root;
    private final Path work;
    private final Path state;
    private final Path journalFile;
    private final String loaderType;
    Installer(Path root) throws IOException {
        this(root, "fabric-loader");
    }
    Installer(Path root, String loaderType) throws IOException {
        this.root = root;
        this.loaderType = loaderType;
        work = FilesSupport.resolve(root, "ModpackUpdate");
        state = FilesSupport.resolve(root, "ModpackUpdate/config/installed.json");
        journalFile = FilesSupport.resolve(root, "ModpackUpdate/transaction.json");
    }
    boolean pending() { return Files.exists(journalFile); }

    void install(Models.Installed previous, Models.Installed next, List<Models.Change> selected,
                 Map<String, Path> staged, boolean deleteReplacedMods) throws Exception {
        if (pending()) throw new IOException("有未恢复的安装事务");
        validateModIds(selected, staged);
        Models.Journal journal = new Models.Journal();
        journal.id = UUID.randomUUID().toString();
        journal.backupDirectory = safeVersion(previous.version) + "/" + journal.id;
        journal.deleteReplacedMods = deleteReplacedMods;
        for (Models.Change change : selected) {
            Path destination = FilesSupport.resolve(root, change.path());
            String actual = Files.exists(destination) ? FilesSupport.hash(destination) : null;
            if (!Objects.equals(actual, change.observedHash())) throw new IOException("文件在确认后发生变化 " + change.path());
            if (!change.disable() && change.target().sha512().equals(actual)) continue;
            if (!change.disable()) {
                Path source = staged.get(change.path());
                if (source == null || !FilesSupport.hash(source).equals(change.target().sha512())) throw new IOException("暂存文件损坏");
            }
            String disabledPath = null;
            if (change.disable()) {
                disabledPath = change.path() + ".disable";
                if (Files.exists(FilesSupport.resolve(root, disabledPath))) disabledPath = change.path() + "." + journal.id + ".disable";
            }
            journal.operations.add(new Models.Operation(change.path(), journal.backupDirectory + "/" + change.path(),
                    disabledPath, change.disable() ? null : change.target().sha512(), actual));
        }
        FilesSupport.write(journalFile, journal);
        try {
            for (Models.Operation operation : journal.operations) {
                Path destination = FilesSupport.resolve(root, operation.path());
                if (operation.disabledPath() != null) {
                    Files.move(destination, FilesSupport.resolve(root, operation.disabledPath()));
                    continue;
                }
                if (operation.oldHash() != null) {
                    Path backup = FilesSupport.resolve(work, operation.backup());
                    Files.createDirectories(backup.getParent());
                    Files.move(destination, backup);
                }
                Path temporary = Files.createTempFile(work.resolve("downloads"), "install-", ".tmp");
                try {
                    Files.copy(staged.get(operation.path()), temporary, StandardCopyOption.REPLACE_EXISTING);
                    FilesSupport.replace(temporary, destination);
                } finally { Files.deleteIfExists(temporary); }
            }
            next.transactionId = journal.id;
            FilesSupport.write(state, next);
        } catch (Exception failure) {
            try { recover(); } catch (Exception recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
        cleanupCommitted(journal);
    }

    void recover() throws Exception {
        if (!pending()) return;
        Models.Journal journal = FilesSupport.read(journalFile, Models.Journal.class);
        Models.Installed installed = Files.exists(state) ? FilesSupport.read(state, Models.Installed.class) : new Models.Installed();
        if (journal.id.equals(installed.transactionId)) { cleanupCommitted(journal); return; }
        for (int index = journal.operations.size() - 1; index >= 0; index--) {
            Models.Operation operation = journal.operations.get(index);
            Path destination = FilesSupport.resolve(root, operation.path());
            if (operation.disabledPath() != null) {
                Path disabled = FilesSupport.resolve(root, operation.disabledPath());
                if (Files.exists(disabled)) {
                    if (!FilesSupport.hash(disabled).equals(operation.oldHash())) throw new IOException("禁用文件已被外部修改 " + operation.path());
                    if (Files.exists(destination)) throw new IOException("恢复时发现同名文件 " + operation.path());
                    Files.move(disabled, destination);
                }
                continue;
            }
            Path backup = FilesSupport.resolve(work, operation.backup());
            String currentHash = Files.exists(destination) ? FilesSupport.hash(destination) : null;
            if (currentHash != null && !currentHash.equals(operation.newHash()) && !currentHash.equals(operation.oldHash())) {
                throw new IOException("恢复时发现外部修改 " + operation.path());
            }
            if (Files.exists(backup)) {
                if (!FilesSupport.hash(backup).equals(operation.oldHash())) throw new IOException("备份校验失败");
                FilesSupport.replace(backup, destination);
            } else if (operation.oldHash() == null) {
                Files.deleteIfExists(destination);
            } else if (!operation.oldHash().equals(currentHash)) {
                throw new IOException("旧文件及备份缺失 " + operation.path());
            }
        }
        Files.delete(journalFile);
    }
    private void cleanupCommitted(Models.Journal journal) throws IOException {
        if (journal.deleteReplacedMods) {
            for (Models.Operation operation : journal.operations) {
                if (operation.disabledPath() == null && FilesSupport.managedMod(operation.path())) {
                    Files.deleteIfExists(FilesSupport.resolve(work, operation.backup()));
                }
            }
        }
        Files.deleteIfExists(journalFile);
    }
    private String safeVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) return "unknown";
        return version;
    }
    private void validateModIds(List<Models.Change> selected, Map<String, Path> staged) throws Exception {
        Map<String, Path> active = new HashMap<>();
        Path mods = FilesSupport.resolve(root, "mods");
        if (Files.isDirectory(mods)) {
            try (var files = Files.list(mods)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                    String relative = "mods/" + file.getFileName();
                    FilesSupport.resolve(root, relative);
                    active.put(relative, file);
                }
            }
        }
        for (Models.Change change : selected) {
            if (!FilesSupport.managedMod(change.path())) continue;
            if (change.disable()) active.remove(change.path());
            else active.put(change.path(), staged.getOrDefault(change.path(), FilesSupport.resolve(root, change.path())));
        }
        Map<String, String> ids = new HashMap<>();
        for (var entry : active.entrySet()) {
            try (ZipFile zip = new ZipFile(entry.getValue().toFile())) {
                for (String id : ModMetadata.ids(zip, loaderType)) {
                    String previous = ids.putIfAbsent(id, entry.getKey());
                    if (previous != null) throw new IOException("存在两个相同 ID 的模组 " + previous + " / " + entry.getKey() + "，请调整冲突选择");
                }
            }
        }
    }
}
