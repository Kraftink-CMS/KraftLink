package fr.nivcoo.kraftlink.command;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.service.DeliveryEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class ConnectionCommandHandler {

    private final DeliveryEngine engine;
    private final Path configPath;

    public ConnectionCommandHandler(DeliveryEngine engine, Path configPath) {
        this.engine = engine;
        this.configPath = configPath;
    }

    public void execute(String label, String[] args, boolean permitted, Consumer<String> reply) {
        if (!engine.running()) {
            return;
        }
        MainConfig.Messages messages = engine.config().messages;
        if (!permitted) {
            reply.accept(messages.permissionDenied);
            return;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status":
                if (args.length > 1) {
                    usage(label, reply);
                    return;
                }
                reply.accept(messages.status
                        .replace("{status}", engine.status())
                        .replace("{uncertain}", Integer.toString(engine.journal().uncertain().size())));
                break;
            case "uncertain":
                if (args.length != 1) {
                    usage(label, reply);
                    return;
                }
                engine.journal().uncertain().stream().limit(20).forEach(entry ->
                        reply.accept(messages.uncertainEntry
                                .replace("{id}", entry.key())
                                .replace("{state}", entry.state().name())));
                break;
            case "reload":
                if (args.length != 1) {
                    usage(label, reply);
                    return;
                }
                async(() -> reload(reply));
                break;
            case "resolve":
                if (args.length != 3
                        || !(args[2].equalsIgnoreCase("delivered") || args[2].equalsIgnoreCase("retry"))) {
                    usage(label, reply);
                    return;
                }
                async(() -> resolve(args[1], args[2].equalsIgnoreCase("delivered"), reply));
                break;
            default:
                usage(label, reply);
        }
    }

    public List<String> complete(String[] args) {
        return complete(args, true, false);
    }

    public void link(String label, String[] args, boolean permitted, Supplier<BedrockIdentity> player,
                     Consumer<String> reply) {
        link(label, args, 1, permitted, player, reply);
    }

    public void linkShortcut(String label, String[] args, boolean permitted, Supplier<BedrockIdentity> player,
                             Consumer<String> reply) {
        link(label, args, 0, permitted, player, reply);
    }

    private void link(String label, String[] args, int codeIndex, boolean permitted, Supplier<BedrockIdentity> player,
                      Consumer<String> reply) {
        if (!engine.running()) {
            return;
        }
        MainConfig config = engine.config();
        MainConfig.Messages messages = config.messages;
        if (!config.bedrockLinkEnabled) {
            reply.accept(messages.linkDisabled);
            return;
        }
        if (player == null) {
            reply.accept(messages.linkPlayerOnly);
            return;
        }
        if (!permitted) {
            reply.accept(messages.permissionDenied);
            return;
        }
        if (args.length != codeIndex + 1 || !args[codeIndex].matches("[A-Za-z2-7]{10}")) {
            String usage = codeIndex == 0 ? messages.linkShortcutUsage : messages.linkUsage;
            reply.accept(usage.replace("{label}", label));
            return;
        }
        BedrockIdentity identity = player.get();
        if (identity == null) {
            reply.accept(messages.linkFloodgateRequired);
            return;
        }
        if (identity.linkedToJava()) {
            reply.accept(messages.linkJavaRequired);
            return;
        }

        engine.verifyBedrock(args[codeIndex].toUpperCase(Locale.ROOT), identity, reply);
    }

    public List<String> complete(String[] args, boolean admin, boolean linkPermitted) {
        boolean link = linkPermitted && engine.config().bedrockLinkEnabled;
        List<String> values;
        if (args.length == 1 && admin && link) {
            values = Arrays.asList("status", "reload", "uncertain", "resolve", "link");
        } else if (args.length == 1 && admin) {
            values = Arrays.asList("status", "reload", "uncertain", "resolve");
        } else if (args.length == 1 && link) {
            values = Collections.singletonList("link");
        } else if (args.length == 3 && admin && args[0].equalsIgnoreCase("resolve")) {
            values = Arrays.asList("delivered", "retry");
        } else {
            return Collections.emptyList();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).collect(Collectors.toList());
    }

    private void usage(String label, Consumer<String> reply) {
        reply.accept(engine.config().messages.usage.replace("{label}", label));
    }

    private void async(Runnable action) {
        Thread thread = new Thread(() -> {
            if (engine.running()) {
                action.run();
            }
        }, "kraftlink-command");
        thread.setDaemon(true);
        thread.start();
    }

    private void reload(Consumer<String> reply) {
        try {
            MainConfig candidate = MainConfig.load(configPath);
            if (!engine.running()) {
                return;
            }
            engine.reconfigure(candidate);
            reply.accept(engine.config().messages.reloaded);
        } catch (IOException | IllegalArgumentException error) {
            reply.accept(engine.config().messages.reloadRejected.replace("{error}", String.valueOf(error.getMessage())));
        }
    }

    private void resolve(String id, boolean delivered, Consumer<String> reply) {
        try {
            boolean changed = engine.journal().resolve(id, delivered);
            MainConfig.Messages messages = engine.config().messages;
            reply.accept(changed ? messages.resolved : messages.notUncertain);
        } catch (IOException error) {
            reply.accept(engine.config().messages.journalFailed);
        }
    }
}
