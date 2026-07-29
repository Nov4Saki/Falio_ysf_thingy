package theLifesteal.customitem;

public enum CustomItemFlag {
    GLOW,
    CONSUMABLE,         // Item is deleted after successful ability use
    UNBREAKABLE,
    HIDE_ENCHANTS,
    HIDE_ATTRIBUTES,
    HIDE_UNBREAKABLE,
    HIDE_DESTROYS,
    HIDE_PLACED_ON,
    HIDE_ADDITIONAL_TOOLTIP,
    HIDE_DYE,
    HIDE_ARMOR_TRIM,
    NO_INSTANCE_UUID, // Item can stack, no unique ID assigned (ignored for Weapons/Armor/Tools)
    OFFHAND_STATS     // Attribute stats apply in the offhand; disabled by default
}
