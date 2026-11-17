package dev.modpackupdate.updater;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class NeoForgeTest {
    @TempDir Path root;

    @Test void acceptsExactNeoForgeDependencyAndRejectsOtherPlatformsAndVersions() throws Exception {
        var dependencies = JsonParser.parseString("{\"minecraft\":\"1.21.1\",\"neoforge\":\"21.1.266\"}").getAsJsonObject();
        Protocol.validateDependencies(dependencies, "1.21.1", "21.1.266", "neoforge");
        assertThrows(IOException.class, () -> Protocol.validateDependencies(dependencies, "1.21.1", "21.1.265", "neoforge"));
        assertThrows(IOException.class, () -> Protocol.validateDependencies(dependencies, "1.20.1", "21.1.266", "neoforge"));
        assertThrows(IOException.class, () -> Protocol.validateDependencies(dependencies, "1.21.1", "0.16.10", "fabric-loader"));
        dependencies.addProperty("fabric-loader", "0.16.10");
        assertThrows(IOException.class, () -> Protocol.validateDependencies(dependencies, "1.21.1", "21.1.266", "neoforge"));
    }

    @Test void parsesEveryModTableWithoutTreatingDependenciesOrCommentsAsMods() throws Exception {
        Path file = jar("multiple.jar", """
                # modId = "ignored_comment"
                modLoader = "javafml"
                [[mods]]
                modId = "first"
                description = '''
                modId = "ignored_description"
                '''
                [[dependencies.first]]
                modId = "neoforge"
                [[mods]]
                modId = 'second'
                """);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertEquals(List.of("first", "second"), ModMetadata.ids(zip, "neoforge"));
            assertTrue(ModMetadata.ids(zip, "fabric-loader").isEmpty());
        }
    }

    @Test void duplicateNeoForgeIdFailsBeforeFileChanges() throws Exception {
        Path old = jar("mods/old.jar", "[[mods]]\nmodId=\"same\"\n");
        Path replacement = jar("ModpackUpdate/downloads/new.jar", "[[mods]]\nmodId=\"other\"\n[[mods]]\nmodId=\"same\"\n");
        Models.Target target = new Models.Target("mods/new.jar", FilesSupport.hash(replacement), Files.size(replacement), List.of(), false, false);
        var selected = List.of(new Models.Change(target.path(), target, null, null));
        assertThrows(IOException.class, () -> new Installer(root, "neoforge").install(new Models.Installed(), new Models.Installed(), selected,
                Map.of(target.path(), replacement), false));
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(root.resolve("mods/new.jar")));
    }

    @Test void replacingRenamedNeoForgeJarDisablesOldFile() throws Exception {
        Path old = jar("mods/old.jar", "[[mods]]\nmodId=\"same\"\nversion=\"1\"\n");
        Path replacement = jar("ModpackUpdate/downloads/new.jar", "[[mods]]\nmodId=\"same\"\nversion=\"2\"\n");
        Models.Installed previous = new Models.Installed();
        previous.version = "1.0.0";
        previous.files.put("mods/old.jar", new Models.Baseline(FilesSupport.hash(old), FilesSupport.hash(old), false));
        Models.Target target = new Models.Target("mods/new.jar", FilesSupport.hash(replacement), Files.size(replacement), List.of(), false, false);
        var changes = Planner.plan(root, new Models.Config(), previous, Map.of(target.path(), target));
        new Installer(root, "neoforge").install(previous, new Models.Installed(), changes, Map.of(target.path(), replacement), false);
        assertTrue(Files.exists(root.resolve("mods/old.jar.disable")));
        assertEquals(target.sha512(), FilesSupport.hash(root.resolve("mods/new.jar")));
        assertFalse(Files.exists(old));
    }

    private Path jar(String name, String metadata) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
}
