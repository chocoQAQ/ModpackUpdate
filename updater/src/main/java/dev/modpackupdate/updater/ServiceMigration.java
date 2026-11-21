package dev.modpackupdate.updater;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

final class ServiceMigration {
    static void save(Path root, Models.Config expected, String nextUrl) throws IOException {
        Downloads.allowed(nextUrl);
        Path path = FilesSupport.resolve(root, "ModpackUpdate/config/config.json");
        // Preserve all current settings and extension fields instead of serializing a stale Config snapshot.
        JsonObject current = FilesSupport.read(path, JsonObject.class);
        if (!matches(current, "update-service-url", expected.updateServiceUrl)
                || !matches(current, "pack-id", expected.packId)) {
            throw new IOException("检查期间更新地址或整合包 ID 已修改，未保存迁移地址");
        }
        CheckSession.checkInterrupted();
        current.addProperty("update-service-url", nextUrl);
        FilesSupport.write(path, current);
    }

    private static boolean matches(JsonObject object, String name, String expected) {
        var value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && value.getAsString().equals(expected);
    }
    private ServiceMigration() {}
}
