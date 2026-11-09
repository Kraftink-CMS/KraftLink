package fr.nivcoo.kraftlink.sponge;

import com.google.inject.Inject;
import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.platform.StandaloneLink;
import fr.nivcoo.kraftlink.sponge.platform.SpongePlatform;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.Server;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.nio.file.Path;

@Plugin("kraftlink")
public final class KraftLink {

    private final PluginContainer container;
    private final StandaloneLink link;
    private volatile SpongePlatform platform;

    @Inject
    public KraftLink(PluginContainer container, @ConfigDir(sharedRoot = false) Path directory) {
        this.container = container;
        link = new StandaloneLink(KraftLinkPlatform.SPONGE, directory);
    }

    @Listener
    public void onStart(StartedEngineEvent<Server> event) {
        platform = new SpongePlatform(event.engine(), container);
        link.start(platform);
    }

    @Listener
    public void onStop(StoppingEngineEvent<Server> event) {
        platform = null;
        link.close();
    }

    @Listener
    public void registerCommand(RegisterCommandEvent<Command.Parameterized> event) {
        Parameter.Value<String> arguments = Parameter.remainingJoinedStrings().key("arguments").optional().build();
        event.register(container, Command.builder()
                .permission("kraftlink.admin")
                .addParameter(arguments)
                .executor(context -> {
                    SpongePlatform current = platform;
                    if (current == null) {
                        return CommandResult.error(Component.text("KraftLink is unavailable."));
                    }
                    link.command(context.one(arguments).orElse(""), true, message ->
                            current.run(() -> context.sendMessage(Component.text(message))));
                    return CommandResult.success();
                })
                .build(), "kraftlink");
    }
}
