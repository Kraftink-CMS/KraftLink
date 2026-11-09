package fr.nivcoo.kraftlink.service;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.LinkTransport;
import fr.nivcoo.kraftlink.model.InGameSignInPlayer;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;
import fr.nivcoo.kraftlink.platform.InGameSignInPlatform;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class InGameSignInService implements AutoCloseable {

    private static final int MAX_PENDING = 2000;

    private final InGameSignInPlatform platform;
    private final LinkTransport transport;
    private final PresenceSample<InGameSignInPlayer, UUID> playerSample = new PresenceSample<>(InGameSignInPlayer::uuid);
    private final Map<String, PendingPrompt> pending = new ConcurrentHashMap<>();
    private final ExecutorService responses = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "kraftlink-signin-response");
        thread.setDaemon(true);
        return thread;
    });
    private volatile MainConfig active;

    InGameSignInService(InGameSignInPlatform platform, LinkTransport transport) {
        this.platform = platform;
        this.transport = transport;
    }

    void availability(MainConfig config) {
        active = config;
    }

    boolean available(MainConfig config) {
        return active == config;
    }

    void expirePrompts() {
        long now = System.nanoTime();
        MainConfig config = active;
        pending.entrySet().removeIf(entry -> !entry.getValue().valid(config, now));
    }

    List<InGameSignInPlayer> players() throws InterruptedException, ExecutionException, TimeoutException {
        return playerSample.next(platform.inGameSignInPlayers().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    void accept(MainConfig config, List<InGameSignInPlayer> players, List<InGameSignInPrompt> prompts) {
        Map<String, InGameSignInPlayer> recipients = new HashMap<>();
        for (InGameSignInPlayer player : players) {
            recipients.put(player.uuid().toString().replace("-", ""), player);
        }
        for (InGameSignInPrompt prompt : prompts) {
            if (active != config || pending.size() >= MAX_PENDING) {
                return;
            }
            InGameSignInPlayer recipient = recipients.get(prompt.playerKey());
            if (recipient == null) {
                continue;
            }
            PendingPrompt request = new PendingPrompt(config, prompt, recipient);
            if (pending.putIfAbsent(prompt.id(), request) != null) {
                continue;
            }
            platform.showInGameSignIn(recipient, prompt, config.messages.inGameSignIn,
                    (selection, reply) -> respond(config, recipient, prompt.id(), selection, reply))
                    .whenComplete((shown, error) -> {
                        if (!Boolean.TRUE.equals(shown)) {
                            pending.remove(prompt.id(), request);
                        }
                    });
        }
    }

    void respond(MainConfig config, InGameSignInPlayer player, String id, String selection, Consumer<String> reply) {
        MainConfig.SignInMessages messages = config.messages.inGameSignIn;
        PendingPrompt request = pending.get(id);
        if (request == null || active != config || !request.valid(config, System.nanoTime())
                || !request.recipient.matches(player) || !request.prompt.accepts(selection)) {
            reply.accept(messages.expired);
            return;
        }
        if (!request.submitting.compareAndSet(false, true)) {
            return;
        }
        try {
            responses.execute(() -> submit(request, selection, reply));
        } catch (RejectedExecutionException error) {
            request.submitting.set(false);
            reply.accept(messages.unavailable);
        }
    }

    private void submit(PendingPrompt request, String selection, Consumer<String> reply) {
        MainConfig.SignInMessages messages = request.config.messages.inGameSignIn;
        try {
            if (!request.valid(active, System.nanoTime())) {
                reply.accept(messages.expired);
                return;
            }
            String result = sendResponse(request, selection);
            pending.remove(request.prompt.id(), request);
            switch (result) {
                case "approved":
                    reply.accept(messages.approved);
                    break;
                case "rejected":
                    reply.accept(messages.rejected);
                    break;
                case "muted":
                    reply.accept(messages.muted);
                    break;
                default:
                    reply.accept(messages.expired);
            }
        } catch (IOException error) {
            reply.accept(messages.unavailable);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            request.submitting.set(false);
        }
    }

    private String sendResponse(PendingPrompt request, String selection) throws IOException, InterruptedException {
        try {
            return transport.respondToInGameSignIn(request.config, request.prompt, request.recipient, selection);
        } catch (IOException error) {
            if (!request.valid(active, System.nanoTime())) {
                throw error;
            }
            return transport.respondToInGameSignIn(request.config, request.prompt, request.recipient, selection);
        }
    }

    @Override
    public void close() {
        active = null;
        pending.clear();
        responses.shutdownNow();
    }

    private static final class PendingPrompt {

        private final MainConfig config;
        private final InGameSignInPrompt prompt;
        private final InGameSignInPlayer recipient;
        private final long expiresAt;
        private final AtomicBoolean submitting = new AtomicBoolean();

        private PendingPrompt(MainConfig config, InGameSignInPrompt prompt, InGameSignInPlayer recipient) {
            this.config = config;
            this.prompt = prompt;
            this.recipient = recipient;
            expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(prompt.expiresIn());
        }

        private boolean valid(MainConfig active, long now) {
            return config == active && expiresAt - now > 0;
        }
    }
}
