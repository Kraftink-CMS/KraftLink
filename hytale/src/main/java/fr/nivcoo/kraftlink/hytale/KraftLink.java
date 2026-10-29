package fr.nivcoo.kraftlink.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.HttpLinkTransport;
import fr.nivcoo.kraftlink.hytale.command.KraftLinkCommand;
import fr.nivcoo.kraftlink.hytale.platform.HytalePlatform;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import fr.nivcoo.kraftlink.storage.CommandJournal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

public final class KraftLink extends JavaPlugin {

    private DeliveryEngine engine;

    public KraftLink(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        try {
            MainConfig config = MainConfig.load(configPath());
            engine = new DeliveryEngine(
                    config,
                    new HytalePlatform(),
                    new CommandJournal(getDataDirectory().resolve("commands.jsonl")),
                    new HttpLinkTransport(),
                    Logger.getLogger("KraftLink")
            );
            getCommandRegistry().registerCommand(new KraftLinkCommand(this));
        } catch (IOException error) {
            throw new IllegalStateException("KraftLink storage could not start", error);
        }
    }

    @Override
    protected void start() {
        engine.start();
    }

    @Override
    protected void shutdown() {
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

    public Path configPath() {
        return getDataDirectory().resolve("config.json");
    }
}
