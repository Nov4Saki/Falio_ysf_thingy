package theLifesteal.customitem;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
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
 * Unified item integrity manager.
 *
 * Responsibilities:
 * - Restore item_model DataComponent on drop/move (lightweight, in-place)
 * - Scan player inventories on join/world-change/command for outdated items
 * - Apply safe definition updates via component-level rebuild (no setItemMeta on originals)
 * - Handle disabled items → broken placeholder conversion
 * - Handle legacy stack splitting for non-stackable categories
 * - Handle instance UUID assignment for non-stackable items
 * - Update format stamps without triggering definition rebuilds
 */
public class ItemIntegrityManager implements Listener {

    private final JavaPlugin plugin;
    private final AdvancedCustomItemManager itemManager;

    // PDC keys
    private final NamespacedKey itemIdKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey formatKey;
    private final NamespacedKey instanceUuidKey;
    private final NamespacedKey modelBackupKey;

    // Configuration
    private boolean enabled;
    private boolean onJoin;
    private boolean onWorldChange;

    // Debounce: only one pending scan per player
    private final Set<UUID> pendingScans = ConcurrentHashMap.newKeySet();

    /**
     * DataComponent types that the plugin explicitly manages during definition builds.
     * These are NOT preserved from the original item during a safe rebuild because
     * the fresh build provides the updated values.
     *
     * ALL other components are preserved from the original item.
     */
    private static final Set<DataComponentType> MANAGED_COMPONENTS = Set.of(
            DataComponentTypes.CUSTOM_NAME,
            DataComponentTypes.LORE,
            DataComponentTypes.ENCHANTMENTS,
            DataComponentTypes.ATTRIBUTE_MODIFIERS,
            DataComponentTypes.EQUIPPABLE,
            DataComponentTypes.CUSTOM_MODEL_DATA,
            DataComponentTypes.ITEM_MODEL,
            DataComponentTypes.DAMAGE,
            DataComponentTypes.UNBREAKABLE,
            DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,
            DataComponentTypes.TOOLTIP_DISPLAY
    );

    /**
     * PDC keys owned by this plugin. These are overwritten during rebuild;
     * all other PDC keys from the original are preserved.
     */
    private static final Set<String> OWNED_PDC_KEYS = Set.of(
            "custom_item_id",
            "item_version",
            "custom_item_format",
            "item_instance_uuid",
            "model_backup",
            "reaper_bonus_damage"
    );

    public ItemIntegrityManager(JavaPlugin plugin, AdvancedCustomItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;

        this.itemIdKey = itemManager.getItemIdKey();
        this.versionKey = new NamespacedKey(plugin, "item_version");
        this.formatKey = itemManager.getFormatKey();
        this.instanceUuidKey = new NamespacedKey(plugin, "item_instance_uuid");
        this.modelBackupKey = itemManager.getModelBackupKey();

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

    // ==================== EVENT HANDLERS: MODEL RESTORATION ====================

    /**
     * When any item spawns in the world, restore its item_model DataComponent
     * if it was stripped during serialization. This is a lightweight in-place fix.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!enabled) return;
        restoreModel(event.getEntity().getItemStack());
    }

    /**
     * Player drop: fix model before the item entity is created.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!enabled) return;
        restoreModel(event.getItemDrop().getItemStack());
    }

    /**
     * Hopper/container move: fix model after transfer.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!enabled) return;
        restoreModel(event.getItem());
    }

    /**
     * Restore the item_model DataComponent from the definition.
     * Completely non-destructive: only touches ITEM_MODEL and modelBackup PDC.
     */
    private void restoreModel(ItemStack item) {
        if (item == null || item.getType().isAir()) return;

        String itemId = item.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (itemId == null) return;

        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return;

        NamespacedKey definedModel = definition.getItemModel();
        if (definedModel == null) return;

        Key expectedModel = Key.key(definedModel.getNamespace(), definedModel.getKey());
        Key currentModel = item.getData(DataComponentTypes.ITEM_MODEL);

        if (!expectedModel.equals(currentModel)) {
            item.setData(DataComponentTypes.ITEM_MODEL, expectedModel);
            item.editPersistentDataContainer(container -> {
                container.set(modelBackupKey, PersistentDataType.STRING,
                        definedModel.getNamespace() + ":" + definedModel.getKey());
            });
        }
    }

    // ==================== EVENT HANDLERS: INVENTORY SCANS ====================

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

    /**
     * Schedule a debounced inventory scan for a player.
     * Only one scan can be pending per player at a time.
     */
    private void scheduleScan(Player player, long delayTicks) {
        if (player == null || !player.isOnline()) return;

        UUID playerId = player.getUniqueId();
        if (!pendingScans.add(playerId)) return; // Already pending

        Runnable clearPending = () -> pendingScans.remove(playerId);
        FoliaScheduler.runEntityLater(
                player,
                plugin,
                () -> {
                    try {
                        if (player.isOnline()) {
                            scanPlayerInventory(player);
                        }
                    } finally {
                        pendingScans.remove(playerId);
                    }
                },
                clearPending,
                delayTicks
        );
    }

    // ==================== PUBLIC API (used by CommandHandler) ====================

    /**
     * Refresh all online players' inventories.
     * @return total number of items updated
     */
    public int refreshAllPlayers() {
        if (!enabled) return 0;
        int total = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            total += refreshSinglePlayer(player);
        }
        return total;
    }

