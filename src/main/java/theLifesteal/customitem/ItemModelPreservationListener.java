package theLifesteal.customitem;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Listener that ensures custom item models are preserved when items
 * are dropped or moved between containers. The item model component
 * can be stripped during serialization round-trips, so we re-apply
 * it from the YML definition whenever an item enters the world or
 * moves via hoppers.
 */
public class ItemModelPreservationListener implements Listener {

    private final JavaPlugin plugin;
    private final AdvancedCustomItemManager itemManager;
    private final ItemUpdateManager itemUpdateManager;
    private final NamespacedKey itemIdKey;

    public ItemModelPreservationListener(JavaPlugin plugin, AdvancedCustomItemManager itemManager) {
        this(plugin, itemManager, null);
    }

    public ItemModelPreservationListener(JavaPlugin plugin,
                                         AdvancedCustomItemManager itemManager,
                                         ItemUpdateManager itemUpdateManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.itemUpdateManager = itemUpdateManager;
        this.itemIdKey = itemManager.getItemIdKey();
    }

    /**
     * When an item drops into the world, re-apply the item model from the definition.
     * This fixes the issue where Paper strips the item_model data component
     * during the drop serialization round-trip.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item droppedItem = event.getEntity();
        ItemStack itemStack = droppedItem.getItemStack();

        boolean knownCustomItem = itemManager.getItemId(itemStack) != null;
        boolean repaired = itemUpdateManager != null && itemUpdateManager.repairItem(itemStack);
        boolean modelRestored = restoreItemModel(itemStack);

        // Always write a known custom stack back to the entity, even when its
        // version was current. This prevents the item entity's first
        // serialization round-trip from replacing it with a partial stack.
        if (knownCustomItem || repaired || modelRestored) {
            droppedItem.setItemStack(itemStack);
        }
    }

    /**
     * PlayerDropItemEvent runs at the boundary between the inventory stack and
     * the item entity. Repair here as well as at ItemSpawnEvent because some
     * server implementations perform the component serialization after the
     * spawn callback has already completed.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        ItemStack itemStack = item.getItemStack();
        boolean knownCustomItem = itemManager.getItemId(itemStack) != null;
        boolean repaired = itemUpdateManager != null && itemUpdateManager.repairItem(itemStack);
        boolean modelRestored = restoreItemModel(itemStack);

        if (knownCustomItem || repaired || modelRestored) {
            item.setItemStack(itemStack);
        }
    }

    /**
     * When a hopper or similar moves an item between containers,
     * re-apply the model after the move.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        boolean repaired = itemUpdateManager != null && itemUpdateManager.repairItem(item);
        boolean modelRestored = restoreItemModel(item);
        if (repaired || modelRestored) {
            event.setItem(item);
        }
    }

    /**
     * Check if an ItemStack is a custom item with a defined item model,
     * and re-apply the model if it's missing or incorrect.
     *
     * @param itemStack the ItemStack to check and fix
     * @return true if the item was modified
     */
    private boolean restoreItemModel(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }

        // Get the custom item ID from PDC
        String itemId = itemStack.getPersistentDataContainer()
                .get(itemIdKey, PersistentDataType.STRING);
        if (itemId == null) {
            return false;
        }

        // Look up the definition
        AdvancedCustomItem definition = itemManager.getItem(itemId);
        if (definition == null) {
            return false;
        }

        // Check if this item has a custom model defined
        NamespacedKey definedModel = definition.getItemModel();
        if (definedModel == null) {
            return false; // No custom model, nothing to restore
        }

        // Check if model is missing or wrong
        Key model = Key.key(definedModel.getNamespace(), definedModel.getKey());
        Key currentModel = itemStack.getData(DataComponentTypes.ITEM_MODEL);
        if (!model.equals(currentModel)) {
            itemStack.setData(DataComponentTypes.ITEM_MODEL, model);
            itemStack.editPersistentDataContainer(container ->
                    container.set(
                            itemManager.getModelBackupKey(),
                            PersistentDataType.STRING,
                            definedModel.getNamespace() + ":" + definedModel.getKey()
                    ));
            return true;
        }

        return false; // Model is correct, nothing to do
    }
}
