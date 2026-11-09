package fr.nivcoo.kraftlink.model;

import java.util.UUID;
import java.util.regex.Pattern;

public final class BedrockIdentity {

    private static final Pattern XUID = Pattern.compile("[1-9][0-9]{0,19}");
    private static final String MAX_XUID = "18446744073709551615";

    private final String xuid;
    private final String username;
    private final boolean linkedToJava;

    public BedrockIdentity(String xuid, String username, boolean linkedToJava) {
        this.xuid = xuid;
        this.username = username;
        this.linkedToJava = linkedToJava;
    }

    public String xuid() {
        return xuid;
    }

    public String username() {
        return username;
    }

    public boolean linkedToJava() {
        return linkedToJava;
    }

    public static UUID xboxUuid(String xuid) {
        if (xuid == null || !XUID.matcher(xuid).matches()
                || (xuid.length() == MAX_XUID.length() && xuid.compareTo(MAX_XUID) > 0)) {
            return null;
        }
        return new UUID(0, Long.parseUnsignedLong(xuid));
    }
}
