package fr.nivcoo.kraftlink.bukkit.platform;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.InGameSignInPrompt;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class SignInInventory implements Listener {

    private final Map<UUID, Menu> open = new ConcurrentHashMap<>();
    private final BiConsumer<Player, Runnable> schedule;

    SignInInventory(BiConsumer<Player, Runnable> schedule) {
        this.schedule = schedule;
    }

    boolean show(Player player, InGameSignInPrompt prompt, MainConfig.SignInMessages messages, Consumer<String> respond) {
        int[] choices = prompt.choices();
        String title = choices[0] + " / " + choices[1] + " / " + choices[2] + " · " + messages.title;
        Menu menu = new Menu(player, title.substring(0, Math.min(title.length(), 32)), respond);
        for (int index = 0; index < choices.length; index++) {
            String number = Integer.toString(choices[index]);
            menu.button(11 + 2 * index, Material.PAPER, ChatColor.GREEN + number, number);
        }
        menu.button(20, Material.REDSTONE, ChatColor.RED + messages.reject, "reject");
        menu.button(24, Material.COMPASS, ChatColor.YELLOW + messages.mute, "mute");
        if (player.openInventory(menu.inventory) == null) {
            return false;
        }
        open.put(player.getUniqueId(), menu);
        player.sendMessage(messages.instructions);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Menu menu = menu(event.getInventory());
        if (menu == null) {
            return;
        }
        boolean allowed = !event.isCancelled() && event.getWhoClicked() == menu.player
                && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT);
        event.setCancelled(true);
        String selection = allowed ? menu.buttons.get(event.getRawSlot()) : null;
        if (selection != null) {
            schedule.accept(menu.player, () -> {
                if (open.remove(menu.player.getUniqueId(), menu)) {
                    menu.inventory.clear();
                    menu.player.closeInventory();
                    menu.respond.accept(selection);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (menu(event.getInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menu(event.getInventory());
        if (menu != null) {
            open.remove(menu.player.getUniqueId(), menu);
            menu.inventory.clear();
        }
    }

    void close() {
        for (Menu menu : open.values().toArray(new Menu[0])) {
            menu.inventory.clear();
            menu.player.closeInventory();
        }
        open.clear();
    }

    private Menu menu(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof Menu && ((Menu) holder).inventory.equals(inventory) ? (Menu) holder : null;
    }

    private static final class Menu implements InventoryHolder {

        private final Player player;
        private final Inventory inventory;
        private final Map<Integer, String> buttons = new HashMap<>();
        private final Consumer<String> respond;

        private Menu(Player player, String title, Consumer<String> respond) {
            this.player = player;
            this.respond = respond;
            inventory = Bukkit.createInventory(this, 27, title);
        }

        private void button(int slot, Material material, String label, String selection) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(label);
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
            buttons.put(slot, selection);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
