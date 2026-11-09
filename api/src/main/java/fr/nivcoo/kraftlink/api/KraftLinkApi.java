package fr.nivcoo.kraftlink.api;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface KraftLinkApi {

    UUID instanceId();

    KraftLinkPlatform platform();

    OptionalLong serverId();

    CompletableFuture<Map<String, Object>> request(String operation, Map<String, Object> payload);

    KraftLinkRegistration registerPollOperation(LinkPollOperation operation);
}
