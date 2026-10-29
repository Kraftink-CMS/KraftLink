package fr.nivcoo.kraftlink.service;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.LinkTransport;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.platform.ExecutionResult;
import fr.nivcoo.kraftlink.platform.ServerPlatform;
import fr.nivcoo.kraftlink.storage.CommandJournal;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class DeliveryEngine implements AutoCloseable {

    private final ServerPlatform platform;
    private final CommandJournal journal;
    private final LinkTransport transport;
    private final Logger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(action -> {
        Thread thread = new Thread(action, "kraftlink-http");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile MainConfig config;
    private volatile String status = "waiting";
    private volatile long nextHeartbeat;
    private volatile long nextPoll;
    private int failures;

    public DeliveryEngine(MainConfig config, ServerPlatform platform, CommandJournal journal,
                          LinkTransport transport, Logger logger) {
        this.config = config;
        this.platform = platform;
        this.journal = journal;
        this.transport = transport;
        this.logger = logger;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public void reconfigure(MainConfig config) {
        if (!running.get()) {
            return;
        }
        this.config = config;
        nextHeartbeat = 0;
        nextPoll = 0;
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

    private void tick() {
        MainConfig current = config;
        if (!running.get() || !current.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextPoll && now < nextHeartbeat) {
            return;
        }
        try {
            Heartbeat snapshot = platform.snapshot().toCompletableFuture().get(10, TimeUnit.SECONDS);
            if (now >= nextHeartbeat && !MainConfig.blank(current.token)) {
                transport.heartbeat(current, snapshot);
                nextHeartbeat = now + current.heartbeatSeconds * 1000L;
            }
            if (now >= nextPoll) {
                for (MainConfig.Channel channel : current.channels) {
                    if (current.allowedCommandRoots.isEmpty() || !channel.enabled || MainConfig.blank(current.token(channel))) {
                        continue;
                    }
                    for (Delivery delivery : transport.claim(current, channel, snapshot)) {
                        process(current, channel, delivery);
                    }
                }
                nextPoll = now + current.pollSeconds * 1000L;
            }
            failures = 0;
            status = "connected";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | TimeoutException | IllegalArgumentException error) {
            failures = Math.min(failures + 1, 6);
            long retry = Math.min(60, 1L << failures) * 1000;
            nextPoll = System.currentTimeMillis() + retry;
            nextHeartbeat = nextPoll;
            status = "disconnected";
            logger.warning("KraftLink communication failed: " + error.getClass().getSimpleName()
                    + ". Retry in " + retry / 1000 + "s.");
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
        String root = delivery.command().replaceFirst("^/", "").split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
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
                            () -> authorized.get() && running.get() && delivery.leaseValid())
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
        executor.shutdownNow();
        transport.close();
        journal.close();
    }
}
