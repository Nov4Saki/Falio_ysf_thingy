package theLifesteal.customitem;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.abilities.ItemAbilityGUI;
import theLifesteal.util.FoliaScheduler;

public class CustomItemListener implements Listener {
    private final JavaPlugin plugin;
    private final CustomItemGUI gui;
    private final ItemAbilityGUI abilityGUI;

    public CustomItemListener(JavaPlugin plugin, CustomItemGUI gui, ItemAbilityGUI abilityGUI) {
        this.plugin = plugin;
        this.gui = gui;
        this.abilityGUI = abilityGUI;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        if (abilityGUI != null && abilityGUI.isAbilityGUI(event.getView().getTitle())) {
            event.setCancelled(true);
            abilityGUI.handleClick((Player) event.getWhoClicked(), event.getView().getTitle(),
                    event.getSlot(), event.getClick());
            return;
        }

        if (!gui.isCustomItemGUI(event.getView().getTitle())) return;
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getBottomInventory()))
            return;
        event.setCancelled(true);
        gui.handleClick(event);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();

        if (title.contains("Edit Item") || title.contains("Attributes") ||
                title.contains("Name & Lore") || title.contains("Flags")) {
            gui.saveOnClose(player);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();

        if (abilityGUI != null && abilityGUI.isAwaitingInput(p.getUniqueId())) {
            event.setCancelled(true);
            FoliaScheduler.runEntity(p, plugin, () -> abilityGUI.handleChatInput(p, event.getMessage()));
            return;
        }

        if (gui.isAwaitingInput(p.getUniqueId())) {
            event.setCancelled(true);
            FoliaScheduler.runEntity(p, plugin, () -> gui.handleChatInput(p, event.getMessage()));
        }
    }
}