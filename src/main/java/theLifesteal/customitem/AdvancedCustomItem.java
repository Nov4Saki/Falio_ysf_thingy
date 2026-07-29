package theLifesteal.customitem;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;

import java.util.*;

public class AdvancedCustomItem {

    public static final Set<String> NON_STACKABLE_CATEGORIES = Set.of(
            "Weapons", "Armor", "Tools"
    );

    private final String id;
    private ItemStack baseItem;
    private Material visualItemType;
    private String displayName;
    private List<String> lore;
    private Map<Attribute, Double> attributes;
    private EnumSet<CustomItemFlag> flags;
    private int customModelData;
    private NamespacedKey itemModel;
    private int damage;
    private Map<String, Object> futureExtensions;
    private List<PotionEffectData> potionEffects;
    private String category;
    private ItemLoreBuilder.Rarity rarity;
    private Map<ItemAbilityType, List<ItemAbilityData>> abilities;
    private Map<Enchantment, Integer> enchants;
    private ArmorPiece armorPiece;
    private long version;
    private boolean disabled;

    public AdvancedCustomItem(String id, ItemStack baseItem) {
        this.id = id;
        this.baseItem = baseItem.clone();
        this.visualItemType = baseItem.getType();
        this.displayName = null;
        this.lore = new ArrayList<>();
        this.attributes = new HashMap<>();
        this.flags = EnumSet.noneOf(CustomItemFlag.class);
        this.customModelData = 0;
        this.itemModel = null;
        this.damage = 0;
        this.futureExtensions = new HashMap<>();
        this.potionEffects = new ArrayList<>();
        this.category = "Misc";
        this.rarity = ItemLoreBuilder.Rarity.COMMON;
        this.abilities = new LinkedHashMap<>();
        for (ItemAbilityType type : ItemAbilityType.values()) {
            this.abilities.put(type, new ArrayList<>());
        }
        this.enchants = new LinkedHashMap<>();
        this.armorPiece = null;
        this.version = 1L;
        this.disabled = false;
    }

    public String getId() { return id; }
    public ItemStack getBaseItem() { return baseItem.clone(); }
    public void setBaseItem(ItemStack baseItem) { this.baseItem = baseItem.clone(); }
    public Material getVisualItemType() { return visualItemType; }
    public void setVisualItemType(Material visualItemType) { this.visualItemType = visualItemType; }

    public boolean shouldGetInstanceUuid() {
        // Equipment categories must always be unique, even when a stale or
        // manually-added NO_INSTANCE_UUID flag exists on an old definition.
        return isNonStackableCategory(category);
    }

    public static boolean isNonStackableCategory(String category) {
        if (category == null) return false;
        return NON_STACKABLE_CATEGORIES.stream().anyMatch(category::equalsIgnoreCase);
    }

    public static boolean isArmorCategory(String category) {
        return category != null && "Armor".equalsIgnoreCase(category);
    }

    public static ItemStack stripVanillaStats(ItemStack item) {
        ItemStack clean = item.clone();
        ItemStack original = clean.clone();
        org.bukkit.inventory.meta.ItemMeta meta = clean.getItemMeta();
        if (meta == null) return clean;
        meta.setDisplayName(null);
        meta.setLore(null);
        if (clean.getType().getMaxDurability() > 0) {
            ((org.bukkit.inventory.meta.Damageable) meta).setDamage(0);
        }
        meta.setUnbreakable(false);
        for (Attribute attr : Attribute.values()) {
            meta.removeAttributeModifier(attr);
        }
        for (Enchantment ench : meta.getEnchants().keySet()) {
            meta.removeEnchant(ench);
        }
        meta.removeItemFlags(org.bukkit.inventory.ItemFlag.values());
        clean.setItemMeta(meta);
        ItemComponentUtil.preserveUnmanagedComponents(clean, original);
        return clean;
    }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public List<String> getLore() { return new ArrayList<>(lore); }
    public void setLore(List<String> lore) { this.lore = new ArrayList<>(lore); }
    public Map<Attribute, Double> getAttributes() { return new HashMap<>(attributes); }
    public void setAttributes(Map<Attribute, Double> attributes) { this.attributes = new HashMap<>(attributes); }
    public void addAttribute(Attribute attribute, double value) { this.attributes.put(attribute, value); }
    public void removeAttribute(Attribute attribute) { this.attributes.remove(attribute); }
    public EnumSet<CustomItemFlag> getFlags() { return EnumSet.copyOf(flags); }
    public void setFlags(EnumSet<CustomItemFlag> flags) { this.flags = EnumSet.copyOf(flags); }
    public boolean hasFlag(CustomItemFlag flag) { return flags.contains(flag); }
    public void addFlag(CustomItemFlag flag) { if (flag != null) flags.add(flag); }
    public void removeFlag(CustomItemFlag flag) { if (flag != null) flags.remove(flag); }
    public void toggleFlag(CustomItemFlag flag) {
        if (flags.contains(flag)) flags.remove(flag);
        else flags.add(flag);
    }
    public int getCustomModelData() { return customModelData; }
    public void setCustomModelData(int customModelData) { this.customModelData = customModelData; }
    public NamespacedKey getItemModel() { return itemModel; }
    public void setItemModel(NamespacedKey itemModel) { this.itemModel = itemModel; }
    public int getDamage() { return damage; }
    public void setDamage(int damage) { this.damage = Math.max(0, damage); }
    public Map<String, Object> getFutureExtensions() { return new HashMap<>(futureExtensions); }
    public void setFutureExtensions(Map<String, Object> futureExtensions) { this.futureExtensions = new HashMap<>(futureExtensions); }

