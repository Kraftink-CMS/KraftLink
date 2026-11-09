package fr.nivcoo.kraftlink.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.InGameSignInPlayer;
import fr.nivcoo.kraftlink.model.Heartbeat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class LinkPollRequest {

    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final int MAX_EXTENSION_BYTES = 65_536;

    public static final String HEARTBEAT = "heartbeat";
    public static final String COMMANDS = "commands";
    public static final String IN_GAME_SIGN_IN = "in_game_sign_in";

    private final JsonObject operations = new JsonObject();

    public void heartbeat(Heartbeat heartbeat) {
        operations.add(HEARTBEAT, MainConfig.JSON.toJsonTree(heartbeatPayload(heartbeat)));
    }

    public void commands(Heartbeat heartbeat) {
        operations.add(COMMANDS, commandPayload(heartbeat));
    }

    public void inGameSignIn(List<InGameSignInPlayer> players) {
        JsonObject payload = new JsonObject();
        payload.add("players", MainConfig.JSON.toJsonTree(players));
        operations.add(IN_GAME_SIGN_IN, payload);
    }

    public void extension(String name, Map<String, Object> data) {
        JsonElement payload = MainConfig.JSON.toJsonTree(data);
        if (jsonBytes(payload) > MAX_EXTENSION_BYTES) {
            throw new IllegalArgumentException("KraftLink poll operation exceeds the payload limit");
        }
        operations.add(name, payload);
        if (jsonBytes(body()) > MAX_REQUEST_BYTES) {
            operations.remove(name);
            throw new IllegalArgumentException("KraftLink poll request exceeds the payload limit");
        }
    }

    public boolean isEmpty() {
        return operations.size() == 0;
    }

    JsonObject body() {
        JsonObject body = new JsonObject();
        body.addProperty("protocol", 1);
        body.add("operations", operations);
        return body;
    }

    private int jsonBytes(JsonElement value) {
        return MainConfig.JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    static Heartbeat heartbeatPayload(Heartbeat heartbeat) {
        List<Heartbeat.PlayerIdentity> players = heartbeat.players();
        return players.size() <= Heartbeat.MAX_PLAYER_SAMPLE
                ? heartbeat : heartbeat.withPlayerSample(players.subList(0, Heartbeat.MAX_PLAYER_SAMPLE));
    }

    static JsonObject commandPayload(Heartbeat heartbeat) {
        JsonObject body = new JsonObject();
        body.addProperty("protocol", 1);
        body.addProperty("limit", 20);
        JsonArray players = new JsonArray();
        JsonArray names = new JsonArray();
        for (Heartbeat.PlayerIdentity player : heartbeat.players()) {
            players.add(player.uuid());
            names.add(player.name());
        }
        body.add("players", players);
        body.add("player_names", names);
        return body;
    }
}
