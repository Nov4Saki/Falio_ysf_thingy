package theLifesteal.customitem;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.ItemAbilityManager;
import theLifesteal.util.FoliaScheduler;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AdvancedCustomItemManager {

    private final JavaPlugin plugin;
    private final File dataFile;
    private final Map<String, AdvancedCustomItem> items;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey instanceUuidKey;
    private final NamespacedKey versionKey;
    private final Set<String> activeInstanceUuids;
    private ItemAbilityManager abilityManager;

    private UUID getAttributeUuid(Attribute attribute) {
        return UUID.nameUUIDFromBytes(("thelifesteal_attr_" + attribute.name().toLowerCase()).getBytes());
    }

    public AdvancedCustomItemManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "custom_items.yml");
        this.items = new LinkedHashMap<>();
        this.itemIdKey = new NamespacedKey(plugin, "custom_item_id");
        this.instanceUuidKey = new NamespacedKey(plugin, "item_instance_uuid");
        this.versionKey = new NamespacedKey(plugin, "item_version");
        this.activeInstanceUuids = ConcurrentHashMap.newKeySet();
    }

    public void setAbilityManager(ItemAbilityManager abilityManager) {
        this.abilityManager = abilityManager;
    }

    public NamespacedKey getItemIdKey() { return itemIdKey; }

    @SuppressWarnings("deprecation")
    public void loadItems() {
        if (!dataFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = config.getConfigurationSection("advanced-items");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            try {
                ConfigurationSection itemSection = section.getConfigurationSection(id);
                if (itemSection == null) continue;

                ItemStack baseItem = itemSection.getItemStack("baseItem");
                if (baseItem == null) continue;

                AdvancedCustomItem item = new AdvancedCustomItem(id, baseItem);

                if (itemSection.contains("visualItemType")) {
                    String matName = itemSection.getString("visualItemType");
                    try { item.setVisualItemType(Material.valueOf(matName)); }
                    catch (IllegalArgumentException e) { item.setVisualItemType(baseItem.getType()); }
                }

                if (itemSection.contains("displayName")) item.setDisplayName(itemSection.getString("displayName"));
                if (itemSection.contains("lore")) item.setLore(itemSection.getStringList("lore"));
                if (itemSection.contains("category")) item.setCategory(itemSection.getString("category", "Misc"));
                if (itemSection.contains("rarity")) {
                    String rarityStr = itemSection.getString("rarity", "COMMON");
                    try { item.setRarity(ItemLoreBuilder.Rarity.valueOf(rarityStr.toUpperCase())); }
                    catch (IllegalArgumentException e) { item.setRarity(ItemLoreBuilder.Rarity.COMMON); }
                }

                if (itemSection.contains("version")) item.setVersion(itemSection.getLong("version"));
                if (itemSection.contains("disabled")) item.setDisabled(itemSection.getBoolean("disabled"));

                ConfigurationSection attrSection = itemSection.getConfigurationSection("attributes");
                if (attrSection != null) {
                    for (String attrName : attrSection.getKeys(false)) {
                        try {
                            Attribute attr = Attribute.valueOf(attrName);
                            item.addAttribute(attr, attrSection.getDouble(attrName));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }

                List<String> flagNames = itemSection.getStringList("flags");
                if (flagNames != null && !flagNames.isEmpty()) {
                    EnumSet<CustomItemFlag> flags = EnumSet.noneOf(CustomItemFlag.class);
                    for (String flagName : flagNames) {
                        try { flags.add(CustomItemFlag.valueOf(flagName)); }
                        catch (IllegalArgumentException ignored) {}
                    }
                    item.setFlags(flags);
                }

                if (itemSection.contains("customModelData")) item.setCustomModelData(itemSection.getInt("customModelData"));
                if (itemSection.contains("itemModel")) {
                    String modelStr = itemSection.getString("itemModel");
                    if (modelStr != null && modelStr.contains(":")) {
                        String[] parts = modelStr.split(":", 2);
                        item.setItemModel(new NamespacedKey(parts[0], parts[1]));
                    }
                }
                if (itemSection.contains("damage")) item.setDamage(itemSection.getInt("damage"));

                List<String> enchantData = itemSection.getStringList("enchants");
                if (!enchantData.isEmpty()) {
                    Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
                    for (String data : enchantData) {
                        String[] parts = data.split(":");
                        if (parts.length == 2) {
                            Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(parts[0].toLowerCase()));
                            if (ench != null) {
                                try { enchants.put(ench, Integer.parseInt(parts[1])); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                    item.setEnchants(enchants);
                }

                List<String> potionData = itemSection.getStringList("potionEffects");
                if (!potionData.isEmpty()) {
                    List<AdvancedCustomItem.PotionEffectData> effects = new ArrayList<>();
                    for (String data : potionData) {
                        AdvancedCustomItem.PotionEffectData effect = AdvancedCustomItem.PotionEffectData.deserialize(data);
                        if (effect != null) effects.add(effect);
                    }
                    item.setPotionEffects(effects);
                }

                if (itemSection.contains("abilities") && abilityManager != null) {
                    ConfigurationSection abilitiesSection = itemSection.getConfigurationSection("abilities");
                    if (abilitiesSection != null) {
                        Map<String, Object> rawMap = new LinkedHashMap<>();
                        for (String key : abilitiesSection.getKeys(false)) rawMap.put(key, abilitiesSection.get(key));
                        item.setAbilities(abilityManager.deserialize(rawMap));
                    }
                }

                if (itemSection.contains("futureExtensions")) {
                    ConfigurationSection extSection = itemSection.getConfigurationSection("futureExtensions");
                    if (extSection != null) {
                        Map<String, Object> extMap = new HashMap<>();
                        for (String key : extSection.getKeys(false)) extMap.put(key, extSection.get(key));
                        item.setFutureExtensions(extMap);
                    }
                }
                items.put(id, item);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load item: " + id + " - " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + items.size() + " advanced items.");
    }

    @SuppressWarnings("deprecation")
    public void saveItems() {
        YamlConfiguration config = new YamlConfiguration();
        ConfigurationSection section = config.createSection("advanced-items");
        for (AdvancedCustomItem item : items.values()) {
            ConfigurationSection itemSection = section.createSection(item.getId());
            itemSection.set("baseItem", item.getBaseItem());
            itemSection.set("visualItemType", item.getVisualItemType().name());
            if (item.getDisplayName() != null) itemSection.set("displayName", item.getDisplayName());
            if (!item.getLore().isEmpty()) itemSection.set("lore", item.getLore());
            itemSection.set("category", item.getCategory());
            itemSection.set("rarity", item.getRarity().name());
            itemSection.set("version", item.getVersion());
            itemSection.set("disabled", item.isDisabled());
            if (!item.getAttributes().isEmpty()) {
                ConfigurationSection attrSection = itemSection.createSection("attributes");
                for (Map.Entry<Attribute, Double> entry : item.getAttributes().entrySet())
                    attrSection.set(entry.getKey().name(), entry.getValue());
            }
            if (!item.getFlags().isEmpty()) {
                List<String> flagNames = new ArrayList<>();
                for (CustomItemFlag flag : item.getFlags()) flagNames.add(flag.name());
                itemSection.set("flags", flagNames);
            }
            if (item.getCustomModelData() != 0) itemSection.set("customModelData", item.getCustomModelData());
            if (item.getItemModel() != null) {
                itemSection.set("itemModel", item.getItemModel().getNamespace() + ":" + item.getItemModel().getKey());
            }
            if (item.getDamage() != 0) itemSection.set("damage", item.getDamage());
            if (!item.getEnchants().isEmpty()) {
                List<String> enchantData = new ArrayList<>();
                for (Map.Entry<Enchantment, Integer> entry : item.getEnchants().entrySet())
                    enchantData.add(entry.getKey().getKey().getKey() + ":" + entry.getValue());
                itemSection.set("enchants", enchantData);
            }
            if (!item.getPotionEffects().isEmpty()) {
                List<String> potionData = new ArrayList<>();
                for (AdvancedCustomItem.PotionEffectData effect : item.getPotionEffects())
                    potionData.add(effect.serialize());
                itemSection.set("potionEffects", potionData);
            }
            if (abilityManager != null) {
                Map<String, Object> abilityData = abilityManager.serialize(item.getAbilities());
                if (!abilityData.isEmpty()) itemSection.createSection("abilities", abilityData);
            }
            if (!item.getFutureExtensions().isEmpty()) {
                ConfigurationSection extSection = itemSection.createSection("futureExtensions");
                for (Map.Entry<String, Object> entry : item.getFutureExtensions().entrySet())
                    extSection.set(entry.getKey(), entry.getValue());
            }
        }
        try { config.save(dataFile); } catch (IOException e) { plugin.getLogger().warning("Failed to save: " + e.getMessage()); }
    }

    public void registerDefaultItems(FileConfiguration config) {
        ConfigurationSection defaultsSection = config.getConfigurationSection("default-items");
        if (defaultsSection == null) { plugin.getLogger().info("No default items configured."); return; }

        for (String itemId : defaultsSection.getKeys(false)) {
            if (items.containsKey(itemId)) continue;
            try {
                ConfigurationSection itemSection = defaultsSection.getConfigurationSection(itemId);
                if (itemSection == null) continue;
                String materialName = itemSection.getString("material", "STONE");
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material == null) { plugin.getLogger().warning("Invalid material: " + materialName); continue; }

                ItemStack baseItem = new ItemStack(material);
                AdvancedCustomItem item = createItem(itemId, baseItem);
                String displayName = itemSection.getString("display-name");
                if (displayName != null) item.setDisplayName(displayName);
                List<String> lore = itemSection.getStringList("lore");
                if (!lore.isEmpty()) item.setLore(lore);
                String rarityStr = itemSection.getString("rarity", "COMMON");
                try { item.setRarity(ItemLoreBuilder.Rarity.valueOf(rarityStr.toUpperCase())); }
                catch (IllegalArgumentException e) { item.setRarity(ItemLoreBuilder.Rarity.COMMON); }
                item.setCategory(itemSection.getString("category", "Misc"));
                List<String> flagNames = itemSection.getStringList("flags");
                for (String flagName : flagNames) {
                    try { item.getFlags().add(CustomItemFlag.valueOf(flagName.toUpperCase())); }
                    catch (IllegalArgumentException e) { plugin.getLogger().warning("Invalid flag: " + flagName); }
                }
                if (!AdvancedCustomItem.NON_STACKABLE_CATEGORIES.contains(item.getCategory()))
                    item.getFlags().add(CustomItemFlag.NO_INSTANCE_UUID);

                ConfigurationSection abilitiesSection = itemSection.getConfigurationSection("abilities");
                if (abilitiesSection != null && abilityManager != null) {
                    Map<String, Object> rawMap = new LinkedHashMap<>();
                    for (String key : abilitiesSection.getKeys(false)) rawMap.put(key, abilitiesSection.get(key));
                    item.setAbilities(abilityManager.deserialize(rawMap));
                }
                saveItems();
                plugin.getLogger().info("Created default item: " + itemId);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to create default item " + itemId + ": " + e.getMessage());
            }
        }
    }

    public AdvancedCustomItem createItem(String id, ItemStack baseItem) {
        ItemStack stripped = AdvancedCustomItem.stripVanillaStats(baseItem);
        Material visualType = stripped.getType();
        AdvancedCustomItem item = new AdvancedCustomItem(id, stripped);
        item.setVisualItemType(visualType);
        item.setBaseItem(stripped);
        items.put(id, item);
        saveItems();
        return item;
    }

    public void deleteItem(String id) { items.remove(id); saveItems(); }
    public AdvancedCustomItem getItem(String id) { return items.get(id); }
    public Collection<AdvancedCustomItem> getAllItems() { return new ArrayList<>(items.values()); }

    @SuppressWarnings("deprecation")
    public ItemStack buildItem(AdvancedCustomItem item) {
        if (item == null) return null;
        return buildItem(item, item.getId());
    }

    @SuppressWarnings("deprecation")
    public ItemStack buildItem(AdvancedCustomItem item, String idToStore) {
        if (item == null) return null;
        ItemStack result = item.getBaseItem().clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return result;

        meta.setDisplayName(null);
        meta.setLore(null);
        meta.setUnbreakable(false);
        for (Attribute attr : Attribute.values()) meta.removeAttributeModifier(attr);
        meta.removeItemFlags(ItemFlag.values());
        for (Enchantment ench : meta.getEnchants().keySet()) meta.removeEnchant(ench);

        if (AdvancedCustomItem.NON_STACKABLE_CATEGORIES.contains(item.getCategory())) {
            Attribute[] zeroAttrs = new Attribute[]{
                    Attribute.ATTACK_DAMAGE, Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS
            };
            for (Attribute attr : zeroAttrs) {
                AttributeModifier zeroMod = new AttributeModifier(
                        getAttributeUuid(attr), "custom_attr_" + attr.name().toLowerCase(), 0, AttributeModifier.Operation.ADD_NUMBER);
                meta.addAttributeModifier(attr, zeroMod);
            }
        }

        String rarityColor = ItemLoreBuilder.getRarityColor(item.getRarity());
        if (item.getDisplayName() != null && !item.getDisplayName().isEmpty())
            meta.setDisplayName(ColorUtils.colorize(rarityColor + item.getDisplayName()));
        else
            meta.setDisplayName(ColorUtils.colorize(rarityColor + formatMaterialName(item.getVisualItemType())));

        List<String> builtLore = ItemLoreBuilder.buildLore(item, abilityManager);
        if (!builtLore.isEmpty()) meta.setLore(builtLore);

        for (Map.Entry<Enchantment, Integer> entry : item.getEnchants().entrySet())
            meta.addEnchant(entry.getKey(), entry.getValue(), true);

        if (item.getDamage() > 0) ((org.bukkit.inventory.meta.Damageable) meta).setDamage(item.getDamage());

        if (item.hasFlag(CustomItemFlag.GLOW)) { meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true); meta.addItemFlags(ItemFlag.HIDE_ENCHANTS); }
        if (item.hasFlag(CustomItemFlag.UNBREAKABLE)) { meta.setUnbreakable(true); if (item.hasFlag(CustomItemFlag.HIDE_UNBREAKABLE)) meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE); }
        if (item.hasFlag(CustomItemFlag.HIDE_ENCHANTS)) meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        if (item.hasFlag(CustomItemFlag.HIDE_ATTRIBUTES)) meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (item.hasFlag(CustomItemFlag.HIDE_DESTROYS)) meta.addItemFlags(ItemFlag.HIDE_DESTROYS);
        if (item.hasFlag(CustomItemFlag.HIDE_PLACED_ON)) meta.addItemFlags(ItemFlag.HIDE_PLACED_ON);
        if (item.hasFlag(CustomItemFlag.HIDE_ADDITIONAL_TOOLTIP)) meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (item.hasFlag(CustomItemFlag.HIDE_DYE)) meta.addItemFlags(ItemFlag.HIDE_DYE);
        if (item.hasFlag(CustomItemFlag.HIDE_ARMOR_TRIM)) meta.addItemFlags(ItemFlag.HIDE_ARMOR_TRIM);

        for (Map.Entry<Attribute, Double> entry : item.getAttributes().entrySet()) {
            Attribute attr = entry.getKey();
            meta.removeAttributeModifier(attr);
            AttributeModifier modifier = new AttributeModifier(
                    getAttributeUuid(attr), "custom_attr_" + attr.name().toLowerCase(),
                    entry.getValue(), AttributeModifier.Operation.ADD_NUMBER);
            meta.addAttributeModifier(attr, modifier);
        }

        if (item.getItemModel() != null) {
            meta.setItemModel(item.getItemModel());
        }

        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, idToStore);
        meta.getPersistentDataContainer().set(versionKey, PersistentDataType.LONG, item.getVersion());

        result.setItemMeta(meta);
        return result;
    }

    public ItemStack buildItemForPlayer(AdvancedCustomItem item) {
        ItemStack stack = buildItem(item);
        if (item.shouldGetInstanceUuid()) {
            String uuid;
            do { uuid = UUID.randomUUID().toString(); } while (activeInstanceUuids.contains(uuid));
            activeInstanceUuids.add(uuid);
            ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(instanceUuidKey, PersistentDataType.STRING, uuid);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public String getInstanceUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(instanceUuidKey, PersistentDataType.STRING);
    }

    public void removeInstance(String uuid) { if (uuid != null) activeInstanceUuids.remove(uuid); }

    public void storeItemId(ItemStack item, String id) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
    }

    public String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    }

    public AdvancedCustomItem getItemByStack(ItemStack item) {
        String id = getItemId(item);
        if (id == null) return null;
        return items.get(id);
    }

    public void bumpVersion(String itemId) {
        AdvancedCustomItem item = items.get(itemId);
        if (item != null) {
            item.incrementVersion();
            saveItems();
        }
    }

    public int purgeInstances(String itemId, ItemUpdateManager updateManager) {
        if (updateManager == null) return 0;
        return updateManager.purgeItem(itemId);
    }

    public int refreshRecipeResults(theLifesteal.crafting.CraftingManager craftingManager) {
        int updated = 0;
        for (theLifesteal.crafting.CraftingRecipe recipe : craftingManager.getAllRecipes()) {
            ItemStack result = recipe.getResult();
            String itemId = getItemId(result);
            if (itemId != null) {
                AdvancedCustomItem definition = getItem(itemId);
                if (definition != null) {
                    ItemStack fresh = buildItem(definition);
                    fresh.setAmount(result.getAmount());
                    recipe.setResult(fresh);
                    updated++;
                }
            }
        }
        if (updated > 0) craftingManager.saveRecipes();
        return updated;
    }

    private String formatMaterialName(Material mat) {
        String name = mat.name().replace("_", " ").toLowerCase();
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) { sb.append(Character.toUpperCase(word.charAt(0))); if (word.length() > 1) sb.append(word.substring(1)); sb.append(" "); }
        }
        return sb.toString().trim();
    }
}