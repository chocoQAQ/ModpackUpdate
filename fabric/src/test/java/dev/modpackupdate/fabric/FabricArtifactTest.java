package dev.modpackupdate.fabric;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarInputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class FabricArtifactTest {
    @Test void packagedMetadataAndEntrypointMatchTarget() throws Exception {
        Path jar = Path.of(System.getProperty("target.mod.jar"));
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            try (var reader = new InputStreamReader(zip.getInputStream(zip.getEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
                var metadata = JsonParser.parseReader(reader).getAsJsonObject();
                assertEquals("modpackupdate", metadata.get("id").getAsString());
                assertEquals("client", metadata.get("environment").getAsString());
                assertEquals(System.getProperty("target.version"), metadata.get("version").getAsString());
                var depends = metadata.getAsJsonObject("depends");
                assertEquals(System.getProperty("target.minecraft"), depends.get("minecraft").getAsString());
                assertEquals(System.getProperty("target.loaderRange"), depends.get("fabricloader").getAsString());
                assertEquals(">=" + System.getProperty("target.java"), depends.get("java").getAsString());
                String entrypoint = metadata.getAsJsonObject("entrypoints").getAsJsonArray("preLaunch").get(0).getAsString();
                // Load the entrypoint from the packaged artifact, with only loader API visibility from the parent.
                try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, PreLaunchEntrypoint.class.getClassLoader()) {
                    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (!name.startsWith("dev.modpackupdate.")) return super.loadClass(name, resolve);
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }) {
                    assertInstanceOf(PreLaunchEntrypoint.class, loader.loadClass(entrypoint).getConstructor().newInstance());
                    assertNotNull(loader.loadClass("dev.modpackupdate.bootstrap.UpdateBootstrap"));
                }
                try (var input = new DataInputStream(zip.getInputStream(zip.getEntry(entrypoint.replace('.', '/') + ".class")))) {
                    assertEquals(0xCAFEBABE, input.readInt());
                    input.readUnsignedShort();
                    assertEquals(Integer.parseInt(System.getProperty("target.java")) + 44, input.readUnsignedShort());
                }
            }
            assertNull(zip.getEntry("META-INF/neoforge.mods.toml"));
        }
    }

    @Test void embeddedUpdaterHasMatchingVersionAndRunnableManifest() throws Exception {
        try (ZipFile zip = new ZipFile(System.getProperty("target.mod.jar"));
             var updater = new JarInputStream(zip.getInputStream(zip.getEntry("modpackupdate/updater.jar")))) {
            var attributes = updater.getManifest().getMainAttributes();
            assertEquals("dev.modpackupdate.updater.Main", attributes.getValue("Main-Class"));
            assertEquals(System.getProperty("target.version"), attributes.getValue("Implementation-Version"));
            boolean hasMain = false;
            for (var entry = updater.getNextJarEntry(); entry != null; entry = updater.getNextJarEntry()) {
                if (entry.getName().equals("dev/modpackupdate/updater/Main.class")) hasMain = true;
            }
            assertTrue(hasMain);
        }
    }
}
