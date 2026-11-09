package fr.nivcoo.kraftlink.model;

public final class LinkCapabilities {

    private final long serverId;
    private final boolean inGameSignIn;
    private final boolean authMeRegistration;

    public LinkCapabilities(long serverId, boolean inGameSignIn, boolean authMeRegistration) {
        this.serverId = serverId;
        this.inGameSignIn = inGameSignIn;
        this.authMeRegistration = authMeRegistration;
    }

    public long serverId() {
        return serverId;
    }

    public boolean inGameSignIn() {
        return inGameSignIn;
    }

    public boolean authMeRegistration() {
        return authMeRegistration;
    }
}
