package fr.nivcoo.kraftlink.model;

import java.util.UUID;

public final class InGameSignInPlayer {

    private final UUID uuid;
    private final String username;
    private final String platform;

    public InGameSignInPlayer(UUID uuid, String username, String platform) {
        this.uuid = uuid;
        this.username = username;
        this.platform = platform;
    }

    public UUID uuid() {
        return uuid;
    }

    public boolean bedrock() {
        return "bedrock".equals(platform);
    }

    public boolean matches(InGameSignInPlayer player) {
        return player != null && uuid.equals(player.uuid) && username.equalsIgnoreCase(player.username)
                && platform.equals(player.platform);
    }
}
