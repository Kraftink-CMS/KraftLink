package fr.nivcoo.kraftlink.api;

import java.util.Optional;

public interface KraftLinkProvider {

    static void register(KraftLinkApi api) {
        KraftLinkApiRegistry.register(api);
    }

    static void unregister(KraftLinkApi api) {
        KraftLinkApiRegistry.unregister(api);
    }

    Optional<KraftLinkApi> api();
}
