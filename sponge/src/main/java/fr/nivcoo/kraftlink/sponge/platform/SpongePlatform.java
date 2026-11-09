package fr.nivcoo.kraftlink.sponge.platform;

import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.ServerPlatform;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.exception.CommandException;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.plugin.PluginContainer;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class SpongePlatform implements ServerPlatform {

    private final Server server;
    private final PluginContainer plugin;

    public SpongePlatform(Server server, PluginContainer plugin) {
        this.server = server;
        this.plugin = plugin;
    }

    @Override
    public CompletionStage<Heartbeat> snapshot() {
        return schedule(() -> {
            Runtime runtime = Runtime.getRuntime();
            return new Heartbeat(1, "minecraft", "Sponge", Sponge.platform().minecraftVersion().name(),
                    server.onlinePlayers().stream()
                            .map(player -> new Heartbeat.PlayerIdentity(player.uniqueId().toString(), player.name()))
                            .collect(Collectors.toList()),
                    server.maxPlayers(), ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                    Collections.singletonList(server.ticksPerSecond()),
                    new Heartbeat.Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()),
                    server.worldManager().worlds().stream()
                            .map(world -> new Heartbeat.WorldStatus(world.key().asString(), world.players().size()))
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
            ServerPlayer named = delivery.player() == null ? null : server.player(delivery.player()).orElse(null);
            ServerPlayer player = uuid == null ? named : server.player(uuid).orElse(null);
            if (!delivery.matchesPlayer(uuid, named == null ? null : named.uniqueId(),
                    player == null ? null : player.name())) {
                return ExecutionResult.playerMismatch();
            }
            if (requireOnline && player == null) {
                return ExecutionResult.deferred("The player is offline");
            }
            if (!authorized.getAsBoolean()) {
                return ExecutionResult.deferred("The command lease is no longer active");
            }
            try {
                if (server.commandManager().process(Sponge.systemSubject(), delivery.commandLine()).isSuccess()) {
                    return ExecutionResult.executed();
                }
            } catch (CommandException error) {
                return new ExecutionResult(ExecutionResult.State.FAILED, "The server rejected the command");
            }
            return new ExecutionResult(ExecutionResult.State.FAILED, "The server did not accept the command");
        });
    }

    public void run(Runnable action) {
        server.scheduler().submit(Task.builder().plugin(plugin).execute(action).build());
    }

    private <T> CompletionStage<T> schedule(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
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
