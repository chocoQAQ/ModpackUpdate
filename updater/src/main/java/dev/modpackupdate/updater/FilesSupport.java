package dev.modpackupdate.updater;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class FilesSupport {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_DASHES).create();
    static Path resolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains("\\") || relative.contains(":")) {
            throw new IOException("无效路径 " + relative);
        }
        Path local;
        try { local = Path.of(relative); }
        catch (InvalidPathException e) { throw new IOException("无效路径 " + relative, e); }
        if (local.isAbsolute()) throw new IOException("禁止绝对路径 " + relative);
        for (Path segment : local) {
            String name = segment.toString();
            if (name.equals("..") || name.equals(".") || name.endsWith(".") || name.endsWith(" ")
                    || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) {
                throw new IOException("不安全的路径 " + relative);
            }
        }
        Path destination = root.resolve(local).normalize();
        if (!destination.startsWith(root) || destination.equals(root)) throw new IOException("路径超出实例目录");
        Path cursor = root;
        for (Path segment : root.relativize(destination)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw new IOException("拒绝符号链接 " + cursor);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().startsWith(root.toRealPath())) {
                throw new IOException("路径指向实例外部 " + cursor);
            }
        }
        return destination;
    }
    static boolean managedMod(String path) {
        return path.startsWith("mods/") && path.endsWith(".jar");
    }
    static boolean protectedPath(String path, Models.Config config) {
        String normalized = path.toLowerCase(Locale.ROOT);
        if (normalized.equals("modpackupdate") || normalized.startsWith("modpackupdate/")) return true;
        for (String rule : config.protectedPaths) {
            String lowered = rule.toLowerCase(Locale.ROOT);
            if (lowered.endsWith("/") ? normalized.startsWith(lowered) : normalized.equals(lowered)) return true;
        }
        return false;
    }
    static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[128 * 1024];
                int length;
                while ((length = input.read(buffer)) >= 0) digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void requireHash(String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{128}")) throw new IOException("无效 SHA-512");
    }
    static <T> T read(Path file, Class<T> type) throws IOException {
        try {
            T result = JSON.fromJson(Files.readString(file), type);
            if (result == null) throw new IOException("空 JSON " + file);
            return result;
        } catch (RuntimeException e) { throw new IOException("JSON 格式错误 " + file, e); }
    }
    static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".write-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(value));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            replace(temporary, file);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void replace(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private FilesSupport() {}
}
