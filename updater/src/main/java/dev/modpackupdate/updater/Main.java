package dev.modpackupdate.updater;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class Main {
    public static void main(String[] args) {
        int code = 0;
        try {
            if (args.length == 1 && args[0].equals("--version")) {
                String version = Main.class.getPackage().getImplementationVersion();
                System.out.println("ModpackUpdate " + (version == null ? "development" : version));
            } else if ((args.length == 6 || args.length == 7) && args[0].equals("seed")) {
                seed(args);
            } else if ((args.length == 6 || args.length == 7) && args[0].equals("check")) {
                code = check(args);
            } else {
                System.err.println("Usage: java -jar updater.jar seed <instance> <release-url> <pack-id> <minecraft> <loader-version> [fabric-loader|neoforge]");
                code = 2;
            }
        } catch (Exception e) { e.printStackTrace(); code = 1; }
        System.exit(code);
    }
    private static int check(String[] args) throws Exception {
        Path root = Path.of(args[1]).toRealPath();
        Path work = FilesSupport.resolve(root, "ModpackUpdate");
        Files.createDirectories(work);
        Path lockPath = FilesSupport.resolve(root, "ModpackUpdate/update.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) { System.err.println("已有更新程序正在处理此实例"); return 20; }
            return checkLocked(root, args);
        }
    }
    private static int checkLocked(Path root, String[] args) throws Exception {
        long parentId = Long.parseLong(args[2]);
        Path requestedSignal = Path.of(args[3]).toAbsolutePath().normalize();
        Path signal = requestedSignal.getParent().toRealPath().resolve(requestedSignal.getFileName());
        Path runtime = FilesSupport.resolve(root, "ModpackUpdate/runtime");
        if (!signal.getParent().equals(runtime) || !signal.getFileName().toString().startsWith("handoff-")) throw new IOException("无效交接路径");
        String loaderType = loaderType(args);
        Installer installer = new Installer(root, loaderType);
        boolean recovery = installer.pending();
        UpdateUi ui = new UpdateUi();
        boolean handedOff = false;
        try {
            if (otherGameRunning(root, parentId)) {
                if (recovery) return 20;
                System.out.println("同一实例的另一个游戏进程仍在运行，本次跳过检查");
                return 0;
            }
            if (recovery) {
                ui.progress("恢复上次中断的安装，正在等待游戏进程退出…");
                handoff(signal, parentId);
                handedOff = true;
                installer.recover();
                ui.message("已恢复上次安装状态，请从启动器重新启动游戏。", false);
                return 0;
            }
            Path configFile = FilesSupport.resolve(root, "ModpackUpdate/config/config.json");
            if (!Files.exists(configFile)) FilesSupport.write(configFile, new Models.Config());
            Models.Config config = FilesSupport.read(configFile, Models.Config.class);
            if (!config.enable) return 0;
            if (config.protectedPaths == null) throw new IOException("protected-paths 必须是数组");
            Path state = FilesSupport.resolve(root, "ModpackUpdate/config/installed.json");
            Models.Installed installed = Files.exists(state) ? FilesSupport.read(state, Models.Installed.class) : new Models.Installed();
            validateInstalled(root, installed, config.packId);
            Path cache = FilesSupport.resolve(root, "ModpackUpdate/downloads");
            Protocol protocol = new Protocol(new Downloads(config.timeoutSeconds), cache);
            Models.Release release = protocol.release(config.updateServiceUrl, config.packId);
            if (release.releaseId() <= installed.releaseId) return 0;
            if (!config.silentUpdate && !ui.confirm(installed, release)) return 0;
            ui.progress("正在等待游戏进程退出…");
            handoff(signal, parentId);
            handedOff = true;
            ui.progress("读取整合包索引并检查游戏版本…");
            Map<String, Models.Target> targets = protocol.targets(release, root, args[4], args[5], loaderType);
            ui.progress("检查即将变更的文件…");
            var changes = Planner.plan(root, config, installed, targets);
            UpdateUi.Selection selection = ui.choose(changes, config);
            if (selection == null) return 0;
            Map<String, Path> staged = new LinkedHashMap<>();
            int number = 0;
            for (Models.Change change : selection.changes()) {
                if (change.disable() || change.target().sha512().equals(change.observedHash())) continue;
                ui.progress("下载 " + (++number) + " · " + change.path());
                staged.put(change.path(), protocol.stage(change.target(), release));
            }
            ui.progress("安装文件并保存版本记录…");
            Models.Installed next = Planner.next(installed, release, targets, changes, selection.changes(), config);
            installer.install(installed, next, selection.changes(), staged, selection.deleteReplacedMods());
            long retained = next.files.values().stream().filter(Models.Baseline::retained).count();
            ui.message("已更新至 " + release.version() + "。\n保留本地差异 " + retained + " 项。\n请从启动器重新启动游戏。", false);
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            if (handedOff) {
                try { ui.message("更新失败\n" + e.getMessage() + "\n详细记录见 ModpackUpdate/runtime/ 下的日志。", true); }
                catch (Exception unavailable) { unavailable.printStackTrace(); }
            }
            return recovery ? 20 : 1;
        } finally { ui.close(); }
    }
    private static void handoff(Path signal, long parentId) throws Exception {
        ProcessHandle parent = ProcessHandle.of(parentId).orElseThrow(() -> new IOException("游戏进程已经退出"));
        FilesSupport.write(signal, Map.of("ready", true));
        parent.onExit().get(120, TimeUnit.SECONDS);
    }
    private static boolean otherGameRunning(Path root, long parentId) throws Exception {
        Path games = FilesSupport.resolve(root, "ModpackUpdate/runtime/games");
        if (!Files.isDirectory(games)) return false;
        try (var files = Files.list(games)) {
            for (Path file : files.toList()) {
                long pid = Long.parseLong(file.getFileName().toString());
                if (pid == parentId) continue;
                var process = ProcessHandle.of(pid);
                if (process.isPresent() && process.get().isAlive()) {
                    String started = process.get().info().startInstant().map(Object::toString).orElse("");
                    if (started.equals(Files.readString(file))) return true;
                }
            }
        }
        return false;
    }
    private static void validateInstalled(Path root, Models.Installed installed, String packId) throws IOException {
        if (installed.schemaVersion != 1 || installed.files == null || installed.packId == null
                || (!installed.packId.isEmpty() && !installed.packId.equals(packId))) throw new IOException("本地安装记录不匹配");
        for (var entry : installed.files.entrySet()) {
            FilesSupport.resolve(root, entry.getKey());
            if (entry.getValue() == null) throw new IOException("无效文件基线");
            if (entry.getValue().sha512() != null) FilesSupport.requireHash(entry.getValue().sha512());
            FilesSupport.requireHash(entry.getValue().targetSha512());
        }
    }
    private static void seed(String[] args) throws Exception {
        Path root = Path.of(args[1]).toRealPath();
        Path state = FilesSupport.resolve(root, "ModpackUpdate/config/installed.json");
        if (Files.exists(state)) throw new IOException("installed.json 已存在，请使用全新打包目录生成基线");
        Path cache = FilesSupport.resolve(root, "ModpackUpdate/downloads");
        Protocol protocol = new Protocol(new Downloads(30), cache);
        Models.Release release = protocol.release(args[2], args[3]);
        Map<String, Models.Target> targets = protocol.targets(release, root, args[4], args[5], loaderType(args));
        Models.Config config = new Models.Config();
        config.enable = true;
        config.packId = args[3];
        config.updateServiceUrl = args[2];
        Models.Installed installed = new Models.Installed();
        installed.packId = release.packId();
        installed.releaseId = release.releaseId();
        installed.version = release.version();
        for (Models.Target target : targets.values()) {
            if (FilesSupport.protectedPath(target.path(), config)) continue;
            if (target.optional() && !Files.exists(FilesSupport.resolve(root, target.path()))) continue;
            installed.files.put(target.path(), new Models.Baseline(target.sha512(), target.sha512(), false, target.optional()));
        }
        FilesSupport.write(state, installed);
        Path configFile = FilesSupport.resolve(root, "ModpackUpdate/config/config.json");
        if (!Files.exists(configFile)) FilesSupport.write(configFile, config);
        System.out.println("已生成 " + state + "。请将 config.json 的 update-service-url 指向正式更新入口。");
    }
    private static String loaderType(String[] args) throws IOException {
        String type = args.length == 7 ? args[6] : "fabric-loader";
        if (!type.equals("fabric-loader") && !type.equals("neoforge")) throw new IOException("不支持的加载器 " + type);
        return type;
    }

    private Main() {}
}
