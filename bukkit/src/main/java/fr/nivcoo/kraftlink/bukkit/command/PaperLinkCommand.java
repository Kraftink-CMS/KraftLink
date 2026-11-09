package fr.nivcoo.kraftlink.bukkit.command;

import com.mojang.brigadier.tree.RootCommandNode;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandMap;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperLinkCommand implements BasicCommand {

    private static final String LABEL = "link";
    private static final String NAMESPACED_LABEL = "kraftlink:link";

    private final KraftLinkCommand executor;

    private PaperLinkCommand(KraftLinkCommand executor) {
        this.executor = executor;
    }

    public static void register(JavaPlugin plugin, KraftLinkCommand executor) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS.newHandler(event -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Commands commands = event.registrar();
            RootCommandNode<CommandSourceStack> root = commands.getDispatcher().getRoot();
            CommandMap commandMap = plugin.getServer().getCommandMap();
            if (root.getChild(LABEL) != null || commandMap.getCommand(LABEL) != null
                    || root.getChild(NAMESPACED_LABEL) != null || commandMap.getCommand(NAMESPACED_LABEL) != null) {
                plugin.getLogger().info("The /link shortcut is already registered. Use /kraftlink link.");
                return;
            }
            commands.register(LABEL, "Link your Bedrock account to the website", new PaperLinkCommand(executor));
        }).priority(Integer.MAX_VALUE));
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        executor.linkShortcut(source.getSender(), args);
    }

    @Override
    public String permission() {
        return "kraftlink.link";
    }
}
