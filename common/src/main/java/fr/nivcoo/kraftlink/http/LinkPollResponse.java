package fr.nivcoo.kraftlink.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;
import fr.nivcoo.kraftlink.model.LinkCapabilities;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class LinkPollResponse {

    private static final TypeToken<Map<String, Object>> OBJECT_MAP = new TypeToken<Map<String, Object>>() {};
    private final JsonObject operations;

    LinkPollResponse(JsonObject response) throws IOException {
        JsonElement protocol = response.get("protocol");
        JsonElement results = response.get("operations");
        if (protocol == null || !protocol.isJsonPrimitive() || !protocol.getAsJsonPrimitive().isNumber()
                || !"1".equals(protocol.getAsString()) || results == null || !results.isJsonObject()) {
            throw new IOException("Invalid KraftLink poll response");
        }
        operations = results.getAsJsonObject();
    }

    public LinkCapabilities heartbeat() throws IOException {
        return capabilities(data(LinkPollRequest.HEARTBEAT));
    }

    public List<Delivery> commands() throws IOException {
        return deliveries(data(LinkPollRequest.COMMANDS));
    }

    public List<InGameSignInPrompt> inGameSignIn() throws IOException {
        return prompts(data(LinkPollRequest.IN_GAME_SIGN_IN));
    }

    public Map<String, Object> extension(String name) throws IOException {
        return objectMap(data(name));
    }

    static Map<String, Object> objectMap(JsonObject data) {
        return MainConfig.JSON.fromJson(data, OBJECT_MAP);
    }

    private JsonObject data(String name) throws IOException {
        JsonElement result = operations.get(name);
        if (result == null || !result.isJsonObject()) {
            throw new IOException("Missing KraftLink poll result");
        }
        JsonObject operation = result.getAsJsonObject();
        JsonElement success = operation.get("success");
        JsonElement data = operation.get("data");
        if (enabled(success) && data != null && data.isJsonObject()) {
            return data.getAsJsonObject();
        }
        JsonElement status = operation.get("status");
        if (success != null && success.isJsonPrimitive() && success.getAsJsonPrimitive().isBoolean()
                && !success.getAsBoolean() && status != null && status.isJsonPrimitive()
                && status.getAsJsonPrimitive().isNumber() && status.getAsString().matches("[45][0-9]{2}")) {
            throw new RejectedOperation(status.getAsInt());
        }
        throw new IOException("Invalid KraftLink poll result");
    }

    static LinkCapabilities capabilities(JsonObject data) {
        JsonElement id = data.get("server_id");
        long serverId = 0;
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()) {
            try {
                serverId = Long.parseLong(id.getAsString());
            } catch (NumberFormatException ignored) {
                serverId = 0;
            }
        }
        return new LinkCapabilities(serverId, enabled(data.get("in_game_sign_in")), enabled(data.get("authme_registration")));
    }

    private static boolean enabled(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static List<InGameSignInPrompt> prompts(JsonObject data) throws IOException {
        JsonElement requests = data.get("requests");
        if (requests == null || !requests.isJsonArray() || requests.getAsJsonArray().size() > 100) {
            throw new IOException("Invalid in-game sign-in request batch");
        }
        try {
            InGameSignInPrompt[] prompts = MainConfig.JSON.fromJson(requests, InGameSignInPrompt[].class);
            for (InGameSignInPrompt prompt : prompts) {
                if (prompt == null || !prompt.valid()) {
                    throw new IOException("Invalid in-game sign-in request");
                }
            }
            return Arrays.asList(prompts);
        } catch (JsonParseException error) {
            throw new IOException("Invalid in-game sign-in request batch", error);
        }
    }

    static List<Delivery> deliveries(JsonObject data) throws IOException {
        JsonElement commands = data.get("commands");
        if (commands == null || !commands.isJsonArray() || commands.getAsJsonArray().size() > 20) {
            throw new IOException("Invalid command batch");
        }
        try {
            Delivery[] deliveries = MainConfig.JSON.fromJson(commands, Delivery[].class);
            for (Delivery delivery : deliveries) {
                if (delivery == null || !delivery.valid()) {
                    throw new IOException("Invalid delivery payload");
                }
            }
            return Collections.unmodifiableList(Arrays.asList(deliveries));
        } catch (JsonParseException error) {
            throw new IOException("Invalid command batch", error);
        }
    }

    public static final class RejectedOperation extends IOException {

        private final int status;

        RejectedOperation(int status) {
            super("KraftLink poll operation rejected (HTTP " + status + ")");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
