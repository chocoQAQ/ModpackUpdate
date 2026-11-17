package dev.modpackupdate.updater;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

final class ModMetadata {
    static List<String> ids(ZipFile zip, String loaderType) throws IOException {
        String name = loaderType.equals("neoforge") ? "META-INF/neoforge.mods.toml" : "fabric.mod.json";
        var metadata = zip.getEntry(name);
        if (metadata == null) return List.of();
        try (var input = zip.getInputStream(metadata)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException(name + " 过大");
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (loaderType.equals("fabric-loader")) {
                return List.of(JsonParser.parseString(text).getAsJsonObject().get("id").getAsString());
            }
            Object mods = new TomlParser().parse(new StringReader(text)).get("mods");
            if (!(mods instanceof List<?> entries)) throw new IOException("缺少 [[mods]] " + zip.getName());
            List<String> ids = new ArrayList<>();
            for (Object entry : entries) {
                if (!(entry instanceof UnmodifiableConfig config)) throw new IOException("无效 [[mods]]");
                Object id = config.get("modId");
                if (!(id instanceof String value) || value.isBlank()) throw new IOException("缺少 modId");
                ids.add(value);
            }
            return ids;
        } catch (RuntimeException e) {
            throw new IOException("模组元数据格式错误 " + zip.getName(), e);
        }
    }

    private ModMetadata() {}
}
