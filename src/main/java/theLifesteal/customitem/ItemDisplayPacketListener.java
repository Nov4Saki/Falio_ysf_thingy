package theLifesteal.customitem;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTooltipDisplay;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.TheLifesteal;
import theLifesteal.abilities.ItemAbilityManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final boolean DEBUG = false;

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
        try {
            if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                handleWindowItems(event);
            } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                handleSetSlot(event);
            }
        } catch (Exception e) {
            if (DEBUG) {
                plugin.getLogger().warning("[PacketLore] Error processing packet: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void handleWindowItems(PacketSendEvent event) {
        WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
        List<ItemStack> items = wrapper.getItems();
        boolean modified = false;

        if (DEBUG) {
            plugin.getLogger().info("[PacketLore] Processing WINDOW_ITEMS with " + items.size() + " items");
        }

        for (int i = 0; i < items.size(); i++) {
            ItemStack packetItem = items.get(i);
            if (packetItem == null || packetItem.isEmpty()) continue;

            ItemStack decorated = injectDisplay(packetItem);
            if (decorated != null) {
                items.set(i, decorated);
                modified = true;
            }
        }

        // Handle the carried (cursor) item - uses Optional<ItemStack>
        Optional<ItemStack> carriedOpt = wrapper.getCarriedItem();
        if (carriedOpt.isPresent()) {
            ItemStack carried = carriedOpt.get();
            if (carried != null && !carried.isEmpty()) {
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
        ItemStack packetItem = wrapper.getItem();

        if (packetItem == null || packetItem.isEmpty()) return;

        if (DEBUG) {
            plugin.getLogger().info("[PacketLore] Processing SET_SLOT");
        }

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

        // Try converting to Bukkit ItemStack first
        org.bukkit.inventory.ItemStack bukkitItem;
        try {
            bukkitItem = SpigotConversionUtil.toBukkitItemStack(packetItem);
        } catch (Exception e) {
            if (DEBUG) {
                plugin.getLogger().warning("[PacketLore] Failed to convert PacketEvents item to Bukkit: " + e.getMessage());
            }
            return null;
        }

        if (bukkitItem == null || bukkitItem.getType().isAir() || !bukkitItem.hasItemMeta()) {
            return null;
        }

        // Try reading custom_item_id from PDC
        String itemId = null;
        try {
            ItemMeta meta = bukkitItem.getItemMeta();
            if (meta != null) {
                itemId = meta.getPersistentDataContainer()
                        .get(itemIdKey, PersistentDataType.STRING);
            }
        } catch (Exception e) {
            if (DEBUG) {
                plugin.getLogger().warning("[PacketLore] Failed to read PDC: " + e.getMessage());
            }
        }

        // Fallback: try reading directly from PacketEvents NBT if PDC failed
        if (itemId == null) {
            try {
                itemId = readItemIdFromNBT(packetItem);
            } catch (Exception e) {
                if (DEBUG) {
                    plugin.getLogger().warning("[PacketLore] Failed to read NBT: " + e.getMessage());
                }
            }
        }

        if (itemId == null) {
            return null; // Not a custom item, no lore to inject
        }

        // Look up definition
        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) {
            if (DEBUG) {
                plugin.getLogger().warning("[PacketLore] Unknown item ID: " + itemId);
            }
            return null;
        }

        // Get or build cached lore
        List<Component> loreComponents = getOrBuildLore(itemId, definition);

        /*
         * Do not convert the decorated Bukkit stack back into a PacketEvents
         * stack here.  That conversion is lossy for newer item components on
         * 1.21.x.  In particular, it can drop CUSTOM_DATA (where the item ID
         * lives) and ITEM_MODEL while retaining the lore that was just added.
         *
         * A client can send an inventory/creative action after receiving this
         * packet.  If the packet was lossy, that client-visible stack can then
         * become the real server-side stack: exactly the corruption this
         * listener must never cause.
         *
         * Copy the original PacketEvents stack instead and replace only its
         * display component.  All custom data and every other component are
         * retained by the copy.
         */
        try {
            ItemStack result = packetItem.copy();
            result.setComponent(ComponentTypes.LORE, new ItemLore(loreComponents));
            suppressVanillaTooltipSections(result);

            if (DEBUG) {
                plugin.getLogger().info("[PacketLore] Successfully injected lore for item: " + itemId);
            }
            return result;
        } catch (Exception e) {
            if (DEBUG) {
                plugin.getLogger().warning("[PacketLore] Failed to inject lore component: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Keep the old ItemMeta flag behavior without doing an ItemStack
     * conversion.  The conversion was the data-loss bug; the tooltip display
     * component is the lossless PacketEvents equivalent of those flags.
     */
    private void suppressVanillaTooltipSections(ItemStack item) {
        ItemTooltipDisplay tooltip = item.getComponent(ComponentTypes.TOOLTIP_DISPLAY)
                .orElseGet(() -> new ItemTooltipDisplay(false, new HashSet<>()));

        Set<ComponentType<?>> hidden = new HashSet<>(tooltip.getHiddenComponents());
        hidden.add(ComponentTypes.ATTRIBUTE_MODIFIERS);
        hidden.add(ComponentTypes.ENCHANTMENTS);
        hidden.add(ComponentTypes.TRIM);
        hidden.add(ComponentTypes.DYED_COLOR);
        hidden.add(ComponentTypes.CAN_BREAK);
        hidden.add(ComponentTypes.CAN_PLACE_ON);
        tooltip.setHiddenComponents(hidden);
        item.setComponent(ComponentTypes.TOOLTIP_DISPLAY, tooltip);
    }

    /**
     * Attempts to read the custom_item_id directly from the PacketEvents ItemStack's NBT
     * as a fallback when Bukkit PDC conversion doesn't work.
     */
    private String readItemIdFromNBT(ItemStack packetItem) {
        try {
            // The PDC is stored under: minecraft:custom_data -> PublicBukkitValues -> thelifesteal:custom_item_id
            // getComponent() returns Optional<NBTCompound>
            Optional<NBTCompound> customDataOpt =
                    packetItem.getComponent(
                            com.github.retrooper.packetevents.protocol.component.ComponentTypes.CUSTOM_DATA
                    );

            if (!customDataOpt.isPresent()) return null;

            NBTCompound customData = customDataOpt.get();
            if (customData == null) return null;

            NBTCompound bukkitValues =
                    customData.getCompoundTagOrNull("PublicBukkitValues");
            if (bukkitValues == null) return null;

            String value = bukkitValues.getStringTagValueOrNull("thelifesteal:custom_item_id");
            return value;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets lore from cache or builds it from the definition.
     */
    private List<Component> getOrBuildLore(String itemId, AdvancedCustomItem definition) {
        List<Component> cached = loreCache.get(itemId);
        if (cached != null) {
            return cached;
        }

        // Build lore from definition
        List<String> loreLines = ItemLoreBuilder.buildLore(definition, abilityManager);
        List<Component> components = new ArrayList<>(loreLines.size());
        for (String line : loreLines) {
            // Deserialize the legacy color-coded string to a Component
            Component component = LEGACY_SERIALIZER.deserialize(line);
            // Force italic: false to prevent Minecraft's default italic lore styling
            component = component.style(component.style().decoration(TextDecoration.ITALIC, false));
            components.add(component);
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
        plugin.getLogger().info("§a✓ Packet Lore Injection loaded!");
    }
}