    /**
     * Refresh a single player's inventory.
     * @return number of items updated
     */
    public int refreshSinglePlayer(Player player) {
        if (!enabled || player == null || !player.isOnline()) return 0;
        return scanPlayerInventory(player);
    }

    /**
     * Purge all instances of a specific item ID from all online players,
     * replacing them with broken placeholder items.
     * @return number of items purged
     */
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

    // ==================== CORE SCAN LOGIC ====================

    /**
     * Scan a player's entire inventory (main, armor, offhand, ender chest).
     * @return number of items updated
     */
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

    /**
     * Process a single slot in an inventory array.
     * @return true if the item was modified
     */
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
     * Mutates the item in-place for disabled/lightweight fixes.
     * Replaces the item (via the setter pattern in processSlot) for safe rebuilds.
     *
     * @param item the ItemStack to check (may be mutated in-place for some fixes)
     * @return true if the item was modified
     */
    private boolean checkAndUpdateItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        // Quick PDC check
        String itemId = item.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (itemId == null) return false;

        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return false;

        // --- Disabled items: replace with broken placeholder ---
        if (definition.isDisabled()) {
            ItemStack broken = definition.buildBrokenReplacement();
            broken.setAmount(item.getAmount());
            item.setType(broken.getType());
            item.setItemMeta(broken.getItemMeta());
            // Clear PDC so future scans ignore this item
            item.editPersistentDataContainer(container -> {
                container.remove(itemIdKey);
                container.remove(versionKey);
                container.remove(formatKey);
                container.remove(instanceUuidKey);
                container.remove(modelBackupKey);
            });
            return true;
        }

        // --- Read current stamps ---
        Long storedVersion = item.getPersistentDataContainer().get(versionKey, PersistentDataType.LONG);
        Integer storedFormat = item.getPersistentDataContainer().get(formatKey, PersistentDataType.INTEGER);
        long currentVersion = definition.getVersion();

        // --- Check what needs fixing ---
        boolean needsModelFix = checkModelNeedsFix(item, definition);
        boolean needsInstanceUuid = definition.shouldGetInstanceUuid()
                && getInstanceUuid(item) == null;
        boolean needsFormatUpgrade = storedFormat == null
                || storedFormat < AdvancedCustomItemManager.CURRENT_ITEM_FORMAT;
        boolean definitionChanged = storedVersion == null || storedVersion < currentVersion;

        // --- If everything is current, skip ---
        if (!definitionChanged && !needsFormatUpgrade && !needsModelFix && !needsInstanceUuid) {
            return false;
        }

        // --- Apply fixes ---
        boolean modified = false;

