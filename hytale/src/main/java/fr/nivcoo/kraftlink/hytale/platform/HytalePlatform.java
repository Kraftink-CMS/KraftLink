package fr.nivcoo.kraftlink.hytale.platform;

import com.hypixel.hytale.logger.sentry.SkipSentryException;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.ParserContext;
import com.hypixel.hytale.server.core.command.system.Tokenizer;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.ServerPlatform;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public final class HytalePlatform implements ServerPlatform {

    @Override
    public CompletionStage<Heartbeat> snapshot() {
        List<CompletableFuture<WorldSample>> samples = Universe.get().getWorlds().values().stream()
                .map(this::sample)
                .collect(Collectors.toList());
        return CompletableFuture.allOf(samples.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
            Runtime runtime = Runtime.getRuntime();
            List<WorldSample> worlds = samples.stream().map(CompletableFuture::join)
                    .filter(Objects::nonNull).collect(Collectors.toList());
            String version = HytaleServer.class.getPackage().getImplementationVersion();
            return new Heartbeat(
                    1,
                    "hytale",
                    "Hytale",
                    version == null ? "unknown" : version,
                    worlds.stream().flatMap(world -> world.players().stream()).collect(Collectors.toList()),
                    HytaleServer.get().getConfig().getMaxPlayers(),
                    ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                    Collections.emptyList(),
                    new Heartbeat.Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()),
                    worlds.stream()
                            .map(world -> new Heartbeat.WorldStatus(world.name(), world.players().size()))
                            .collect(Collectors.toList())
            );
        });
    }

    @Override
    public CompletionStage<ExecutionResult> execute(Delivery delivery, boolean requireOnline, BooleanSupplier authorized) {
        UUID uuid = delivery.playerId();
        String name = delivery.player();
        Universe universe = Universe.get();
        PlayerRef player = uuid == null ? findByName(universe, name) : universe.getPlayer(uuid);
        if (requireOnline && player == null) {
            PlayerRef named = uuid == null ? null : findByName(universe, name);
            if (!delivery.matchesPlayer(uuid, named == null ? null : named.getUuid(), null)) {
                return CompletableFuture.completedFuture(ExecutionResult.playerMismatch());
            }
            return CompletableFuture.completedFuture(ExecutionResult.deferred("The player is offline"));
        }
        UUID worldId = player == null ? null : player.getWorldUuid();
        World world = player == null ? universe.getDefaultWorld()
                : worldId == null ? null : universe.getWorld(worldId);
        if (world == null || !world.isAlive()) {
            return CompletableFuture.completedFuture(ExecutionResult.deferred("The target world is unavailable"));
        }
        CompletableFuture<ExecutionResult> result = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    if (!world.isAlive() || !authorized.getAsBoolean()) {
                        result.complete(ExecutionResult.deferred("The command lease or target world is no longer active"));
                        return;
                    }
                    PlayerRef named = findByName(universe, name);
                    PlayerRef current = uuid == null ? named : universe.getPlayer(uuid);
                    if (!delivery.matchesPlayer(uuid, named == null ? null : named.getUuid(),
                            current == null ? null : current.getUsername())) {
                        result.complete(ExecutionResult.playerMismatch());
                        return;
                    }
                    if (requireOnline && (current == null || !current.isValid())) {
                        result.complete(ExecutionResult.deferred("The player is no longer online"));
                        return;
                    }
                    if (current != null && !world.getPlayerRefs().contains(current)) {
                        result.complete(ExecutionResult.deferred("The player is changing worlds"));
                        return;
                    }
                    dispatch(delivery, authorized).whenComplete((execution, error) -> {
                        if (error != null) result.completeExceptionally(error);
                        else result.complete(execution);
                    });
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (SkipSentryException error) {
            if (error.getCause() instanceof IllegalThreadStateException) {
                result.complete(ExecutionResult.deferred("The target world is unavailable"));
            } else {
                result.completeExceptionally(error);
            }
        }
        return result;
    }

    private CompletionStage<ExecutionResult> dispatch(Delivery delivery, BooleanSupplier authorized) {
        AbstractCommand registered = CommandManager.get().resolveCommand(delivery.commandRoot());
        if (registered == null) {
            return CompletableFuture.completedFuture(
                    new ExecutionResult(ExecutionResult.State.FAILED, "The command is not registered on this server"));
        }
        ParseResult parsed = new ParseResult();
        ParserContext context;
        String command = delivery.commandLine();
        try {
            List<String> arguments = Tokenizer.parseArguments(command, parsed);
            if (parsed.failed()) return rejected(parsed);
            context = ParserContext.of(arguments, command, parsed);
            if (parsed.failed()) return rejected(parsed);
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(
                    new ExecutionResult(ExecutionResult.State.FAILED, "The server rejected the command input"));
        }
        if (!authorized.getAsBoolean()) {
            return CompletableFuture.completedFuture(ExecutionResult.deferred("The command lease is no longer active"));
        }
        CompletableFuture<Void> execution = registered.acceptCall(ConsoleSender.INSTANCE, context, parsed);
        if (parsed.failed()) return rejected(parsed);
        if (execution == null) return CompletableFuture.completedFuture(ExecutionResult.executed());
        return execution.thenApply(ignored -> ExecutionResult.executed());
    }

    private CompletionStage<ExecutionResult> rejected(ParseResult parsed) {
        parsed.sendMessages(ConsoleSender.INSTANCE);
        return CompletableFuture.completedFuture(
                new ExecutionResult(ExecutionResult.State.FAILED, "The server rejected the command arguments or permissions"));
    }

    private CompletableFuture<WorldSample> sample(World world) {
        if (!world.isAlive()) return CompletableFuture.completedFuture(null);
        CompletableFuture<WorldSample> future = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    if (!world.isAlive()) {
                        future.complete(null);
                        return;
                    }
                    List<Heartbeat.PlayerIdentity> players = world.getPlayerRefs().stream()
                            .map(player -> new Heartbeat.PlayerIdentity(player.getUuid().toString(), player.getUsername()))
                            .collect(Collectors.toList());
                    future.complete(new WorldSample(world.getName(), players));
                } catch (RuntimeException error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (SkipSentryException error) {
            if (error.getCause() instanceof IllegalThreadStateException) future.complete(null);
            else future.completeExceptionally(error);
        }
        return future;
    }

    private PlayerRef findByName(Universe universe, String name) {
        return name == null ? null : universe.getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE);
    }

    private static final class WorldSample {

        private final String name;
        private final List<Heartbeat.PlayerIdentity> players;

        private WorldSample(String name, List<Heartbeat.PlayerIdentity> players) {
            this.name = name;
            this.players = players;
        }

        private String name() {
            return name;
        }

        private List<Heartbeat.PlayerIdentity> players() {
            return players;
        }
    }
}
