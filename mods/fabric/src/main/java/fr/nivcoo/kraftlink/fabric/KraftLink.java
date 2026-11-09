package fr.nivcoo.kraftlink.fabric;

import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.minecraft.MinecraftPlatform;
import fr.nivcoo.kraftlink.platform.StandaloneLink;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class KraftLink implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        StandaloneLink link = new StandaloneLink(KraftLinkPlatform.FABRIC,
                FabricLoader.getInstance().getConfigDir().resolve("kraftlink"));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> link.start(new MinecraftPlatform(server, "Fabric")));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> link.close());
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                MinecraftPlatform.registerCommand(dispatcher, link));
    }
}
