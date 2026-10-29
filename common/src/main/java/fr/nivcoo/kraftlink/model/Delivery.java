package fr.nivcoo.kraftlink.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public final class Delivery {

    private final String id;
    private final String command;
    private final String player;
    private final String uuid;
    private final String channel;
    private final String lease;
    private final String leaseExpiresAt;
    private final boolean requiresOnline;

    public Delivery(String id, String command, String player, String uuid, String channel, String lease,
                    String leaseExpiresAt, boolean requiresOnline) {
        this.id = id;
        this.command = command;
        this.player = player;
        this.uuid = uuid;
        this.channel = channel;
        this.lease = lease;
        this.leaseExpiresAt = leaseExpiresAt;
        this.requiresOnline = requiresOnline;
    }

    public String id() {
        return id;
    }

    public String command() {
        return command;
    }

    public String player() {
        return player;
    }

    public String uuid() {
        return uuid;
    }

    public String channel() {
        return channel;
    }

    public String lease() {
        return lease;
    }

    public String leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public boolean requiresOnline() {
        return requiresOnline;
    }

    public boolean leaseValid() {
        if (leaseExpiresAt == null) {
            return false;
        }
        try {
            return Instant.now().isBefore(Instant.parse(leaseExpiresAt.replace(' ', 'T')).minusSeconds(2));
        } catch (DateTimeParseException error) {
            return false;
        }
    }

    public boolean valid() {
        return id != null && id.matches("(?:[a-z][a-z0-9_-]{1,31}:)?[a-fA-F0-9-]{36}")
                && command != null && !blank(command) && command.length() <= 4096
                && command.chars().noneMatch(value -> value < 32 || value == 127)
                && lease != null && lease.length() >= 20 && lease.length() <= 64
                && (uuid == null || blank(uuid) || uuid.matches(
                "[a-fA-F0-9]{32}|[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"));
    }

    public UUID playerId() {
        if (uuid == null || blank(uuid)) {
            return null;
        }
        String value = uuid.replace("-", "");
        return UUID.fromString(value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20));
    }

    private static boolean blank(String value) {
        return value.codePoints().allMatch(Character::isWhitespace);
    }
}
