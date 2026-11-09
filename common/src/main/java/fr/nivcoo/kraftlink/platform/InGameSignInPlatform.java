package fr.nivcoo.kraftlink.platform;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.InGameSignInPlayer;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface InGameSignInPlatform {

    CompletionStage<List<InGameSignInPlayer>> inGameSignInPlayers();

    CompletionStage<Boolean> showInGameSignIn(InGameSignInPlayer recipient, InGameSignInPrompt prompt,
                                           MainConfig.SignInMessages messages,
                                           BiConsumer<String, Consumer<String>> respond);
}
