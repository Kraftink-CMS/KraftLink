package fr.nivcoo.kraftlink.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import fr.nivcoo.kraftlink.api.KraftLinkApi;
import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.api.KraftLinkProvider;
import fr.nivcoo.kraftlink.config.LinkInstance;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.HttpLinkTransport;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import fr.nivcoo.kraftlink.storage.CommandJournal;
import fr.nivcoo.kraftlink.velocity.command.KraftLinkCommand;
import fr.nivcoo.kraftlink.velocity.platform.VelocityPlatform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class KraftLink implements KraftLinkProvider {

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final Logger logger = Logger.getLogger(KraftLink.class.getName());
    private volatile KraftLinkApi api;
    private DeliveryEngine engine;
    private CommandMeta commandMeta;

    @Inject
    public KraftLink(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe(priority = 100)
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            MainConfig config = MainConfig.load(configPath());
            LinkInstance instance = LinkInstance.load(dataDirectory, KraftLinkPlatform.VELOCITY);
            VelocityPlatform platform = new VelocityPlatform(this);
            engine = new DeliveryEngine(config, platform,
                    new CommandJournal(dataDirectory.resolve("commands.jsonl")), new HttpLinkTransport(instance), logger, instance);
            CommandMeta meta = proxy.getCommandManager().metaBuilder("kraftlink").plugin(this).build();
            proxy.getCommandManager().register(meta, new KraftLinkCommand(this, platform));
            commandMeta = meta;
            engine.start();
            if (!config.enabled()) logger.info("KraftLink is waiting for cms_url and token in config.json.");
            if (config.allowedCommandRoots.isEmpty()) {
                logger.info("No proxy commands are authorized. Configure allowed_command_roots before enabling deliveries.");
            }
            api = engine.api();
        } catch (IOException | IllegalArgumentException error) {
            logger.log(Level.SEVERE, "KraftLink could not start", error);
            close();
        }
    }

    @Subscribe(priority = -100)
    public void onProxyShutdown(ProxyShutdownEvent event) {
        close();
    }

    private void close() {
        api = null;
        if (engine != null) {
            try {
                engine.close();
            } catch (IOException error) {
                logger.log(Level.SEVERE, "KraftLink journal could not close", error);
            }
        }
        if (commandMeta != null) {
            proxy.getCommandManager().unregister(commandMeta);
            commandMeta = null;
        }
        proxy.getScheduler().tasksByPlugin(this).forEach(ScheduledTask::cancel);
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public DeliveryEngine engine() {
        return engine;
    }

    @Override
    public Optional<KraftLinkApi> api() {
        return Optional.ofNullable(api);
    }

    public boolean running() {
        return engine != null && engine.running() && !proxy.isShuttingDown();
    }

    public Path configPath() {
        return dataDirectory.resolve("config.json");
    }
}
