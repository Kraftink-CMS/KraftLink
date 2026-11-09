package fr.nivcoo.kraftlink.platform;

import fr.nivcoo.kraftlink.api.KraftLinkApi;
import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.api.KraftLinkProvider;
import fr.nivcoo.kraftlink.command.ConnectionCommandHandler;
import fr.nivcoo.kraftlink.config.LinkInstance;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.HttpLinkTransport;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import fr.nivcoo.kraftlink.storage.CommandJournal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StandaloneLink implements AutoCloseable {

    private final KraftLinkPlatform platform;
    private final Path directory;
    private final Logger logger = Logger.getLogger("KraftLink");
    private final AtomicReference<ConnectionCommandHandler> commands = new AtomicReference<>();
    private DeliveryEngine engine;

    public StandaloneLink(KraftLinkPlatform platform, Path directory) {
        this.platform = platform;
        this.directory = directory;
    }

    public void start(ServerPlatform server) {
        if (engine != null) {
            return;
        }
        try {
            Path configPath = directory.resolve("config.json");
            MainConfig config = MainConfig.load(configPath);
            LinkInstance instance = LinkInstance.load(directory, platform);
            CommandJournal journal = new CommandJournal(directory.resolve("commands.jsonl"));
            engine = new DeliveryEngine(config, server, journal, new HttpLinkTransport(instance), logger, instance);
            KraftLinkProvider.register(engine.api());
            commands.set(new ConnectionCommandHandler(engine, configPath));
            engine.start();
            if (!config.enabled()) {
                logger.info("KraftLink is waiting for cms_url and token in config.json.");
            }
            if (config.allowedCommandRoots.isEmpty()) {
                logger.info("No server commands are authorized. Configure allowed_command_roots before enabling deliveries.");
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            close();
            logger.severe("KraftLink could not start. Check its configuration and data directory.");
        }
    }

    public void command(String input, boolean allowed, Consumer<String> reply) {
        ConnectionCommandHandler handler = commands.get();
        if (handler == null) {
            reply.accept("KraftLink is unavailable.");
            return;
        }
        String arguments = input.trim();
        handler.execute("kraftlink", arguments.isEmpty() ? new String[0] : arguments.split("\\s+"), allowed, reply);
    }

    public void link(String label, String[] arguments, boolean shortcut, boolean allowed, Supplier<BedrockIdentity> player,
                     Consumer<String> reply) {
        ConnectionCommandHandler handler = commands.get();
        if (handler == null) {
            reply.accept("KraftLink is unavailable.");
            return;
        }
        if (shortcut) {
            handler.linkShortcut(label, arguments, allowed, player, reply);
        } else {
            handler.link(label, arguments, allowed, player, reply);
        }
    }

    @Override
    public void close() {
        commands.set(null);
        if (engine == null) {
            return;
        }
        KraftLinkApi api = engine.api();
        KraftLinkProvider.unregister(api);
        try {
            engine.close();
        } catch (IOException error) {
            logger.log(Level.SEVERE, "KraftLink journal could not close", error);
        } finally {
            engine = null;
        }
    }
}
