package fr.nivcoo.kraftlink.neoforge;

import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.minecraft.MinecraftPlatform;
import fr.nivcoo.kraftlink.platform.StandaloneLink;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(value = "kraftlink", dist = Dist.DEDICATED_SERVER)
public final class KraftLink {

    public KraftLink() {
        StandaloneLink link = new StandaloneLink(KraftLinkPlatform.NEOFORGE,
                FMLPaths.CONFIGDIR.get().resolve("kraftlink"));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                link.start(new MinecraftPlatform(event.getServer(), "NeoForge")));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> link.close());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                MinecraftPlatform.registerCommand(event.getDispatcher(), link));
    }
}
