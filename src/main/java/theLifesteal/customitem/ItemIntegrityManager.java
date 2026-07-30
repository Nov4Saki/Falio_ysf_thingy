package theLifesteal.customitem;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import theLifesteal.util.FoliaScheduler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Simplified item integrity manager.
 *
 * Since lore is now packet-injected (not baked on items), this manager only handles:
 * - Gameplay-critical definition updates (enchants, attributes, model, damage,
 *   unbreakable, equippable, display name)
 * - Disabled item → broken placeholder conversion
 * - Instance UUID assignment for non-stackable items
 * - Legacy lore stripping (one-time migration for pre-existing items)
 * - item_model DataComponent restoration on drop/move
 */
public class ItemIntegrityManager implements Listener {

    private final JavaPlugin plugin;
    private final AdvancedCustomItemManager itemManager;

    // PDC keys
    private final NamespacedKey itemIdKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey instanceUuidKey;

    // Configuration
    private boolean enabled;
    private boolean onJoin;
    private boolean onWorldChange;

    // Debounce: only one pending scan per player
    private final Set<UUID> pendingScans = ConcurrentHashMap.newKeySet();

    public ItemIntegrityManager(JavaPlugin plugin, AdvancedCustomItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;

        this.itemIdKey = itemManager.getItemIdKey();
        this.versionKey = itemManager.getVersionKey();
        this.instanceUuidKey = new NamespacedKey(plugin, "item_instance_uuid");

        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ==================== CONFIGURATION ====================

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

    // ==================== MODEL RESTORATION EVENTS ====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!enabled) return;
        restoreModel(event.getEntity().getItemStack());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!enabled) return;
        restoreModel(event.getItemDrop().getItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!enabled) return;
        restoreModel(event.getItem());
    }

    private void restoreModel(ItemStack item) {
        if (item == null || item.getType().isAir()) return;

        String itemId = getItemId(item);
        if (itemId == null) return;

        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return;

        NamespacedKey definedModel = definition.getItemModel();
        if (definedModel == null) return;

        Key expectedModel = Key.key(definedModel.getNamespace(), definedModel.getKey());
        Key currentModel = item.getData(DataComponentTypes.ITEM_MODEL);

        if (!expectedModel.equals(currentModel)) {
            item.setData(DataComponentTypes.ITEM_MODEL, expectedModel);
        }
    }

    // ==================== INVENTORY SCAN EVENTS ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled || !onJoin) return;
        scheduleScan(event.getPlayer(), 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!enabled || !onWorldChange) return;
        scheduleScan(event.getPlayer(), 1L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingScans.remove(event.getPlayer().getUniqueId());
    }

    private void scheduleScan(Player player, long delayTicks) {
        if (player == null || !player.isOnline()) return;

        UUID playerId = player.getUniqueId();
        if (!pendingScans.add(playerId)) return;

        FoliaScheduler.runEntityLater(
                player, plugin,
                () -> {
                    try {
                        if (player.isOnline()) {
                            scanPlayerInventory(player);
                        }
                    } finally {
                        pendingScans.remove(playerId);
                    }
                },
                delayTicks
        );
    }

    // ==================== PUBLIC API ====================

    public int refreshAllPlayers() {
        if (!enabled) return 0;
        int total = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            total += refreshSinglePlayer(player);
        }
        return total;
    }

    public int refreshSinglePlayer(Player player) {
        if (!enabled || player == null || !player.isOnline()) return 0;
        return scanPlayerInventory(player);
    }

    public int purgeItem(String itemId) {
        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return 0;

        int purged = 0;
        ItemStack broken = definition.buildBrokenReplacement();

        for (Player player : Bukkit.getOnlinePlayers()) {
            purged += purgeFromPlayer(player, itemId, broken);
        }
        return purged;
    }

    // ==================== SCAN LOGIC ====================

    private int scanPlayerInventory(Player player) {
        if (!enabled || player == null || !player.isOnline()) return 0;

        int updated = 0;

        // Main inventory
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            int slot = i;
            if (processSlot(contents, i, updatedStack -> player.getInventory().setItem(slot, updatedStack))) {
                updated++;
            }
            if (splitLegacyStack(player, contents[i],
                    updatedStack -> player.getInventory().setItem(slot, updatedStack),
                    player.getInventory())) {
                updated++;
            }
        }

        // Armor
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean[] armorChanged = {false};
        for (int i = 0; i < armor.length; i++) {
            int slot = i;
            if (processSlot(armor, i, updatedStack -> {
                armor[slot] = updatedStack;
                armorChanged[0] = true;
            })) {
                updated++;
            }
            if (splitLegacyStack(player, armor[i], updatedStack -> {
                armor[slot] = updatedStack;
                armorChanged[0] = true;
            }, player.getInventory())) {
                updated++;
            }
        }
        if (armorChanged[0]) {
            player.getInventory().setArmorContents(armor);
        }

        // Offhand
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (processSlot(new ItemStack[]{offhand}, 0, player.getInventory()::setItemInOffHand)) {
            updated++;
        }
        if (splitLegacyStack(player, offhand, player.getInventory()::setItemInOffHand, player.getInventory())) {
            updated++;
        }

        // Ender chest
        Inventory enderChest = player.getEnderChest();
        if (enderChest != null) {
            ItemStack[] enderContents = enderChest.getContents();
            for (int i = 0; i < enderContents.length; i++) {
                int slot = i;
                if (processSlot(enderContents, i, updatedStack -> enderChest.setItem(slot, updatedStack))) {
                    updated++;
                }
                if (splitLegacyStack(player, enderContents[i],
                        updatedStack -> enderChest.setItem(slot, updatedStack),
                        enderChest)) {
                    updated++;
                }
            }
        }

        if (updated > 0) {
            player.sendMessage(ColorUtils.colorize("&a⟳ &e" + updated + " &7item(s) updated to latest version."));
        }

        return updated;
    }

    private boolean processSlot(ItemStack[] inventory, int index, Consumer<ItemStack> setter) {
        ItemStack item = inventory[index];
        if (item == null || item.getType().isAir()) return false;

        if (checkAndUpdateItem(item)) {
            setter.accept(item);
            return true;
        }
        return false;
    }

    /**
     * Check a single ItemStack and update it if necessary.
     */
    private boolean checkAndUpdateItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        String itemId = getItemId(item);
        if (itemId == null) return false;

        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return false;

        // --- Disabled items: replace with broken placeholder ---
        if (definition.isDisabled()) {
            replaceWithBroken(item, definition);
            return true;
        }

        // --- Read version stamp ---
        Long storedVersion = item.getPersistentDataContainer().get(versionKey, PersistentDataType.LONG);
        long currentVersion = definition.getVersion();

        // --- Legacy migration: strip old baked lore ---
        boolean strippedLore = stripLegacyLore(item);

        // --- Check what needs fixing ---
        boolean needsModelFix = checkModelNeedsFix(item, definition);
        boolean needsInstanceUuid = definition.shouldGetInstanceUuid()
                && getInstanceUuid(item) == null;
        boolean definitionChanged = storedVersion == null || storedVersion < currentVersion;

        boolean modified = strippedLore;

        // Lightweight fixes
        if (needsModelFix && !definitionChanged) {
            applyModelFix(item, definition);
            modified = true;
        }
        if (needsInstanceUuid && !definitionChanged) {
            itemManager.ensureInstanceUuid(item, definition);
            modified = true;
        }

        // Gameplay-critical rebuild
        if (definitionChanged) {
            performGameplayRebuild(item, definition);
            modified = true;
        } else if (modified) {
            // Update version stamp for lightweight fixes
            item.editPersistentDataContainer(container -> {
                container.set(versionKey, PersistentDataType.LONG, currentVersion);
            });
        }

        return modified;
    }

    /**
     * Strip baked lore from legacy items (pre-migration).
     */
    private boolean stripLegacyLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        boolean hadLore = meta.hasLore() && meta.getLore() != null && !meta.getLore().isEmpty();
        boolean hadOldKeys = false;

        // Check for old PDC keys to clean up
        var container = item.getPersistentDataContainer();
        NamespacedKey oldModelBackup = new NamespacedKey(plugin, "model_backup");
        NamespacedKey oldFormat = new NamespacedKey(plugin, "custom_item_format");

        if (container.has(oldModelBackup, PersistentDataType.STRING)) {
            item.editPersistentDataContainer(c -> c.remove(oldModelBackup));
            hadOldKeys = true;
        }
        if (container.has(oldFormat, PersistentDataType.INTEGER)) {
            item.editPersistentDataContainer(c -> c.remove(oldFormat));
            hadOldKeys = true;
        }

        if (hadLore) {
            meta.setLore(null);
            item.setItemMeta(meta);
        }

        return hadLore || hadOldKeys;
    }

    private boolean checkModelNeedsFix(ItemStack item, AdvancedCustomItem definition) {
        NamespacedKey definedModel = definition.getItemModel();
        if (definedModel == null) return false;

        Key expected = Key.key(definedModel.getNamespace(), definedModel.getKey());
        Key current = item.getData(DataComponentTypes.ITEM_MODEL);
        return !expected.equals(current);
    }

    private void applyModelFix(ItemStack item, AdvancedCustomItem definition) {
        NamespacedKey definedModel = definition.getItemModel();
        if (definedModel == null) return;

        Key model = Key.key(definedModel.getNamespace(), definedModel.getKey());
        item.setData(DataComponentTypes.ITEM_MODEL, model);
    }

    /**
     * Replace an item with its broken placeholder.
     */
    private void replaceWithBroken(ItemStack item, AdvancedCustomItem definition) {
        ItemStack broken = definition.buildBrokenReplacement();
        broken.setAmount(item.getAmount());

        // Copy all data from broken to item
        item.setType(broken.getType());
        item.setItemMeta(broken.getItemMeta());

        // Clear PDC so future scans ignore this item
        item.editPersistentDataContainer(container -> {
            container.remove(itemIdKey);
            container.remove(versionKey);
            container.remove(instanceUuidKey);
        });
    }

    // ==================== GAMEPLAY REBUILD ====================

    /**
     * Rebuild gameplay-critical components from the current definition.
     * Does NOT touch lore (which is packet-injected).
     */
    private void performGameplayRebuild(ItemStack item, AdvancedCustomItem definition) {
        // Build fresh item from current definition
        ItemStack fresh = itemManager.buildItem(definition);

        // Preserve player-applied durability
        ItemMeta originalMeta = item.getItemMeta();
        ItemMeta freshMeta = fresh.getItemMeta();
        if (originalMeta instanceof Damageable origDamageable
                && freshMeta instanceof Damageable freshDamageable
                && item.getType().getMaxDurability() > 0) {
            int originalDamage = origDamageable.getDamage();
            int freshDamage = freshDamageable.getDamage();
            if (originalDamage > freshDamage) {
                freshDamageable.setDamage(originalDamage);
                fresh.setItemMeta(freshMeta);
            }
        }

        // Preserve player-applied enchantments (from anvils/enchanting tables)
        if (originalMeta != null && freshMeta != null) {
            Map<Enchantment, Integer> freshEnchants = new HashMap<>(freshMeta.getEnchants());
            Map<Enchantment, Integer> originalEnchants = originalMeta.getEnchants();

            for (Map.Entry<Enchantment, Integer> entry : originalEnchants.entrySet()) {
                Integer freshLevel = freshEnchants.get(entry.getKey());
                if (freshLevel == null || entry.getValue() > freshLevel) {
                    freshMeta.addEnchant(entry.getKey(), entry.getValue(), true);
                }
            }
            fresh.setItemMeta(freshMeta);
        }

        // Preserve instance UUID
        String originalUuid = getInstanceUuid(item);
        if (originalUuid != null) {
            fresh.editPersistentDataContainer(container -> {
                container.set(instanceUuidKey, PersistentDataType.STRING, originalUuid);
            });
        }

        // Preserve amount
        fresh.setAmount(item.getAmount());

        // Replace item data
        replaceItemData(item, fresh);
    }

    /**
     * Replace all data on the target item with data from the source item.
     */
    private void replaceItemData(ItemStack target, ItemStack source) {
        // Remove all components from target that aren't on source
        for (var type : new HashSet<>(target.getDataTypes())) {
            if (!source.hasData(type)) {
                target.unsetData(type);
            }
        }
        // Copy all components from source
        target.copyDataFrom(source, t -> true);
        // Copy PDC
        target.editPersistentDataContainer(container -> {
            source.getPersistentDataContainer().copyTo(container, true);
        });
        // Set type and amount
        target.setType(source.getType());
        target.setAmount(source.getAmount());
    }

    // ==================== LEGACY STACK SPLITTING ====================

    private boolean splitLegacyStack(Player player, ItemStack stack,
                                     Consumer<ItemStack> setter,
                                     Inventory preferredInventory) {
        if (stack == null || stack.getAmount() <= 1) return false;

        AdvancedCustomItem definition = itemManager.getItemByStack(stack);
        if (definition == null || !definition.shouldGetInstanceUuid()) return false;

        int amount = stack.getAmount();
        stack.setAmount(1);
        setter.accept(stack);

        for (int i = 1; i < amount; i++) {
            ItemStack single = stack.clone();
            single.setAmount(1);
            itemManager.assignFreshInstanceUuid(single, definition);
            placeSplitItem(player, preferredInventory, single);
        }
        return true;
    }

    private void placeSplitItem(Player player, Inventory preferredInventory, ItemStack item) {
        Map<Integer, ItemStack> remaining = preferredInventory.addItem(item);
        if (preferredInventory != player.getInventory() && !remaining.isEmpty()) {
            Map<Integer, ItemStack> playerRemaining = new HashMap<>();
            for (ItemStack leftover : remaining.values()) {
                playerRemaining.putAll(player.getInventory().addItem(leftover));
            }
            remaining = playerRemaining;
        }
        for (ItemStack leftover : remaining.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    // ==================== PURGE LOGIC ====================

    private int purgeFromPlayer(Player player, String itemId, ItemStack brokenPlaceholder) {
        int count = 0;
        count += purgeFromInventory(player.getInventory(), itemId, brokenPlaceholder);
        count += purgeFromArmor(player, itemId, brokenPlaceholder);
        count += purgeFromEnderChest(player, itemId, brokenPlaceholder);
        return count;
    }

    private int purgeFromInventory(PlayerInventory inv, String itemId, ItemStack brokenPlaceholder) {
        int count = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null) {
                String storedId = getItemId(item);
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
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item != null) {
                String storedId = getItemId(item);
                if (itemId.equals(storedId)) {
                    armor[i] = brokenPlaceholder.clone();
                    count++;
                    changed = true;
                }
            }
        }
        if (changed) {
            player.getInventory().setArmorContents(armor);
        }

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null) {
            String storedId = getItemId(offhand);
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
            if (item != null) {
                String storedId = getItemId(item);
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

    // ==================== UTILITY ====================

    private String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    }

    private String getInstanceUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(instanceUuidKey, PersistentDataType.STRING);
    }
}