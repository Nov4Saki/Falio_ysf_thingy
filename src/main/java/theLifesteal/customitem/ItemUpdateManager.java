package theLifesteal.customitem;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;

import java.util.*;

public class ItemUpdateManager implements Listener {

    private final JavaPlugin plugin;
    private final AdvancedCustomItemManager itemManager;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey instanceUuidKey;
    private final NamespacedKey reaperBonusKey;
    private final NamespacedKey customItemIdKey;

    private boolean enabled;
    private boolean onJoin;
    private boolean onWorldChange;

    public ItemUpdateManager(JavaPlugin plugin, AdvancedCustomItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.itemIdKey = itemManager.getItemIdKey();
        this.versionKey = new NamespacedKey(plugin, "item_version");
        this.instanceUuidKey = new NamespacedKey(plugin, "item_instance_uuid");
        this.reaperBonusKey = new NamespacedKey(plugin, "reaper_bonus_damage");
        this.customItemIdKey = itemManager.getItemIdKey();
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void loadConfig() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("settings.item-updates.enabled", true);
        this.onJoin = config.getBoolean("settings.item-updates.on-join", true);
        this.onWorldChange = config.getBoolean("settings.item-updates.on-world-change", true);
    }

    public boolean isEnabled() { return enabled; }
    public boolean isOnJoin() { return onJoin; }
    public boolean isOnWorldChange() { return onWorldChange; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        plugin.getConfig().set("settings.item-updates.enabled", enabled);
        plugin.saveConfig();
    }

    public void setOnJoin(boolean onJoin) {
        this.onJoin = onJoin;
        plugin.getConfig().set("settings.item-updates.on-join", onJoin);
        plugin.saveConfig();
    }

    public void setOnWorldChange(boolean onWorldChange) {
        this.onWorldChange = onWorldChange;
        plugin.getConfig().set("settings.item-updates.on-world-change", onWorldChange);
        plugin.saveConfig();
    }

    // ===== EVENT HANDLERS =====

    /**
     * When a player picks up an item from the ground, check and refresh it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!enabled) return;
        if (!(event.getEntity() instanceof Player)) return;

        ItemStack item = event.getItem().getItemStack();
        if (checkAndRefreshItem(item)) {
            event.getItem().setItemStack(item);
            event.getEntity().sendMessage(ColorUtils.colorize("&a⟳ &7Item updated to latest version."));
        }
    }

    /**
     * When items move in inventories (chest→player, player→player, shift-click, etc.),
     * scan the player's inventory for any outdated items that just entered.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Only scan player inventory, not the top inventory (chest, etc.)
        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv == null) return;

        // If player clicked in their own inventory (or shift-clicked into it), scan it
        boolean targetsPlayerInv = clickedInv instanceof PlayerInventory;

        // Shift-click moves items FROM top inventory TO player inventory
        boolean shiftClickToPlayer = event.isShiftClick() && event.getClickedInventory() != null
                && !(event.getClickedInventory() instanceof PlayerInventory);

        if (targetsPlayerInv || shiftClickToPlayer) {
            // Delay one tick so the inventory has settled after the event
            plugin.getServer().getScheduler().runTask(plugin, () -> scanInventory(player.getInventory()));
        }
    }

    /**
     * When items are dragged into the player's inventory, scan afterward.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Check if any dragged slots belong to the player's inventory
        boolean affectsPlayerInv = event.getInventorySlots().stream().anyMatch(slot -> {
            Inventory inv = event.getView().getInventory(slot);
            return inv instanceof PlayerInventory;
        });

        if (affectsPlayerInv) {
            plugin.getServer().getScheduler().runTask(plugin, () -> scanInventory(player.getInventory()));
        }
    }

    // ===== SCANNING =====

    /**
     * Scan a player's entire inventory (contents + armor + offhand) and update any outdated items.
     */
    private void scanInventory(PlayerInventory inv) {
        Player player = (Player) inv.getHolder();
        if (player == null || !player.isOnline()) return;

        int updated = 0;

        // Main contents
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (checkAndRefreshItem(contents[i])) {
                updated++;
                inv.setItem(i, contents[i]);
            }
        }

