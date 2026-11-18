package dev.modpackupdate.quilt;

import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quiltmc.loader.api.Version;
import org.quiltmc.loader.api.plugin.ModContainerExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.launch.common.QuiltLauncher;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;

import java.io.DataInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class QuiltEntrypointTest {
    @TempDir Path root;

    @Test void packagedMetadataAndEntrypointMatchTarget() throws Exception {
        try (ZipFile zip = new ZipFile(System.getProperty("target.mod.jar"))) {
            try (var reader = new InputStreamReader(zip.getInputStream(zip.getEntry("quilt.mod.json")), StandardCharsets.UTF_8)) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                assertEquals(1, json.get("schema_version").getAsInt());
                assertEquals("client", json.getAsJsonObject("minecraft").get("environment").getAsString());
                var loader = json.getAsJsonObject("quilt_loader");
                assertEquals("modpackupdate", loader.get("id").getAsString());
                assertEquals(System.getProperty("target.version"), loader.get("version").getAsString());
                var dependencies = new java.util.HashMap<String, String>();
                for (var dependency : loader.getAsJsonArray("depends")) {
                    var item = dependency.getAsJsonObject();
                    dependencies.put(item.get("id").getAsString(), item.get("versions").getAsString());
                }
                assertEquals(System.getProperty("target.loaderRange"), dependencies.get("quilt_loader"));
                assertEquals(System.getProperty("target.minecraft"), dependencies.get("minecraft"));
                assertEquals(">=" + System.getProperty("target.java"), dependencies.get("java"));
                String entry = loader.getAsJsonObject("entrypoints").get("pre_launch").getAsString().replace('.', '/') + ".class";
                try (var input = new DataInputStream(zip.getInputStream(zip.getEntry(entry)))) {
                    assertEquals(0xCAFEBABE, input.readInt());
                    input.readUnsignedShort();
                    assertEquals(Integer.parseInt(System.getProperty("target.java")) + 44, input.readUnsignedShort());
                }
            }
            assertNotNull(zip.getEntry("modpackupdate/updater.jar"));
            assertNotNull(zip.getEntry("dev/modpackupdate/bootstrap/UpdateBootstrap.class"));
            assertNull(zip.getEntry("fabric.mod.json"));
        }
    }

    @Test void nativePreLaunchRunsIndependentUpdater() throws Exception {
        runProbe("client");
        assertTrue(Files.exists(root.resolve("ModpackUpdate/config/config.json")));
    }

    @Test void dedicatedServerSkipsUpdater() throws Exception {
        runProbe("server");
        assertFalse(Files.exists(root.resolve("ModpackUpdate")));
    }

    @Test void activeInstallerStopsPreLaunch() throws Exception {
        Files.createDirectories(root.resolve("ModpackUpdate"));
        try (var channel = FileChannel.open(root.resolve("ModpackUpdate/update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            runProbe("locked");
        }
    }

    private void runProbe(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("target.mod.jar") + File.pathSeparator + System.getProperty("quilt.test.classpath");
        Process process = new ProcessBuilder(java, "-cp", classpath, Probe.class.getName(), root.toString(), mode,
                System.getProperty("target.minecraft"), System.getProperty("target.loaderVersion"))
                .redirectErrorStream(true).redirectOutput(root.resolve("probe.log").toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(root.resolve("probe.log")));
        } finally { process.destroyForcibly(); }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            // Supply the loader's launch context without loading Minecraft classes.
            QuiltLauncher launcher = (QuiltLauncher) Proxy.newProxyInstance(Probe.class.getClassLoader(), new Class[]{QuiltLauncher.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getEnvironmentType" -> args[1].equals("server") ? EnvType.SERVER : EnvType.CLIENT;
                        case "isDevelopment" -> false;
                        default -> null;
                    });
            QuiltLauncherBase.setLauncher(launcher);
            var instance = QuiltLoaderImpl.INSTANCE;
            var directory = QuiltLoaderImpl.class.getDeclaredField("gameDir");
            directory.setAccessible(true);
            directory.set(instance, Path.of(args[0]));
            var field = QuiltLoaderImpl.class.getDeclaredField("modMap");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var mods = (Map<String, ModContainerExt>) field.get(instance);
            mods.put("minecraft", mod("minecraft", args[2]));
            mods.put("quilt_loader", mod("quilt_loader", args[3]));
            try {
                new UpdatePreLaunch().onPreLaunch(mod("modpackupdate", "test"));
                if (args[1].equals("locked")) throw new AssertionError("Pre-launch continued with active installer");
            } catch (IllegalStateException expected) {
                if (!args[1].equals("locked")) throw expected;
            }
        }

        private static ModContainerExt mod(String id, String version) {
            var metadata = (ModMetadataExt) Proxy.newProxyInstance(Probe.class.getClassLoader(), new Class[]{ModMetadataExt.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "id" -> id;
                        case "version" -> Version.of(version);
                        default -> null;
                    });
            return (ModContainerExt) Proxy.newProxyInstance(Probe.class.getClassLoader(), new Class[]{ModContainerExt.class},
                    (proxy, method, values) -> method.getName().equals("metadata") ? metadata : null);
        }
    }
}
