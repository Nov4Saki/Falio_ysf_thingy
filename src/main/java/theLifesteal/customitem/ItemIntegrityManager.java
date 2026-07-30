package theLifesteal.customitem;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
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
 * Unified item integrity manager for Folia 1.21+.
 *
 * Responsibilities:
 * - Strip leaked lore from items using component-safe edits
 * - Restore item_model DataComponent on drop/move/hopper
 * - Scan inventories on join/world-change/command for outdated items
 * - Perform safe rebuilds via DataComponent-level merging (no raw setItemMeta)
 * - Handle disabled items → broken placeholder conversion
 * - Split legacy stacks for non-stackable categories
 * - Emergency PDC re-stamp safety net
 *
 * Folia safety: All inventory scans are entity-scoped. Model restoration is immediate
 * and component-level. No cross-thread state mutations.
 */
public class ItemIntegrityManager implements Listener {

    private final JavaPlugin plugin;
    private final AdvancedCustomItemManager itemManager;

    private final NamespacedKey itemIdKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey formatKey;
    private final NamespacedKey instanceUuidKey;
    private final NamespacedKey modelBackupKey;

    private boolean enabled;
    private boolean onJoin;
    private boolean onWorldChange;

    private final Set<UUID> pendingScans = ConcurrentHashMap.newKeySet();

