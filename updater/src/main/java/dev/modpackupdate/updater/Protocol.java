package dev.modpackupdate.updater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class Protocol {
    static final String REQUEST_PROTOCOL_HEADER = "X-ModpackUpdate-Protocol-Version";
    static final String REQUEST_PROTOCOL_VERSION = "2";
    private final Downloads downloads;
    private final Path cache;
    private FullPackFallback fallback;
    private boolean confirmationRequired;
    private Models.Release resolved;
    private Models.Release resolvedEntry;
    private Runnable fallbackStarted = () -> {};
    Protocol(Downloads downloads, Path cache) { this.downloads = downloads; this.cache = cache; }

    Models.Release release(String url, String packId) throws Exception {
        Path file = cache.resolve("release.json");
        try { downloads.fetch(url, file, 16 * 1024 * 1024,
                Map.of(REQUEST_PROTOCOL_HEADER, REQUEST_PROTOCOL_VERSION)); }
        catch (InterruptedException e) { throw e; }
        catch (Exception e) { throw new ReleaseUnavailableException(e); }
        Models.Release release = FilesSupport.read(file, Models.Release.class);
        validateRelease(release, packId);
        if (release.protocolVersion() == 2 && Files.size(file) > 64 * 1024) throw new IOException("v2 版本入口大小超限");
        return release;
    }

    // Version checks deliberately do not fetch the installation manifest or file indexes.
    Models.Release details(Models.Release release) throws Exception {
        if (release.manifest() == null) return release;
        if (release.equals(resolvedEntry)) return resolved;
        Path file = downloads.artifact(release.manifest(), cache);
        Models.Release detail = FilesSupport.read(file, Models.Release.class);
        validateRelease(detail, release.packId());
        if (detail.manifest() != null || detail.protocolVersion() != 1
                || detail.releaseId() != release.releaseId() || !detail.version().equals(release.version())) {
            throw new IOException("安装清单与版本入口不匹配");
        }
        resolved = detail;
        resolvedEntry = release;
        return detail;
    }

    private static final class ReleaseUnavailableException extends IOException {
        ReleaseUnavailableException(Exception cause) { super("无法获取更新接口清单", cause); }
    }

    boolean usingFallback() { return fallback != null; }
    boolean sequenceKnown() { return fallback == null || fallback.sequenceKnown(); }
    boolean requiresConfirmation() { return confirmationRequired; }
    void onFallback(Runnable action) { fallbackStarted = action; }

    Models.Release findUpdate(Models.Config config, Models.Installed installed, Path root,
                              String minecraft, String loader, String loaderType) throws Exception {
        return findUpdate(config, installed, root, minecraft, loader, loaderType, false);
    }

    Models.Release findUpdate(Models.Config config, Models.Installed installed, Path root,
                              String minecraft, String loader, String loaderType, boolean repair) throws Exception {
        fallback = null;
        resolved = null;
        resolvedEntry = null;
        confirmationRequired = false;
        try {
            Models.Release release = release(config.updateServiceUrl, config.packId);
            return candidate(release, installed, true, repair);
        } catch (ReleaseUnavailableException unavailable) {
            if (config.fullPackUrl == null || config.fullPackUrl.isBlank()) throw unavailable;
            CheckSession.checkInterrupted();
            System.out.println("更新接口清单不可用，检查完整整合包兜底源。");
            fallbackStarted.run();
            fallback = FullPackFallback.load(downloads, cache, config, installed, root, minecraft, loader, loaderType);
            return candidate(fallback.release(), installed, fallback.sequenceKnown(), repair);
        }
    }

    private Models.Release candidate(Models.Release release, Models.Installed installed, boolean numbered, boolean repair) {
        boolean sameVersion = release.version().equals(installed.version);
        boolean unknownInstalled = installed.fullPackFallback && !installed.fullPackSequenceKnown;
        confirmationRequired = !numbered || unknownInstalled;
        if (repair && sameVersion && (!numbered || release.releaseId() >= installed.releaseId)) return release;
        if (numbered && release.releaseId() <= installed.releaseId) return null;
        if (sameVersion && (usingFallback() || installed.fullPackFallback)) return null;
        return release;
    }

    static void validateRelease(Models.Release release, String packId) throws IOException {
        if (release == null || (release.protocolVersion() != 1 && release.protocolVersion() != 2)
                || packId == null || packId.isBlank() || !packId.equals(release.packId())) {
            throw new IOException("发布协议版本或整合包 ID 不匹配");
        }
        if (release.releaseId() < 0 || release.version() == null || !release.version().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IOException("发布版本无效");
        }
        if (release.protocolVersion() == 2) {
            validateArtifact(release.manifest());
            if (release.manifest().size() > 16 * 1024 * 1024) throw new IOException("安装清单大小超限");
            if (release.index() != null || release.overrides() != null || release.fullPack() != null || release.announcement() != null) {
                throw new IOException("v2 版本入口只允许检查字段，安装数据请放入 manifest");
            }
            return;
        }
        if (release.manifest() != null) throw new IOException("v1 清单不能引用其他安装清单");
        validateArtifact(release.index());
        validateArtifact(release.fullPack());
        if (release.overrides() == null || release.overrides().files() == null) throw new IOException("缺少 overrides 声明");
        if (release.overrides().present()) validateArtifact(release.overrides().archive());
        else if (!release.overrides().files().isEmpty()) throw new IOException("overrides 声明不一致");
    }
    private static void validateArtifact(Models.Artifact artifact) throws IOException {
        if (artifact == null || artifact.url() == null || artifact.size() < 0 || artifact.size() > 20L * 1024 * 1024 * 1024) {
            throw new IOException("无效的下载产物");
        }
        FilesSupport.requireHash(artifact.sha512());
    }

    Map<String, Models.Target> targets(Models.Release release, Path root, String minecraft, String loader) throws Exception {
        return targets(release, root, minecraft, loader, "fabric-loader");
    }

    Map<String, Models.Target> targets(Models.Release release, Path root, String minecraft, String loader, String loaderType) throws Exception {
        if (fallback != null) return fallback.targets();
        release = details(release);
        Path index;
        try { index = downloads.artifact(release.index(), cache); }
        catch (Exception e) {
            Path pack = downloads.artifact(release.fullPack(), cache);
            index = extract(pack, "modrinth.index.json", release.index().sha512(), release.index().size());
        }
        return parseTargets(release, root, minecraft, loader, loaderType, index);
    }

    static Map<String, Models.Target> parseTargets(Models.Release release, Path root, String minecraft, String loader, String loaderType, Path index) throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
        if (json.get("formatVersion").getAsInt() != 1 || !json.get("game").getAsString().equals("minecraft")) {
            throw new IOException("只支持 Modrinth formatVersion 1 / Minecraft");
        }
        if (!release.version().equals(json.get("versionId").getAsString())) throw new IOException("清单版本不一致");
        validateDependencies(json.getAsJsonObject("dependencies"), minecraft, loader, loaderType);
        Map<String, Models.Target> result = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        for (var element : json.getAsJsonArray("files")) {
            CheckSession.checkInterrupted();
            JsonObject file = element.getAsJsonObject();
            String path = file.get("path").getAsString();
            validatePath(root, paths, path);
            String environment = file.has("env") ? file.getAsJsonObject("env").get("client").getAsString() : "required";
            if (environment.equals("unsupported")) continue;
            if (!List.of("required", "optional").contains(environment)) throw new IOException("未知客户端 env");
            String hash = file.getAsJsonObject("hashes").get("sha512").getAsString();
            FilesSupport.requireHash(hash);
            long size = file.get("fileSize").getAsLong();
            if (size < 0 || size > 20L * 1024 * 1024 * 1024) throw new IOException("文件大小超限");
            List<String> urls = new ArrayList<>();
            for (var url : file.getAsJsonArray("downloads")) urls.add(url.getAsString());
            if (urls.isEmpty()) throw new IOException("缺少文件下载地址");
            result.put(path, new Models.Target(path, hash, size, urls, false, environment.equals("optional")));
        }
        Set<String> overridePaths = new HashSet<>();
        for (Models.OverrideFile file : release.overrides().files()) {
            validatePath(root, overridePaths, file.path());
            FilesSupport.requireHash(file.sha512());
            if (file.size() < 0 || file.size() > 20L * 1024 * 1024 * 1024) throw new IOException("override 文件大小超限");
            // Overrides are the final client layer and can replace an indexed file at the same path.
            for (String existing : result.keySet()) {
                if (!existing.equals(file.path()) && existing.equalsIgnoreCase(file.path())) throw new IOException("路径大小写冲突");
            }
            result.put(file.path(), new Models.Target(file.path(), file.sha512(), file.size(), List.of(), true, false));
        }
        FilesSupport.validateFilePaths(result.keySet());
        return result;
    }

    static final List<String> LOADERS = List.of("fabric-loader", "neoforge", "forge", "quilt-loader");

    static void validateDependencies(JsonObject dependencies, String minecraft, String loader, String loaderType) throws IOException {
        if (!LOADERS.contains(loaderType)) throw new IOException("不支持的加载器 " + loaderType);
        if (dependencies == null || !dependencies.has("minecraft") || !dependencies.has(loaderType)
                || !minecraft.equals(dependencies.get("minecraft").getAsString())
                || !loader.equals(dependencies.get(loaderType).getAsString())) {
            throw new IOException("目标 Minecraft / " + loaderType + " 版本与当前实例不同，请先通过启动器安装对应版本");
        }
        for (String other : LOADERS) {
            if (!other.equals(loaderType) && dependencies.has(other)) throw new IOException("整合包包含不匹配的加载器 " + other);
        }
    }
    private static void validatePath(Path root, Set<String> paths, String path) throws IOException {
        FilesSupport.resolve(root, path);
        if (!paths.add(path.toLowerCase(Locale.ROOT))) throw new IOException("重复文件路径 " + path);
    }
    Path stage(Models.Target target, Models.Release release) throws Exception {
        release = details(release);
        if (!target.override()) return downloads.verified(target.downloads(), target.sha512(), target.size(), cache);
        if (fallback != null) return extract(fallback.archive(), fallback.entries().get(target.path()), target.sha512(), target.size());
        try {
            Path archive = downloads.artifact(release.overrides().archive(), cache);
            return extract(archive, target.path(), target.sha512(), target.size());
        } catch (Exception e) {
            Path pack = downloads.artifact(release.fullPack(), cache);
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                String entry = zip.getEntry("client-overrides/" + target.path()) != null
                        ? "client-overrides/" + target.path() : "overrides/" + target.path();
                return extract(pack, entry, target.sha512(), target.size());
            }
        }
    }
    private Path extract(Path archive, String name, String hash, long size) throws Exception {
        Path destination = cache.resolve(hash);
        if (Files.isRegularFile(destination) && Files.size(destination) == size && FilesSupport.hash(destination).equals(hash)) return destination;
        Path part = cache.resolve(hash + ".extract");
        Storage.requireSpace(cache, size);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory() || entry.getSize() != size) throw new IOException("压缩包缺少文件或大小不一致 " + name);
            long count = zip.stream().filter(item -> item.getName().equals(name)).count();
            if (count != 1) throw new IOException("ZIP 条目重复 " + name);
            try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(part)) {
                byte[] buffer = new byte[128 * 1024];
                long total = 0;
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    total += length;
                    if (total > size) throw new IOException("ZIP 解压大小超限");
                    output.write(buffer, 0, length);
                }
            }
            if (Files.size(part) != size || !FilesSupport.hash(part).equals(hash)) throw new IOException("ZIP 文件校验失败 " + name);
            FilesSupport.replace(part, destination);
            return destination;
        } finally { Files.deleteIfExists(part); }
    }
}
