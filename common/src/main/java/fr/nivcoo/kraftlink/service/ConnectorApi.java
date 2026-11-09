package fr.nivcoo.kraftlink.service;

import com.google.gson.JsonObject;
import fr.nivcoo.kraftlink.api.KraftLinkApi;
import fr.nivcoo.kraftlink.api.KraftLinkPlatform;
import fr.nivcoo.kraftlink.api.KraftLinkRegistration;
import fr.nivcoo.kraftlink.api.KraftLinkRequestException;
import fr.nivcoo.kraftlink.api.LinkPollOperation;
import fr.nivcoo.kraftlink.config.LinkInstance;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.http.LinkTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ConnectorApi implements KraftLinkApi, AutoCloseable {

    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final int REQUEST_QUEUE_SIZE = 32;

    private final DeliveryEngine engine;
    private final LinkTransport transport;
    private final LinkInstance instance;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(REQUEST_QUEUE_SIZE), task -> {
                Thread thread = new Thread(task, "kraftlink-plugin-api");
                thread.setDaemon(true);
                return thread;
            });

    ConnectorApi(DeliveryEngine engine, LinkTransport transport, LinkInstance instance) {
        this.engine = engine;
        this.transport = transport;
        this.instance = instance;
    }

    @Override
    public UUID instanceId() {
        return instance.id();
    }

    @Override
    public KraftLinkPlatform platform() {
        return instance.platform();
    }

    @Override
    public OptionalLong serverId() {
        long serverId = engine.connectedServerId();
        return serverId > 0 ? OptionalLong.of(serverId) : OptionalLong.empty();
    }

    @Override
    public CompletableFuture<Map<String, Object>> request(String operation, Map<String, Object> payload) {
        MainConfig config = engine.config();
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        if (!engine.running() || MainConfig.blank(config.cmsUrl) || MainConfig.blank(config.token)) {
            result.completeExceptionally(new KraftLinkRequestException(503));
            return result;
        }
        if (!MainConfig.validOperationName(operation) || payload == null) {
            result.completeExceptionally(new KraftLinkRequestException(400));
            return result;
        }
        JsonObject body = new JsonObject();
        try {
            body.addProperty("protocol", 1);
            body.addProperty("operation", operation);
            body.add("payload", MainConfig.JSON.toJsonTree(payload));
            if (MainConfig.JSON.toJson(body).getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
                result.completeExceptionally(new KraftLinkRequestException(413));
                return result;
            }
        } catch (RuntimeException | StackOverflowError error) {
            result.completeExceptionally(new KraftLinkRequestException(400));
            return result;
        }
        try {
            requests.execute(new PendingRequest(config, body, result));
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(new KraftLinkRequestException(requests.isShutdown() ? 503 : 429));
        }
        return result;
    }

    @Override
    public KraftLinkRegistration registerPollOperation(LinkPollOperation operation) {
        return engine.registerPollOperation(operation);
    }

    @Override
    public void close() {
        for (Runnable task : requests.shutdownNow()) {
            ((PendingRequest) task).unavailable();
        }
    }

    private final class PendingRequest implements Runnable {

        private final MainConfig config;
        private final JsonObject body;
        private final CompletableFuture<Map<String, Object>> result;

        private PendingRequest(MainConfig config, JsonObject body, CompletableFuture<Map<String, Object>> result) {
            this.config = config;
            this.body = body;
            this.result = result;
        }

        @Override
        public void run() {
            if (result.isDone()) {
                return;
            }
            if (!engine.running() || engine.config() != config) {
                unavailable();
                return;
            }
            try {
                result.complete(transport.requestOperation(config, body));
            } catch (KraftLinkRequestException error) {
                result.completeExceptionally(error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                unavailable();
            } catch (IOException | RuntimeException error) {
                result.completeExceptionally(new KraftLinkRequestException(0));
            }
        }

        private void unavailable() {
            result.completeExceptionally(new KraftLinkRequestException(503));
        }
    }
}
