package fr.nivcoo.kraftlink.bukkit.platform;

import fr.nivcoo.kraftlink.model.Heartbeat;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public final class PaperPlatform extends BukkitPlatform {

    private final boolean regions;
    private final boolean metrics;
    private final boolean folia;

    public PaperPlatform(JavaPlugin plugin, boolean regions, boolean metrics, boolean folia) {
        super(plugin);
        this.regions = regions;
        this.metrics = metrics;
        this.folia = folia;
    }

    @Override
    public CompletionStage<Heartbeat> snapshot() {
        if (!regions) return super.snapshot();
        return onServerThread(() -> {
            String software = Bukkit.getName();
            String version = version();
            int maxPlayers = Bukkit.getMaxPlayers();
            List<Double> tps = tps();
            List<CompletableFuture<PlayerSample>> players = new ArrayList<>(Bukkit.getOnlinePlayers()).stream()
                    .map(player -> RegionTasks.sample(plugin, player))
                    .collect(Collectors.toList());
            return CompletableFuture.allOf(players.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
                Runtime runtime = Runtime.getRuntime();
                List<PlayerSample> samples = players.stream().map(CompletableFuture::join)
                        .filter(Objects::nonNull).collect(Collectors.toList());
                Map<String, Long> worlds = samples.stream()
                        .collect(Collectors.groupingBy(sample -> sample.world, Collectors.counting()));
                return new Heartbeat(1, "minecraft", software, version,
                        samples.stream().map(sample -> sample.identity).collect(Collectors.toList()),
                        maxPlayers, ManagementFactory.getRuntimeMXBean().getUptime() / 1000, tps,
                        new Heartbeat.Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()),
                        worlds.entrySet().stream()
                                .map(world -> new Heartbeat.WorldStatus(world.getKey(), world.getValue().intValue()))
                                .collect(Collectors.toList()));
            });
        }).thenCompose(value -> value);
    }

    @Override
    public void send(CommandSender sender, String message) {
        if (!plugin.isEnabled()) return;
        if (regions && sender instanceof Player) {
            RegionTasks.send(plugin, (Player) sender, message);
        } else {
            super.send(sender, message);
        }
    }

    @Override
    protected String version() {
        return metrics ? Bukkit.getMinecraftVersion() : super.version();
    }

    @Override
    protected List<Double> tps() {
        if (!metrics || folia) return Collections.emptyList();
        return Arrays.stream(Bukkit.getTPS()).boxed().collect(Collectors.toList());
    }

    @Override
    protected void dispatch(Runnable action) {
        if (regions) RegionTasks.dispatch(plugin, action);
        else super.dispatch(action);
    }

    private static final class RegionTasks {

        private static void dispatch(JavaPlugin plugin, Runnable action) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, action);
        }

        private static void send(JavaPlugin plugin, Player player, String message) {
            player.getScheduler().execute(plugin, () -> {
                if (plugin.isEnabled()) player.sendMessage(message);
            }, null, 1);
        }

        private static CompletableFuture<PlayerSample> sample(JavaPlugin plugin, Player player) {
            CompletableFuture<PlayerSample> sample = new CompletableFuture<>();
            boolean accepted = player.getScheduler().execute(plugin, () -> {
                Heartbeat.PlayerIdentity identity = new Heartbeat.PlayerIdentity(player.getUniqueId().toString(), player.getName());
                sample.complete(new PlayerSample(identity, player.getWorld().getName()));
            }, () -> sample.complete(null), 1);
            if (!accepted) sample.complete(null);
            return sample;
        }
    }

    private static final class PlayerSample {

        private final Heartbeat.PlayerIdentity identity;
        private final String world;

        private PlayerSample(Heartbeat.PlayerIdentity identity, String world) {
            this.identity = identity;
            this.world = world;
        }
    }
}
