package fr.nivcoo.kraftlink.api;

import java.util.Objects;

final class KraftLinkApiRegistry {

    private static volatile KraftLinkApi instance;

    private KraftLinkApiRegistry() {
    }

    static KraftLinkApi current() {
        return instance;
    }

    static synchronized void register(KraftLinkApi api) {
        Objects.requireNonNull(api, "api");
        if (instance != null && instance != api) {
            throw new IllegalStateException("Another KraftLink API instance is already installed");
        }
        instance = api;
    }

    static synchronized void unregister(KraftLinkApi api) {
        if (instance == api) {
            instance = null;
        }
    }
}
