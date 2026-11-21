package dev.modpackupdate.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class UpdateBootstrap {
    public static void check(Path gameDirectory, String minecraft, String loaderVersion, String loaderType) {
        Process child = null;
        boolean blocked = false;
        Path journal = gameDirectory.toAbsolutePath().normalize().resolve("ModpackUpdate/transaction.json");
        try {
            Path root = gameDirectory.toRealPath();
            Path work = root.resolve("ModpackUpdate");
            journal = work.resolve("transaction.json");
            Path runtime = work.resolve("runtime");
            Files.createDirectories(runtime);
            if (!runtime.toRealPath().startsWith(root)) throw new IOException("Unsafe runtime directory");
            Path games = runtime.resolve("games");
            Files.createDirectories(games);
            if (!games.toRealPath().startsWith(root)) throw new IOException("Unsafe games directory");
            Path marker = games.resolve(Long.toString(ProcessHandle.current().pid()));
            Files.writeString(marker, ProcessHandle.current().info().startInstant().map(Object::toString).orElse(""));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.deleteIfExists(marker); } catch (IOException ignored) {}
            }, "modpackupdate-instance-marker"));
            Path helper = extractHelper(runtime);
            Path signal = runtime.resolve("handoff-" + UUID.randomUUID());
            String javaName = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
            Path log = runtime.resolve("check-" + UUID.randomUUID() + ".log");
            child = new ProcessBuilder(java.toString(), "-jar", helper.toString(), "check",
                    root.toString(), Long.toString(ProcessHandle.current().pid()), signal.toString(), minecraft, loaderVersion, loaderType)
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long startupDeadline = System.currentTimeMillis() + 120_000;
            Path phase = signal.resolveSibling(signal.getFileName() + ".phase");
            while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (Files.isRegularFile(signal)) {
                    Files.deleteIfExists(signal);
                    System.exit(0);
                    return;
                }
                if (checkExpired(phase, startupDeadline, System.currentTimeMillis())) {
                    child.destroyForcibly();
                    if (!child.waitFor(10, TimeUnit.SECONDS)) blocked = true;
                    System.err.println("[ModpackUpdate] Update check timed out.");
                    break;
                }
            }
            Files.deleteIfExists(signal);
            Files.deleteIfExists(phase);
            blocked = blocked || child.isAlive() || child.exitValue() == 20;
        } catch (Exception e) {
            if (child != null) child.destroy();
            System.err.println("[ModpackUpdate] Update check skipped: " + e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        if (blocked || needsRecovery(journal)) {
            throw new IllegalStateException("ModpackUpdate 正在安装或恢复此实例，请完成更新后重新启动。");
        }
    }

    static boolean checkExpired(Path phase, long startupDeadline, long now) {
        try {
            if (Files.isRegularFile(phase) && Files.size(phase) < 64) {
                String value = Files.readString(phase).trim();
                if (value.equals("interactive")) return false;
                return now > Long.parseLong(value) + 5_000; // Allow the helper to stop and clean up first.
            }
        } catch (IOException | NumberFormatException ignored) {}
        return now > startupDeadline;
    }

    private static boolean needsRecovery(Path journal) {
        try { return !Files.notExists(journal, LinkOption.NOFOLLOW_LINKS); }
        catch (SecurityException e) { return true; }
    }

    private static Path extractHelper(Path runtime) throws Exception {
        byte[] contents;
        try (InputStream input = UpdateBootstrap.class.getResourceAsStream("/modpackupdate/updater.jar")) {
            if (input == null) throw new IOException("Missing updater.jar");
            contents = input.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        Path destination = runtime.resolve("updater-" + hash + ".jar");
        if (!validHelper(destination, contents.length, hash)) {
            Path temporary = Files.createTempFile(runtime, "extract-", ".jar");
            try {
                Files.write(temporary, contents);
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
        return destination;
    }

    private static boolean validHelper(Path path, int size, String hash) throws Exception {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != size) return false;
            try (InputStream input = Files.newInputStream(path)) {
                byte[] bytes = input.readNBytes(size + 1);
                return bytes.length == size && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(hash);
            }
        } catch (IOException e) { return false; }
    }

    private UpdateBootstrap() {}
}
