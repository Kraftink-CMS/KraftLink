package fr.nivcoo.kraftlink.velocity.platform;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
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
import fr.nivcoo.kraftlink.velocity.KraftLink;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
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

public final class VelocityPlatform implements ServerPlatform, InGameSignInPlatform {

    private final KraftLink plugin;
    private final ProxyServer proxy;

    public VelocityPlatform(KraftLink plugin) {
        this.plugin = plugin;
        proxy = plugin.proxy();
    }

    @Override
    public CompletionStage<List<InGameSignInPlayer>> inGameSignInPlayers() {
        return schedule(() -> proxy.getAllPlayers().stream()
                .map(this::inGameSignInIdentity).filter(Objects::nonNull).collect(Collectors.toList()));
    }

    public InGameSignInPlayer inGameSignInIdentity(Player player) {
        if (!player.isActive() || player.getPermissionValue("kraftlink.signin") == Tristate.FALSE) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        if (proxy.getPluginManager().isLoaded("floodgate") && FloodgateProfiles.isPlayer(uuid)) {
            BedrockIdentity identity = FloodgateProfiles.find(uuid);
            return identity == null || identity.linkedToJava() ? null
                    : new InGameSignInPlayer(uuid, identity.username(), "bedrock");
        }
        return player.isOnlineMode() ? new InGameSignInPlayer(uuid, player.getUsername(), "java") : null;
    }

    @Override
    public CompletionStage<Boolean> showInGameSignIn(InGameSignInPlayer recipient, InGameSignInPrompt prompt,
                                                  MainConfig.SignInMessages messages,
                                                  BiConsumer<String, Consumer<String>> respond) {
        return schedule(() -> {
            Player player = proxy.getPlayer(recipient.uuid()).orElse(null);
            if (player == null || !recipient.matches(inGameSignInIdentity(player))) {
                return false;
            }
            if (recipient.bedrock()) {
                return FloodgateProfiles.showSignIn(recipient.uuid(), prompt, messages, selection -> schedule(() -> {
                    if (recipient.matches(inGameSignInIdentity(player))) {
                        respond.accept(selection, message -> sendSignInResult(player, message));
                    }
                    return null;
                }));
            }
            sendSignInChoices(player, prompt, messages);
            return true;
        });
    }

    private void sendSignInChoices(Player player, InGameSignInPrompt prompt, MainConfig.SignInMessages messages) {
        player.sendMessage(Component.text(messages.title, NamedTextColor.AQUA, TextDecoration.BOLD));
        player.sendMessage(Component.text(messages.instructions));
        Component choices = Component.empty();
        for (int choice : prompt.choices()) {
            String number = Integer.toString(choice);
            choices = choices.append(signInButton(number, prompt.command(number), NamedTextColor.GREEN))
                    .append(Component.space());
        }
        player.sendMessage(choices.append(signInButton(messages.reject, prompt.command("reject"), NamedTextColor.RED))
                .append(Component.space()).append(signInButton(messages.mute, prompt.command("mute"), NamedTextColor.YELLOW)));
    }

    private Component signInButton(String label, String command, NamedTextColor color) {
        return Component.text("[" + label + "]", color).clickEvent(ClickEvent.runCommand(command));
    }

    private void sendSignInResult(Player player, String message) {
        schedule(() -> {
            if (player.isActive()) {
                player.sendMessage(Component.text(message));
            }
            return null;
        });
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
            if (!delivery.matchesPlayer(uuid, named == null ? null : named.getUniqueId(),
                    player == null ? null : player.getUsername())) {
                return CompletableFuture.completedFuture(ExecutionResult.playerMismatch());
            }
            if (requireOnline && (player == null || !player.isActive())) {
                return CompletableFuture.completedFuture(ExecutionResult.deferred("The player is offline"));
            }
            CommandManager manager = proxy.getCommandManager();
            ConsoleCommandSource console = proxy.getConsoleCommandSource();
            if (!manager.hasCommand(delivery.commandRoot(), console)) {
                return CompletableFuture.completedFuture(new ExecutionResult(ExecutionResult.State.FAILED,
                        "The command is not registered or available on this proxy"));
            }
            if (!authorized.getAsBoolean()) {
                return CompletableFuture.completedFuture(ExecutionResult.deferred("The command lease is no longer active"));
            }
            return manager.executeAsync(console, delivery.commandLine()).thenApply(accepted -> Boolean.TRUE.equals(accepted)
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
