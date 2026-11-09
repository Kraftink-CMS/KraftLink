package fr.nivcoo.kraftlink.model;

import java.util.Arrays;
import java.util.Locale;

public final class InGameSignInPrompt {

    private String id;
    private String uuid;
    private int[] choices;
    private int expiresIn;

    public String id() {
        return id;
    }

    public String playerKey() {
        return uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    public int[] choices() {
        return choices.clone();
    }

    public int expiresIn() {
        return expiresIn;
    }

    public boolean valid() {
        return id != null && id.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                && uuid != null && uuid.matches("(?i)(?:[a-f0-9]{32}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})")
                && choices != null && choices.length == 3
                && Arrays.stream(choices).allMatch(choice -> choice >= 10 && choice <= 99)
                && Arrays.stream(choices).distinct().count() == choices.length && expiresIn > 0 && expiresIn <= 300;
    }

    public boolean accepts(String selection) {
        return "reject".equals(selection) || "mute".equals(selection)
                || Arrays.stream(choices).anyMatch(choice -> Integer.toString(choice).equals(selection));
    }

    public String command(String selection) {
        return "/kraftlink signin " + id + " " + selection;
    }
}