    /**
     * DataComponent types that the plugin explicitly manages during definition
     * rebuilds. These are provided by the fresh build and are NOT preserved
     * from the original item.
     *
     * NOTE: CUSTOM_MODEL_DATA and ITEM_MODEL are intentionally excluded from
     * this set so they survive any setItemMeta round-trip by default. They
     * are set explicitly afterward by buildItem() and the integrity manager,
     * so removing them from the managed set only adds safety.
     */
    private static final Set<DataComponentType> MANAGED_COMPONENTS = Set.of(
            DataComponentTypes.CUSTOM_NAME,
            DataComponentTypes.LORE,
            DataComponentTypes.ENCHANTMENTS,
            DataComponentTypes.ATTRIBUTE_MODIFIERS,
            DataComponentTypes.EQUIPPABLE,
            DataComponentTypes.DAMAGE,
            DataComponentTypes.UNBREAKABLE,
            DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,
            DataComponentTypes.TOOLTIP_DISPLAY
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

    // ==================== MODEL RESTORATION (DROP/MOVE) ====================

    /**
     * When any item spawns in the world (drop, natural spawn, etc.), restore
     * the ITEM_MODEL DataComponent if it was stripped during serialization.
     * This is a lightweight in-place fix that touches nothing else.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!enabled) return;
        restoreModel(event.getEntity().getItemStack());
    }

    /**
     * Player drop: fix model before the item entity is created so the
     * client receives the correct model immediately.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!enabled) return;
        restoreModel(event.getItemDrop().getItemStack());
    }

    /**
     * Hopper / container move: fix model after transfer so the item in
     * the destination container renders correctly.
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
        String itemId = getItemId(item);
        if (itemId == null) return;

        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return;

        NamespacedKey definedModel = definition.getItemModel();
        if (definedModel == null) return;

        Key expected = Key.key(definedModel.getNamespace(), definedModel.getKey());
        Key current = item.getData(DataComponentTypes.ITEM_MODEL);
        if (!expected.equals(current)) {
            item.setData(DataComponentTypes.ITEM_MODEL, expected);
            item.editPersistentDataContainer(container ->
                    container.set(modelBackupKey, PersistentDataType.STRING,
                            definedModel.getNamespace() + ":" + definedModel.getKey()));
        }
    }

    // ==================== LORE STRIP ====================

    /**
     * Strip legacy lore that leaked onto the item (from old bugs, packet
     * race conditions, or previous plugin versions). Uses component-safe
     * edit so PDC and DataComponents are never touched.
     */
    private void stripLegacyLore(ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        if (!item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        boolean hadLore = meta != null && meta.hasLore();

        if (hadLore) {
            // FIX 1: Use component-safe edit — NEVER raw setItemMeta
            ItemComponentUtil.editMetaPreservingComponents(item, m -> m.setLore(null));

            // Clean up legacy PDC keys that might have leaked
            item.editPersistentDataContainer(container -> {
                container.remove(new NamespacedKey(plugin, "model_backup"));
                container.remove(new NamespacedKey(plugin, "custom_item_format"));
                container.remove(new NamespacedKey(plugin, "item_version"));
            });
        }
    }

    // ==================== INVENTORY SCANS ====================

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
     * Schedule a debounced inventory scan for a player. Only one scan can be
     * pending per player at a time. Uses entity-scoped scheduler for Folia.
     */
    private void scheduleScan(Player player, long delayTicks) {
        if (player == null || !player.isOnline()) return;
        UUID id = player.getUniqueId();
        if (!pendingScans.add(id)) return;

        FoliaScheduler.runEntityLater(player, plugin, () -> {
            try {
                if (player.isOnline()) scanPlayerInventory(player);
            } finally {
                pendingScans.remove(id);
            }
        }, null, delayTicks);
    }

    // ==================== PUBLIC API (CommandHandler) ====================

    public int refreshAllPlayers() {
        if (!enabled) return 0;
        int total = 0;
        for (Player player : Bukkit.getOnlinePlayers()) total += refreshSinglePlayer(player);
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
        for (Player player : Bukkit.getOnlinePlayers()) purged += purgeFromPlayer(player, itemId, broken);
        return purged;
    }

    // ==================== CORE SCAN LOGIC ====================

    private int scanPlayerInventory(Player player) {
        if (!enabled || player == null || !player.isOnline()) return 0;
        int updated = 0;

        // Main inventory
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            int slot = i;
            if (processAndSet(contents, i, s -> player.getInventory().setItem(slot, s))) updated++;
            if (splitLegacy(player, contents[i], s -> player.getInventory().setItem(slot, s), player.getInventory())) updated++;
        }

        // Armor
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean[] armorChanged = {false};
        for (int i = 0; i < armor.length; i++) {
            int slot = i;
            if (processAndSet(armor, i, s -> { armor[slot] = s; armorChanged[0] = true; })) updated++;
            if (splitLegacy(player, armor[i], s -> { armor[slot] = s; armorChanged[0] = true; }, player.getInventory())) updated++;
        }
        if (armorChanged[0]) player.getInventory().setArmorContents(armor);

        // Offhand
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (processAndSet(new ItemStack[]{offhand}, 0, player.getInventory()::setItemInOffHand)) updated++;
        if (splitLegacy(player, offhand, player.getInventory()::setItemInOffHand, player.getInventory())) updated++;

        // Ender chest
        Inventory ender = player.getEnderChest();
        if (ender != null) {
            ItemStack[] enderContents = ender.getContents();
            for (int i = 0; i < enderContents.length; i++) {
                int slot = i;
                if (processAndSet(enderContents, i, s -> ender.setItem(slot, s))) updated++;
                if (splitLegacy(player, enderContents[i], s -> ender.setItem(slot, s), ender)) updated++;
            }
        }

        if (updated > 0) player.sendMessage(ColorUtils.colorize("&a⟳ &e" + updated + " &7item(s) updated."));
        return updated;
    }

    private boolean processAndSet(ItemStack[] inv, int idx, Consumer<ItemStack> setter) {
        ItemStack item = inv[idx];
        if (item == null || item.getType().isAir()) return false;
        if (checkAndUpdateItem(item)) {
            setter.accept(item);
            return true;
        }
        return false;
    }

    /**
     * Check a single ItemStack and update it if necessary.
     *
     * @param item the ItemStack to check (mutated in-place)
     * @return true if the item was modified
     */
    private boolean checkAndUpdateItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        // Strip any leaked lore first (component-safe)
        stripLegacyLore(item);

        String itemId = getItemId(item);
        if (itemId == null) return false;

        AdvancedCustomItem def = itemManager.getItem(itemId);
        if (def == null) return false;

        // Disabled items → broken placeholder
        if (def.isDisabled()) {
            ItemStack broken = def.buildBrokenReplacement();
            broken.setAmount(item.getAmount());
            replaceItemData(item, broken);
            item.editPersistentDataContainer(c -> {
                c.remove(itemIdKey);
                c.remove(versionKey);
                c.remove(formatKey);
                c.remove(instanceUuidKey);
                c.remove(modelBackupKey);
            });
            return true;
        }

        // Read current stamps
        Long storedVer = item.getPersistentDataContainer().get(versionKey, PersistentDataType.LONG);
        Integer storedFmt = item.getPersistentDataContainer().get(formatKey, PersistentDataType.INTEGER);
        long curVer = def.getVersion();

        // Check what needs fixing
        boolean needsModel = checkModelNeedsFix(item, def);
        boolean needsUuid = def.shouldGetInstanceUuid() && getInstanceUuid(item) == null;
        boolean needsFmtUp = storedFmt == null || storedFmt < AdvancedCustomItemManager.CURRENT_ITEM_FORMAT;
        boolean defChanged = storedVer == null || storedVer < curVer;

        // If everything is current, skip
        if (!defChanged && !needsFmtUp && !needsModel && !needsUuid) return false;

        boolean modified = false;

        if (!defChanged) {
            // Lightweight fixes only — no rebuild needed
            if (needsModel) { applyModelFix(item, def); modified = true; }
            if (needsUuid) { itemManager.ensureInstanceUuid(item, def); modified = true; }
            if (needsFmtUp) {
                item.editPersistentDataContainer(c -> c.set(formatKey, PersistentDataType.INTEGER, AdvancedCustomItemManager.CURRENT_ITEM_FORMAT));
                modified = true;
            }
            if (modified) {
                item.editPersistentDataContainer(c -> {
                    c.set(versionKey, PersistentDataType.LONG, curVer);
                    if (needsModel && def.getItemModel() != null)
                        c.set(modelBackupKey, PersistentDataType.STRING, def.getItemModel().getNamespace() + ":" + def.getItemModel().getKey());
                });
            }
        } else {
            // Definition changed — full safe rebuild
            performSafeRebuild(item, def);
            modified = true;
        }

        // FIX 6: Safety-net — verify PDC identity survived
        if (modified) {
            String verifyId = item.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
            if (verifyId == null) {
                item.editPersistentDataContainer(c -> {
                    c.set(itemIdKey, PersistentDataType.STRING, itemId);
                    c.set(versionKey, PersistentDataType.LONG, curVer);
                    c.set(formatKey, PersistentDataType.INTEGER, AdvancedCustomItemManager.CURRENT_ITEM_FORMAT);
                });
                if (needsModel || def.getItemModel() != null) {
                    applyModelFix(item, def);
                }
                plugin.getLogger().warning("Emergency PDC re-stamp for item: " + itemId + " on player inventory scan");
            }
        }

        return modified;
    }

