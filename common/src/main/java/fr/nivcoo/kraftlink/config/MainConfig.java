package fr.nivcoo.kraftlink.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainConfig {

    public static final Gson JSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .disableHtmlEscaping()
            .create();

    public String cmsUrl = "";
    public String token = "";
    public String heartbeatPath = "/api/link/heartbeat";
    public int heartbeatSeconds = 30;
    public int pollSeconds = 5;
    public int requestTimeoutSeconds = 15;
    public int executionTimeoutSeconds = 20;
    public List<String> allowedCommandRoots = new ArrayList<>();
    public List<Channel> channels = new ArrayList<>(Arrays.asList(new Channel()));
    public Messages messages = new Messages();

    public static MainConfig load(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            String defaults = new GsonBuilder()
                    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                    .setPrettyPrinting()
                    .create()
                    .toJson(new MainConfig());
            Files.write(path, defaults.getBytes(StandardCharsets.UTF_8));
        }

        MainConfig config;
        try {
            String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
            config = JSON.fromJson(source, MainConfig.class);
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("Invalid KraftLink configuration", error);
        }
        if (config == null) {
            throw new IllegalArgumentException("Invalid KraftLink configuration");
        }
        config.validate();
        return config;
    }

    public boolean enabled() {
        return !blank(cmsUrl) && (!blank(token)
                || channels.stream().anyMatch(channel -> channel.enabled && !blank(channel.token)));
    }

    public String token(Channel channel) {
        return blank(channel.token) ? token : channel.token;
    }

    public URI endpoint(String path) {
        return URI.create(cmsUrl.replaceAll("/+$", "") + path);
    }

    private void validate() {
        if (cmsUrl == null || token == null || channels == null || allowedCommandRoots == null || messages == null) {
            throw new IllegalArgumentException("Missing KraftLink configuration section");
        }
        if (!blank(cmsUrl)) {
            URI uri = URI.create(cmsUrl);
            boolean local = uri.getHost() != null
                    && Arrays.asList("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))) {
                throw new IllegalArgumentException("The CMS URL must use HTTPS or localhost HTTP");
            }
        }
        validateToken(token);
        validatePath(heartbeatPath);
        if (heartbeatSeconds < 10 || heartbeatSeconds > 3600 || pollSeconds < 1 || pollSeconds > 300
                || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 60
                || executionTimeoutSeconds < 1 || executionTimeoutSeconds > 60) {
            throw new IllegalArgumentException("Invalid KraftLink interval or timeout");
        }

        Set<String> channelIds = new HashSet<>();
        for (Channel channel : channels) {
            if (channel == null) {
                throw new IllegalArgumentException("Invalid delivery channel");
            }
            if (!channel.enabled) {
                continue;
            }
            if (channel.id == null || !channel.id.matches("[a-z][a-z0-9_-]{1,31}")) {
                throw new IllegalArgumentException("Invalid delivery channel");
            }
            if (!channelIds.add(channel.id)) {
                throw new IllegalArgumentException("Duplicate delivery channel");
            }
            validateToken(channel.token);
            validatePath(channel.claimPath);
            validatePath(channel.ackPath);
            if (!channel.ackPath.contains("{id}")) {
                throw new IllegalArgumentException("The acknowledgement path needs {id}");
            }
        }
        for (String command : allowedCommandRoots) {
            if (command == null || !command.matches("[a-zA-Z0-9_:.\\-]+")) {
                throw new IllegalArgumentException("Invalid explicitly allowed command root");
            }
        }
        messages.validate();
    }

    private static void validateToken(String token) {
        if (token == null || (!blank(token) && !token.matches("[a-fA-F0-9]{64}"))) {
            throw new IllegalArgumentException("Tokens must contain 64 hexadecimal characters");
        }
    }

    public static boolean blank(String value) {
        return value.codePoints().allMatch(Character::isWhitespace);
    }

    private static void validatePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("..") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException("Invalid API endpoint path");
        }
    }

    public static final class Channel {

        public String id = "cms";
        public boolean enabled = true;
        public String token = "";
        public String claimPath = "/api/link/commands/claim";
        public String ackPath = "/api/link/commands/{id}/ack";
        public boolean requirePlayerOnline = false;
    }

    public static final class Messages {

        public String permissionDenied = "KraftLink: permission denied.";
        public String status = "KraftLink: {status}, manual review: {uncertain}";
        public String uncertainEntry = "{id} {state}";
        public String reloaded = "KraftLink configuration reloaded.";
        public String reloadRejected = "KraftLink configuration rejected: {error}";
        public String resolved = "KraftLink delivery resolved. Also retry it in the CMS if necessary.";
        public String notUncertain = "KraftLink delivery is not awaiting manual review.";
        public String journalFailed = "KraftLink journal update failed.";
        public String usage = "/{label} status|reload|uncertain|resolve <id> delivered|retry";

        private void validate() {
            if (permissionDenied == null || status == null || uncertainEntry == null || reloaded == null
                    || reloadRejected == null || resolved == null || notUncertain == null
                    || journalFailed == null || usage == null) {
                throw new IllegalArgumentException("Missing KraftLink command message");
            }
        }
    }
}
