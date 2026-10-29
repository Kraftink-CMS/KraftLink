package fr.nivcoo.kraftlink.bukkit;

import fr.nivcoo.kraftlink.bukkit.command.KraftLinkCommand;
import fr.nivcoo.kraftlink.bukkit.platform.BukkitPlatform;
import fr.nivcoo.kraftlink.bukkit.platform.PaperPlatform;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.HttpLinkTransport;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import fr.nivcoo.kraftlink.storage.CommandJournal;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;

public final class KraftLink extends JavaPlugin {

    private DeliveryEngine engine;

    @Override
    public void onEnable() {
        try {
            MainConfig config = MainConfig.load(configPath());
            BukkitPlatform platform = platform();
            CommandJournal journal = new CommandJournal(getDataFolder().toPath().resolve("commands.jsonl"));
            engine = new DeliveryEngine(config, platform, journal, new HttpLinkTransport(), getLogger());
            KraftLinkCommand executor = new KraftLinkCommand(this, engine, platform);
            PluginCommand command = Objects.requireNonNull(getCommand("kraftlink"));
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            engine.start();
            if (!config.enabled()) getLogger().info("KraftLink is waiting for cms_url and token in config.json.");
            if (config.allowedCommandRoots.isEmpty()) {
                getLogger().info("No server commands are authorized. Configure allowed_command_roots before enabling deliveries.");
            }
        } catch (IOException | IllegalArgumentException error) {
            getLogger().log(Level.SEVERE, "KraftLink could not start", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (engine == null) return;
        try {
            engine.close();
        } catch (IOException error) {
            getLogger().log(Level.SEVERE, "KraftLink journal could not close", error);
        }
    }

    public Path configPath() {
        return getDataFolder().toPath().resolve("config.json");
    }

    private BukkitPlatform platform() {
        boolean regions = hasMethod(Server.class, "getGlobalRegionScheduler");
        boolean metrics = hasMethod(Bukkit.class, "getTPS") && hasMethod(Bukkit.class, "getMinecraftVersion");
        if (!regions && !metrics) return new BukkitPlatform(this);
        boolean folia = getServer().getClass().getClassLoader()
                .getResource("io/papermc/paper/threadedregions/RegionizedServer.class") != null;
        return PaperBridge.create(this, regions, metrics, folia);
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) return true;
        }
        return false;
    }

    private static final class PaperBridge {

        private static BukkitPlatform create(JavaPlugin plugin, boolean regions, boolean metrics, boolean folia) {
            return new PaperPlatform(plugin, regions, metrics, folia);
        }
    }
}
