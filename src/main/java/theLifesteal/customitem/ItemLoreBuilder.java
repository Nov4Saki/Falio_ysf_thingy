package theLifesteal.customitem;

import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityManager;
import theLifesteal.abilities.ItemAbilityType;

import java.util.*;

public class ItemLoreBuilder {

    public enum Rarity {
        COMMON("Common", "&7"),
        UNCOMMON("Uncommon", "&a"),
        RARE("Rare", "&b"),
        EPIC("Epic", "&5"),
        LEGENDARY("Legendary", "&6"),
        MYTHIC("Mythic", "&d"),
        UNOBTAINABLE("Unobtainable", "&4");

        private final String displayName;
        private final String colorCode;

        Rarity(String displayName, String colorCode) {
            this.displayName = displayName;
            this.colorCode = colorCode;
        }

        public String getDisplayName() { return displayName; }
        public String getColorCode() { return colorCode; }

        public static Rarity fromDisplayName(String name) {
            for (Rarity r : values()) {
                if (r.getDisplayName().equalsIgnoreCase(name)) return r;
            }
            return COMMON;
        }
    }

    private static final Map<Attribute, AttributeDisplay> ATTRIBUTE_DISPLAYS = new LinkedHashMap<>();

    static {
        ATTRIBUTE_DISPLAYS.put(Attribute.ARMOR, new AttributeDisplay("&b🛡 Armor", "&b"));
        ATTRIBUTE_DISPLAYS.put(Attribute.ARMOR_TOUGHNESS, new AttributeDisplay("&b🛡 Toughness", "&b"));
        ATTRIBUTE_DISPLAYS.put(Attribute.ATTACK_DAMAGE, new AttributeDisplay("&c🗡 Attack Damage", "&c"));
        ATTRIBUTE_DISPLAYS.put(Attribute.ATTACK_KNOCKBACK, new AttributeDisplay("&c💢 Knockback", "&c"));
       // ATTRIBUTE_DISPLAYS.put(Attribute.ATTACK_SPEED, new AttributeDisplay("&e⚡ Attack Speed", "&e"));
        ATTRIBUTE_DISPLAYS.put(Attribute.BLOCK_BREAK_SPEED, new AttributeDisplay("&e⛏ Mining Speed", "&e"));
        ATTRIBUTE_DISPLAYS.put(Attribute.BLOCK_INTERACTION_RANGE, new AttributeDisplay("&6📏 Reach", "&6"));
        ATTRIBUTE_DISPLAYS.put(Attribute.ENTITY_INTERACTION_RANGE, new AttributeDisplay("&6📏 Entity Reach", "&6"));
        ATTRIBUTE_DISPLAYS.put(Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, new AttributeDisplay("&7💥 Explosion Resist", "&7"));
        ATTRIBUTE_DISPLAYS.put(Attribute.FALL_DAMAGE_MULTIPLIER, new AttributeDisplay("&7🍂 Fall Damage Multiplier", "&7"));
        ATTRIBUTE_DISPLAYS.put(Attribute.GRAVITY, new AttributeDisplay("&7🌍 Gravity", "&7"));
        ATTRIBUTE_DISPLAYS.put(Attribute.JUMP_STRENGTH, new AttributeDisplay("&a🐇 Jump Strength", "&a"));
        ATTRIBUTE_DISPLAYS.put(Attribute.KNOCKBACK_RESISTANCE, new AttributeDisplay("&7🦶 Knockback Resist", "&7"));
        ATTRIBUTE_DISPLAYS.put(Attribute.LUCK, new AttributeDisplay("&e🍀 Luck", "&e"));
        ATTRIBUTE_DISPLAYS.put(Attribute.MAX_HEALTH, new AttributeDisplay("&c❤ Max Health", "&c"));
        ATTRIBUTE_DISPLAYS.put(Attribute.MOVEMENT_SPEED, new AttributeDisplay("&a💨 Speed", "&a"));
        ATTRIBUTE_DISPLAYS.put(Attribute.OXYGEN_BONUS, new AttributeDisplay("&b🫧 Oxygen Bonus", "&b"));
        ATTRIBUTE_DISPLAYS.put(Attribute.SAFE_FALL_DISTANCE, new AttributeDisplay("&a🍂 Safe Fall Distance", "&a"));
        ATTRIBUTE_DISPLAYS.put(Attribute.SCALE, new AttributeDisplay("&5📐 Scale", "&5"));
        ATTRIBUTE_DISPLAYS.put(Attribute.STEP_HEIGHT, new AttributeDisplay("&a📶 Step Height", "&a"));
        ATTRIBUTE_DISPLAYS.put(Attribute.WATER_MOVEMENT_EFFICIENCY, new AttributeDisplay("&3🌊 Water Movement", "&3"));
        ATTRIBUTE_DISPLAYS.put(Attribute.SNEAKING_SPEED, new AttributeDisplay("&7👟 Sneaking Speed", "&7"));
        ATTRIBUTE_DISPLAYS.put(Attribute.SUBMERGED_MINING_SPEED, new AttributeDisplay("&3⛏ Underwater Mining", "&3"));
        ATTRIBUTE_DISPLAYS.put(Attribute.SWEEPING_DAMAGE_RATIO, new AttributeDisplay("&c⚔ Sweeping Damage", "&c"));
    }

    private static class AttributeDisplay {
        final String label;
        final String color;

