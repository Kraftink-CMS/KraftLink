package fr.nivcoo.kraftlink.bukkit.hook;

import fr.nivcoo.kraftlink.bukkit.platform.BukkitPlatform;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.AuthMeRegistration;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.api.v3.AuthMePlayer;
import fr.xephi.authme.events.AuthMeAsyncPreRegisterEvent;
import fr.xephi.authme.events.PasswordEncryptionEvent;
import fr.xephi.authme.events.RegisterEvent;
import fr.xephi.authme.security.crypts.EncryptionMethod;
import fr.xephi.authme.security.crypts.HashedPassword;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AuthMeRegistrationHook implements Listener, AutoCloseable {

    private static final int MAX_PENDING = 256;
    private static final long PASSWORD_LIFETIME = TimeUnit.SECONDS.toNanos(15);

    private final BukkitPlatform platform;
    private final DeliveryEngine engine;
    private final File authMeConfig;
    private final Map<String, Registration> pending = new HashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(action -> {
        Thread thread = new Thread(action, "kraftlink-authme-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean passwordRegistration;

    public static AuthMeRegistrationHook install(JavaPlugin plugin, BukkitPlatform platform, DeliveryEngine engine,
                                                  Plugin authMe) {
        AuthMeRegistrationHook hook = new AuthMeRegistrationHook(platform, engine, authMe);
        try {
            plugin.getServer().getPluginManager().registerEvents(hook, plugin);
            hook.cleanup.scheduleWithFixedDelay(hook::expire, 1, 1, TimeUnit.SECONDS);
            return hook;
        } catch (RuntimeException | LinkageError error) {
            hook.close();
            throw error;
        }
    }

    private AuthMeRegistrationHook(BukkitPlatform platform, DeliveryEngine engine, Plugin authMe) {
        this.platform = platform;
        this.engine = engine;
        authMeConfig = new File(authMe.getDataFolder(), "config.yml");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPasswordEncryption(PasswordEncryptionEvent event) {
        passwordRegistration = "PASSWORD".equalsIgnoreCase(YamlConfiguration.loadConfiguration(authMeConfig)
                .getString("settings.registration.type"));
        synchronized (pending) {
            clearPending();
        }
        if (passwordRegistration && event.getMethod() != null) {
            event.setMethod(new RegistrationEncryption(event.getMethod()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegistrationAttempt(AuthMeAsyncPreRegisterEvent event) {
        Player player = event.getPlayer();
        MainConfig config = engine.config();
        if (!event.canRegister() || !passwordRegistration || !engine.authMeRegistrationEnabled(config)) {
            return;
        }
        String username = player.getName();
        if (!username.matches("[A-Za-z0-9_]{3,16}") || !platform.supportsAuthMeRegistration(player)) {
            return;
        }
        synchronized (pending) {
            if (pending.size() < MAX_PENDING) {
                Registration previous = pending.put(username.toLowerCase(Locale.ROOT),
                        new Registration(config, player.getUniqueId(), username));
                if (previous != null) {
                    previous.password = null;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegistration(RegisterEvent event) {
        Player player = event.getPlayer();
        String key = player.getName().toLowerCase(Locale.ROOT);
        Registration registration;
        synchronized (pending) {
            registration = pending.get(key);
            if (registration == null || registration.expired() || registration.claimed || registration.password == null
                    || !registration.uuid.equals(player.getUniqueId())
                    || !engine.authMeRegistrationEnabled(registration.config)) {
                return;
            }
            registration.claimed = true;
        }
        engine.registerAuthMe(registration.config, () -> verified(key, registration));
    }

    private void remember(String name, String password) {
        if (!passwordRegistration || name == null
                || password == null || password.isEmpty() || password.length() > 255) {
            return;
        }
        synchronized (pending) {
            Registration registration = pending.get(name.toLowerCase(Locale.ROOT));
            if (registration != null && !registration.expired() && !registration.claimed && registration.password == null
                    && engine.authMeRegistrationEnabled(registration.config)) {
                registration.password = password;
            }
        }
    }

    private AuthMeRegistration verified(String key, Registration registration) {
        String password;
        synchronized (pending) {
            if (!pending.remove(key, registration) || registration.expired()) {
                registration.password = null;
                return null;
            }
            password = registration.password;
            registration.password = null;
        }
        try {
            AuthMeApi api = AuthMeApi.getInstance();
            if (api == null || !api.isRegistered(key)) {
                return null;
            }
            long generation = registrationDate(api, key);
            if (generation < registration.startedAt - 1000 || !api.checkPassword(key, password)
                    || registrationDate(api, key) != generation || registration.expired()) {
                return null;
            }
            return new AuthMeRegistration(registration.uuid, registration.username, password, generation, registration.expiresAt);
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    private long registrationDate(AuthMeApi api, String name) {
        return api.getPlayerInfo(name).map(AuthMePlayer::getRegistrationDate).map(date -> date.toEpochMilli()).orElse(0L);
    }

    private void expire() {
        synchronized (pending) {
            pending.values().removeIf(registration -> {
                if (!registration.expired() && engine.authMeRegistrationEnabled(registration.config)) {
                    return false;
                }
                registration.password = null;
                return true;
            });
        }
    }

    private void clearPending() {
        pending.values().forEach(registration -> registration.password = null);
        pending.clear();
    }

    @Override
    public void close() {
        passwordRegistration = false;
        cleanup.shutdownNow();
        synchronized (pending) {
            clearPending();
        }
    }

    private final class RegistrationEncryption implements EncryptionMethod {

        private final EncryptionMethod delegate;

        private RegistrationEncryption(EncryptionMethod delegate) {
            this.delegate = delegate;
        }

        @Override
        public HashedPassword computeHash(String password, String name) {
            HashedPassword hash = delegate.computeHash(password, name);
            remember(name, password);
            return hash;
        }

        @Override
        public String computeHash(String password, String salt, String name) {
            return delegate.computeHash(password, salt, name);
        }

        @Override
        public boolean comparePassword(String password, HashedPassword hash, String name) {
            return delegate.comparePassword(password, hash, name);
        }

        @Override
        public String generateSalt() {
            return delegate.generateSalt();
        }

        @Override
        public boolean hasSeparateSalt() {
            return delegate.hasSeparateSalt();
        }
    }

    private static final class Registration {

        private final MainConfig config;
        private final UUID uuid;
        private final String username;
        private final long startedAt = System.currentTimeMillis();
        private final long expiresAt = System.nanoTime() + PASSWORD_LIFETIME;
        private String password;
        private boolean claimed;

        private Registration(MainConfig config, UUID uuid, String username) {
            this.config = config;
            this.uuid = uuid;
            this.username = username;
        }

        private boolean expired() {
            return expiresAt - System.nanoTime() <= 0;
        }
    }
}
