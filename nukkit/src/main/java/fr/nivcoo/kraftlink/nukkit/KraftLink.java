package fr.nivcoo.kraftlink.nukkit;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.PluginCommand;
import cn.nukkit.command.SimpleCommandMap;
import cn.nukkit.plugin.PluginBase;
import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.nukkit.platform.NukkitPlatform;
import fr.nivcoo.kraftlink.platform.StandaloneLink;

import java.util.function.Consumer;

public final class KraftLink extends PluginBase {

    private static final String LINK_SHORTCUT = "link";
    private static final String LINK_PERMISSION = "kraftlink.link";
    private static final String LINK_NAMESPACE = "kraftlink";

    private StandaloneLink link;
    private NukkitPlatform platform;
    private PluginCommand<KraftLink> linkShortcut;

    @Override
    public void onEnable() {
        platform = new NukkitPlatform(this);
        link = new StandaloneLink(KraftLinkPlatform.NUKKIT, getDataFolder().toPath());
        link.start(platform);
        platform.run(this::registerLinkShortcut);
    }

    @Override
    public void onDisable() {
        if (linkShortcut != null) {
            SimpleCommandMap commands = getServer().getCommandMap();
            commands.getCommands().values().removeIf(command -> command == linkShortcut);
            linkShortcut.unregister(commands);
            linkShortcut = null;
        }
        if (link != null) {
            link.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        boolean shortcut = LINK_SHORTCUT.equals(command.getName());
        if ((!shortcut && !LINK_NAMESPACE.equals(command.getName())) || link == null) {
            return false;
        }
        Consumer<String> reply = message -> platform.run(() -> sender.sendMessage(message));
        if (shortcut || (arguments.length > 0 && arguments[0].equalsIgnoreCase(LINK_SHORTCUT))) {
            link.link(label, arguments, shortcut, sender.hasPermission(LINK_PERMISSION),
                    sender instanceof Player ? () -> platform.xboxIdentity((Player) sender) : null, reply);
        } else {
            link.command(String.join(" ", arguments), sender.hasPermission("kraftlink.admin"), reply);
        }
        return true;
    }

    private void registerLinkShortcut() {
        SimpleCommandMap commands = getServer().getCommandMap();
        if (commands.getCommand(LINK_SHORTCUT) != null || commands.getCommand(LINK_NAMESPACE + ':' + LINK_SHORTCUT) != null) {
            getLogger().info("The /link shortcut is already registered. Use /kraftlink link.");
            return;
        }
        PluginCommand<KraftLink> command = new PluginCommand<>(LINK_SHORTCUT, this);
        command.setDescription("Link your Bedrock account to the website");
        command.setPermission(LINK_PERMISSION);
        commands.register(LINK_NAMESPACE, command);
        linkShortcut = command;
    }
}
