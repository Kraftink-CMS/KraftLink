package fr.nivcoo.kraftlink.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpLinkTransport implements LinkTransport {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "kraftlink-request");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<HttpURLConnection> active = new AtomicReference<>();
    private volatile boolean closed;

    @Override
    public void heartbeat(MainConfig config, Heartbeat heartbeat) throws IOException, InterruptedException {
        request(config, config.heartbeatPath, config.token, heartbeat);
    }

    @Override
    public List<Delivery> claim(MainConfig config, MainConfig.Channel channel, Heartbeat snapshot)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("protocol", 1);
        body.addProperty("limit", 20);
        JsonArray players = new JsonArray();
        JsonArray names = new JsonArray();
        for (Heartbeat.PlayerIdentity player : snapshot.players()) {
            players.add(player.uuid());
            names.add(player.name());
        }
        body.add("players", players);
        body.add("player_names", names);
        JsonObject response = request(config, channel.claimPath, config.token(channel), body);
        if (!response.has("commands") || !response.get("commands").isJsonArray()) {
            throw new IOException("Invalid command batch");
        }
        JsonArray commands = response.getAsJsonArray("commands");
        if (commands.size() > 20) {
            throw new IOException("Command batch exceeds the protocol limit");
        }
        Delivery[] deliveries;
        try {
            deliveries = MainConfig.JSON.fromJson(commands, Delivery[].class);
        } catch (JsonParseException error) {
            throw new IOException("Invalid command batch", error);
        }
        for (Delivery delivery : deliveries) {
            if (delivery == null || !delivery.valid()) {
                throw new IOException("Invalid delivery payload");
            }
        }
        return Collections.unmodifiableList(Arrays.asList(deliveries));
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

    private synchronized JsonObject request(MainConfig config, String path, String token, Object body)
            throws IOException, InterruptedException {
        if (closed) throw new IOException("KraftLink transport is closed");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.requestTimeoutSeconds);
        Future<JsonObject> pending;
        try {
            pending = executor.submit(() -> send(config, path, token, body, deadline));
        } catch (RejectedExecutionException error) {
            throw new IOException("KraftLink transport is closed", error);
        }
        try {
            return pending.get(config.requestTimeoutSeconds + 1L, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException error) {
            cancel(pending);
            throw new IOException("The KraftLink response could not be read", error);
        } catch (InterruptedException error) {
            cancel(pending);
            throw error;
        }
    }

    private JsonObject send(MainConfig config, String path, String token, Object body, long deadline) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) config.endpoint(path).toURL().openConnection();
        active.set(connection);
        try {
            if (closed) throw new IOException("KraftLink transport is closed");
            connection.setConnectTimeout(Math.min(10_000, remaining(deadline)));
            connection.setReadTimeout(remaining(deadline));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
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
            if (status < 200 || status >= 300) throw new IOException("KraftLink HTTP " + status);
            if (connection.getContentLengthLong() > 1_048_576) {
                throw new IOException("KraftLink response exceeds one megabyte");
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while (true) {
                    connection.setReadTimeout(remaining(deadline));
                    count = input.read(buffer);
                    if (count == -1) break;
                    if (content.size() + count > 1_048_576) {
                        throw new IOException("KraftLink response exceeds one megabyte");
                    }
                    content.write(buffer, 0, count);
                }
            }
            if (content.size() == 0) return new JsonObject();
            JsonObject result = MainConfig.JSON.fromJson(new String(content.toByteArray(), StandardCharsets.UTF_8), JsonObject.class);
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

    private int remaining(long deadline) throws SocketTimeoutException {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis <= 0) throw new SocketTimeoutException("KraftLink request timed out");
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private void cancel(Future<?> pending) {
        pending.cancel(true);
        disconnect();
    }

    private void disconnect() {
        HttpURLConnection connection = active.getAndSet(null);
        if (connection == null) return;
        Thread thread = new Thread(connection::disconnect, "kraftlink-disconnect");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        disconnect();
    }
}
