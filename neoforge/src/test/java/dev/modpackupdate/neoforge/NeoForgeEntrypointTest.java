package dev.modpackupdate.neoforge;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.VersionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class NeoForgeEntrypointTest {
    @TempDir Path root;

    @Test void packagedMetadataAndClassVersionMatchRequestedPlatform() throws Exception {
        try (ZipFile zip = new ZipFile(System.getProperty("target.mod.jar"))) {
            var metadata = zip.getEntry("META-INF/neoforge.mods.toml");
            assertNotNull(metadata);
            try (var reader = new InputStreamReader(zip.getInputStream(metadata), StandardCharsets.UTF_8)) {
                var toml = new TomlParser().parse(reader);
                assertEquals("javafml", toml.get("modLoader"));
                List<UnmodifiableConfig> dependencies = toml.get("dependencies.modpackupdate");
                assertEquals(System.getProperty("target.loaderRange"), dependencies.stream().filter(item -> "neoforge".equals(item.get("modId"))).findFirst().orElseThrow().get("versionRange"));
                assertEquals("[" + System.getProperty("target.minecraft") + "]", dependencies.stream().filter(item -> "minecraft".equals(item.get("modId"))).findFirst().orElseThrow().get("versionRange"));
                List<UnmodifiableConfig> mods = toml.get("mods");
                assertEquals(System.getProperty("target.version"), mods.get(0).get("version"));
                assertEquals("[" + System.getProperty("target.java") + ",)", toml.get("features.modpackupdate.javaVersion"));
                List<UnmodifiableConfig> mixins = toml.get("mixins");
                assertNotNull(zip.getEntry(mixins.get(0).get("config")));
            }
            assertNotNull(zip.getEntry("modpackupdate/updater.jar"));
            assertNotNull(zip.getEntry("dev/modpackupdate/bootstrap/UpdateBootstrap.class"));
            try (var input = new java.io.DataInputStream(zip.getInputStream(zip.getEntry("dev/modpackupdate/neoforge/UpdateMixinPlugin.class")))) {
                assertEquals(0xCAFEBABE, input.readInt());
                input.readUnsignedShort();
                assertEquals(Integer.parseInt(System.getProperty("target.java")) + 44, input.readUnsignedShort());
            }
        }
    }

    @Test void packagedEarlyEntryRunsOnceAndConstructFallbackDoesNotRepeat() throws Exception {
        runProbe("client");
        assertTrue(Files.exists(root.resolve("ModpackUpdate/config/config.json")));
        try (var files = Files.list(root.resolve("ModpackUpdate/runtime"))) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("check-")).count());
        }
    }

    @Test void dedicatedServerSkipsUpdater() throws Exception {
        runProbe("server");
        assertFalse(Files.exists(root.resolve("ModpackUpdate")));
    }

    @Test void activeInstallerStopsEarlyEntryAndConstructorFallback() throws Exception {
        Files.createDirectories(root.resolve("ModpackUpdate"));
        try (var channel = FileChannel.open(root.resolve("ModpackUpdate/update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            runProbe("locked");
        }
    }

    private void runProbe(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("target.mod.jar") + File.pathSeparator + System.getProperty("neoforge.test.classpath");
        Process process = new ProcessBuilder(java, "-cp", classpath, Probe.class.getName(), root.toString(), mode,
                System.getProperty("target.loaderVersion"), System.getProperty("target.fml"), System.getProperty("target.minecraft"))
                .redirectErrorStream(true).redirectOutput(root.resolve("probe.log").toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(root.resolve("probe.log")));
        } finally { process.destroyForcibly(); }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            setLoaderField("dist", args[1].equals("server") ? Dist.DEDICATED_SERVER : Dist.CLIENT);
            setLoaderField("gamePath", Path.of(args[0]));
            setLoaderField("versionInfo", new VersionInfo(args[2], args[3], args[4], "20240808.144430"));
            var plugin = new UpdateMixinPlugin();
            if (args[1].equals("locked")) {
                try {
                    plugin.onLoad("dev.modpackupdate.neoforge.mixin");
                    throw new AssertionError("Early entry continued with an active installer");
                } catch (ExceptionInInitializerError expected) {
                    try {
                        new ModpackUpdate();
                        throw new AssertionError("Constructor fallback ignored the blocked state");
                    } catch (IllegalStateException alsoExpected) { return; }
                }
            }
            plugin.onLoad("dev.modpackupdate.neoforge.mixin");
            plugin.onLoad("dev.modpackupdate.neoforge.mixin");
            if (!args[1].equals("server")) new ModpackUpdate();
        }

        private static void setLoaderField(String name, Object value) throws Exception {
            var field = FMLLoader.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        }
    }
}
