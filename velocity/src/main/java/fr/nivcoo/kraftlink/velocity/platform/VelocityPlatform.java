package fr.nivcoo.kraftlink.velocity.platform;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.ServerPlatform;
import fr.nivcoo.kraftlink.velocity.KraftLink;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class VelocityPlatform implements ServerPlatform {

    private final KraftLink plugin;
    private final ProxyServer proxy;

    public VelocityPlatform(KraftLink plugin) {
        this.plugin = plugin;
        proxy = plugin.proxy();
    }

    @Override
    public CompletionStage<Heartbeat> snapshot() {
        return schedule(() -> {
            Runtime runtime = Runtime.getRuntime();
            List<Heartbeat.PlayerIdentity> players = new ArrayList<>(proxy.getAllPlayers()).stream()
                    .filter(Player::isActive)
                    .map(player -> new Heartbeat.PlayerIdentity(player.getUniqueId().toString(), player.getUsername()))
                    .collect(Collectors.toList());
            return new Heartbeat(1, "minecraft", proxy.getVersion().getName(), proxy.getVersion().getVersion(),
                    players, proxy.getConfiguration().getShowMaxPlayers(),
                    ManagementFactory.getRuntimeMXBean().getUptime() / 1000, Collections.emptyList(),
                    new Heartbeat.Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()),
                    Collections.emptyList());
        });
    }

    @Override
    public CompletionStage<ExecutionResult> execute(Delivery delivery, boolean requireOnline, BooleanSupplier authorized) {
        return schedule(() -> {
            if (!authorized.getAsBoolean()) {
                return CompletableFuture.completedFuture(ExecutionResult.deferred("The command lease is no longer active"));
            }
            UUID uuid = delivery.playerId();
            Player named = delivery.player() == null ? null : proxy.getPlayer(delivery.player()).orElse(null);
            Player player = uuid == null ? named : proxy.getPlayer(uuid).orElse(null);
            if (uuid != null && named != null && !named.getUniqueId().equals(uuid)
                    || player != null && delivery.player() != null && !player.getUsername().equalsIgnoreCase(delivery.player())) {
                return CompletableFuture.completedFuture(new ExecutionResult(ExecutionResult.State.FAILED,
                        "Player name does not match the UUID"));
            }
            if (requireOnline && (player == null || !player.isActive())) {
                return CompletableFuture.completedFuture(ExecutionResult.deferred("The player is offline"));
            }
            String command = delivery.command().replaceFirst("^/", "");
            String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            CommandManager manager = proxy.getCommandManager();
            ConsoleCommandSource console = proxy.getConsoleCommandSource();
            if (!manager.hasCommand(root, console)) {
                return CompletableFuture.completedFuture(new ExecutionResult(ExecutionResult.State.FAILED,
                        "The command is not registered or available on this proxy"));
            }
            if (!authorized.getAsBoolean()) {
                return CompletableFuture.completedFuture(ExecutionResult.deferred("The command lease is no longer active"));
            }
            return manager.executeAsync(console, command).thenApply(accepted -> Boolean.TRUE.equals(accepted)
                    ? ExecutionResult.executed()
                    : new ExecutionResult(ExecutionResult.State.FAILED, "The proxy did not accept the command"));
        }).thenCompose(result -> result);
    }

    private <T> CompletableFuture<T> schedule(Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!plugin.running()) {
            future.completeExceptionally(new IllegalStateException("KraftLink is disabled"));
            return future;
        }
        proxy.getScheduler().buildTask(plugin, () -> {
            if (!plugin.running()) {
                future.completeExceptionally(new IllegalStateException("KraftLink is disabled"));
                return;
            }
            try {
                future.complete(action.get());
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            }
        }).schedule();
        return future;
    }
}
