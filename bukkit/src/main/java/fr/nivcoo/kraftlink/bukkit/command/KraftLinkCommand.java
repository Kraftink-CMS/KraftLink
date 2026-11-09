package fr.nivcoo.kraftlink.bukkit.command;

import fr.nivcoo.kraftlink.bukkit.KraftLink;
import fr.nivcoo.kraftlink.bukkit.platform.BukkitPlatform;
import fr.nivcoo.kraftlink.command.ConnectionCommandHandler;
import fr.nivcoo.kraftlink.hook.FloodgateProfiles;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

public final class KraftLinkCommand implements TabExecutor {

    private final KraftLink plugin;
    private final DeliveryEngine engine;
    private final BukkitPlatform platform;
    private final ConnectionCommandHandler handler;

    public KraftLinkCommand(KraftLink plugin, DeliveryEngine engine, BukkitPlatform platform) {
        this.plugin = plugin;
        this.engine = engine;
        this.platform = platform;
        handler = new ConnectionCommandHandler(engine, plugin.configPath());
    }

    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, String[] args) {
        Consumer<String> reply = message -> {
            if (plugin.isEnabled() && engine.running()) platform.send(sender, message);
        };
        if (args.length > 0 && args[0].equalsIgnoreCase("link")) {
            handler.link(label, args, sender.hasPermission("kraftlink.link"),
                    sender instanceof Player ? () -> bedrock((Player) sender) : null, reply);
        } else if (args.length > 0 && args[0].equalsIgnoreCase("signin")) {
            if (args.length == 3 && sender instanceof Player) {
                engine.respondToInGameSignIn(platform.inGameSignInIdentity((Player) sender), args[1], args[2], reply);
            }
        } else {
            handler.execute(label, args, sender.hasPermission("kraftlink.admin"), reply);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return handler.complete(args, sender.hasPermission("kraftlink.admin"),
                sender instanceof Player && sender.hasPermission("kraftlink.link") && hasFloodgate());
    }

    private boolean hasFloodgate() {
        return plugin.getServer().getPluginManager().isPluginEnabled("floodgate");
    }

    private BedrockIdentity bedrock(Player player) {
        return hasFloodgate() && player.isOnline() ? FloodgateProfiles.find(player.getUniqueId()) : null;
    }
}
