package fr.nivcoo.kraftlink.nukkit.platform;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.utils.LoginChainData;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.ServerPlatform;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class NukkitPlatform implements ServerPlatform {

    private final Plugin plugin;
    private final Server server;

    public NukkitPlatform(Plugin plugin) {
        this.plugin = plugin;
        server = plugin.getServer();
    }

    @Override
    public CompletionStage<Heartbeat> snapshot() {
        return schedule(() -> {
            Runtime runtime = Runtime.getRuntime();
            return new Heartbeat(1, "minecraftbedrock", "Nukkit", server.getVersion(),
                    server.getOnlinePlayers().values().stream()
                            .filter(Player::isOnline)
                            .map(player -> new Heartbeat.PlayerIdentity(identity(player).toString(), player.getName()))
                            .collect(Collectors.toList()),
                    server.getMaxPlayers(), ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                    Collections.singletonList((double) server.getTicksPerSecondAverage()),
                    new Heartbeat.Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()),
                    server.getLevels().values().stream()
                            .map(world -> new Heartbeat.WorldStatus(world.getName(), world.getPlayers().size()))
                            .collect(Collectors.toList()));
        });
    }

    @Override
    public CompletionStage<ExecutionResult> execute(Delivery delivery, boolean requireOnline,
                                                    BooleanSupplier authorized) {
        return schedule(() -> {
            if (!authorized.getAsBoolean()) {
                return ExecutionResult.deferred("The command lease is no longer active");
            }
            UUID uuid = delivery.playerId();
            Player named = delivery.player() == null ? null : server.getPlayerExact(delivery.player());
            Player player = uuid == null ? named : server.getOnlinePlayers().values().stream()
                    .filter(candidate -> uuid.equals(identity(candidate)))
                    .findFirst().orElse(null);
            if (!delivery.matchesPlayer(uuid, named == null ? null : identity(named),
                    player == null ? null : player.getName())) {
                return ExecutionResult.playerMismatch();
            }
            if (requireOnline && (player == null || !player.isOnline())) {
                return ExecutionResult.deferred("The player is offline");
            }
            if (!authorized.getAsBoolean()) {
                return ExecutionResult.deferred("The command lease is no longer active");
            }
            if (!server.dispatchCommand(server.getConsoleSender(), delivery.commandLine())) {
                return new ExecutionResult(ExecutionResult.State.FAILED, "The server did not accept the command");
            }
            return ExecutionResult.executed();
        });
    }

    private UUID identity(Player player) {
        LoginChainData chain = player.getLoginChainData();
        String xuid = chain != null && chain.isXboxAuthed() ? chain.getXUID() : null;
        if (xuid != null && xuid.matches("[1-9][0-9]{0,18}")) {
            return new UUID(0, Long.parseUnsignedLong(xuid));
        }
        return player.getUniqueId();
    }

    public void run(Runnable action) {
        if (plugin.isEnabled()) {
            server.getScheduler().scheduleTask(plugin, action);
        }
    }

    private <T> CompletionStage<T> schedule(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            result.completeExceptionally(new IllegalStateException("KraftLink is disabled"));
            return result;
        }
        run(() -> {
            try {
                result.complete(action.get());
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
