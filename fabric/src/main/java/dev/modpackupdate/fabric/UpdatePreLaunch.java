package dev.modpackupdate.fabric;

import dev.modpackupdate.bootstrap.UpdateBootstrap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class UpdatePreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        FabricLoader loader = FabricLoader.getInstance();
        String minecraft = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
        String fabric = loader.getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString();
        UpdateBootstrap.check(loader.getGameDir(), minecraft, fabric, "fabric-loader");
    }
}