        AttributeDisplay(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

    /**
     * Build the full lore list.
     * Order: enchants, blank, attributes, potion effects, abilities, blank, custom lore, blank, category, rarity.
     */
    public static List<String> buildLore(AdvancedCustomItem item, ItemAbilityManager abilityManager) {
        List<String> lore = new ArrayList<>();
        boolean hasContent = false;

        // 0. CONSUMABLE warning (NEW - shown at top if flag is set)
        if (item.hasFlag(CustomItemFlag.CONSUMABLE)) {
            lore.add(ColorUtils.colorize("&c⚠ &4&lCONSUMABLE &c⚠"));
            lore.add(ColorUtils.colorize("&7This item will be destroyed"));
            lore.add(ColorUtils.colorize("&7after a successful ability use."));
            lore.add("");
            hasContent = true;
        }

        // 1. Enchantments section
        List<String> enchantLines = buildEnchantLines(item);
        if (!enchantLines.isEmpty()) {
            if (hasContent) lore.add("");
            lore.addAll(enchantLines);
            hasContent = true;
        }

        // 2. Attributes section
        List<String> attrLines = buildAttributeLines(item);
        if (!attrLines.isEmpty()) {
            if (hasContent) lore.add("");
            lore.addAll(attrLines);
            hasContent = true;
        }

        // 3. Potion Effects section
        List<String> potionLines = buildPotionEffectLines(item);
        if (!potionLines.isEmpty()) {
            if (hasContent) lore.add("");
            lore.addAll(potionLines);
            hasContent = true;
        }

        // 4. Abilities section
        List<String> abilityLines = buildAbilityLines(item, abilityManager);
        if (!abilityLines.isEmpty()) {
            if (hasContent) lore.add("");
            lore.addAll(abilityLines);
            hasContent = true;
        }

        // 5. Blank line before custom lore
        if (hasContent) lore.add("");

        // 6. Custom lore
        List<String> customLore = item.getLore();
        if (!customLore.isEmpty()) {
            for (String line : customLore) {
                lore.add(ColorUtils.colorize(line));
            }
        }

        // 7. Blank line before category
        lore.add("");

        // 8. Category line
        String category = item.getCategory();
        lore.add(ColorUtils.colorize("&b&o✚ " + category));

        // 9. Rarity line
        Rarity rarity = item.getRarity();
        String rarityLine = rarity.getColorCode() + "✦ " + rarity.getDisplayName() + " ✦";
        if (rarity == Rarity.UNOBTAINABLE) {
            rarityLine = rarity.getColorCode() + "&m✦ " + rarity.getDisplayName() + " ✦";
        }
        lore.add(ColorUtils.colorize(rarityLine));

        return lore;
    }

    public static List<String> buildEnchantLines(AdvancedCustomItem item) {
        List<String> lines = new ArrayList<>();
        Map<Enchantment, Integer> enchants = item.getEnchants();
        if (enchants.isEmpty()) return lines;

        lines.add(ColorUtils.colorize("&5&l✦ Enchantments"));
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            String name = formatEnchantName(entry.getKey());
            String level = toRoman(entry.getValue());
            lines.add(ColorUtils.colorize("&7" + name + " &5" + level));
        }
        return lines;
    }

    public static List<String> buildAttributeLines(AdvancedCustomItem item) {
        List<String> lines = new ArrayList<>();
        Map<Attribute, Double> attributes = item.getAttributes();

        for (Map.Entry<Attribute, AttributeDisplay> entry : ATTRIBUTE_DISPLAYS.entrySet()) {
            Attribute attr = entry.getKey();
            AttributeDisplay display = entry.getValue();

            if (attributes.containsKey(attr) && attributes.get(attr) != 0) {
                double value = attributes.get(attr);
                String valueStr;
                if (value >= 0) {
                    valueStr = "&f+" + formatValue(value);
                } else {
                    valueStr = "&c" + formatValue(value);
                }
                lines.add(ColorUtils.colorize("&l" + display.label + ": " + valueStr));
            }
        }
        return lines;
    }

    public static List<String> buildPotionEffectLines(AdvancedCustomItem item) {
        List<String> lines = new ArrayList<>();
        List<AdvancedCustomItem.PotionEffectData> effects = item.getPotionEffects();
        if (effects.isEmpty()) return lines;

        lines.add(ColorUtils.colorize("&5&l🧪 Potion Effects"));
        for (AdvancedCustomItem.PotionEffectData effect : effects) {
            String effectName = formatPotionName(effect.getType());
            int level = effect.getAmplifier() + 1;
            lines.add(ColorUtils.colorize("&d" + effectName + " &5: &f" + level));
        }
        return lines;
    }

    public static List<String> buildAbilityLines(AdvancedCustomItem item, ItemAbilityManager abilityManager) {
        if (abilityManager == null) return new ArrayList<>();
        return abilityManager.buildAbilityLore(item.getAbilities());
    }

    private static String formatValue(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((int) value);
        }
        String formatted = String.format("%.2f", value);
        formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        return formatted;
    }

    private static String formatPotionName(org.bukkit.potion.PotionEffectType type) {
        String key = type.getKey().getKey();
        String[] words = key.replace("_", " ").split(" ");
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

    private static String formatEnchantName(Enchantment ench) {
        String key = ench.getKey().getKey();
        String[] words = key.replace("_", " ").split(" ");
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

    private static String toRoman(int num) {
        String[] romans = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
                "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"};
        if (num > 0 && num < romans.length) return romans[num];
        return String.valueOf(num);
    }

    public static String getRarityColor(Rarity rarity) {
        return rarity.getColorCode();
    }

    public static List<String> getCategoryNames() {
        return Arrays.asList("Weapons", "Armor", "Tools", "Items", "Food", "Blocks", "Misc");
    }

    public static List<String> getRarityDisplayNames() {
        List<String> names = new ArrayList<>();
        for (Rarity r : Rarity.values()) {
            names.add(r.getDisplayName());
        }
        return names;
    }
}