    public List<PotionEffectData> getPotionEffects() { return new ArrayList<>(potionEffects); }
    public void setPotionEffects(List<PotionEffectData> effects) { this.potionEffects = new ArrayList<>(effects); }
    public void addPotionEffect(PotionEffectData effect) { this.potionEffects.add(effect); }
    public void removePotionEffect(int index) {
        if (index >= 0 && index < potionEffects.size()) potionEffects.remove(index);
    }
    public void clearPotionEffects() { this.potionEffects.clear(); }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public ItemLoreBuilder.Rarity getRarity() { return rarity; }
    public void setRarity(ItemLoreBuilder.Rarity rarity) { this.rarity = rarity; }

    public Map<ItemAbilityType, List<ItemAbilityData>> getAbilities() { return abilities; }
    public void setAbilities(Map<ItemAbilityType, List<ItemAbilityData>> abilities) { this.abilities = abilities; }

    public Map<Enchantment, Integer> getEnchants() { return enchants; }
    public void setEnchants(Map<Enchantment, Integer> enchants) { this.enchants = new LinkedHashMap<>(enchants); }

    public ArmorPiece getArmorPiece() { return armorPiece; }
    public void setArmorPiece(ArmorPiece armorPiece) { this.armorPiece = armorPiece; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public void incrementVersion() { this.version++; }

    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }

    public ItemStack buildBrokenReplacement() {
        ItemStack broken = new ItemStack(visualItemType);
        org.bukkit.inventory.meta.ItemMeta meta = broken.getItemMeta();
        if (meta == null) return broken;
        String name = displayName != null ? displayName : formatMaterialName(visualItemType);
        meta.setDisplayName(theLifesteal.ColorUtils.colorize("&8" + name));
        List<String> brokenLore = new ArrayList<>();
        brokenLore.add(theLifesteal.ColorUtils.colorize("&c&m-------------------"));
        brokenLore.add(theLifesteal.ColorUtils.colorize("&4&lBROKEN ITEM"));
        brokenLore.add(theLifesteal.ColorUtils.colorize("&7This item has been disabled"));
        brokenLore.add(theLifesteal.ColorUtils.colorize("&7by the server administrators."));
        brokenLore.add("");
        brokenLore.add(theLifesteal.ColorUtils.colorize("&eContact support for an item refund."));
        brokenLore.add(theLifesteal.ColorUtils.colorize("&c&m-------------------"));
        meta.setLore(brokenLore);
        broken.setItemMeta(meta);
        return broken;
    }

    public AdvancedCustomItem clone() {
        AdvancedCustomItem clone = new AdvancedCustomItem(this.id, this.baseItem);
        clone.setVisualItemType(this.visualItemType);
        clone.setDisplayName(this.displayName);
        clone.setLore(this.lore);
        clone.setAttributes(this.attributes);
        clone.setFlags(this.flags);
        clone.setCustomModelData(this.customModelData);
        clone.setItemModel(this.itemModel);
        clone.setDamage(this.damage);
        clone.setFutureExtensions(this.futureExtensions);
        clone.setPotionEffects(this.potionEffects);
        clone.setCategory(this.category);
        clone.setRarity(this.rarity);
        clone.setVersion(this.version);
        clone.setDisabled(this.disabled);
        Map<ItemAbilityType, List<ItemAbilityData>> abilitiesCopy = new LinkedHashMap<>();
        for (Map.Entry<ItemAbilityType, List<ItemAbilityData>> entry : this.abilities.entrySet()) {
            List<ItemAbilityData> listCopy = new ArrayList<>();
            for (ItemAbilityData data : entry.getValue()) {
                ItemAbilityData dataCopy = new ItemAbilityData(data.getAbilityId(), data.getType());
                dataCopy.setConfig(new LinkedHashMap<>(data.getConfig()));
                listCopy.add(dataCopy);
            }
            abilitiesCopy.put(entry.getKey(), listCopy);
        }
        clone.setAbilities(abilitiesCopy);
        clone.setEnchants(new LinkedHashMap<>(this.enchants));
        clone.setArmorPiece(this.armorPiece);
        return clone;
    }

    private String formatMaterialName(Material mat) {
        String name = mat.name().replace("_", " ").toLowerCase();
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1));
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }

    public static class PotionEffectData {
        private final PotionEffectType type;
        private final int amplifier;
        private final boolean showParticles;

        public PotionEffectData(PotionEffectType type, int amplifier, boolean showParticles) {
            this.type = type;
            this.amplifier = amplifier;
            this.showParticles = showParticles;
        }

        public PotionEffectType getType() { return type; }
        public int getAmplifier() { return amplifier; }
        public boolean showParticles() { return showParticles; }

        public PotionEffect toEffect(int duration) {
            return new PotionEffect(type, duration, amplifier, false, showParticles, true);
        }

        public String serialize() {
            return type.getKey().getKey() + ":" + amplifier + ":" + showParticles;
        }

        public static PotionEffectData deserialize(String data) {
            String[] parts = data.split(":");
            if (parts.length < 3) return null;
            PotionEffectType type = PotionEffectType.getByKey(org.bukkit.NamespacedKey.minecraft(parts[0]));
            if (type == null) return null;
            try {
                int amplifier = Integer.parseInt(parts[1]);
                boolean particles = Boolean.parseBoolean(parts[2]);
                return new PotionEffectData(type, amplifier, particles);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
