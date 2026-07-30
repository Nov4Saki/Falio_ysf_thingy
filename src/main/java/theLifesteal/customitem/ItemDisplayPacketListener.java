package theLifesteal.customitem;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import theLifesteal.TheLifesteal;
import theLifesteal.abilities.ItemAbilityManager;
import java.util.Optional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts outgoing inventory packets and injects full rendered lore
 * onto custom items before they reach the client.
 *
 * The actual ItemStack in server memory/disk has NO lore — only PDC keys.
 * Lore is generated on-demand and exists only in network packets.
 */
public class ItemDisplayPacketListener extends PacketListenerAbstract {

    private final AdvancedCustomItemManager itemManager;
    private final ItemAbilityManager abilityManager;
    private final LoreCache loreCache;
    private final NamespacedKey itemIdKey;
    private final JavaPlugin plugin;

    // Track which windows we've already decorated to avoid duplicate work
    private final Set<Integer> recentlyDecoratedWindows = ConcurrentHashMap.newKeySet();

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    public ItemDisplayPacketListener(AdvancedCustomItemManager itemManager,
                                     ItemAbilityManager abilityManager) {
        super(PacketListenerPriority.HIGH);
        this.itemManager = itemManager;
        this.abilityManager = abilityManager;
        this.loreCache = new LoreCache();
        this.itemIdKey = itemManager.getItemIdKey();
        this.plugin = TheLifesteal.getInstance();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            handleWindowItems(event);
        } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            handleSetSlot(event);
        }
    }

    private void handleWindowItems(PacketSendEvent event) {
        WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
        int windowId = wrapper.getWindowId();

        // Mark this window as recently decorated
        recentlyDecoratedWindows.add(windowId);
        // Schedule cleanup after 5 ticks
        Bukkit.getGlobalRegionScheduler().runDelayed(
                plugin,
                task -> recentlyDecoratedWindows.remove(windowId),
                5L
        );

        List<ItemStack> items = wrapper.getItems();
        boolean modified = false;

        for (int i = 0; i < items.size(); i++) {
            ItemStack packetItem = items.get(i);
            if (packetItem == null || packetItem.isEmpty()) continue;

            ItemStack decorated = injectDisplay(packetItem);
            if (decorated != null) {
                items.set(i, decorated);
                modified = true;
            }
        }

        // Also handle the carried (cursor) item
        Optional<ItemStack> carriedOpt = wrapper.getCarriedItem();
        if (carriedOpt.isPresent()) {
            ItemStack carried = carriedOpt.get();
            if (!carried.isEmpty()) {
                ItemStack decoratedCarried = injectDisplay(carried);
                if (decoratedCarried != null) {
                    wrapper.setCarriedItem(decoratedCarried);
                    modified = true;
                }
            }
        }

        if (modified) {
            event.markForReEncode(true);
        }
    }

    private void handleSetSlot(PacketSendEvent event) {
        WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);

        // If this window was recently fully decorated via WINDOW_ITEMS,
        // skip individual SET_SLOT decoration to avoid duplicate work
        if (recentlyDecoratedWindows.contains(wrapper.getWindowId())) {
            return;
        }

        ItemStack packetItem = wrapper.getItem();
        if (packetItem == null || packetItem.isEmpty()) return;

        ItemStack decorated = injectDisplay(packetItem);
        if (decorated != null) {
            wrapper.setItem(decorated);
            event.markForReEncode(true);
        }
    }

    /**
     * Injects full rendered lore onto a custom item for display purposes.
     *
     * @param packetItem the PacketEvents ItemStack from the outgoing packet
     * @return a new ItemStack with lore injected, or null if no changes needed
     */
    private ItemStack injectDisplay(ItemStack packetItem) {
        if (packetItem == null || packetItem.isEmpty()) return null;

        // Convert to Bukkit ItemStack to read PDC
        org.bukkit.inventory.ItemStack bukkitItem;
        try {
            bukkitItem = SpigotConversionUtil.toBukkitItemStack(packetItem);
        } catch (Exception e) {
            return null;
        }

        if (bukkitItem == null || bukkitItem.getType().isAir() || !bukkitItem.hasItemMeta()) {
            return null;
        }

        // Read custom_item_id from PDC
        ItemMeta meta = bukkitItem.getItemMeta();
        if (meta == null) return null;

        String itemId = meta.getPersistentDataContainer()
                .get(itemIdKey, PersistentDataType.STRING);
        if (itemId == null) return null;

        // Look up definition
        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) return null;

        // Get or build cached lore
        List<Component> loreComponents = getOrBuildLore(itemId, definition);

        // Clone the Bukkit item for modification
        org.bukkit.inventory.ItemStack clone = bukkitItem.clone();
        ItemMeta cloneMeta = clone.getItemMeta();
        if (cloneMeta == null) return null;

        // Set the lore on the clone
        cloneMeta.lore(loreComponents);

        // Suppress vanilla tooltip sections that we render ourselves
        cloneMeta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_ARMOR_TRIM,
                ItemFlag.HIDE_DYE,
                ItemFlag.HIDE_DESTROYS,
                ItemFlag.HIDE_PLACED_ON
        );

        clone.setItemMeta(cloneMeta);

        // Convert back to PacketEvents ItemStack
        try {
            return SpigotConversionUtil.fromBukkitItemStack(clone);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets lore from cache or builds it from the definition.
     */
    private List<Component> getOrBuildLore(String itemId, AdvancedCustomItem definition) {
        List<Component> cached = loreCache.get(itemId);
        if (cached != null) return cached;

        // Build lore from definition
        List<String> loreLines = ItemLoreBuilder.buildLore(definition, abilityManager);
        List<Component> components = new ArrayList<>(loreLines.size());
        for (String line : loreLines) {
            // Use LegacyComponentSerializer to parse color codes
            components.add(LEGACY_SERIALIZER.deserialize(ColorUtils.colorize(line)));
        }

        loreCache.put(itemId, components);
        return components;
    }

    /**
     * Invalidate a specific item's cached lore (call when definition changes).
     */
    public void invalidateCache(String itemId) {
        loreCache.invalidate(itemId);
    }

    /**
     * Invalidate all cached lore (call on reload).
     */
    public void invalidateAllCache() {
        loreCache.invalidateAll();
    }

    /**
     * Register this listener with the PacketEvents event manager.
     */
    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }
}