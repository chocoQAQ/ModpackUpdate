package dev.modpackupdate.updater;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Planner {
    static List<Models.Change> plan(Path root, Models.Config config, Models.Installed installed,
                                    Map<String, Models.Target> targets) throws Exception {
        List<Models.Change> changes = new ArrayList<>();
        for (Models.Target target : targets.values()) {
            if (FilesSupport.protectedPath(target.path(), config)) continue;
            Models.Baseline baseline = installed.files.get(target.path());
            boolean becameRequired = baseline != null && baseline.optional() && !target.optional();
            if (baseline != null && target.sha512().equals(baseline.targetSha512()) && !becameRequired) continue;
            Path destination = FilesSupport.resolve(root, target.path());
            String observed = Files.exists(destination) ? FilesSupport.hash(destination) : null;
            if (target.sha512().equals(observed)) {
                changes.add(new Models.Change(target.path(), target, observed, null));
                continue;
            }
            String conflict = null;
            if (baseline != null && !Objects.equals(observed, baseline.sha512())) conflict = "本地文件已修改或移除";
            if (baseline == null && observed != null) conflict = "现有文件缺少原版基线";
            if (target.optional() && observed == null) conflict = "可选模组，勾选后安装";
            if (becameRequired && observed == null) conflict = "此前未安装的可选文件已改为必需";
            changes.add(new Models.Change(target.path(), target, observed, conflict));
        }
        for (var entry : installed.files.entrySet()) {
            String path = entry.getKey();
            if (targets.containsKey(path) || !FilesSupport.managedMod(path) || FilesSupport.protectedPath(path, config)) continue;
            Path destination = FilesSupport.resolve(root, path);
            if (!Files.exists(destination)) continue;
            String observed = FilesSupport.hash(destination);
            String conflict = observed.equals(entry.getValue().sha512()) ? null : "即将禁用的旧模组已被修改";
            changes.add(new Models.Change(path, null, observed, conflict));
        }
        return changes;
    }
    static Models.Installed next(Models.Installed previous, Models.Release release, Map<String, Models.Target> targets,
                                 List<Models.Change> changes, List<Models.Change> selected, Models.Config config) {
        Models.Installed next = new Models.Installed();
        next.packId = release.packId();
        next.releaseId = release.releaseId();
        next.version = release.version();
        next.files.putAll(previous.files);
        for (Models.Change change : changes) {
            if (selected.contains(change)) {
                if (change.disable()) next.files.remove(change.path());
                else next.files.put(change.path(), new Models.Baseline(change.target().sha512(), change.target().sha512(), false, change.target().optional()));
            } else if (!change.disable()) {
                Models.Baseline old = previous.files.get(change.path());
                next.files.put(change.path(), new Models.Baseline(old == null ? null : old.sha512(), change.target().sha512(), true, change.target().optional()));
            }
        }
        next.files.keySet().removeIf(path -> FilesSupport.protectedPath(path, config));
        return next;
    }
    private Planner() {}
}
