package fr.nivcoo.kraftlink.api;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface KraftLinkApi {

    static KraftLinkApi get() {
        KraftLinkApi api = KraftLinkApiRegistry.current();
        if (api == null) {
            throw new IllegalStateException("KraftLink is not available");
        }
        return api;
    }

    static Optional<KraftLinkApi> find() {
        return Optional.ofNullable(KraftLinkApiRegistry.current());
    }

    static boolean isAvailable() {
        return KraftLinkApiRegistry.current() != null;
    }

    UUID instanceId();

    KraftLinkPlatform platform();

    OptionalLong serverId();

    CompletableFuture<Map<String, Object>> request(String operation, Map<String, Object> payload);

    KraftLinkRegistration registerPollOperation(LinkPollOperation operation);
}
