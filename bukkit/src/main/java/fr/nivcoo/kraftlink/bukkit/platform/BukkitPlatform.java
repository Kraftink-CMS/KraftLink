package fr.nivcoo.kraftlink.bukkit.platform;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.hook.FloodgateProfiles;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.InGameSignInPlayer;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.InGameSignInPlatform;
import fr.nivcoo.kraftlink.platform.ServerPlatform;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class BukkitPlatform implements ServerPlatform, InGameSignInPlatform {

    protected final JavaPlugin plugin;
    private final SignInInventory signInInventory;

    public BukkitPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
        signInInventory = new SignInInventory((player, action) -> onPlayerThread(player, () -> {
            action.run();
            return null;
        }));
    }

    public void registerSignInMenu() {
        if (supportsSignInInventory()) {
            plugin.getServer().getPluginManager().registerEvents(signInInventory, plugin);
        }
    }

    public void closeSignInMenu() {
        if (supportsSignInInventory()) {
            signInInventory.close();
        }
    }

    @Override
    public CompletionStage<List<InGameSignInPlayer>> inGameSignInPlayers() {
        return onServerThread(() -> Bukkit.getOnlinePlayers().stream()
                .map(this::inGameSignInIdentity).filter(Objects::nonNull).collect(Collectors.toList()));
    }

    public InGameSignInPlayer inGameSignInIdentity(Player player) {
        if (!player.isOnline() || !player.hasPermission("kraftlink.signin")) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        if (hasFloodgate() && FloodgateProfiles.isPlayer(uuid)) {
            BedrockIdentity identity = FloodgateProfiles.find(uuid);
            return identity == null || identity.linkedToJava() ? null
                    : new InGameSignInPlayer(uuid, identity.username(), "bedrock");
        }
        if (!standaloneOnlineMode() || uuid.version() != 4) {
            return null;
        }
        return new InGameSignInPlayer(uuid, player.getName(), "java");
    }

    public boolean supportsAuthMeRegistration(Player player) {
        UUID uuid = player.getUniqueId();
        if (hasFloodgate() && FloodgateProfiles.isPlayer(uuid)) {
            return false;
        }
        if (uuid.version() == 4) {
            return standaloneOnlineMode();
        }
        return !Bukkit.getOnlineMode()
                && uuid.equals(UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName()).getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public CompletionStage<Boolean> showInGameSignIn(InGameSignInPlayer recipient, InGameSignInPrompt prompt,
                                                  MainConfig.SignInMessages messages,
                                                  BiConsumer<String, Consumer<String>> respond) {
        return onServerThread(() -> Bukkit.getPlayer(recipient.uuid())).thenCompose(player -> {
            if (player == null) {
                return CompletableFuture.completedFuture(false);
            }
            return onPlayerThread(player, () -> {
                if (!recipient.matches(inGameSignInIdentity(player))) {
                    return false;
                }
                if (recipient.bedrock()) {
                    return FloodgateProfiles.showSignIn(recipient.uuid(), prompt, messages, selection ->
                            onPlayerThread(player, () -> {
                                if (recipient.matches(inGameSignInIdentity(player))) {
                                    respond.accept(selection, message -> send(player, message));
                                }
                                return null;
                            }));
                }
                return showJavaSignIn(player, prompt, messages, selection -> {
                    if (recipient.matches(inGameSignInIdentity(player))) {
                        respond.accept(selection, message -> send(player, message));
                    }
                });
            });
        });
    }

    protected boolean showJavaSignIn(Player player, InGameSignInPrompt prompt, MainConfig.SignInMessages messages,
                                     Consumer<String> respond) {
        if (supportsSignInInventory()) {
            return signInInventory.show(player, prompt, messages, respond);
        }
        sendSignInChoices(player, prompt, messages);
        return true;
    }

    protected boolean supportsSignInInventory() {
        return true;
    }

    protected <T> CompletableFuture<T> onPlayerThread(Player player, Supplier<T> action) {
        return onServerThread(action);
    }

    protected boolean standaloneOnlineMode() {
        return Bukkit.getOnlineMode() && !Bukkit.spigot().getConfig().getBoolean("settings.bungeecord");
    }

    private boolean hasFloodgate() {
        return plugin.getServer().getPluginManager().isPluginEnabled("floodgate");
    }

    private void sendSignInChoices(Player player, InGameSignInPrompt prompt, MainConfig.SignInMessages messages) {
        TextComponent title = new TextComponent(messages.title);
        title.setColor(ChatColor.AQUA);
        title.setBold(true);
        player.spigot().sendMessage(title);
        player.sendMessage(messages.instructions);
        TextComponent choices = new TextComponent();
        for (int choice : prompt.choices()) {
            String number = Integer.toString(choice);
            choices.addExtra(signInButton(number, prompt.command(number), ChatColor.GREEN));
            choices.addExtra("  ");
        }
        choices.addExtra(signInButton(messages.reject, prompt.command("reject"), ChatColor.RED));
        choices.addExtra("  ");
        choices.addExtra(signInButton(messages.mute, prompt.command("mute"), ChatColor.YELLOW));
        player.spigot().sendMessage(choices);
    }

    private TextComponent signInButton(String label, String command, ChatColor color) {
        TextComponent button = new TextComponent("[" + label + "]");
        button.setColor(color);
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        return button;
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
            UUID uuid = delivery.playerId();
            Player named = delivery.player() == null ? null : Bukkit.getPlayerExact(delivery.player());
            Player player = uuid == null ? named : Bukkit.getPlayer(uuid);
            if (!delivery.matchesPlayer(uuid, named == null ? null : named.getUniqueId(),
                    player == null ? null : player.getName())) {
                return ExecutionResult.playerMismatch();
            }
            if (requireOnline && (player == null || !player.isOnline())) {
                return ExecutionResult.deferred("The player is offline");
            }
            if (!authorized.getAsBoolean()) {
                return ExecutionResult.deferred("The command lease is no longer active");
            }
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), delivery.commandLine())) {
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