        // Lightweight fixes first (no rebuild needed)
        if (needsModelFix && !definitionChanged) {
            applyModelFix(item, definition);
            modified = true;
        }
        if (needsInstanceUuid && !definitionChanged) {
            itemManager.ensureInstanceUuid(item, definition);
            modified = true;
        }
        if (needsFormatUpgrade && !definitionChanged) {
            // Format-only upgrade: just bump the stamp, no rebuild
            item.editPersistentDataContainer(container -> {
                container.set(formatKey, PersistentDataType.INTEGER,
                        AdvancedCustomItemManager.CURRENT_ITEM_FORMAT);
            });
            modified = true;
        }

        // Definition changed: need a full safe rebuild
        if (definitionChanged) {
            performSafeRebuild(item, definition);
            modified = true;
        } else if (modified) {
            // Update stamps for lightweight fixes
            final boolean finalNeedsModelFix = needsModelFix;
            item.editPersistentDataContainer(container -> {
                container.set(versionKey, PersistentDataType.LONG, currentVersion);
                if (needsFormatUpgrade) {
                    container.set(formatKey, PersistentDataType.INTEGER,
                            AdvancedCustomItemManager.CURRENT_ITEM_FORMAT);
                }
                if (finalNeedsModelFix && definition.getItemModel() != null) {
                    container.set(modelBackupKey, PersistentDataType.STRING,
                            definition.getItemModel().getNamespace() + ":" + definition.getItemModel().getKey());
                }
            });
        }

