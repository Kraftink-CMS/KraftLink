package fr.nivcoo.kraftlink.nukkit;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.nukkit.platform.NukkitPlatform;
import fr.nivcoo.kraftlink.platform.StandaloneLink;

import java.util.function.Consumer;

public final class KraftLink extends PluginBase {

    private StandaloneLink link;
    private NukkitPlatform platform;

    @Override
    public void onEnable() {
        platform = new NukkitPlatform(this);
        link = new StandaloneLink(KraftLinkPlatform.NUKKIT, getDataFolder().toPath());
        link.start(platform);
    }

    @Override
    public void onDisable() {
        if (link != null) {
            link.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!"kraftlink".equals(command.getName()) || link == null) {
            return false;
        }
        Consumer<String> reply = message -> platform.run(() -> sender.sendMessage(message));
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("link")) {
            link.link(label, arguments, sender.hasPermission("kraftlink.link"),
                    sender instanceof Player ? () -> platform.xboxIdentity((Player) sender) : null, reply);
        } else {
            link.command(String.join(" ", arguments), sender.hasPermission("kraftlink.admin"), reply);
        }
        return true;
    }
}
