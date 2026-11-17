package dev.modpackupdate.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class UpdatePreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        Process child = null;
        boolean blocked = false;
        try {
            FabricLoader loader = FabricLoader.getInstance();
            Path root = loader.getGameDir().toRealPath();
            Path work = root.resolve("ModpackUpdate");
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
            String minecraft = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
            String fabric = loader.getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString();
            Path log = runtime.resolve("check-" + UUID.randomUUID() + ".log");
            child = new ProcessBuilder(java.toString(), "-jar", helper.toString(), "check",
                    root.toString(), Long.toString(ProcessHandle.current().pid()), signal.toString(), minecraft, fabric)
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (Files.isRegularFile(signal)) {
                    Files.deleteIfExists(signal);
                    System.exit(0);
                    return;
                }
            }
            Files.deleteIfExists(signal);
            blocked = child.exitValue() == 20;
        } catch (Exception e) {
            if (child != null) child.destroy();
            System.err.println("[ModpackUpdate] Update check skipped: " + e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        if (blocked) throw new IllegalStateException("ModpackUpdate 正在安装或恢复此实例，请完成更新后重新启动。");
    }

    private Path extractHelper(Path runtime) throws Exception {
        byte[] contents;
        try (InputStream input = getClass().getResourceAsStream("/modpackupdate/updater.jar")) {
            if (input == null) throw new IOException("Missing updater.jar");
            contents = input.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        Path destination = runtime.resolve("updater-" + hash + ".jar");
        if (!Files.exists(destination)) {
            Path temporary = Files.createTempFile(runtime, "extract-", ".jar");
            Files.write(temporary, contents);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }
}
