package fr.nivcoo.kraftlink.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import fr.nivcoo.kraftlink.api.KraftLinkRequestException;
import fr.nivcoo.kraftlink.config.LinkInstance;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.AuthMeRegistration;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.InGameSignInPlayer;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.model.LinkCapabilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpLinkTransport implements LinkTransport {

    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_ERROR_BYTES = 65_536;

    private final LinkInstance instance;
    private final RequestLane standard = new RequestLane("kraftlink-request");
    private final RequestLane extensions = new RequestLane("kraftlink-extension-request");
    private volatile boolean closed;

    public HttpLinkTransport(LinkInstance instance) {
        this.instance = Objects.requireNonNull(instance, "instance");
    }

    @Override
    public LinkPollResponse poll(MainConfig config, LinkPollRequest poll) throws IOException, InterruptedException {
        try {
            return new LinkPollResponse(request(config, "/api/link/poll", config.token, poll.body()));
        } catch (KraftLinkRequestException error) {
            if (error.status() == 404 || error.status() == 405) {
                return null;
            }
            throw error;
        }
    }

    @Override
    public Map<String, Object> requestOperation(MainConfig config, JsonObject body)
            throws IOException, InterruptedException {
        JsonObject data = extensions.request(config, "/api/link/request", config.token, body, true);
        return LinkPollResponse.objectMap(data);
    }

    @Override
    public LinkCapabilities heartbeat(MainConfig config, Heartbeat heartbeat) throws IOException, InterruptedException {
        return LinkPollResponse.capabilities(request(config, config.heartbeatPath, config.token,
                LinkPollRequest.heartbeatPayload(heartbeat)));
    }

    @Override
    public String respondToInGameSignIn(MainConfig config, InGameSignInPrompt prompt, InGameSignInPlayer player, String selection)
            throws IOException, InterruptedException {
        JsonObject body = MainConfig.JSON.toJsonTree(player).getAsJsonObject();
        body.addProperty("id", prompt.id());
        if ("reject".equals(selection) || "mute".equals(selection)) {
            body.addProperty("action", selection);
        } else {
            body.addProperty("action", "choose");
            body.addProperty("choice", Integer.parseInt(selection));
        }
        JsonElement state;
        try {
            state = request(config, "/api/link/in-game-sign-in/respond", config.token, body).get("state");
        } catch (KraftLinkRequestException error) {
            if (error.status() == 404 || error.status() == 409) {
                return "expired";
            }
            throw error;
        }
        if (state != null && state.isJsonPrimitive() && state.getAsJsonPrimitive().isString()) {
            String value = state.getAsString();
            if ("approved".equals(value) || "rejected".equals(value) || "muted".equals(value)) {
                return value;
            }
        }
        throw new IOException("Invalid in-game sign-in response");
    }

    @Override
    public int registerAuthMe(MainConfig config, AuthMeRegistration registration)
            throws IOException, InterruptedException {
        try {
            JsonElement state = request(config, "/api/link/authme/registration", config.token, registration).get("state");
            if (state != null && state.isJsonPrimitive() && state.getAsJsonPrimitive().isString()) {
                if ("created".equals(state.getAsString())) {
                    return 201;
                }
            }
            throw new IOException("Invalid AuthMe registration response");
        } catch (KraftLinkRequestException error) {
            if (error.status() >= 400 && error.status() < 500) {
                return error.status();
            }
            throw error;
        }
    }

    @Override
    public boolean verifyBedrock(MainConfig config, String code, BedrockIdentity identity)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("xuid", identity.xuid());
        body.addProperty("username", identity.username());
        JsonObject response;
        try {
            response = request(config, "/api/link/bedrock/verify", config.token, body);
        } catch (KraftLinkRequestException error) {
            if (error.status() == 404) {
                return false;
            }
            throw error;
        }
        JsonElement verified = response.get("verified");
        if (verified == null || !verified.isJsonPrimitive() || !verified.getAsJsonPrimitive().isBoolean()
                || !verified.getAsBoolean()) {
            throw new IOException("Invalid Bedrock verification response");
        }
        return true;
    }

    @Override
    public List<Delivery> claim(MainConfig config, MainConfig.Channel channel, Heartbeat snapshot)
            throws IOException, InterruptedException {
        return LinkPollResponse.deliveries(request(config, channel.claimPath, config.token(channel),
                LinkPollRequest.commandPayload(snapshot)));
    }

    @Override
    public void acknowledge(MainConfig config, MainConfig.Channel channel, Delivery delivery, String state, String error)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("lease", delivery.lease());
        body.addProperty("state", state);
        if (error != null) {
            body.addProperty("error", error.substring(0, Math.min(error.length(), 1000)));
        }
        String path = channel.ackPath.replace("{id}", URLEncoder.encode(delivery.id(), StandardCharsets.UTF_8.name()));
        request(config, path, config.token(channel), body);
    }

    private JsonObject request(MainConfig config, String path, String token, Object body)
            throws IOException, InterruptedException {
        return standard.request(config, path, token, body, false);
    }

    private JsonObject send(MainConfig config, String path, String token, Object body, long deadline,
                            AtomicReference<HttpURLConnection> active, boolean extensionRequest) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) config.endpoint(path).toURL().openConnection();
        active.set(connection);
        try {
            if (closed) throw new IOException("KraftLink transport is closed");
            connection.setConnectTimeout(Math.min(10_000, remaining(deadline)));
            connection.setReadTimeout(remaining(deadline));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-KraftLink-Instance", instance.id().toString());
            connection.setRequestProperty("X-KraftLink-Platform", instance.platform().name().toLowerCase(Locale.ROOT));
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "KraftLink");
            byte[] payload = MainConfig.JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            connection.setReadTimeout(remaining(deadline));
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new KraftLinkRequestException(status, extensionRequest ? readValidationErrors(connection, deadline)
                        : Collections.emptyMap());
            }
            String content = readResponse(connection, connection.getInputStream(), deadline, MAX_RESPONSE_BYTES);
            if (content.isEmpty() && !extensionRequest) return new JsonObject();
            JsonObject result = MainConfig.JSON.fromJson(content, JsonObject.class);
            if (result == null || !result.has("success") || !result.get("success").isJsonPrimitive()
                    || !result.getAsJsonPrimitive("success").isBoolean() || !result.get("success").getAsBoolean()
                    || !result.has("data") || !result.get("data").isJsonObject()) {
                throw new IOException("Invalid KraftLink response");
            }
            return result.getAsJsonObject("data");
        } catch (JsonParseException error) {
            throw new IOException("Invalid KraftLink response", error);
        } finally {
            if (active.compareAndSet(connection, null)) connection.disconnect();
        }
    }

    private String readResponse(HttpURLConnection connection, InputStream stream, long deadline, int limit)
            throws IOException {
        try (InputStream input = stream) {
            if (connection.getContentLengthLong() > limit) {
                throw new IOException("KraftLink response exceeds the size limit");
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while (true) {
                connection.setReadTimeout(remaining(deadline));
                count = input.read(buffer);
                if (count == -1) {
                    return new String(content.toByteArray(), StandardCharsets.UTF_8);
                }
                if (content.size() + count > limit) {
                    throw new IOException("KraftLink response exceeds the size limit");
                }
                content.write(buffer, 0, count);
            }
        }
    }

    private Map<String, List<String>> readValidationErrors(HttpURLConnection connection, long deadline) {
        InputStream input = connection.getErrorStream();
        if (input == null) {
            return Collections.emptyMap();
        }
        try {
            JsonObject result = MainConfig.JSON.fromJson(readResponse(connection, input, deadline, MAX_ERROR_BYTES),
                    JsonObject.class);
            JsonElement data = result == null ? null : result.get("data");
            JsonElement errors = data == null || !data.isJsonObject() ? null : data.getAsJsonObject().get("errors");
            Map<String, List<String>> resultErrors = new LinkedHashMap<>();
            if (errors != null && errors.isJsonObject()) {
                errors.getAsJsonObject().entrySet().forEach(entry -> {
                    if (!entry.getValue().isJsonArray()) {
                        return;
                    }
                    List<String> messages = new ArrayList<>();
                    for (JsonElement message : entry.getValue().getAsJsonArray()) {
                        if (message.isJsonPrimitive() && message.getAsJsonPrimitive().isString()) {
                            messages.add(message.getAsString());
                        }
                    }
                    if (!messages.isEmpty()) {
                        resultErrors.put(entry.getKey(), messages);
                    }
                });
            }
            return resultErrors;
        } catch (IOException | JsonParseException error) {
            return Collections.emptyMap();
        }
    }

    private int remaining(long deadline) throws SocketTimeoutException {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis <= 0) throw new SocketTimeoutException("KraftLink request timed out");
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    @Override
    public void close() {
        closed = true;
        standard.close();
        extensions.close();
    }

    private final class RequestLane {

        private final ExecutorService executor;
        private final AtomicReference<HttpURLConnection> active = new AtomicReference<>();

        private RequestLane(String threadName) {
            executor = Executors.newSingleThreadExecutor(action -> {
                Thread thread = new Thread(action, threadName);
                thread.setDaemon(true);
                return thread;
            });
        }

        private synchronized JsonObject request(MainConfig config, String path, String token, Object body,
                                                boolean extensionRequest) throws IOException, InterruptedException {
            if (closed) {
                throw new IOException("KraftLink transport is closed");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.requestTimeoutSeconds);
            Future<JsonObject> pending;
            try {
                pending = executor.submit(() -> send(config, path, token, body, deadline, active, extensionRequest));
            } catch (RejectedExecutionException error) {
                throw new IOException("KraftLink transport is closed", error);
            }
            try {
                return pending.get(config.requestTimeoutSeconds + 1L, TimeUnit.SECONDS);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof KraftLinkRequestException) {
                    throw (KraftLinkRequestException) error.getCause();
                }
                throw new IOException("The KraftLink response could not be read", error.getCause());
            } catch (TimeoutException | CancellationException error) {
                cancel(pending);
                throw new IOException("The KraftLink response could not be read", error);
            } catch (InterruptedException error) {
                cancel(pending);
                throw error;
            }
        }

        private void cancel(Future<?> pending) {
            pending.cancel(true);
            disconnect();
        }

        private void disconnect() {
            HttpURLConnection connection = active.getAndSet(null);
            if (connection != null) {
                Thread thread = new Thread(connection::disconnect, "kraftlink-disconnect");
                thread.setDaemon(true);
                thread.start();
            }
        }

        private void close() {
            for (Runnable task : executor.shutdownNow()) {
                ((Future<?>) task).cancel(true);
            }
            disconnect();
        }
    }
}
