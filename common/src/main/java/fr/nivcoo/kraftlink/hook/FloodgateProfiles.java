package fr.nivcoo.kraftlink.hook;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.BedrockIdentity;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.UUID;
import java.util.function.Consumer;

public final class FloodgateProfiles {

    private FloodgateProfiles() {
    }

    public static boolean isPlayer(UUID playerId) {
        FloodgateApi api = FloodgateApi.getInstance();
        return api != null && api.isFloodgatePlayer(playerId);
    }

    public static BedrockIdentity find(UUID playerId) {
        FloodgateApi api = FloodgateApi.getInstance();
        if (api == null) {
            return null;
        }
        FloodgatePlayer player = api.getPlayer(playerId);
        if (player == null || !playerId.equals(player.getCorrectUniqueId())) {
            return null;
        }
        boolean linkedToJava = player.isLinked();
        String xuid = player.getXuid();
        String username = linkedToJava ? player.getCorrectUsername() : player.getJavaUsername();
        if (xuid == null || !xuid.matches("[1-9][0-9]{0,19}") || username == null || username.isEmpty()) {
            return null;
        }

        return new BedrockIdentity(xuid, username, linkedToJava);
    }

    public static boolean showSignIn(UUID playerId, InGameSignInPrompt prompt, MainConfig.SignInMessages messages,
                                     Consumer<String> respond) {
        FloodgateApi api = FloodgateApi.getInstance();
        if (api == null) {
            return false;
        }
        int[] choices = prompt.choices();
        SimpleForm.Builder form = SimpleForm.builder().title(messages.title).content(messages.instructions);
        for (int choice : choices) {
            form.button(Integer.toString(choice));
        }
        form.button(messages.reject).button(messages.mute).validResultHandler(result -> {
            int selected = result.clickedButtonId();
            if (selected >= 0 && selected < choices.length) {
                respond.accept(Integer.toString(choices[selected]));
            } else if (selected == choices.length) {
                respond.accept("reject");
            } else if (selected == choices.length + 1) {
                respond.accept("mute");
            }
        });

        return api.sendForm(playerId, form.build());
    }
}
