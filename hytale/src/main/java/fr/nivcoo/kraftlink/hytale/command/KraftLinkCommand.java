package fr.nivcoo.kraftlink.hytale.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import fr.nivcoo.kraftlink.command.ConnectionCommandHandler;
import fr.nivcoo.kraftlink.hytale.KraftLink;

import java.util.Arrays;

public final class KraftLinkCommand extends CommandBase {

    private final KraftLink plugin;
    private final ConnectionCommandHandler handler;

    public KraftLinkCommand(KraftLink plugin) {
        super("kraftlink", "Manage the KraftInk server connection");
        this.plugin = plugin;
        handler = new ConnectionCommandHandler(plugin.engine(), plugin.configPath());
        requirePermission("kraftlink.admin");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeSync(CommandContext context) {
        String[] input = context.getInputString().trim().split("\\s+");
        String[] args = input.length < 2 ? new String[0] : Arrays.copyOfRange(input, 1, input.length);
        handler.execute("kraftlink", args, context.sender().hasPermission("kraftlink.admin"), message -> {
            if (plugin.engine().running()) {
                context.sendMessage(Message.raw(message));
            }
        });
    }
}
