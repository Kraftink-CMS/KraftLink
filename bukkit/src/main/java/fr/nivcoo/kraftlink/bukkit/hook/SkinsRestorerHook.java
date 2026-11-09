package fr.nivcoo.kraftlink.bukkit.hook;

import fr.nivcoo.kraftlink.api.KraftLinkRegistration;
import fr.nivcoo.kraftlink.api.LinkPollOperation;
import fr.nivcoo.kraftlink.bukkit.platform.BukkitPlatform;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.service.DeliveryEngine;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.exception.DataRequestException;
import net.skinsrestorer.api.exception.MineSkinException;
import net.skinsrestorer.api.property.SkinIdentifier;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.property.SkinVariant;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public final class SkinsRestorerHook implements LinkPollOperation, AutoCloseable {

    private static final int PLAYER_LIMIT = 40;
    private static final int JOB_LIMIT = 16;
    private static final int PNG_LIMIT = 32 * 1024;
    private static final int PROPERTY_LIMIT = 4096;
    private static final byte[] PNG_HEADER = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private final Plugin dependency;
    private final BukkitPlatform platform;
    private final DeliveryEngine engine;
    private final SkinsRestorer skins;
    private final KraftLinkRegistration registration;
    private final Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(JOB_LIMIT), action -> {
                Thread thread = new Thread(action, "kraftlink-skins");
                thread.setDaemon(true);
                return thread;
            });
    private Map<String, Map<String, Object>> sent = Collections.emptyMap();
    private MainConfig requestConfig;
    private int playerOffset;
    private volatile boolean running = true;

    private SkinsRestorerHook(Plugin dependency, BukkitPlatform platform, DeliveryEngine engine, SkinsRestorer skins) {
        this.dependency = dependency;
        this.platform = platform;
        this.engine = engine;
        this.skins = skins;
        registration = engine.api().registerPollOperation(this);
    }

    public static SkinsRestorerHook install(Plugin dependency, BukkitPlatform platform, DeliveryEngine engine) {
        return new SkinsRestorerHook(dependency, platform, engine, SkinsRestorerProvider.get());
    }

    @Override
    public String name() {
        return "skins.pending";
    }

    @Override
    public Map<String, Object> request() {
        if (!running || !dependency.isEnabled()) {
            return null;
        }
        MainConfig config = engine.config();
        if (requestConfig != config) {
            synchronized (pending) {
                pending.clear();
            }
            requestConfig = config;
            playerOffset = 0;
        }
        List<Heartbeat.PlayerIdentity> online;
        try {
            online = await(platform.onlinePlayers());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException error) {
            return null;
        }
        int size = online.size();
        List<Map<String, String>> players = new ArrayList<>();
        for (int index = 0; index < Math.min(PLAYER_LIMIT, size); index++) {
            Heartbeat.PlayerIdentity player = online.get((playerOffset + index) % size);
            players.add(Collections.singletonMap("uuid", player.uuid()));
        }
        playerOffset = size == 0 ? 0 : (playerOffset + PLAYER_LIMIT) % size;
        Map<String, Object> request = new HashMap<>();
        request.put("players", players);
        synchronized (pending) {
            sent = pending.entrySet().stream()
                    .filter(entry -> entry.getValue() != null)
                    .limit(JOB_LIMIT)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            request.put("results", new ArrayList<>(sent.values()));
        }
        return request;
    }

    @Override
    public void receive(Map<String, Object> data) {
        Object jobs = data.get("skins");
        synchronized (pending) {
            sent.forEach(pending::remove);
            sent = Collections.emptyMap();
        }
        if (!(jobs instanceof List<?>) || !active(requestConfig)) {
            return;
        }
        int count = 0;
        for (Object candidate : (List<?>) jobs) {
            if (++count > JOB_LIMIT) {
                break;
            }
            if (candidate instanceof Map<?, ?>) {
                enqueue((Map<?, ?>) candidate, requestConfig);
            }
        }
    }

    private void enqueue(Map<?, ?> job, MainConfig config) {
        String identity = string(job.get("uuid"), 36);
        String revision = string(job.get("revision"), 64);
        if (!identity.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || !revision.matches("[a-zA-Z0-9_-]+")) {
            return;
        }
        UUID uuid = UUID.fromString(identity);
        String key = uuid + ":" + revision;
        synchronized (pending) {
            if (!running || pending.size() >= JOB_LIMIT * 2 || pending.containsKey(key)) {
                return;
            }
            pending.put(key, null);
        }
        try {
            worker.execute(() -> execute(key, uuid, revision, job, config));
        } catch (RejectedExecutionException error) {
            synchronized (pending) {
                pending.remove(key);
            }
        }
    }

    private void execute(String key, UUID uuid, String revision, Map<?, ?> job, MainConfig config) {
        Map<String, Object> result = new HashMap<>();
        result.put("uuid", uuid.toString());
        result.put("revision", revision);
        String status = "error";
        try {
            if (active(config)) {
                status = synchronize(uuid, job, result, config);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (DataRequestException | MineSkinException | ExecutionException | TimeoutException |
                 RuntimeException error) {
            dependency.getLogger().fine("KraftLink could not complete a skin synchronization: " +
                    error.getClass().getSimpleName());
        } finally {
            result.put("status", status);
            synchronized (pending) {
                if (active(config)) {
                    pending.put(key, result);
                }
            }
        }
    }

    private String synchronize(UUID uuid, Map<?, ?> job, Map<String, Object> result, MainConfig config)
            throws DataRequestException, MineSkinException, ExecutionException, TimeoutException,
            InterruptedException {
        String playerName = await(platform.withOnlinePlayer(uuid, Player::getName));
        if (playerName == null) {
            return "offline";
        }
        if (!active(config)) {
            return "error";
        }
        if (Boolean.TRUE.equals(job.get("read"))) {
            SkinProperty property = skins.getPlayerStorage().getSkinForPlayer(uuid, playerName).orElse(null);
            return putProperty(result, property) ? "read" : "missing";
        }

        SkinIdentifier identifier = SkinIdentifier.ofCustom("kraftlink_" + uuid.toString().replace("-", ""));
        SkinProperty property;
        if (Boolean.TRUE.equals(job.get("reset"))) {
            if (skins.getPlayerStorage().getSkinIdOfPlayer(uuid).filter(identifier::equals).isPresent()) {
                skins.getPlayerStorage().removeSkinIdOfPlayer(uuid);
            }
            skins.getSkinStorage().removeSkinData(identifier);
            property = skins.getPlayerStorage().getSkinForPlayer(uuid, playerName).orElse(PropertyUtils.EMPTY_SKIN);
        } else {
            boolean generated = job.containsKey("png");
            property = generated ? generate(job) : signed(job);
            if (property == null || (generated && !putProperty(result, property)) || !active(config)) {
                return "error";
            }
            skins.getSkinStorage().setCustomSkinData(identifier.getIdentifier(), property);
            skins.getPlayerStorage().setSkinIdOfPlayer(uuid, identifier);
        }

        if (active(config)) {
            await(platform.withOnlinePlayer(uuid, player -> {
                if (active(config)) {
                    skins.getSkinApplier(Player.class).applySkin(player, property);
                }
                return null;
            }));
        }
        return "applied";
    }

    private boolean active(MainConfig config) {
        return running && dependency.isEnabled() && config == engine.config();
    }

    private SkinProperty signed(Map<?, ?> job) {
        String value = string(job.get("value"), PROPERTY_LIMIT);
        String signature = string(job.get("signature"), PROPERTY_LIMIT);
        if (!base64(value) || !base64(signature)) {
            return null;
        }
        return SkinProperty.of(value, signature);
    }

    private SkinProperty generate(Map<?, ?> job) throws DataRequestException, MineSkinException {
        String image = string(job.get("png"), (PNG_LIMIT + 2) / 3 * 4);
        String model = string(job.get("variant"), 7);
        if (!base64(image) || !(model.isEmpty() || model.equals("classic") || model.equals("slim"))) {
            return null;
        }
        byte[] png = Base64.getDecoder().decode(image);
        if (png.length < 33 || png.length > PNG_LIMIT
                || !Arrays.equals(PNG_HEADER, Arrays.copyOf(png, PNG_HEADER.length))
                || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R'
                || png[16] != 0 || png[17] != 0 || png[18] != 0 || png[19] != 64
                || png[20] != 0 || png[21] != 0 || png[22] != 0 || (png[23] != 32 && png[23] != 64)) {
            return null;
        }
        SkinVariant variant = model.equals("slim") ? SkinVariant.SLIM : SkinVariant.CLASSIC;
        return skins.getMineSkinAPI().genSkin(png, variant).getProperty();
    }

    private boolean putProperty(Map<String, Object> result, SkinProperty property) {
        if (property == null || !base64(string(property.getValue(), PROPERTY_LIMIT)) ||
                !base64(string(property.getSignature(), PROPERTY_LIMIT))) {
            return false;
        }
        result.put("value", property.getValue());
        result.put("signature", property.getSignature());
        return true;
    }

    private static boolean base64(String value) {
        return !value.isEmpty() && value.matches("[A-Za-z0-9+/]+={0,2}");
    }

    private static String string(Object value, int maxLength) {
        return value instanceof String && ((String) value).length() <= maxLength ? (String) value : "";
    }

    private static <T> T await(CompletionStage<T> task)
            throws ExecutionException, TimeoutException, InterruptedException {
        return task.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        running = false;
        registration.close();
        worker.shutdownNow();
        synchronized (pending) {
            pending.clear();
            sent = Collections.emptyMap();
        }
    }
}
