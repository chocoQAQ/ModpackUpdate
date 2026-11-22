package dev.modpackupdate.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class LocalImports {
    private static final long LIMIT = 20L * 1024 * 1024 * 1024;
    record Prepared(Path archive, Models.ImportReceipt receipt) {}

    static List<Path> list(Path root) throws IOException {
        Path inbox = FilesSupport.resolve(root, "ModpackUpdate/inbox");
        Files.createDirectories(inbox);
        try (var paths = Files.list(inbox)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mrpack"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    static Prepared prepare(Path root, Path source) throws Exception {
        Path inbox = FilesSupport.resolve(root, "ModpackUpdate/inbox");
        if (!source.getParent().equals(inbox)) throw new IOException("本地包必须位于 inbox 顶层");
        String relative = FilesSupport.portable(root.relativize(source));
        source = FilesSupport.resolve(root, relative);
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > LIMIT) throw new IOException("本地包不是普通文件或超过 20 GiB");
        Path cache = FilesSupport.resolve(root, "ModpackUpdate/downloads");
        Storage.requireSpace(cache, before.size());
        Path temporary = Files.createTempFile(cache, "local-", ".tmp");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            long total = 0;
            try (var input = Files.newInputStream(source); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[128 * 1024];
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    CheckSession.checkInterrupted();
                    total += length;
                    if (total > before.size()) throw new IOException("本地包仍在写入，请完成复制后重试");
                    output.write(buffer, 0, length);
                    digest.update(buffer, 0, length);
                }
            }
            BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (total != before.size() || after.size() != before.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("本地包在读取期间发生变化，请重试");
            String hash = HexFormat.of().formatHex(digest.digest());
            Path archive = FilesSupport.resolve(root, "ModpackUpdate/downloads/" + hash);
            FilesSupport.replace(temporary, archive);
            String destination = "ModpackUpdate/imported/" + UUID.randomUUID() + "-" + source.getFileName();
            FilesSupport.resolve(root, destination);
            return new Prepared(archive, new Models.ImportReceipt(relative, hash, destination));
        } finally { Files.deleteIfExists(temporary); }
    }

    // Receipt is committed with installed.json. Recovery can finish archiving after a committed install.
    static String finish(Path root, Models.Installed installed) {
        Models.ImportReceipt receipt = installed.localImport;
        if (receipt == null) return null;
        try {
            FilesSupport.requireHash(receipt.sha512());
            Path source = FilesSupport.resolve(root, receipt.source());
            Path destination = FilesSupport.resolve(root, receipt.archivedPath());
            if (!source.getParent().equals(FilesSupport.resolve(root, "ModpackUpdate/inbox"))
                    || !destination.getParent().equals(FilesSupport.resolve(root, "ModpackUpdate/imported"))) {
                throw new IOException("归档记录路径无效");
            }
            if (Files.isRegularFile(destination)) return null;
            if (!Files.isRegularFile(source) || !FilesSupport.hash(source).equals(receipt.sha512())) {
                throw new IOException("原包已被移除或修改，保留 inbox 当前内容");
            }
            Files.createDirectories(destination.getParent());
            Files.move(source, destination);
            FilesSupport.syncDirectory(destination.getParent());
            FilesSupport.syncDirectory(source.getParent());
            return null;
        } catch (Exception e) {
            String warning = "本地包归档未完成（安装记录已提交）: " + e.getMessage();
            System.err.println(warning);
            return warning;
        }
    }
    private LocalImports() {}
}
