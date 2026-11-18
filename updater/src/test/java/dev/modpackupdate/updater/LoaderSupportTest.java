package dev.modpackupdate.updater;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LoaderSupportTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"forge,1.20.1,47.4.28", "quilt-loader,1.21.1,0.30.2", "neoforge,1.21.4,21.4.161", "fabric-loader,1.21.4,0.16.10"})
    void validatesExactPlatformAndVersion(String type, String minecraft, String version) throws Exception {
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", minecraft);
        dependencies.addProperty(type, version);
        Protocol.validateDependencies(dependencies, minecraft, version, type);
        assertThrows(IOException.class, () -> Protocol.validateDependencies(dependencies, minecraft, "different", type));
        assertThrows(IOException.class, () -> Protocol.validateDependencies(dependencies, "different", version, type));
        for (String other : Protocol.LOADERS) {
            if (other.equals(type)) continue;
            assertThrows(IOException.class, () -> Protocol.validateDependencies(dependencies, minecraft, version, other));
            dependencies.addProperty(other, "1");
            assertThrows(IOException.class, () -> Protocol.validateDependencies(dependencies, minecraft, version, type));
            dependencies.remove(other);
        }
    }

    @Test void quiltUsesNativeMetadataBeforeFabricAndFallsBackToFabric() throws Exception {
        Path both = jar("both.jar", Map.of("quilt.mod.json", "{\"quilt_loader\":{\"id\":\"native_mod\"}}",
                "fabric.mod.json", "{\"id\":\"fabric_mod\"}"));
        try (ZipFile zip = new ZipFile(both.toFile())) {
            assertEquals(List.of("native_mod"), ModMetadata.ids(zip, "quilt-loader"));
            assertEquals(List.of("fabric_mod"), ModMetadata.ids(zip, "fabric-loader"));
        }
        Path fabric = jar("fabric.jar", Map.of("fabric.mod.json", "{\"id\":\"fabric_mod\"}"));
        try (ZipFile zip = new ZipFile(fabric.toFile())) {
            assertEquals(List.of("fabric_mod"), ModMetadata.ids(zip, "quilt-loader"));
        }
        Path invalid = jar("invalid.jar", Map.of("quilt.mod.json", "{}", "fabric.mod.json", "{\"id\":\"fabric_mod\"}"));
        try (ZipFile zip = new ZipFile(invalid.toFile())) {
            assertThrows(IOException.class, () -> ModMetadata.ids(zip, "quilt-loader"));
        }
    }

    @Test void forgeUsesItsOwnTomlAndParsesAllModTables() throws Exception {
        Path file = jar("forge.jar", Map.of("META-INF/mods.toml", "# modId='ignored'\n[[mods]]\nmodId='first'\n[[dependencies.first]]\nmodId='forge'\n[[mods]]\nmodId='second'\n",
                "META-INF/neoforge.mods.toml", "[[mods]]\nmodId='neo_only'\n"));
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertEquals(List.of("first", "second"), ModMetadata.ids(zip, "forge"));
            assertEquals(List.of("neo_only"), ModMetadata.ids(zip, "neoforge"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"forge", "quilt-loader"})
    void duplicateIdsStopBeforeMutationAndRenamedManagedModsAreDisabled(String type) throws Exception {
        String metadataPath = type.equals("forge") ? "META-INF/mods.toml" : "quilt.mod.json";
        String metadata = type.equals("forge") ? "[[mods]]\nmodId='same'\n" : "{\"quilt_loader\":{\"id\":\"same\"}}";
        Path old = jar("mods/old.jar", Map.of(metadataPath, metadata));
        Path replacement = jar("ModpackUpdate/downloads/new.jar", Map.of(metadataPath, metadata));
        var target = new Models.Target("mods/new.jar", FilesSupport.hash(replacement), Files.size(replacement), List.of(), false, false);
        var selected = List.of(new Models.Change(target.path(), target, null, null));
        var installer = new Installer(root, type);
        assertThrows(IOException.class, () -> installer.install(new Models.Installed(), new Models.Installed(), selected, Map.of(target.path(), replacement), false));
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(root.resolve(target.path())));
        Models.Installed previous = new Models.Installed();
        previous.version = "1.0.0";
        previous.files.put("mods/old.jar", new Models.Baseline(FilesSupport.hash(old), FilesSupport.hash(old), false));
        var changes = Planner.plan(root, new Models.Config(), previous, Map.of(target.path(), target));
        installer.install(previous, new Models.Installed(), changes, Map.of(target.path(), replacement), false);
        assertTrue(Files.exists(root.resolve("mods/old.jar.disable")));
        assertFalse(Files.exists(old));
        assertEquals(target.sha512(), FilesSupport.hash(root.resolve(target.path())));
    }

    private Path jar(String name, Map<String, String> metadata) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : metadata.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
