package fr.nivcoo.kraftlink.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.ServerPlatform;
import fr.nivcoo.kraftlink.platform.StandaloneLink;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public final class MinecraftPlatform implements ServerPlatform {

    private final MinecraftServer server;
    private final String software;

    public MinecraftPlatform(MinecraftServer server, String software) {
        this.server = server;
        this.software = software;
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher, StandaloneLink link) {
        dispatcher.register(Commands.literal("kraftlink")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(context -> manage(context, link, ""))
                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(context -> manage(context, link,
                                StringArgumentType.getString(context, "arguments")))));
    }

    private static int manage(CommandContext<CommandSourceStack> context, StandaloneLink link, String arguments) {
        CommandSourceStack source = context.getSource();
        link.command(arguments, true, message -> source.getServer().execute(() ->
                source.sendSuccess(() -> Component.literal(message), false)));
        return 1;
    }

    @Override
    public CompletionStage<Heartbeat> snapshot() {
        return server.submit(() -> {
            Runtime runtime = Runtime.getRuntime();
            PlayerList playerList = server.getPlayerList();
            List<Heartbeat.PlayerIdentity> players = playerList.getPlayers().stream()
                    .map(player -> new Heartbeat.PlayerIdentity(player.getUUID().toString(),
                            player.getGameProfile().name()))
                    .collect(Collectors.toList());
            List<Heartbeat.WorldStatus> worlds = new ArrayList<>();
            for (ServerLevel world : server.getAllLevels()) {
                worlds.add(new Heartbeat.WorldStatus(world.dimension().identifier().toString(), world.players().size()));
            }
            long averageTickNanos = server.getAverageTickTimeNanos();
            List<Double> tps = averageTickNanos > 0
                    ? Collections.singletonList(Math.min(server.tickRateManager().tickrate(),
                            1_000_000_000.0 / averageTickNanos))
                    : Collections.emptyList();
            return new Heartbeat(1, "minecraft", software, server.getServerVersion(), players,
                    playerList.getMaxPlayers(), ManagementFactory.getRuntimeMXBean().getUptime() / 1000, tps,
                    new Heartbeat.Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()), worlds);
        });
    }

    @Override
    public CompletionStage<ExecutionResult> execute(Delivery delivery, boolean requireOnline,
                                                    BooleanSupplier authorized) {
        return server.submit(() -> executeCommand(delivery, requireOnline, authorized));
    }

    private ExecutionResult executeCommand(Delivery delivery, boolean requireOnline, BooleanSupplier authorized) {
        if (!authorized.getAsBoolean()) {
            return ExecutionResult.deferred("The command lease is no longer active");
        }
        PlayerList players = server.getPlayerList();
        UUID uuid = delivery.playerId();
        ServerPlayer named = delivery.player() == null ? null : players.getPlayerByName(delivery.player());
        ServerPlayer player = uuid == null ? named : players.getPlayer(uuid);
        if (!delivery.matchesPlayer(uuid, named == null ? null : named.getUUID(),
                player == null ? null : player.getGameProfile().name())) {
            return ExecutionResult.playerMismatch();
        }
        if (requireOnline && player == null) {
            return ExecutionResult.deferred("The player is offline");
        }
        if (!authorized.getAsBoolean()) {
            return ExecutionResult.deferred("The command lease is no longer active");
        }
        AtomicBoolean succeeded = new AtomicBoolean();
        CommandSourceStack console = server.createCommandSourceStack().withCallback((success, value) -> {
            if (success) {
                succeeded.set(true);
            }
        });
        server.getCommands().performPrefixedCommand(console, delivery.commandLine());
        if (succeeded.get()) {
            return ExecutionResult.executed();
        }
        return new ExecutionResult(ExecutionResult.State.FAILED, "The server did not report command success");
    }
}
