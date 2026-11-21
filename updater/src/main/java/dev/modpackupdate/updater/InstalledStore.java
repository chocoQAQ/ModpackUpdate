package dev.modpackupdate.updater;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Commit the immutable file index first, then atomically publish its small state header. */
final class InstalledStore {
    private static final String STATE = "ModpackUpdate/config/installed.json";
    private record FileIndex(Map<String, Models.Baseline> files) {}

    static Models.Installed header(Path root) throws IOException {
        Path path = FilesSupport.resolve(root, STATE);
        if (!Files.exists(path)) return new Models.Installed();
        // Legacy states can contain a large inline table. Skip it without allocating the table.
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path))) {
            JsonObject header = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("files")) reader.skipValue();
                else header.add(name, com.google.gson.JsonParser.parseReader(reader));
            }
            reader.endObject();
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("安装记录尾部包含多余数据");
            Models.Installed result = FilesSupport.JSON.fromJson(header, Models.Installed.class);
            if (result.schemaVersion != 1 && result.schemaVersion != 2) throw new IOException("不支持的本地安装记录版本");
            if (result.schemaVersion == 2) FilesSupport.requireHash(result.filesIndex);
            return result;
        } catch (RuntimeException e) { throw new IOException("本地安装记录格式错误", e); }
    }

    static Models.Installed read(Path root) throws IOException {
        Models.Installed result = header(root);
        if (result.schemaVersion == 1) {
            Path path = FilesSupport.resolve(root, STATE);
            return Files.exists(path) ? FilesSupport.read(path, Models.Installed.class) : result;
        }
        Path index = index(root, result.filesIndex);
        if (!Files.isRegularFile(index) || !result.filesIndex.equals(FilesSupport.hash(index))) {
            throw new IOException("本地文件基线索引丢失或损坏");
        }
        result.files = FilesSupport.read(index, FileIndex.class).files();
        if (result.files == null) throw new IOException("本地文件基线索引无效");
        return result;
    }

    static void write(Path root, Models.Installed installed) throws Exception {
        FileIndex content = new FileIndex(installed.files);
        byte[] bytes = FilesSupport.JSON.toJson(content).getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
        Path index = index(root, hash);
        if (!Files.isRegularFile(index) || !hash.equals(FilesSupport.hash(index))) FilesSupport.write(index, content);
        installed.schemaVersion = 2;
        installed.filesIndex = hash;
        JsonObject header = FilesSupport.JSON.toJsonTree(installed).getAsJsonObject();
        header.remove("files");
        FilesSupport.write(FilesSupport.resolve(root, STATE), header);
    }

    private static Path index(Path root, String hash) throws IOException {
        FilesSupport.requireHash(hash);
        return FilesSupport.resolve(root, "ModpackUpdate/config/file-indexes/" + hash + ".json");
    }
    private InstalledStore() {}
}
