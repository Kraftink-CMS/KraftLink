package fr.nivcoo.kraftlink.forge;

import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.minecraft.MinecraftPlatform;
import fr.nivcoo.kraftlink.platform.StandaloneLink;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("kraftlink")
public final class KraftLink {

    public KraftLink() {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            return;
        }
        StandaloneLink link = new StandaloneLink(KraftLinkPlatform.FORGE, FMLPaths.CONFIGDIR.get().resolve("kraftlink"));
        ServerStartedEvent.BUS.addListener(event -> link.start(new MinecraftPlatform(event.getServer(), "Forge")));
        ServerStoppingEvent.BUS.addListener(event -> link.close());
        RegisterCommandsEvent.BUS.addListener(event -> MinecraftPlatform.registerCommand(event.getDispatcher(), link));
    }
}
