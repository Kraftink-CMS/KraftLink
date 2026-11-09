package fr.nivcoo.kraftlink.service;

import fr.nivcoo.kraftlink.api.KraftLinkApi;
import fr.nivcoo.kraftlink.api.KraftLinkRegistration;
import fr.nivcoo.kraftlink.api.KraftLinkRequestException;
import fr.nivcoo.kraftlink.api.LinkPollOperation;
import fr.nivcoo.kraftlink.config.LinkInstance;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.LinkPollRequest;
import fr.nivcoo.kraftlink.http.LinkPollResponse;
import fr.nivcoo.kraftlink.http.LinkTransport;
import fr.nivcoo.kraftlink.model.AuthMeRegistration;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.InGameSignInPlayer;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.model.LinkCapabilities;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.InGameSignInPlatform;
import fr.nivcoo.kraftlink.platform.ServerPlatform;
import fr.nivcoo.kraftlink.storage.CommandJournal;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class DeliveryEngine implements AutoCloseable {

    private static final int MAX_EXTENSION_OPERATIONS = 13;
    private static final long BEDROCK_RETRY_DELAY = TimeUnit.SECONDS.toNanos(5);

    private final ServerPlatform platform;
    private final CommandJournal journal;
    private final LinkTransport transport;
    private final Logger logger;
    private final InGameSignInService inGameSignIn;
    private final ConnectorApi api;
    private final PresenceSample<Heartbeat.PlayerIdentity, String> commandPlayers =
            new PresenceSample<>(Heartbeat.PlayerIdentity::uuid);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(action -> {
        Thread thread = new Thread(action, "kraftlink-http");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "kraftlink-commands");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, RegisteredOperation> extensionOperations = new ConcurrentHashMap<>();
    private final Map<String, Long> operationWarnings = new HashMap<>();
    private final Map<String, Long> bedrockPlayerRetryAt = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean verifyingBedrock = new AtomicBoolean();
    private final AtomicBoolean processingCommands = new AtomicBoolean();
    private final Semaphore authMeRegistrationSlots = new Semaphore(32);
    private final Object heartbeatLock = new Object();
    private volatile MainConfig config;
    private volatile MainConfig authMeRegistrationConfig;
    private volatile String status = "waiting";
    private volatile long connectedServerId;
    private MainConfig bedrockRetryConfig;
    private Long bedrockServerRetryAt;
    private MainConfig scheduledConfig;
    private long nextHeartbeat;
    private long nextPoll;
    private long nextInGameSignIn;
    private long retryAt;
    private boolean legacyPoll;
    private long authMeWarningAt;
    private int failures;

    public DeliveryEngine(MainConfig config, ServerPlatform platform, CommandJournal journal,
                          LinkTransport transport, Logger logger, LinkInstance instance) {
        this.config = config;
        this.platform = platform;
        this.journal = journal;
        this.transport = transport;
        this.logger = logger;
        inGameSignIn = platform instanceof InGameSignInPlatform
                ? new InGameSignInService((InGameSignInPlatform) platform, transport) : null;
        api = new ConnectorApi(this, transport, instance);
    }

    public void start() {
        if (running.get() && started.compareAndSet(false, true)) {
            try {
                executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
            } catch (RejectedExecutionException error) {
                if (running.get()) {
                    throw error;
                }
            }
        }
    }

    public void reconfigure(MainConfig config) {
        synchronized (heartbeatLock) {
            if (!running.get()) {
                return;
            }
            this.config = config;
            authMeRegistrationConfig = null;
            connectedServerId = 0;
            if (inGameSignIn != null) {
                inGameSignIn.availability(null);
            }
        }
    }

    public KraftLinkApi api() {
        return api;
    }

    long connectedServerId() {
        return connectedServerId;
    }

    KraftLinkRegistration registerPollOperation(LinkPollOperation operation) {
        String name = operation.name();
        if (!MainConfig.validOperationName(name)) {
            throw new IllegalArgumentException("Invalid KraftLink poll operation name");
        }
        int interval = operation.intervalSeconds();
        if (interval < 1 || interval > 300) {
            throw new IllegalArgumentException("Invalid KraftLink operation interval");
        }
        RegisteredOperation registered = new RegisteredOperation(operation, interval);
        synchronized (extensionOperations) {
            if (!running.get() || extensionOperations.size() >= MAX_EXTENSION_OPERATIONS
                    || extensionOperations.putIfAbsent(name, registered) != null) {
                throw new IllegalStateException("KraftLink poll operation cannot be registered");
            }
        }
        return () -> extensionOperations.remove(name, registered);
    }

    public MainConfig config() {
        return config;
    }

    public boolean running() {
        return running.get();
    }

    public String status() {
        return status;
    }

    public CommandJournal journal() {
        return journal;
    }

    public void respondToInGameSignIn(InGameSignInPlayer player, String id, String selection, Consumer<String> reply) {
        if (inGameSignIn != null && running.get()) {
            inGameSignIn.respond(config, player, id, selection, reply);
        }
    }

    public boolean authMeRegistrationEnabled(MainConfig current) {
        return running.get() && authMeRegistrationConfig == current && config == current;
    }

    public void registerAuthMe(MainConfig current, Supplier<AuthMeRegistration> verifiedRegistration) {
        if (!authMeRegistrationEnabled(current) || !authMeRegistrationSlots.tryAcquire()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    if (!authMeRegistrationEnabled(current)) {
                        return;
                    }
                    AuthMeRegistration registration = verifiedRegistration.get();
                    if (registration != null) {
                        sendAuthMeRegistration(current, registration);
                    }
                } catch (IOException error) {
                    warnAuthMeRegistration("KraftLink could not deliver an AuthMe registration.");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    authMeRegistrationSlots.release();
                }
            });
        } catch (RejectedExecutionException error) {
            authMeRegistrationSlots.release();
        }
    }

    private void sendAuthMeRegistration(MainConfig current, AuthMeRegistration registration)
            throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2 && authMeRegistrationEnabled(current) && !registration.expired();
             attempt++) {
            try {
                int status = transport.registerAuthMe(current, registration);
                if (status != 201 && status != 409) {
                    warnAuthMeRegistration("KraftLink AuthMe registration rejected by the website (HTTP " + status + ").");
                }
                return;
            } catch (IOException error) {
                if (attempt == 1 || registration.expired()) {
                    throw error;
                }
            }
        }
    }

    private void warnAuthMeRegistration(String message) {
        long now = System.nanoTime();
        if (authMeWarningAt == 0 || authMeWarningAt - now <= 0) {
            authMeWarningAt = now + TimeUnit.MINUTES.toNanos(1);
            logger.warning(message);
        }
    }

    public void verifyBedrock(String code, BedrockIdentity identity, Consumer<String> reply) {
        MainConfig current = config;
        if (!running.get() || MainConfig.blank(current.cmsUrl) || MainConfig.blank(current.token)
                || !verifyingBedrock.compareAndSet(false, true)) {
            reply.accept(current.messages.linkUnavailable);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    if (!running.get()) {
                        return;
                    }
                    if (config != current) {
                        reply.accept(config.messages.linkUnavailable);
                        return;
                    }
                    reply.accept(bedrockResult(current, code, identity));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    verifyingBedrock.set(false);
                }
            });
        } catch (RejectedExecutionException error) {
            verifyingBedrock.set(false);
            reply.accept(current.messages.linkUnavailable);
        }
    }

    private String bedrockResult(MainConfig current, String code, BedrockIdentity identity) throws InterruptedException {
        if (bedrockRetryConfig != current) {
            bedrockRetryConfig = current;
            bedrockServerRetryAt = null;
            bedrockPlayerRetryAt.clear();
        }
        long now = System.nanoTime();
        if (bedrockServerRetryAt != null && now - bedrockServerRetryAt < 0) {
            return current.messages.linkUnavailable;
        }
        bedrockPlayerRetryAt.values().removeIf(retryAt -> now - retryAt >= 0);
        if (bedrockPlayerRetryAt.containsKey(identity.xuid())) {
            return current.messages.linkRetry;
        }
        String failureMessage;
        try {
            if (transport.verifyBedrock(current, code, identity)) {
                return current.messages.linkAccepted;
            }
            failureMessage = current.messages.linkRejected;
        } catch (KraftLinkRequestException error) {
            if (error.status() != 422) {
                return bedrockUnavailable(current);
            }
            failureMessage = current.messages.linkUnavailable;
        } catch (IOException error) {
            return bedrockUnavailable(current);
        }
        bedrockPlayerRetryAt.put(identity.xuid(), System.nanoTime() + BEDROCK_RETRY_DELAY);
        return failureMessage;
    }

    private String bedrockUnavailable(MainConfig current) {
        bedrockServerRetryAt = System.nanoTime() + BEDROCK_RETRY_DELAY;
        return current.messages.linkUnavailable;
    }

    private void tick() {
        MainConfig current = config;
        if (!running.get() || !current.enabled()) {
            return;
        }
        if (scheduledConfig != current) {
            scheduledConfig = current;
            nextHeartbeat = 0;
            nextPoll = 0;
            nextInGameSignIn = 0;
            retryAt = 0;
            legacyPoll = false;
            failures = 0;
        }
        if (inGameSignIn != null) {
            inGameSignIn.expirePrompts();
        }
        long now = System.currentTimeMillis();
        if (now < retryAt) {
            return;
        }
        try {
            exchange(current, now);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | TimeoutException | IllegalArgumentException error) {
            failures = Math.min(failures + 1, 6);
            long retry = Math.min(60, 1L << failures) * 1000;
            synchronized (heartbeatLock) {
                if (config != current) {
                    return;
                }
                retryAt = System.currentTimeMillis() + retry;
                nextHeartbeat = retryAt;
                nextPoll = retryAt;
                nextInGameSignIn = retryAt;
                authMeRegistrationConfig = null;
                connectedServerId = 0;
            }
            status = "disconnected";
            String reason = error instanceof KraftLinkRequestException
                    && ((KraftLinkRequestException) error).status() == 409
                    ? "the server token is bound to another KraftLink installation"
                    : error.getClass().getSimpleName();
            logger.warning("KraftLink communication failed: " + reason + ". Retry in " + retry / 1000 + "s.");
        }
    }

    private void exchange(MainConfig current, long now)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        boolean hasToken = !MainConfig.blank(current.token);
        boolean heartbeatDue = now >= nextHeartbeat && hasToken;
        boolean canBatch = hasToken && !legacyPoll;
        List<MainConfig.Channel> channels = claimableChannels(current, now);
        MainConfig.Channel batchChannel = canBatch ? channels.stream()
                .filter(channel -> usesStandardCommandEndpoint(current, channel)).findFirst().orElse(null) : null;
        boolean batchHeartbeat = heartbeatDue && canBatch && MainConfig.DEFAULT_HEARTBEAT_PATH.equals(current.heartbeatPath);
        LinkPollRequest request = new LinkPollRequest();
        List<InGameSignInPlayer> players = canBatch ? signInPlayers(current, now) : Collections.emptyList();
        Heartbeat snapshot = heartbeatDue || !channels.isEmpty()
                ? platform.snapshot().toCompletableFuture().get(10, TimeUnit.SECONDS) : null;
        Heartbeat commandSnapshot = channels.isEmpty()
                ? null : snapshot.withPlayerSample(commandPlayers.next(snapshot.players()));
        if (batchHeartbeat) {
            request.heartbeat(snapshot);
        }
        if (batchChannel != null) {
            request.commands(commandSnapshot);
        }
        if (!players.isEmpty()) {
            request.inGameSignIn(players);
        }
        Map<String, RegisteredOperation> addons = canBatch ? collectOperations(current, now, request) : Collections.emptyMap();
        if (!running.get() || config != current || !heartbeatDue && channels.isEmpty() && request.isEmpty()) {
            return;
        }

        LinkPollResponse result = null;
        if (!request.isEmpty()) {
            result = transport.poll(current, request);
            if (result == null) {
                legacyPoll = true;
                if (inGameSignIn != null) {
                    inGameSignIn.availability(null);
                }
                notifyUnsupportedOperations(addons);
            }
        }
        if (!running.get() || config != current) {
            return;
        }
        if (result != null) {
            failures = 0;
            status = "connected";
        }
        if (heartbeatDue) {
            receiveHeartbeat(current, now, snapshot, batchHeartbeat, result);
        }
        if (result != null && !players.isEmpty() && inGameSignIn.available(current)) {
            receiveSignIn(current, now, players, result);
        }
        if (result != null) {
            receiveOperations(addons, result);
        }
        if (!channels.isEmpty()) {
            claimCommands(current, now, commandSnapshot, channels, batchChannel, result);
        }
    }

    private List<MainConfig.Channel> claimableChannels(MainConfig current, long now) {
        if (now < nextPoll || processingCommands.get() || current.allowedCommandRoots.isEmpty()) {
            return Collections.emptyList();
        }
        return current.channels.stream().filter(channel -> channel.enabled && !MainConfig.blank(current.token(channel)))
                .collect(Collectors.toList());
    }

    private boolean usesStandardCommandEndpoint(MainConfig current, MainConfig.Channel channel) {
        return current.token.equals(current.token(channel)) && MainConfig.DEFAULT_CLAIM_PATH.equals(channel.claimPath)
                && MainConfig.DEFAULT_ACK_PATH.equals(channel.ackPath);
    }

    private List<InGameSignInPlayer> signInPlayers(MainConfig current, long now) throws InterruptedException {
        if (inGameSignIn == null || !inGameSignIn.available(current) || now < nextInGameSignIn) {
            return Collections.emptyList();
        }
        nextInGameSignIn = now + current.signInPollSeconds * 1000L;
        try {
            return inGameSignIn.players();
        } catch (ExecutionException | TimeoutException error) {
            nextInGameSignIn = now + 15_000L;
            return Collections.emptyList();
        }
    }

    private void receiveHeartbeat(MainConfig current, long now, Heartbeat snapshot, boolean batched, LinkPollResponse result)
            throws IOException, InterruptedException {
        try {
            LinkCapabilities capabilities = batched && result != null ? result.heartbeat() : transport.heartbeat(current, snapshot);
            updateHeartbeat(current, capabilities, now);
            failures = 0;
            status = "connected";
        } catch (IOException error) {
            if (result == null) {
                throw error;
            }
            synchronized (heartbeatLock) {
                if (config == current) {
                    authMeRegistrationConfig = null;
                    connectedServerId = 0;
                    nextHeartbeat = now + current.heartbeatSeconds * 1000L;
                }
            }
            logOperationFailure(LinkPollRequest.HEARTBEAT, error);
        }
    }

    private void receiveSignIn(MainConfig current, long now, List<InGameSignInPlayer> players, LinkPollResponse result) {
        try {
            inGameSignIn.accept(current, players, result.inGameSignIn());
        } catch (IOException error) {
            nextInGameSignIn = now + 15_000L;
            logOperationFailure(LinkPollRequest.IN_GAME_SIGN_IN, error);
        }
    }

    private void claimCommands(MainConfig current, long now, Heartbeat snapshot, List<MainConfig.Channel> channels,
                               MainConfig.Channel batchChannel, LinkPollResponse result)
            throws IOException, InterruptedException {
        nextPoll = now + current.pollSeconds * 1000L;
        Map<MainConfig.Channel, List<Delivery>> claimed = new LinkedHashMap<>();
        for (MainConfig.Channel channel : channels) {
            if (!running.get() || config != current) {
                return;
            }
            if (result != null && channel == batchChannel) {
                try {
                    claimed.put(channel, result.commands());
                } catch (IOException error) {
                    logOperationFailure(LinkPollRequest.COMMANDS, error);
                }
            } else if (result == null || batchChannel == null || !usesStandardCommandEndpoint(current, channel)
                    || batchChannel.requirePlayerOnline != channel.requirePlayerOnline) {
                try {
                    claimed.put(channel, transport.claim(current, channel, snapshot));
                    failures = 0;
                    status = "connected";
                } catch (IOException error) {
                    if (claimed.isEmpty() && result == null && channels.size() == 1) {
                        throw error;
                    }
                    logOperationFailure(LinkPollRequest.COMMANDS, error);
                }
            }
        }
        claimed.values().removeIf(List::isEmpty);
        if (claimed.isEmpty() || !processingCommands.compareAndSet(false, true)) {
            return;
        }
        try {
            commandExecutor.execute(() -> executeCommands(current, claimed));
        } catch (RejectedExecutionException error) {
            processingCommands.set(false);
        }
    }

    private void executeCommands(MainConfig current, Map<MainConfig.Channel, List<Delivery>> claimed) {
        try {
            for (Map.Entry<MainConfig.Channel, List<Delivery>> batch : claimed.entrySet()) {
                for (Delivery delivery : batch.getValue()) {
                    if (!running.get() || config != current) {
                        return;
                    }
                    process(current, batch.getKey(), delivery);
                }
            }
        } catch (IOException error) {
            logger.warning("KraftLink could not finish a claimed command batch.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            processingCommands.set(false);
        }
    }

    private Map<String, RegisteredOperation> collectOperations(MainConfig current, long now, LinkPollRequest request) {
        Map<String, RegisteredOperation> sent = new LinkedHashMap<>();
        extensionOperations.forEach((name, entry) -> {
            if (entry.config == current && now < entry.next) {
                return;
            }
            entry.config = current;
            entry.next = now + current.operationPollSeconds.getOrDefault(name, entry.interval) * 1000L;
            try {
                Map<String, Object> data = entry.operation.request();
                if (data != null) {
                    request.extension(name, data);
                    sent.put(name, entry);
                }
            } catch (RuntimeException | LinkageError | StackOverflowError error) {
                logger.fine("KraftLink could not prepare poll operation " + name + ".");
            }
        });
        return sent;
    }

    private void receiveOperations(Map<String, RegisteredOperation> sent, LinkPollResponse response) {
        sent.forEach((name, entry) -> {
            if (extensionOperations.get(name) != entry) {
                return;
            }
            try {
                entry.operation.receive(response.extension(name));
            } catch (LinkPollResponse.RejectedOperation error) {
                notifyOperationRejection(name, entry, error.status());
            } catch (IOException | RuntimeException | LinkageError | StackOverflowError error) {
                logger.fine("KraftLink could not process poll operation " + name + ".");
            }
        });
    }

    private void notifyUnsupportedOperations(Map<String, RegisteredOperation> sent) {
        sent.forEach((name, entry) -> notifyOperationRejection(name, entry, 404));
    }

    private void notifyOperationRejection(String name, RegisteredOperation entry, int status) {
        if (extensionOperations.get(name) == entry) {
            try {
                entry.operation.rejected(status);
            } catch (RuntimeException | LinkageError | StackOverflowError error) {
                logger.fine("KraftLink could not notify poll operation " + name + ".");
            }
        }
    }

    private void logOperationFailure(String name, IOException error) {
        long now = System.nanoTime();
        Long warningAt = operationWarnings.get(name);
        if (warningAt == null || warningAt - now <= 0) {
            operationWarnings.put(name, now + TimeUnit.MINUTES.toNanos(1));
            String reason = error instanceof LinkPollResponse.RejectedOperation
                    ? "HTTP " + ((LinkPollResponse.RejectedOperation) error).status() : error.getClass().getSimpleName();
            logger.warning("KraftLink poll operation " + name + " failed: " + reason + ".");
        }
    }

    private void updateHeartbeat(MainConfig current, LinkCapabilities capabilities, long now) {
        synchronized (heartbeatLock) {
            if (config != current || !running.get()) {
                return;
            }
            authMeRegistrationConfig = capabilities.authMeRegistration() ? current : null;
            connectedServerId = capabilities.serverId();
            if (inGameSignIn != null) {
                inGameSignIn.availability(!legacyPoll && capabilities.inGameSignIn() ? current : null);
            }
            nextHeartbeat = now + current.heartbeatSeconds * 1000L;
        }
    }

    public synchronized void process(MainConfig current, MainConfig.Channel channel, Delivery delivery)
            throws IOException, InterruptedException {
        if (!running.get() || delivery == null || !delivery.valid()) {
            throw new IOException("Invalid delivery payload");
        }
        String key = delivery.id();
        String hash = CommandJournal.hash(delivery.command());
        CommandJournal.Entry previous = journal.get(key);
        if (previous != null && !previous.commandHash().equals(hash)) {
            throw new IOException("A delivery identifier was reused with a different command");
        }
        if (previous != null && (previous.state() == CommandJournal.State.EXECUTED
                || previous.state() == CommandJournal.State.ACKED)) {
            transport.acknowledge(current, channel, delivery, "executed", null);
            journal.write(key, CommandJournal.State.ACKED, hash, null);
            return;
        }
        if (previous != null && (previous.state() == CommandJournal.State.UNCERTAIN
                || previous.state() == CommandJournal.State.EXECUTING || previous.state() == CommandJournal.State.FAILED)) {
            String state = previous.state() == CommandJournal.State.FAILED ? "failed" : "uncertain";
            transport.acknowledge(current, channel, delivery, state, previous.error());
            return;
        }
        if (!delivery.leaseValid()) {
            return;
        }
        String root = delivery.commandRoot();
        boolean allowed = current.allowedCommandRoots.stream().anyMatch(command -> command.equalsIgnoreCase(root));
        if (!allowed) {
            String error = "Command root is not explicitly authorized by this server";
            journal.write(key, CommandJournal.State.FAILED, hash, error);
            transport.acknowledge(current, channel, delivery, "failed", error);
            return;
        }
        journal.write(key, CommandJournal.State.RECEIVED, hash, null);
        journal.write(key, CommandJournal.State.EXECUTING, hash, null);
        AtomicBoolean authorized = new AtomicBoolean(true);
        ExecutionResult result;
        try {
            result = platform.execute(delivery, channel.requirePlayerOnline || delivery.requiresOnline(),
                            () -> authorized.get() && running.get() && config == current && delivery.leaseValid())
                    .toCompletableFuture()
                    .get(current.executionTimeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException error) {
            authorized.set(false);
            journal.write(key, CommandJournal.State.UNCERTAIN, hash, error.getClass().getSimpleName());
            transport.acknowledge(current, channel, delivery, "uncertain",
                    "Command execution did not finish with a confirmed result");
            return;
        } catch (InterruptedException error) {
            authorized.set(false);
            throw error;
        }
        authorized.set(false);
        switch (result.state()) {
            case EXECUTED:
                journal.write(key, CommandJournal.State.EXECUTED, hash, null);
                transport.acknowledge(current, channel, delivery, "executed", null);
                journal.write(key, CommandJournal.State.ACKED, hash, null);
                break;
            case FAILED:
                journal.write(key, CommandJournal.State.FAILED, hash, result.error());
                transport.acknowledge(current, channel, delivery, "failed", result.error());
                break;
            case DEFERRED:
                journal.write(key, CommandJournal.State.RECEIVED, hash, result.error());
                transport.acknowledge(current, channel, delivery, "deferred", result.error());
                break;
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        authMeRegistrationConfig = null;
        connectedServerId = 0;
        api.close();
        if (inGameSignIn != null) {
            inGameSignIn.close();
        }
        executor.shutdownNow();
        commandExecutor.shutdownNow();
        synchronized (extensionOperations) {
            extensionOperations.clear();
        }
        transport.close();
        journal.close();
    }

    private static final class RegisteredOperation {

        private final LinkPollOperation operation;
        private final int interval;
        private MainConfig config;
        private long next;

        private RegisteredOperation(LinkPollOperation operation, int interval) {
            this.operation = operation;
            this.interval = interval;
        }
    }
}
