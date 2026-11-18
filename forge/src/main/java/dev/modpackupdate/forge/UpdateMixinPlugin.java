package dev.modpackupdate.forge;

import dev.modpackupdate.bootstrap.UpdateBootstrap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateMixinPlugin implements IMixinConfigPlugin {
    private static final AtomicBoolean checked = new AtomicBoolean();
    private static volatile IllegalStateException blocked;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            checkOnce();
        } catch (IllegalStateException e) {
            // Mixin logs and swallows Exceptions during config selection. An unsafe instance must stop launching.
            throw new ExceptionInInitializerError(e);
        }
    }

    static void checkOnce() {
        if (blocked != null) throw blocked;
        if (FMLLoader.getDist() != Dist.CLIENT || !checked.compareAndSet(false, true)) return;
        var version = FMLLoader.versionInfo();
        try {
            UpdateBootstrap.check(FMLLoader.getGamePath(), version.mcVersion(), version.forgeVersion(), "forge");
        } catch (IllegalStateException e) {
            blocked = e;
            throw e;
        }
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return false; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return List.of(); }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
