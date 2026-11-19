package dev.modpackupdate.updater;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Models {
    static final class Config {
        boolean enable = false;
        String updateServiceUrl = "";
        String fullPackUrl = "";
        String packId = "";
        boolean silentUpdate = false;
        boolean deleteReplacedMods = false;
        int timeoutSeconds = 10;
        List<String> protectedPaths = new ArrayList<>(List.of("options.txt", "optionsof.txt", "servers.dat", "saves/", "screenshots/"));
    }
    record Artifact(String url, String sha512, long size) {}
    record OverrideFile(String path, String sha512, long size) {}
    record Overrides(boolean present, Artifact archive, List<OverrideFile> files) {}
    record Release(int protocolVersion, String packId, long releaseId, String version,
                   String announcement, Artifact index, Overrides overrides, Artifact fullPack) {}
    static final class Installed {
        int schemaVersion = 1;
        String packId = "";
        long releaseId = -1;
        String version = "uninitialized";
        String transactionId = "";
        boolean fullPackFallback = false;
        Map<String, Baseline> files = new LinkedHashMap<>();
    }
    record Baseline(String sha512, String targetSha512, boolean retained, boolean optional) {
        Baseline(String sha512, String targetSha512, boolean retained) { this(sha512, targetSha512, retained, false); }
    }
    record Target(String path, String sha512, long size, List<String> downloads, boolean override, boolean optional) {}
    record Change(String path, Target target, String observedHash, String conflict) {
        boolean disable() { return target == null; }
    }
    static final class Journal {
        String id;
        String backupDirectory;
        boolean deleteReplacedMods;
        List<Operation> operations = new ArrayList<>();
    }
    record Operation(String path, String backup, String disabledPath, String newHash, String oldHash) {}
    private Models() {}
}
