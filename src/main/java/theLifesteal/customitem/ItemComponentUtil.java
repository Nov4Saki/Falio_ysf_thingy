package theLifesteal.customitem;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Helpers for changing ItemMeta without deleting Paper/Minecraft data
 * components that Bukkit's ItemMeta API does not expose.
 *
 * FIX 5: CUSTOM_MODEL_DATA and ITEM_MODEL are REMOVED from META_MANAGED_COMPONENTS.
 * buildItem() sets them explicitly after the preserveUnmanagedComponents() call,
 * so removing them from the managed set only adds safety for other callers.
 */
public final class ItemComponentUtil {

    /**
     * Components that this plugin intentionally owns when it edits ItemMeta.
     * Every other component is copied back from the original stack after the
     * ItemMeta round-trip.
     *
     * NOTE: CUSTOM_MODEL_DATA and ITEM_MODEL are NOT in this set. They will
     * always be preserved from the source item unless the caller explicitly
     * overwrites them afterward (as buildItem() does).
     */
    private static final Set<DataComponentType> META_MANAGED_COMPONENTS = Set.of(
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

    private ItemComponentUtil() {}

    /**
     * Restore every component from {@code source} that is not deliberately
     * managed by the ItemMeta operation which produced {@code target}.
     *
     * CUSTOM_MODEL_DATA and ITEM_MODEL are NOT in the managed set, so they
     * will be preserved from the source. Callers that need to change them
     * must do so explicitly after this method returns.
     */
    public static void preserveUnmanagedComponents(ItemStack target, ItemStack source) {
        if (target == null || source == null) return;

        target.copyDataFrom(source, type -> !META_MANAGED_COMPONENTS.contains(type));

        // Persistent data is exposed separately from the component registry.
        // Merge it directly so custom NBT survives without another ItemMeta conversion.
        copyPersistentData(target, source, false);
    }

    /**
     * Merge persistent data with explicit control over whether source values
     * replace values already present on the target.
     */
    public static void copyPersistentData(ItemStack target, ItemStack source, boolean replaceExisting) {
        if (target == null || source == null) return;
        target.editPersistentDataContainer(container ->
                source.getPersistentDataContainer().copyTo(container, replaceExisting));
    }

    /**
     * Apply a metadata edit while preserving all non-ItemMeta components.
     *
     * This is the ONLY safe way to modify ItemMeta on a custom item.
     * NEVER call item.setItemMeta() directly — always use this method.
     */
    public static boolean editMetaPreservingComponents(ItemStack item, Consumer<ItemMeta> editor) {
        if (item == null || editor == null) return false;

        ItemStack before = item.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        editor.accept(meta);
        boolean changed = item.setItemMeta(meta);
        preserveUnmanagedComponents(item, before);
        return changed;
    }

    /**
     * Set an already-prepared ItemMeta while preserving all components that
     * are not represented by that metadata object.
     */
    public static boolean setMetaPreservingComponents(ItemStack item, ItemMeta meta) {
        if (item == null || meta == null) return false;

        ItemStack before = item.clone();
        boolean changed = item.setItemMeta(meta);
        preserveUnmanagedComponents(item, before);
        return changed;
    }
}