package fr.nivcoo.kraftlink.http;

import com.google.gson.JsonObject;
import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.AuthMeRegistration;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.InGameSignInPlayer;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;
import fr.nivcoo.kraftlink.model.Heartbeat;
import fr.nivcoo.kraftlink.model.LinkCapabilities;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface LinkTransport extends AutoCloseable {

    LinkPollResponse poll(MainConfig config, LinkPollRequest request) throws IOException, InterruptedException;

    Map<String, Object> requestOperation(MainConfig config, JsonObject request) throws IOException, InterruptedException;

    LinkCapabilities heartbeat(MainConfig config, Heartbeat heartbeat) throws IOException, InterruptedException;

    String respondToInGameSignIn(MainConfig config, InGameSignInPrompt prompt, InGameSignInPlayer player, String selection)
            throws IOException, InterruptedException;

    int registerAuthMe(MainConfig config, AuthMeRegistration registration)
            throws IOException, InterruptedException;

    boolean verifyBedrock(MainConfig config, String code, BedrockIdentity identity)
            throws IOException, InterruptedException;

    List<Delivery> claim(MainConfig config, MainConfig.Channel channel, Heartbeat snapshot)
            throws IOException, InterruptedException;

    void acknowledge(MainConfig config, MainConfig.Channel channel, Delivery delivery, String state, String error)
            throws IOException, InterruptedException;

    @Override
    default void close() {
    }
}
