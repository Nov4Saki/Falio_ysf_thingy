package theLifesteal.customitem;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import theLifesteal.util.FoliaScheduler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ItemUpdateManager implements Listener {

    private final JavaPlugin plugin;
    private final AdvancedCustomItemManager itemManager;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey formatKey;
    private final NamespacedKey instanceUuidKey;
    private final NamespacedKey reaperBonusKey;
    private final Set<UUID> pendingScans = ConcurrentHashMap.newKeySet();

    private boolean enabled;
    private boolean onJoin;
    private boolean onWorldChange;

    public ItemUpdateManager(JavaPlugin plugin, AdvancedCustomItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.itemIdKey = itemManager.getItemIdKey();
        this.versionKey = new NamespacedKey(plugin, "item_version");
        this.formatKey = itemManager.getFormatKey();
        this.instanceUuidKey = new NamespacedKey(plugin, "item_instance_uuid");
        this.reaperBonusKey = new NamespacedKey(plugin, "reaper_bonus_damage");
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
     * When a player picks up an item from the ground, scan inventory after 1 tick
     * so the item is fully merged.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!enabled) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // Repair before the item is merged into the inventory. This closes the
        // ground-item serialization gap; the delayed scan below remains as a
        // second safeguard for the final merged stack.
        ItemStack droppedStack = event.getItem().getItemStack();
        if (repairItem(droppedStack)) {
            event.getItem().setItemStack(droppedStack);
        }

        // Delay 1 tick so the item is fully merged into inventory before scanning
        scheduleInventoryScan(player, 1L);
    }

    /**
     * Repair a custom item immediately when a player drops it. The item entity
     * may be serialized separately from the inventory stack, so waiting for a
     * later pickup is too late for model/version components.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!enabled) return;

        ItemStack droppedStack = event.getItemDrop().getItemStack();
        if (repairItem(droppedStack)) {
            event.getItemDrop().setItemStack(droppedStack);
        }
    }

    /**
     * When items move in inventories, scan the player's inventory for outdated items.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv == null) return;

        boolean targetsPlayerInv = clickedInv instanceof PlayerInventory;
        boolean shiftClickToPlayer = event.isShiftClick() && event.getClickedInventory() != null
                && !(event.getClickedInventory() instanceof PlayerInventory);

        if (targetsPlayerInv || shiftClickToPlayer) {
            scheduleInventoryScan(player, 1L);
        }
    }

    /**
     * When items are dragged into the player's inventory, scan afterward.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean affectsPlayerInv = event.getInventorySlots().stream().anyMatch(slot -> {
            Inventory inv = event.getView().getInventory(slot);
            return inv instanceof PlayerInventory;
        });

        if (affectsPlayerInv) {
            scheduleInventoryScan(player, 1L);
        }
    }

    /**
     * Repair items after the inventory has been loaded during login. This is
     * intentionally a one-shot scan and does not introduce a background item
     * rewrite loop.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled || !onJoin) return;
        scheduleInventoryScan(event.getPlayer(), 5L);
    }

    /**
     * Repair items after a world transfer has completed. The entity scheduler
     * keeps this safe on Folia while the delayed scan avoids touching an
     * inventory during the transfer itself.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!enabled || !onWorldChange) return;
        scheduleInventoryScan(event.getPlayer(), 1L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingScans.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Coalesce inventory events for the same player into one entity-scoped
     * scan. Repeated click/drag/pickup events can otherwise queue multiple
     * full inventory rewrites in the same tick.
     */
    private void scheduleInventoryScan(Player player, long delayTicks) {
        if (player == null || !player.isOnline()) return;

        UUID playerId = player.getUniqueId();
        if (!pendingScans.add(playerId)) return;

        Runnable clearPending = () -> pendingScans.remove(playerId);
        FoliaScheduler.TaskHandle handle = FoliaScheduler.runEntityLater(
                player,
                plugin,
                () -> {
                    try {
                        if (player.isOnline()) {
                            scanInventory(player.getInventory());
                        }
                    } finally {
                        pendingScans.remove(playerId);
                    }
                },
                clearPending,
                delayTicks
        );

        if (handle == null) {
            pendingScans.remove(playerId);
        }
    }

    // ===== SCANNING =====

    /**
     * Repair one live stack using the same component-safe path used by
     * inventory updates. A missing version is intentionally treated as an
     * outdated item, provided the durable custom-item identity is still
     * present in the stack's custom data.
     */
    public boolean repairItem(ItemStack item) {
        if (!enabled) return false;
        return checkAndRefreshItem(item);
    }

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
            int slot = i;
            if (splitLegacyCategoryStack(player, contents[i], updatedStack -> inv.setItem(slot, updatedStack), inv)) {
                updated++;
            }
        }

        // Armor
        ItemStack[] armor = inv.getArmorContents();
        boolean armorChanged = false;
        for (int i = 0; i < armor.length; i++) {
            if (checkAndRefreshItem(armor[i])) {
                updated++;
                armorChanged = true;
            }
            int armorSlot = i;
            if (splitLegacyCategoryStack(player, armor[i], updatedStack -> armor[armorSlot] = updatedStack, inv)) {
                updated++;
                armorChanged = true;
            }
        }
        if (armorChanged) {
            inv.setArmorContents(armor);
        }

        // Offhand
        ItemStack offhand = inv.getItemInOffHand();
        if (checkAndRefreshItem(offhand)) {
            updated++;
            inv.setItemInOffHand(offhand);
        }
        if (splitLegacyCategoryStack(player, offhand, inv::setItemInOffHand, inv)) {
            updated++;
        }

        // Ender chest
        if (player.getEnderChest() != null) {
            ItemStack[] enderContents = player.getEnderChest().getContents();
            for (int i = 0; i < enderContents.length; i++) {
                if (checkAndRefreshItem(enderContents[i])) {
                    updated++;
                    player.getEnderChest().setItem(i, enderContents[i]);
                }
                int slot = i;
                if (splitLegacyCategoryStack(player, enderContents[i],
                        updatedStack -> player.getEnderChest().setItem(slot, updatedStack),
                        player.getEnderChest())) {
                    updated++;
                }
            }
        }

        if (updated > 0) {
            player.sendMessage(ColorUtils.colorize("&a⟳ &e" + updated + " &7item(s) updated to latest version."));
        }
    }

    /**
     * Existing servers can already contain a legacy stack of two or more
     * category items. Split it into individually identified stacks the first
     * time it is scanned, then vanilla can no longer merge those items.
     */
    private boolean splitLegacyCategoryStack(Player player, ItemStack stack,
                                             Consumer<ItemStack> sourceSetter,
                                             Inventory preferredInventory) {
        if (stack == null || stack.getAmount() <= 1) return false;

        AdvancedCustomItem definition = itemManager.getItemByStack(stack);
        if (definition == null || !definition.shouldGetInstanceUuid()) return false;

        int amount = stack.getAmount();
        stack.setAmount(1);
        sourceSetter.accept(stack);

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

    /**
     * Check a single ItemStack and refresh it if outdated, disabled, or missing its model.
     * Preserves player modifications (enchantments, durability, PDC keys).
     * @return true if the item was modified
     */
    private boolean checkAndRefreshItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        ItemMeta currentMeta = item.getItemMeta();
        String itemId = item.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
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

        // Check 1.21.4+ item model status
        Key currentModel = item.getData(DataComponentTypes.ITEM_MODEL);
        Key definedModel = definition.getItemModel() == null ? null :
                Key.key(definition.getItemModel().getNamespace(), definition.getItemModel().getKey());
        boolean needsModelFix = definedModel != null && !definedModel.equals(currentModel);
        boolean needsInstanceUuid = definition.shouldGetInstanceUuid()
                && itemManager.getInstanceUuid(item) == null;
        Integer storedFormat = item.getPersistentDataContainer().get(formatKey, PersistentDataType.INTEGER);
        boolean needsFormatUpgrade = storedFormat == null
                || storedFormat < AdvancedCustomItemManager.CURRENT_ITEM_FORMAT;

        // Version check
        Long storedVersion = item.getPersistentDataContainer().get(versionKey, PersistentDataType.LONG);
        long currentVersion = definition.getVersion();

        // If version is current AND model is fine, skip
        if (storedVersion != null && storedVersion >= currentVersion
                && !needsModelFix && !needsInstanceUuid && !needsFormatUpgrade) {
            return false;
        }

        // If the definition is current, patch only missing durable components
        // in-place. This includes the per-stack UUID used to prevent category
        // equipment from merging.
        if (storedVersion != null && storedVersion >= currentVersion && !needsFormatUpgrade) {
            boolean changed = false;
            if (needsModelFix) {
                item.setData(DataComponentTypes.ITEM_MODEL, definedModel);
                item.editPersistentDataContainer(container ->
                        container.set(
                                itemManager.getModelBackupKey(),
                                PersistentDataType.STRING,
                                definition.getItemModel().getNamespace() + ":" + definition.getItemModel().getKey()
                        ));
                changed = true;
            }
            if (needsInstanceUuid) {
                changed |= itemManager.ensureInstanceUuid(item, definition);
            }
            return changed;
        }

        // Otherwise, version is outdated or missing. Build the intended update,
        // but preserve every component from the live item that this plugin does
        // not own. This is deliberately a component-level update, not a
        // destructive ItemMeta replacement.
        ItemStack original = item.clone();
        ItemStack fresh = itemManager.buildItem(definition);
        ItemMeta freshMeta = fresh.getItemMeta();
        if (freshMeta != null) {
            // Preserve player enchantments (anvils, enchanting tables)
            Map<org.bukkit.enchantments.Enchantment, Integer> currentEnchants = currentMeta == null
                    ? Collections.emptyMap()
                    : currentMeta.getEnchants();
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : currentEnchants.entrySet()) {
                org.bukkit.enchantments.Enchantment ench = entry.getKey();
                int currentLevel = entry.getValue();
                int freshLevel = freshMeta.getEnchantLevel(ench);
                if (currentLevel > freshLevel) {
                    freshMeta.addEnchant(ench, currentLevel, true);
                }
            }

            // Preserve damage/durability
            if (currentMeta instanceof org.bukkit.inventory.meta.Damageable currentDamageable &&
                    freshMeta instanceof org.bukkit.inventory.meta.Damageable freshDamageable) {
                if (currentDamageable.hasDamage()) {
                    freshDamageable.setDamage(currentDamageable.getDamage());
                }
            }

            fresh.setItemMeta(freshMeta);

            // The ItemMeta write may discard components unknown to Bukkit.
            // Restore those from the live item, then overwrite only the
            // plugin-owned version/model values below.
            ItemComponentUtil.preserveUnmanagedComponents(fresh, original);
            ItemComponentUtil.copyPersistentData(fresh, original, true);
            if (definedModel != null) {
                fresh.setData(DataComponentTypes.ITEM_MODEL, definedModel);
            } else {
                fresh.unsetData(DataComponentTypes.ITEM_MODEL);
            }
            if (definition.getCustomModelData() != 0) {
                fresh.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                        io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                                .addFloat(definition.getCustomModelData())
                                .build());
            } else {
                fresh.unsetData(DataComponentTypes.CUSTOM_MODEL_DATA);
            }
            fresh.editPersistentDataContainer(container -> {
                container.set(itemIdKey, PersistentDataType.STRING, itemId);
                container.set(versionKey, PersistentDataType.LONG, currentVersion);
                container.set(formatKey, PersistentDataType.INTEGER,
                        AdvancedCustomItemManager.CURRENT_ITEM_FORMAT);
                if (definedModel != null) {
                    container.set(
                            itemManager.getModelBackupKey(),
                            PersistentDataType.STRING,
                            definition.getItemModel().getNamespace() + ":" + definition.getItemModel().getKey()
                    );
                } else {
                    container.remove(itemManager.getModelBackupKey());
                }
            });
            itemManager.ensureInstanceUuid(fresh, definition);

            replaceAllDataComponents(item, fresh);
            item.setAmount(original.getAmount());
            return true;
        }

        return false;
    }

    /**
     * Synchronize a live stack from a freshly built stack without calling
     * setItemMeta on the live stack. Components absent from the source are
     * removed, while every component present in the source is copied directly
     * through Paper's component API.
     */
    private void replaceAllDataComponents(ItemStack target, ItemStack source) {
        target.setType(source.getType());

        for (DataComponentType type : new HashSet<>(target.getDataTypes())) {
            if (!source.hasData(type)) target.unsetData(type);
        }
        target.copyDataFrom(source, type -> true);
        target.editPersistentDataContainer(container ->
                source.getPersistentDataContainer().copyTo(container, true));
    }

    // ===== ADMIN COMMANDS =====

    public int refreshAllPlayers() {
        int total = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            total += refreshSinglePlayer(player);
        }
        return total;
    }

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
        boolean armorChanged = false;
        for (int i = 0; i < armor.length; i++) {
            if (checkAndRefreshItem(armor[i])) {
                updated++;
                armorChanged = true;
            }
        }
        if (armorChanged) {
            player.getInventory().setArmorContents(armor);
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
