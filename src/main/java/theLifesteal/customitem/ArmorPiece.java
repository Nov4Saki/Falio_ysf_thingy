package theLifesteal.customitem;

import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;

/**
 * The wearable slot assigned to an Armor-category custom item.
 */
public enum ArmorPiece {
    HELMET("Helmet", EquipmentSlot.HEAD, EquipmentSlotGroup.HEAD),
    CHESTPLATE("Chestplate", EquipmentSlot.CHEST, EquipmentSlotGroup.CHEST),
    LEGGINGS("Leggings", EquipmentSlot.LEGS, EquipmentSlotGroup.LEGS),
    BOOTS("Boots", EquipmentSlot.FEET, EquipmentSlotGroup.FEET);

    private final String displayName;
    private final EquipmentSlot equipmentSlot;
    private final EquipmentSlotGroup equipmentSlotGroup;

    ArmorPiece(String displayName, EquipmentSlot equipmentSlot,
               EquipmentSlotGroup equipmentSlotGroup) {
        this.displayName = displayName;
        this.equipmentSlot = equipmentSlot;
        this.equipmentSlotGroup = equipmentSlotGroup;
    }

    public String getDisplayName() {
        return displayName;
    }

    public EquipmentSlot getEquipmentSlot() {
        return equipmentSlot;
    }

    public EquipmentSlotGroup getEquipmentSlotGroup() {
        return equipmentSlotGroup;
    }
}