        return modified;
    }

    /**
     * Check if the item's model DataComponent needs restoration.
     */
    private boolean checkModelNeedsFix(ItemStack item, AdvancedCustomItem definition) {
        NamespacedKey definedModel = definition.getItemModel();
        if (definedModel == null) return false;

        Key expected = Key.key(definedModel.getNamespace(), definedModel.getKey());
        Key current = item.getData(DataComponentTypes.ITEM_MODEL);
        return !expected.equals(current);
    }

    /**
     * Apply model fix in-place (no rebuild).
     */
    private void applyModelFix(ItemStack item, AdvancedCustomItem definition) {
        NamespacedKey definedModel = definition.getItemModel();
        if (definedModel == null) return;

        Key model = Key.key(definedModel.getNamespace(), definedModel.getKey());
        item.setData(DataComponentTypes.ITEM_MODEL, model);
        item.editPersistentDataContainer(container -> {
            container.set(modelBackupKey, PersistentDataType.STRING,
                    definedModel.getNamespace() + ":" + definedModel.getKey());
        });
    }

    // ==================== SAFE REBUILD ====================

    /**
     * Perform a safe rebuild when the definition version has changed.
     *
     * Builds a fresh item from the definition, then copies ALL unmanaged
     * DataComponents and non-plugin PDC keys from the original item.
     * Player-applied modifications (durability, anvil enchants, renamed items)
     * are preserved by checking against the fresh definition's values.
     *
     * NEVER calls setItemMeta() on the original item — only on the fresh build
     * before the component-level merge.
     *
     * Mutates the item parameter in-place.
     */
    private void performSafeRebuild(ItemStack item, AdvancedCustomItem definition) {
        // 1. Build fresh item from current definition
        ItemStack fresh = itemManager.buildItem(definition);

        // 2. Preserve player-applied durability (only if definition sets a specific damage)
        ItemMeta originalMeta = item.getItemMeta();
        ItemMeta freshMeta = fresh.getItemMeta();
        if (originalMeta instanceof Damageable origDamageable
                && freshMeta instanceof Damageable freshDamageable
                && item.getType().getMaxDurability() > 0) {
            int originalDamage = origDamageable.getDamage();
            int freshDamage = freshDamageable.getDamage();
            // If the player's item has more damage than the fresh definition,
            // keep the player's damage value (they've been using the item)
            if (originalDamage > freshDamage) {
                freshDamageable.setDamage(originalDamage);
                fresh.setItemMeta(freshMeta);
            }
        }

        // 3. Preserve player-applied enchantments (from anvils/enchanting tables)
        if (originalMeta != null && freshMeta != null) {
            Map<org.bukkit.enchantments.Enchantment, Integer> freshEnchants = new HashMap<>(freshMeta.getEnchants());
            Map<org.bukkit.enchantments.Enchantment, Integer> originalEnchants = originalMeta.getEnchants();

            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : originalEnchants.entrySet()) {
                Integer freshLevel = freshEnchants.get(entry.getKey());
                // If the original has an enchant not in the definition, or at a higher level,
                // preserve the player's value
                if (freshLevel == null || entry.getValue() > freshLevel) {
                    freshMeta.addEnchant(entry.getKey(), entry.getValue(), true);
                }
            }
            fresh.setItemMeta(freshMeta);
        }

        // 4. Copy ALL unmanaged DataComponents from original to fresh
        //    Only MANAGED_COMPONENTS come from the fresh build;
        //    everything else (container data, bundle contents, etc.) comes from the original
        fresh.copyDataFrom(item, type -> !MANAGED_COMPONENTS.contains(type));

        // 5. Copy all non-plugin PDC keys from original, then overwrite with fresh's plugin keys
        fresh.editPersistentDataContainer(freshContainer -> {
            // First, copy all original PDC entries that we don't own
            item.getPersistentDataContainer().copyTo(freshContainer, false);
            // Then overwrite our owned keys with fresh values
            itemManager.buildItem(definition).getPersistentDataContainer().copyTo(freshContainer, true);
            // Ensure correct version and format stamps
            freshContainer.set(versionKey, PersistentDataType.LONG, definition.getVersion());
            freshContainer.set(formatKey, PersistentDataType.INTEGER,
                    AdvancedCustomItemManager.CURRENT_ITEM_FORMAT);
        });

        // 6. Transfer instance UUID if the original had one
        String originalUuid = getInstanceUuid(item);
        if (originalUuid != null) {
            fresh.editPersistentDataContainer(container -> {
                container.set(instanceUuidKey, PersistentDataType.STRING, originalUuid);
            });
        } else {
            itemManager.ensureInstanceUuid(fresh, definition);
        }

        // 7. Preserve amount
        fresh.setAmount(item.getAmount());

        // 8. Replace the original item's data with the merged fresh data
        replaceItemData(item, fresh);
    }

    /**
     * Replace all data on the target item with data from the source item.
     * This is a component-level operation that avoids setItemMeta on the target.
     */
    private void replaceItemData(ItemStack target, ItemStack source) {
        // Remove all components from target that aren't on source
        for (DataComponentType type : new HashSet<>(target.getDataTypes())) {
            if (!source.hasData(type)) {
                target.unsetData(type);
            }
        }
        // Copy all components from source
        target.copyDataFrom(source, type -> true);
        // Copy PDC
        target.editPersistentDataContainer(container -> {
            source.getPersistentDataContainer().copyTo(container, true);
        });
        // Set type and amount
        target.setType(source.getType());
        target.setAmount(source.getAmount());
    }

    // ==================== LEGACY STACK SPLITTING ====================

    /**
     * Split legacy stacks of category items (>1 amount) into individual
     * non-stackable copies with unique instance UUIDs.
     */
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
            if (item != null && item.hasItemMeta()) {
                String storedId = item.getItemMeta().getPersistentDataContainer()
                        .get(itemIdKey, PersistentDataType.STRING);
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
            if (item != null && item.hasItemMeta()) {
                String storedId = item.getItemMeta().getPersistentDataContainer()
                        .get(itemIdKey, PersistentDataType.STRING);
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
        if (offhand != null && offhand.hasItemMeta()) {
            String storedId = offhand.getItemMeta().getPersistentDataContainer()
                    .get(itemIdKey, PersistentDataType.STRING);
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
                String storedId = item.getItemMeta().getPersistentDataContainer()
                        .get(itemIdKey, PersistentDataType.STRING);
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

    private String getInstanceUuid(ItemStack item) {
        if (item == null) return null;
        return item.getPersistentDataContainer().get(instanceUuidKey, PersistentDataType.STRING);
    }
}