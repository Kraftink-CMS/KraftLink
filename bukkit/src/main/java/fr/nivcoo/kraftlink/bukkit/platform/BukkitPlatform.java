package fr.nivcoo.kraftlink.bukkit.platform;

import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class BukkitPlatform implements ServerPlatform {

    protected final JavaPlugin plugin;

    public BukkitPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletionStage<Heartbeat> snapshot() {
        return onServerThread(() -> {
            Runtime runtime = Runtime.getRuntime();
            return new Heartbeat(1, "minecraft", Bukkit.getName(), version(),
                    Bukkit.getOnlinePlayers().stream()
                            .map(player -> new Heartbeat.PlayerIdentity(player.getUniqueId().toString(), player.getName()))
                            .collect(Collectors.toList()),
                    Bukkit.getMaxPlayers(), ManagementFactory.getRuntimeMXBean().getUptime() / 1000, tps(),
                    new Heartbeat.Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()),
                    Bukkit.getWorlds().stream()
                            .map(world -> new Heartbeat.WorldStatus(world.getName(), world.getPlayers().size()))
                            .collect(Collectors.toList()));
        });
    }

    @Override
    public CompletionStage<ExecutionResult> execute(Delivery delivery, boolean requireOnline, BooleanSupplier authorized) {
        return onServerThread(() -> {
            if (!authorized.getAsBoolean()) return ExecutionResult.deferred("The command lease is no longer active");
            Player player = findPlayer(delivery);
            if (requireOnline && (player == null || !player.isOnline())) {
                return ExecutionResult.deferred("The player is offline");
            }
            if (player != null && delivery.player() != null && !player.getName().equalsIgnoreCase(delivery.player())) {
                return new ExecutionResult(ExecutionResult.State.FAILED, "Player name does not match the UUID");
            }
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), delivery.command().replaceFirst("^/", ""))) {
                return new ExecutionResult(ExecutionResult.State.FAILED, "The server did not accept the command");
            }
            return ExecutionResult.executed();
        });
    }

    public void send(CommandSender sender, String message) {
        if (!plugin.isEnabled()) return;
        dispatch(() -> {
            if (plugin.isEnabled()) sender.sendMessage(message);
        });
    }

    protected String version() {
        return Bukkit.getBukkitVersion();
    }

    protected List<Double> tps() {
        return Collections.emptyList();
    }

    protected void dispatch(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private Player findPlayer(Delivery delivery) {
        if (delivery.playerId() != null) return Bukkit.getPlayer(delivery.playerId());
        if (delivery.player() == null) return null;
        return Bukkit.getPlayerExact(delivery.player());
    }

    protected <T> CompletableFuture<T> onServerThread(Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("KraftLink is disabled"));
            return future;
        }
        dispatch(() -> {
            if (!plugin.isEnabled()) {
                future.completeExceptionally(new IllegalStateException("KraftLink is disabled"));
                return;
            }
            try {
                future.complete(action.get());
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }
}