        // Armor
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (checkAndRefreshItem(armor[i])) {
                updated++;
                inv.setArmorContents(armor);
            }
        }

        // Offhand
        ItemStack offhand = inv.getItemInOffHand();
        if (checkAndRefreshItem(offhand)) {
            updated++;
            inv.setItemInOffHand(offhand);
        }

        // Ender chest
        if (player.getEnderChest() != null) {
            ItemStack[] enderContents = player.getEnderChest().getContents();
            for (int i = 0; i < enderContents.length; i++) {
                if (checkAndRefreshItem(enderContents[i])) {
                    updated++;
                    player.getEnderChest().setItem(i, enderContents[i]);
                }
            }
        }

        if (updated > 0) {
            player.sendMessage(ColorUtils.colorize("&a⟳ &e" + updated + " &7item(s) updated to latest version."));
        }
    }

    /**
     * Check a single ItemStack and refresh it if outdated or disabled.
     * @return true if the item was modified
     */
    private boolean checkAndRefreshItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;

        String itemId = item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (itemId == null) return false;

        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return false;

        // Disabled items → replace with broken placeholder
        if (definition.isDisabled()) {
            ItemStack broken = definition.buildBrokenReplacement();
            broken.setAmount(item.getAmount());
            item.setType(broken.getType());
            item.setItemMeta(broken.getItemMeta());
            return true;
        }

        // Version check
        Long storedVersion = item.getItemMeta().getPersistentDataContainer().get(versionKey, PersistentDataType.LONG);
        long currentVersion = definition.getVersion();

        if (storedVersion != null && storedVersion >= currentVersion) return false;

        // Save dynamic data
        String instanceUuid = item.getItemMeta().getPersistentDataContainer().get(instanceUuidKey, PersistentDataType.STRING);
        Double reaperBonus = item.getItemMeta().getPersistentDataContainer().get(reaperBonusKey, PersistentDataType.DOUBLE);

        // Rebuild
        ItemStack fresh = itemManager.buildItem(definition);
        org.bukkit.inventory.meta.ItemMeta freshMeta = fresh.getItemMeta();
        if (freshMeta != null) {
            if (instanceUuid != null) {
                freshMeta.getPersistentDataContainer().set(instanceUuidKey, PersistentDataType.STRING, instanceUuid);
            }
            if (reaperBonus != null) {
                freshMeta.getPersistentDataContainer().set(reaperBonusKey, PersistentDataType.DOUBLE, reaperBonus);
            }
            fresh.setItemMeta(freshMeta);
        }

        fresh.setAmount(item.getAmount());
        item.setType(fresh.getType());
        item.setItemMeta(fresh.getItemMeta());
        return true;
    }

    // ===== ADMIN COMMANDS =====

    /**
     * Manual refresh of all online players (for /reloaditems command).
     */
    public int refreshAllPlayers() {
        int total = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            total += refreshSinglePlayer(player);
        }
        return total;
    }

    /**
     * Manual refresh of a single player (for /reloaditems <player> command).
     */
    public int refreshSinglePlayer(Player player) {
        if (!enabled) return 0;
        int updated = 0;

        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (checkAndRefreshItem(contents[i])) {
                updated++;
                player.getInventory().setItem(i, contents[i]);
            }
        }

        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (checkAndRefreshItem(armor[i])) {
                updated++;
                player.getInventory().setArmorContents(armor);
            }
        }

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (checkAndRefreshItem(offhand)) {
            updated++;
            player.getInventory().setItemInOffHand(offhand);
        }

        ItemStack[] enderContents = player.getEnderChest().getContents();
        for (int i = 0; i < enderContents.length; i++) {
            if (checkAndRefreshItem(enderContents[i])) {
                updated++;
                player.getEnderChest().setItem(i, enderContents[i]);
            }
        }

        if (updated > 0) {
            player.sendMessage(ColorUtils.colorize("&a⟳ &e" + updated + " &7item(s) updated to latest version."));
        }
        return updated;
    }

    /**
     * Purge all instances of a specific item from all online players.
     */
    public int purgeItem(String itemId) {
        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return 0;

        int purged = 0;
        ItemStack broken = definition.buildBrokenReplacement();

        for (Player player : Bukkit.getOnlinePlayers()) {
            purged += purgeFromInventory(player.getInventory(), itemId, broken);
            purged += purgeFromArmor(player, itemId, broken);
            purged += purgeFromEnderChest(player, itemId, broken);
        }
        return purged;
    }

    private int purgeFromInventory(PlayerInventory inv, String itemId, ItemStack brokenPlaceholder) {
        int count = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.hasItemMeta()) {
                String storedId = item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
                if (itemId.equals(storedId)) {
                    ItemStack replacement = brokenPlaceholder.clone();
                    replacement.setAmount(item.getAmount());
                    inv.setItem(i, replacement);
                    count += item.getAmount();
                }
            }
        }
        return count;
    }

    private int purgeFromArmor(Player player, String itemId, ItemStack brokenPlaceholder) {
        int count = 0;
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item != null && item.hasItemMeta()) {
                String storedId = item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
                if (itemId.equals(storedId)) {
                    armor[i] = brokenPlaceholder.clone();
                    count++;
                }
            }
        }
        player.getInventory().setArmorContents(armor);

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.hasItemMeta()) {
            String storedId = offhand.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
            if (itemId.equals(storedId)) {
                player.getInventory().setItemInOffHand(brokenPlaceholder.clone());
                count++;
            }
        }
        return count;
    }

    private int purgeFromEnderChest(Player player, String itemId, ItemStack brokenPlaceholder) {
        int count = 0;
        ItemStack[] contents = player.getEnderChest().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.hasItemMeta()) {
                String storedId = item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
                if (itemId.equals(storedId)) {
                    ItemStack replacement = brokenPlaceholder.clone();
                    replacement.setAmount(item.getAmount());
                    player.getEnderChest().setItem(i, replacement);
                    count += item.getAmount();
                }
            }
        }
        return count;
    }
}