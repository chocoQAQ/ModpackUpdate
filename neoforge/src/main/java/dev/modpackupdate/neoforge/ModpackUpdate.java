package dev.modpackupdate.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = "modpackupdate", dist = Dist.CLIENT)
public final class ModpackUpdate {
    public ModpackUpdate() {
        // Fallback for environments that defer selecting empty Mixin configurations.
        UpdateMixinPlugin.checkOnce();
    }
}