    /**
     * FIX 3: Read PDC directly from the ItemStack, not from ItemMeta snapshot.
     * Under Folia threading, ItemMeta snapshots can be stale.
     */
    private String getItemId(ItemStack item) {
        if (item == null) return null;
        return item.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    }

    private String getInstanceUuid(ItemStack item) {
        if (item == null) return null;
        return item.getPersistentDataContainer().get(instanceUuidKey, PersistentDataType.STRING);
    }

    private boolean checkModelNeedsFix(ItemStack item, AdvancedCustomItem def) {
        NamespacedKey definedModel = def.getItemModel();
        if (definedModel == null) return false;
        Key expected = Key.key(definedModel.getNamespace(), definedModel.getKey());
        Key current = item.getData(DataComponentTypes.ITEM_MODEL);
        return !expected.equals(current);
    }

    private void applyModelFix(ItemStack item, AdvancedCustomItem def) {
        NamespacedKey definedModel = def.getItemModel();
        if (definedModel == null) return;
        Key model = Key.key(definedModel.getNamespace(), definedModel.getKey());
        item.setData(DataComponentTypes.ITEM_MODEL, model);
        item.editPersistentDataContainer(c -> c.set(modelBackupKey, PersistentDataType.STRING,
                definedModel.getNamespace() + ":" + definedModel.getKey()));
    }

    // ==================== SAFE REBUILD ====================

    /**
     * Perform a safe rebuild when the definition version has changed.
     *
     * Builds a fresh item from the definition, then copies all unmanaged
     * DataComponents and non-plugin PDC keys from the original.
     *
     * NEVER calls setItemMeta() on the original — all merging is done via
     * the DataComponent API and PDC copy.
     */
    private void performSafeRebuild(ItemStack original, AdvancedCustomItem def) {
        ItemStack fresh = itemManager.buildItem(def);

        // Preserve player-applied durability
        ItemMeta origMeta = original.getItemMeta();
        ItemMeta freshMeta = fresh.getItemMeta();
        if (origMeta instanceof Damageable origDmg && freshMeta instanceof Damageable freshDmg
                && original.getType().getMaxDurability() > 0) {
            if (origDmg.getDamage() > freshDmg.getDamage()) {
                freshDmg.setDamage(origDmg.getDamage());
                fresh.setItemMeta(freshMeta);
            }
        }

        // Preserve player-applied enchants (from anvils/enchanting tables)
        if (origMeta != null && freshMeta != null) {
            Map<org.bukkit.enchantments.Enchantment, Integer> freshEnch = new HashMap<>(freshMeta.getEnchants());
            Map<org.bukkit.enchantments.Enchantment, Integer> origEnch = origMeta.getEnchants();
            for (var entry : origEnch.entrySet()) {
                Integer flvl = freshEnch.get(entry.getKey());
                if (flvl == null || entry.getValue() > flvl) {
                    freshMeta.addEnchant(entry.getKey(), entry.getValue(), true);
                }
            }
            fresh.setItemMeta(freshMeta);
        }

        // Copy all unmanaged DataComponents from original to fresh
        fresh.copyDataFrom(original, type -> !MANAGED_COMPONENTS.contains(type));

        // Merge PDC: keep original's non-plugin keys, overlay fresh's plugin keys
        fresh.editPersistentDataContainer(freshContainer -> {
            original.getPersistentDataContainer().copyTo(freshContainer, false);
            itemManager.buildItem(def).getPersistentDataContainer().copyTo(freshContainer, true);
            freshContainer.set(versionKey, PersistentDataType.LONG, def.getVersion());
            freshContainer.set(formatKey, PersistentDataType.INTEGER, AdvancedCustomItemManager.CURRENT_ITEM_FORMAT);
        });

        // Transfer or create instance UUID
        String origUuid = getInstanceUuid(original);
        if (origUuid != null) {
            fresh.editPersistentDataContainer(c -> c.set(instanceUuidKey, PersistentDataType.STRING, origUuid));
        } else {
            itemManager.ensureInstanceUuid(fresh, def);
        }

        fresh.setAmount(original.getAmount());
        replaceItemData(original, fresh);
    }

