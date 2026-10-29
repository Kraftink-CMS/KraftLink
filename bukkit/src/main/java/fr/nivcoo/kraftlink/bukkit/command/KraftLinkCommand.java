package fr.nivcoo.kraftlink.bukkit.command;

import fr.nivcoo.kraftlink.bukkit.KraftLink;
import fr.nivcoo.kraftlink.bukkit.platform.BukkitPlatform;
import fr.nivcoo.kraftlink.command.ConnectionCommandHandler;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Collections;
import java.util.List;

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
        handler.execute(label, args, sender.hasPermission("kraftlink.admin"), message -> {
            if (plugin.isEnabled() && engine.running()) platform.send(sender, message);
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return sender.hasPermission("kraftlink.admin") ? handler.complete(args) : Collections.<String>emptyList();
    }
}
