package fr.nivcoo.kraftlink.model;

import java.util.UUID;

public final class AuthMeRegistration {

    private final UUID uuid;
    private final String username;
    private final String password;
    private final long authmeRegisteredAt;
    private final transient long expiresAt;

    public AuthMeRegistration(UUID uuid, String username, String password, long authmeRegisteredAt, long expiresAt) {
        this.uuid = uuid;
        this.username = username;
        this.password = password;
        this.authmeRegisteredAt = authmeRegisteredAt;
        this.expiresAt = expiresAt;
    }

    public boolean expired() {
        return expiresAt - System.nanoTime() <= 0;
    }
}
