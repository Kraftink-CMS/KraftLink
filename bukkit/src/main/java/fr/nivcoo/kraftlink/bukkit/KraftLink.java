package fr.nivcoo.kraftlink.bukkit;

import fr.nivcoo.kraftlink.api.KraftLinkApi;
import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.api.KraftLinkProvider;
import fr.nivcoo.kraftlink.bukkit.command.KraftLinkCommand;
import fr.nivcoo.kraftlink.bukkit.command.PaperLinkCommand;
import fr.nivcoo.kraftlink.bukkit.hook.AuthMeRegistrationHook;
import fr.nivcoo.kraftlink.bukkit.platform.BukkitPlatform;
import fr.nivcoo.kraftlink.bukkit.platform.PaperPlatform;
import fr.nivcoo.kraftlink.config.LinkInstance;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.HttpLinkTransport;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import fr.nivcoo.kraftlink.storage.CommandJournal;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

public final class KraftLink extends JavaPlugin implements KraftLinkProvider {

    private volatile KraftLinkApi api;
    private DeliveryEngine engine;
    private BukkitPlatform platform;
    private AuthMeRegistrationHook authMeRegistration;

    @Override
    public void onEnable() {
        try {
            MainConfig config = MainConfig.load(configPath());
            Path dataDirectory = getDataFolder().toPath();
            LinkInstance instance = LinkInstance.load(dataDirectory, KraftLinkPlatform.BUKKIT);
            platform = platform();
            CommandJournal journal = new CommandJournal(dataDirectory.resolve("commands.jsonl"));
            engine = new DeliveryEngine(config, platform, journal, new HttpLinkTransport(instance), getLogger(), instance);
            KraftLinkCommand executor = new KraftLinkCommand(this, engine, platform);
            PluginCommand command = Objects.requireNonNull(getCommand("kraftlink"));
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            if (config.bedrockLinkEnabled) {
                registerLinkShortcut(executor);
            }
            platform.registerSignInMenu();
            installAuthMeRegistration();
            engine.start();
            if (!config.enabled()) getLogger().info("KraftLink is waiting for cms_url and token in config.json.");
            if (config.allowedCommandRoots.isEmpty()) {
                getLogger().info("No server commands are authorized. Configure allowed_command_roots before enabling deliveries.");
            }
            KraftLinkApi startedApi = engine.api();
            KraftLinkProvider.register(startedApi);
            api = startedApi;
            getServer().getServicesManager().register(KraftLinkApi.class, api, this, ServicePriority.Normal);
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            getLogger().log(Level.SEVERE, "KraftLink could not start", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        KraftLinkApi registeredApi = api;
        api = null;
        if (registeredApi != null) {
            KraftLinkProvider.unregister(registeredApi);
            getServer().getServicesManager().unregister(KraftLinkApi.class, registeredApi);
        }
        if (authMeRegistration != null) {
            authMeRegistration.close();
        }
        if (platform != null) {
            platform.closeSignInMenu();
        }
        if (engine == null) return;
        try {
            engine.close();
        } catch (IOException error) {
            getLogger().log(Level.SEVERE, "KraftLink journal could not close", error);
        }
    }

    private void registerLinkShortcut(KraftLinkCommand executor) {
        ClassLoader serverClasses = getServer().getClass().getClassLoader();
        if (serverClasses.getResource("io/papermc/paper/command/brigadier/BasicCommand.class") == null
                || serverClasses.getResource("io/papermc/paper/plugin/lifecycle/event/types/LifecycleEvents.class") == null) {
            getLogger().info("The /link shortcut is unavailable on this server. Use /kraftlink link.");
            return;
        }
        PaperLinkCommand.register(this, executor, () -> engine.config().bedrockLinkEnabled);
    }

    private void installAuthMeRegistration() {
        Plugin authMe = getServer().getPluginManager().getPlugin("AuthMe");
        if (authMe == null) {
            return;
        }
        try {
            authMeRegistration = AuthMeRegistrationHook.install(this, platform, engine, authMe);
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("KraftLink could not load the AuthMe registration hook.");
        }
    }

    public Path configPath() {
        return getDataFolder().toPath().resolve("config.json");
    }

    @Override
    public Optional<KraftLinkApi> api() {
        return Optional.ofNullable(api);
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
