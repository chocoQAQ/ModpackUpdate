package dev.modpackupdate.quilt;

import dev.modpackupdate.bootstrap.UpdateBootstrap;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class UpdatePreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch(ModContainer mod) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;
        String minecraft = QuiltLoader.getModContainer("minecraft").orElseThrow().metadata().version().raw();
        String quilt = QuiltLoader.getModContainer("quilt_loader").orElseThrow().metadata().version().raw();
        UpdateBootstrap.check(QuiltLoader.getGameDir(), minecraft, quilt, "quilt-loader");
    }
}
