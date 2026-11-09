package fr.nivcoo.kraftlink.config;

import fr.nivcoo.kraftlink.api.KraftLinkPlatform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

public final class LinkInstance {

    private final UUID id;
    private final KraftLinkPlatform platform;

    private LinkInstance(UUID id, KraftLinkPlatform platform) {
        this.id = id;
        this.platform = platform;
    }

    public static LinkInstance load(Path dataDirectory, KraftLinkPlatform platform) throws IOException {
        Objects.requireNonNull(platform, "platform");
        Files.createDirectories(dataDirectory);
        Path path = dataDirectory.resolve("instance-id");
        if (!Files.exists(path)) {
            UUID generated = UUID.randomUUID();
            try {
                Files.write(path, (generated + "\n").getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE_NEW);
                return new LinkInstance(generated, platform);
            } catch (FileAlreadyExistsException ignored) {
                return new LinkInstance(readId(path), platform);
            }
        }
        return new LinkInstance(readId(path), platform);
    }

    private static UUID readId(Path path) throws IOException {
        if (Files.size(path) > 64) {
            throw new IOException("Invalid KraftLink instance identifier");
        }
        String value = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim();
        try {
            UUID id = UUID.fromString(value);
            if (id.version() == 4 && id.variant() == 2 && id.toString().equals(value)) {
                return id;
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid KraftLink instance identifier", error);
        }
        throw new IOException("Invalid KraftLink instance identifier");
    }

    public UUID id() {
        return id;
    }

    public KraftLinkPlatform platform() {
        return platform;
    }
}