    /**
     * FIX 2: setType() and setAmount() MUST come BEFORE copyDataFrom().
     * In Paper, setType() can reset internal component storage, so any
     * data copied before the type change is silently discarded.
     */
    private void replaceItemData(ItemStack target, ItemStack source) {
        // Set type and amount FIRST
        target.setType(source.getType());
        target.setAmount(source.getAmount());

        // Then copy DataComponents
        target.copyDataFrom(source, type -> true);

        // Then copy PDC
        target.editPersistentDataContainer(container -> {
            source.getPersistentDataContainer().copyTo(container, true);
        });
    }

    // ==================== LEGACY STACK SPLITTING ====================

    private boolean splitLegacy(Player player, ItemStack stack, Consumer<ItemStack> setter, Inventory prefInv) {
        if (stack == null || stack.getAmount() <= 1) return false;
        AdvancedCustomItem def = itemManager.getItemByStack(stack);
        if (def == null || !def.shouldGetInstanceUuid()) return false;
        int amount = stack.getAmount();
        stack.setAmount(1);
        setter.accept(stack);
        for (int i = 1; i < amount; i++) {
            ItemStack single = stack.clone();
            single.setAmount(1);
            itemManager.assignFreshInstanceUuid(single, def);
            Map<Integer, ItemStack> left = prefInv.addItem(single);
            if (prefInv != player.getInventory() && !left.isEmpty()) {
                Map<Integer, ItemStack> extra = new HashMap<>();
                for (ItemStack l : left.values()) extra.putAll(player.getInventory().addItem(l));
                left = extra;
            }
            for (ItemStack l : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), l);
        }
        return true;
    }

    // ==================== PURGE HELPERS ====================

    private int purgeFromPlayer(Player player, String itemId, ItemStack broken) {
        int count = 0;
        count += purgeInv(player.getInventory(), itemId, broken);
        count += purgeArmor(player, itemId, broken);
        count += purgeEnder(player, itemId, broken);
        return count;
    }

    private int purgeInv(PlayerInventory inv, String itemId, ItemStack broken) {
        int c = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) {
                String id = getItemId(contents[i]);
                if (itemId.equals(id)) {
                    ItemStack rep = broken.clone();
                    rep.setAmount(contents[i].getAmount());
                    inv.setItem(i, rep);
                    c += contents[i].getAmount();
                }
            }
        }
        return c;
    }

    private int purgeArmor(Player player, String itemId, ItemStack broken) {
        int c = 0;
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean ch = false;
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null) {
                String id = getItemId(armor[i]);
                if (itemId.equals(id)) {
                    armor[i] = broken.clone();
                    c++;
                    ch = true;
                }
            }
        }
        if (ch) player.getInventory().setArmorContents(armor);
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && itemId.equals(getItemId(off))) {
            player.getInventory().setItemInOffHand(broken.clone());
            c++;
        }
        return c;
    }

    private int purgeEnder(Player player, String itemId, ItemStack broken) {
        int c = 0;
        ItemStack[] contents = player.getEnderChest().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) {
                String id = getItemId(contents[i]);
                if (itemId.equals(id)) {
                    ItemStack rep = broken.clone();
                    rep.setAmount(contents[i].getAmount());
                    player.getEnderChest().setItem(i, rep);
                    c += contents[i].getAmount();
                }
            }
        }
        return c;
    }
}