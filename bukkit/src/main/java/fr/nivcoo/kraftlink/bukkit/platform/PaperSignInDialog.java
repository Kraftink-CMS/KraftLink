package fr.nivcoo.kraftlink.bukkit.platform;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PaperSignInDialog {

    private PaperSignInDialog() {
    }

    static void show(Player player, InGameSignInPrompt prompt, MainConfig.SignInMessages messages) {
        List<ActionButton> actions = new ArrayList<>();
        for (int choice : prompt.choices()) {
            String number = Integer.toString(choice);
            actions.add(button(number, prompt.command(number), NamedTextColor.GREEN, 78));
        }
        actions.add(button(messages.reject, prompt.command("reject"), NamedTextColor.RED, 110));
        actions.add(button(messages.mute, prompt.command("mute"), NamedTextColor.YELLOW, 110));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(messages.title))
                        .body(Collections.singletonList(DialogBody.plainMessage(Component.text(messages.instructions))))
                        .build())
                .type(DialogType.multiAction(actions, null, 3)));
        player.showDialog(dialog);
    }

    private static ActionButton button(String label, String command, NamedTextColor color, int width) {
        return ActionButton.builder(Component.text(label, color))
                .width(width)
                .action(DialogAction.staticAction(ClickEvent.runCommand(command)))
                .build();
    }
}
