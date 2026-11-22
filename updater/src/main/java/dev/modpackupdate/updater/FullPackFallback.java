package dev.modpackupdate.updater;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Derives a client installation plan from an author-configured .mrpack when release retrieval fails. */
record FullPackFallback(Path archive, Models.Release release, Map<String, Models.Target> targets,
                        Map<String, String> entries, boolean sequenceKnown) {
    private static final long LIMIT = 20L * 1024 * 1024 * 1024;

    static FullPackFallback load(Downloads downloads, Path cache, Models.Config config, Models.Installed installed,
                                 Path root, String minecraft, String loader, String loaderType) throws Exception {
        if (config.packId == null || config.packId.isBlank()) throw new IOException("缺少 pack-id");
        Files.createDirectories(cache);
        Path part = cache.resolve("fallback.mrpack.part");
        Path archive;
        try {
            // Always fetch the current URL: a previously cached pack must not masquerade as the current release.
            downloads.fetch(config.fullPackUrl, part, LIMIT);
            archive = cache.resolve(FilesSupport.hash(part));
            FilesSupport.replace(part, archive);
        } finally { Files.deleteIfExists(part); }
        return read(archive, cache, config, installed, root, minecraft, loader, loaderType,
                "更新接口不可用，正在使用完整整合包兜底源。");
    }

    static FullPackFallback read(Path archive, Path cache, Models.Config config, Models.Installed installed,
                                 Path root, String minecraft, String loader, String loaderType, String description) throws Exception {
        if (config.packId == null || config.packId.isBlank()) throw new IOException("请先在配置中设置 pack-id");
        Files.createDirectories(cache);
        Path index = cache.resolve("fallback-index.json");
        var entries = new LinkedHashMap<String, String>();
        var files = new ArrayList<Models.OverrideFile>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var names = new HashSet<String>();
            long expanded = 0;
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                CheckSession.checkInterrupted();
                ZipEntry entry = enumeration.nextElement();
                if (!names.add(entry.getName()) || names.size() > 100_000) throw new IOException("ZIP 条目重复或过多");
                if (!entry.isDirectory()) {
                    if (entry.getSize() < 0 || entry.getSize() > LIMIT - expanded) throw new IOException("ZIP 解压大小超限");
                    expanded += entry.getSize();
                }
            }
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null || indexEntry.isDirectory() || indexEntry.getSize() > 16 * 1024 * 1024) {
                throw new IOException("完整包缺少有效的 modrinth.index.json");
            }
            try (var input = zip.getInputStream(indexEntry)) {
                byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
                if (bytes.length != indexEntry.getSize()) throw new IOException("索引大小不一致或超限");
                Files.write(index, bytes);
            }
            var json = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
            String version = json.get("versionId").getAsString();
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw new IOException("整合包版本无效");
            long releaseId = installed.releaseId;
            ZipEntry metadata = zip.getEntry("modpackupdate.release.json");
            boolean sequenceKnown = metadata != null;
            if (metadata != null) {
                if (metadata.isDirectory() || metadata.getSize() > 65536) throw new IOException("完整包发布元数据无效");
                Models.PackRelease identity;
                try (var input = zip.getInputStream(metadata)) {
                    byte[] bytes = input.readNBytes(65537);
                    if (bytes.length != metadata.getSize()) throw new IOException("完整包发布元数据大小无效");
                    identity = FilesSupport.JSON.fromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Models.PackRelease.class);
                }
                if (identity == null || identity.protocolVersion() != 1 || !config.packId.equals(identity.packId())
                        || identity.releaseId() < 0 || !version.equals(identity.version())) throw new IOException("完整包发布身份不匹配");
                releaseId = identity.releaseId();
            }
            // Merge base overrides first, then the client overlay. Server overrides are never installed.
            var spellings = new LinkedHashMap<String, String>();
            for (String prefix : new String[]{"overrides/", "client-overrides/"}) {
                var layer = new HashSet<String>();
                var items = zip.entries();
                while (items.hasMoreElements()) {
                    CheckSession.checkInterrupted();
                    ZipEntry entry = items.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                    String path = entry.getName().substring(prefix.length());
                    FilesSupport.resolve(root, path);
                    String lower = path.toLowerCase(Locale.ROOT);
                    String existing = spellings.putIfAbsent(lower, path);
                    if (!layer.add(lower) || (existing != null && !existing.equals(path))) throw new IOException("overrides 路径冲突");
                    if (!FilesSupport.protectedPath(path, config)) entries.put(path, entry.getName());
                }
            }
            for (var item : entries.entrySet()) {
                ZipEntry entry = zip.getEntry(item.getValue());
                MessageDigest digest = MessageDigest.getInstance("SHA-512");
                long total = 0;
                try (var input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[128 * 1024];
                    int length;
                    while ((length = input.read(buffer)) >= 0) {
                        CheckSession.checkInterrupted();
                        total += length;
                        if (total > entry.getSize()) throw new IOException("ZIP 文件大小超限");
                        digest.update(buffer, 0, length);
                    }
                }
                if (total != entry.getSize()) throw new IOException("ZIP 文件大小不一致");
                files.add(new Models.OverrideFile(item.getKey(), HexFormat.of().formatHex(digest.digest()), total));
            }
            String announcement = description;
            if (json.has("summary") && !json.get("summary").isJsonNull()) announcement += "\n\n" + json.get("summary").getAsString();
            var release = new Models.Release(1, config.packId, releaseId, version, announcement,
                    null, new Models.Overrides(!files.isEmpty(), null, files), null);
            var targets = Protocol.parseTargets(release, root, minecraft, loader, loaderType, index);
            return new FullPackFallback(archive, release, targets, entries, sequenceKnown);
        } finally { Files.deleteIfExists(index); }
    }
}
