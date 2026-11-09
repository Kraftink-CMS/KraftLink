package fr.nivcoo.kraftlink.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import fr.nivcoo.kraftlink.command.ConnectionCommandHandler;
import fr.nivcoo.kraftlink.hook.FloodgateProfiles;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.velocity.KraftLink;
import fr.nivcoo.kraftlink.velocity.platform.VelocityPlatform;
import net.kyori.adventure.text.Component;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class KraftLinkCommand implements SimpleCommand {

    private final KraftLink plugin;
    private final VelocityPlatform platform;
    private final ConnectionCommandHandler handler;

    public KraftLinkCommand(KraftLink plugin, VelocityPlatform platform) {
        this.plugin = plugin;
        this.platform = platform;
        handler = new ConnectionCommandHandler(plugin.engine(), plugin.configPath());
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length > 0 && args[0].equalsIgnoreCase("link")) {
            link(invocation, false);
        } else if (args.length > 0 && args[0].equalsIgnoreCase("signin")) {
            if (args.length == 3 && source instanceof Player) {
                plugin.engine().respondToInGameSignIn(platform.inGameSignInIdentity((Player) source), args[1], args[2],
                        message -> send(source, message));
            }
        } else {
            handler.execute(invocation.alias(), args, source.hasPermission("kraftlink.admin"),
                    message -> send(source, message));
        }
    }

    public SimpleCommand linkShortcut() {
        return new LinkShortcut();
    }

    private void link(Invocation invocation, boolean shortcut) {
        CommandSource source = invocation.source();
        Supplier<BedrockIdentity> player = source instanceof Player ? () -> bedrock((Player) source) : null;
        Consumer<String> reply = message -> send(source, message);
        if (shortcut) {
            handler.linkShortcut(invocation.alias(), invocation.arguments(), canLink(source), player, reply);
        } else {
            handler.link(invocation.alias(), invocation.arguments(), canLink(source), player, reply);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!plugin.running()) return Collections.emptyList();
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        return handler.complete(args.length == 0 ? new String[]{""} : args,
                source.hasPermission("kraftlink.admin"), source instanceof Player && canLink(source) && hasFloodgate());
    }

    private boolean canLink(CommandSource source) {
        return source.getPermissionValue("kraftlink.link") != Tristate.FALSE;
    }

    private boolean hasFloodgate() {
        return plugin.proxy().getPluginManager().isLoaded("floodgate");
    }

    private BedrockIdentity bedrock(Player player) {
        return hasFloodgate() && player.isActive() ? FloodgateProfiles.find(player.getUniqueId()) : null;
    }

    private void send(CommandSource source, String message) {
        if (!plugin.running()) return;
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            if (!plugin.running() || source instanceof Player && !((Player) source).isActive()) return;
            source.sendMessage(Component.text(message));
        }).schedule();
    }

    private final class LinkShortcut implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            link(invocation, true);
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!plugin.running() || !(source instanceof Player) || !canLink(source) || !hasFloodgate()) return false;
            Player player = (Player) source;
            return player.isActive() && FloodgateProfiles.isPlayer(player.getUniqueId());
        }
    }
}
