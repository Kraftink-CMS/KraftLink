package fr.nivcoo.kraftlink.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import fr.nivcoo.kraftlink.api.KraftLinkApi;
import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.api.KraftLinkProvider;
import fr.nivcoo.kraftlink.config.LinkInstance;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.HttpLinkTransport;
import fr.nivcoo.kraftlink.hytale.command.KraftLinkCommand;
import fr.nivcoo.kraftlink.hytale.platform.HytalePlatform;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import fr.nivcoo.kraftlink.storage.CommandJournal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

public final class KraftLink extends JavaPlugin implements KraftLinkProvider {

    private volatile KraftLinkApi api;
    private DeliveryEngine engine;

    public KraftLink(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        try {
            MainConfig config = MainConfig.load(configPath());
            Path dataDirectory = getDataDirectory();
            LinkInstance instance = LinkInstance.load(dataDirectory, KraftLinkPlatform.HYTALE);
            engine = new DeliveryEngine(
                    config,
                    new HytalePlatform(),
                    new CommandJournal(dataDirectory.resolve("commands.jsonl")),
                    new HttpLinkTransport(instance),
                    Logger.getLogger("KraftLink"),
                    instance
            );
            getCommandRegistry().registerCommand(new KraftLinkCommand(this));
        } catch (IOException error) {
            throw new IllegalStateException("KraftLink storage could not start", error);
        }
    }

    @Override
    protected void start() {
        engine.start();
        api = engine.api();
    }

    @Override
    protected void shutdown() {
        api = null;
        if (engine == null) return;
        try {
            engine.close();
        } catch (IOException error) {
            throw new IllegalStateException("KraftLink storage could not close", error);
        }
    }

    public DeliveryEngine engine() {
        return engine;
    }

    @Override
    public Optional<KraftLinkApi> api() {
        return Optional.ofNullable(api);
    }

    public Path configPath() {
        return getDataDirectory().resolve("config.json");
    }
}
