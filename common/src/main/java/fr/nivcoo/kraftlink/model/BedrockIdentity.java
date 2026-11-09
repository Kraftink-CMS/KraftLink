package fr.nivcoo.kraftlink.model;

public final class BedrockIdentity {

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
}
