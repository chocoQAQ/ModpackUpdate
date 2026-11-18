package dev.modpackupdate.forge;

import net.minecraftforge.fml.common.Mod;

@Mod("modpackupdate")
public final class ModpackUpdate {
    public ModpackUpdate() {
        // The early Mixin entry normally performs the check; this is the fallback.
        UpdateMixinPlugin.checkOnce();
    }
}
