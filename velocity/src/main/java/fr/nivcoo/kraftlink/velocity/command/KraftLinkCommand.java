package fr.nivcoo.kraftlink.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fr.nivcoo.kraftlink.command.ConnectionCommandHandler;
import fr.nivcoo.kraftlink.velocity.KraftLink;
import net.kyori.adventure.text.Component;

import java.util.Collections;
import java.util.List;

public final class KraftLinkCommand implements SimpleCommand {

    private final KraftLink plugin;
    private final ConnectionCommandHandler handler;

    public KraftLinkCommand(KraftLink plugin) {
        this.plugin = plugin;
        handler = new ConnectionCommandHandler(plugin.engine(), plugin.configPath());
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        handler.execute(invocation.alias(), invocation.arguments(), source.hasPermission("kraftlink.admin"),
                message -> send(source, message));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!plugin.running() || !invocation.source().hasPermission("kraftlink.admin")) return Collections.emptyList();
        String[] args = invocation.arguments();
        return handler.complete(args.length == 0 ? new String[]{""} : args);
    }

    private void send(CommandSource source, String message) {
        if (!plugin.running()) return;
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            if (!plugin.running() || source instanceof Player && !((Player) source).isActive()) return;
            source.sendMessage(Component.text(message));
        }).schedule();
    }
}
