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
 */
public final class ItemComponentUtil {

    /**
     * Components that this plugin intentionally owns when it edits ItemMeta.
     * Every other component is copied back from the original stack after the
     * ItemMeta round-trip.
     */
    private static final Set<DataComponentType> META_MANAGED_COMPONENTS = Set.of(
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

    private ItemComponentUtil() {
    }

    /**
     * Restore every component from {@code source} that is not deliberately
     * managed by the ItemMeta operation which produced {@code target}.
     */
    public static void preserveUnmanagedComponents(ItemStack target, ItemStack source) {
        if (target == null || source == null) return;

        target.copyDataFrom(source, type -> !META_MANAGED_COMPONENTS.contains(type));

        // Persistent data is exposed separately from the component registry.
        // Merge it directly so custom NBT survives without another ItemMeta
        // conversion. Existing target values win because the target may have
        // just been deliberately edited by the caller.
        copyPersistentData(target, source, false);
    }

    /**
     * Merge persistent data with explicit control over whether source values
     * replace values already present on the target.
     */
    public static void copyPersistentData(ItemStack target, ItemStack source,
                                          boolean replaceExisting) {
        if (target == null || source == null) return;
        target.editPersistentDataContainer(container ->
                source.getPersistentDataContainer().copyTo(container, replaceExisting));
    }

    /**
     * Apply a metadata edit while preserving all non-ItemMeta components.
     */
    public static boolean editMetaPreservingComponents(ItemStack item,
                                                        Consumer<ItemMeta> editor) {
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
