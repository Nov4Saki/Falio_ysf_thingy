package theLifesteal.customitem;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.ItemAbilityGUI;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class CustomItemGUI {

    private final JavaPlugin plugin;
    private final AdvancedCustomItemManager manager;
    private final Map<UUID, EditingSession> sessions;
    private final Map<UUID, String> chatInput;
    private final Map<UUID, Runnable> returnAction;
    private final Map<UUID, Boolean> deleteMode;
    private final Map<UUID, Integer> pageCache;
    private final Set<UUID> inTransition;
    private final Map<UUID, Integer> potionBrowserPage;
    private final Map<UUID, Integer> rarityPage;
    private final Map<UUID, Integer> categoryPage;
    private ItemAbilityGUI abilityGUI;
    private CustomEnchantGUI enchantGUI;

    private static final String MAIN_TITLE = "&5✦ &dItem Creator &5✦";
    private static final int ITEMS_PER_PAGE = 28;
    private static final int[] ITEM_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

    private static final List<Attribute> ATTRIBUTES = Arrays.asList(
            Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS, Attribute.ATTACK_DAMAGE,
            Attribute.ATTACK_KNOCKBACK, Attribute.ATTACK_SPEED,
            Attribute.BLOCK_BREAK_SPEED, Attribute.BLOCK_INTERACTION_RANGE,
            Attribute.ENTITY_INTERACTION_RANGE, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE,
            Attribute.FALL_DAMAGE_MULTIPLIER, Attribute.GRAVITY, Attribute.JUMP_STRENGTH,
            Attribute.KNOCKBACK_RESISTANCE, Attribute.LUCK, Attribute.MAX_HEALTH,
            Attribute.MOVEMENT_SPEED, Attribute.OXYGEN_BONUS, Attribute.SAFE_FALL_DISTANCE,
            Attribute.SCALE, Attribute.STEP_HEIGHT, Attribute.WATER_MOVEMENT_EFFICIENCY,
            Attribute.SNEAKING_SPEED, Attribute.SUBMERGED_MINING_SPEED,
            Attribute.SWEEPING_DAMAGE_RATIO
    );

    private static final Map<Attribute, String> ATTRIBUTE_DISPLAY_NAMES = new LinkedHashMap<>();
    static {
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.ARMOR, "Armor");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.ARMOR_TOUGHNESS, "Armor Toughness");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.ATTACK_DAMAGE, "Attack Damage");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.ATTACK_KNOCKBACK, "Attack Knockback");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.ATTACK_SPEED, "Attack Speed");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.BLOCK_BREAK_SPEED, "Mining Speed");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.BLOCK_INTERACTION_RANGE, "Reach");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.ENTITY_INTERACTION_RANGE, "Entity Reach");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, "Explosion Resist");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.FALL_DAMAGE_MULTIPLIER, "Fall Damage Multiplier");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.GRAVITY, "Gravity");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.JUMP_STRENGTH, "Jump Strength");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.KNOCKBACK_RESISTANCE, "Knockback Resist");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.LUCK, "Luck");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.MAX_HEALTH, "Max Health");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.MOVEMENT_SPEED, "Speed");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.OXYGEN_BONUS, "Oxygen Bonus");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.SAFE_FALL_DISTANCE, "Safe Fall Distance");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.SCALE, "Scale");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.STEP_HEIGHT, "Step Height");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.WATER_MOVEMENT_EFFICIENCY, "Water Movement");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.SNEAKING_SPEED, "Sneaking Speed");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.SUBMERGED_MINING_SPEED, "Underwater Mining");
        ATTRIBUTE_DISPLAY_NAMES.put(Attribute.SWEEPING_DAMAGE_RATIO, "Sweeping Damage");
    }

    private static final List<PotionEffectType> POTION_EFFECTS;
    private static final String[] AMPLIFIER_NAMES = {"I","II","III","IV","V","VI","VII","VIII","IX","X"};
    static {
        List<PotionEffectType> effects = new ArrayList<>();
        for (PotionEffectType type : PotionEffectType.values()) { if (type != null) effects.add(type); }
        effects.sort(Comparator.comparing(e -> e.getKey().getKey()));
        POTION_EFFECTS = Collections.unmodifiableList(effects);
    }

    public CustomItemGUI(JavaPlugin plugin, AdvancedCustomItemManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.sessions = new HashMap<>();
        this.chatInput = new HashMap<>();
        this.returnAction = new HashMap<>();
        this.deleteMode = new HashMap<>();
        this.pageCache = new HashMap<>();
        this.inTransition = new HashSet<>();
        this.potionBrowserPage = new HashMap<>();
        this.rarityPage = new HashMap<>();
        this.categoryPage = new HashMap<>();
    }

    public void setAbilityGUI(ItemAbilityGUI abilityGUI) { this.abilityGUI = abilityGUI; }
    public void setEnchantGUI(CustomEnchantGUI enchantGUI) { this.enchantGUI = enchantGUI; }

    public void openMainMenu(Player player) {
        pageCache.put(player.getUniqueId(), 0);
        player.openInventory(buildMainGUI(player));
    }

    public void openEditGUI(Player player, String itemId) {
        AdvancedCustomItem item = manager.getItem(itemId);
        if (item == null) { player.sendMessage(ColorUtils.colorize("&cItem not found!")); return; }
        sessions.put(player.getUniqueId(), new EditingSession(item.clone()));
        player.openInventory(buildEditMain(player));
    }

    public boolean isAwaitingInput(UUID uuid) { return chatInput.containsKey(uuid); }

    public boolean isCustomItemGUI(String title) {
        return title.contains("Item Creator") || title.contains("Choose Base") ||
                title.contains("Edit Item") || title.contains("Attributes") ||
                title.contains("Name & Lore") || title.contains("Flags") ||
                title.contains("Potion Effects") || title.contains("Choose Effect") ||
                title.contains("Select Level") || title.contains("Show Particles") ||
                title.contains("Category") || title.contains("Armor Piece") || title.contains("Rarity") ||
                title.contains("Preview") || title.contains("Abilities") ||
                title.contains("Choose Ability") || title.contains("Config:") ||
                title.contains("Ability Slots") || title.contains("Enchantments") ||
                title.contains("Choose Enchantment") || title.contains("Enchant Level");
    }

    public void saveOnClose(Player player) {
        UUID uuid = player.getUniqueId();
        if (inTransition.remove(uuid)) return;
        commitSession(player);
    }

    public void handleChatInput(Player player, String message) {
        UUID uuid = player.getUniqueId();
        if (enchantGUI != null && enchantGUI.isAwaitingInput(uuid)) { enchantGUI.handleChatInput(player, message); return; }
        if (abilityGUI != null && abilityGUI.isAwaitingInput(uuid)) { abilityGUI.handleChatInput(player, message); return; }

        String key = chatInput.remove(uuid);
        Runnable back = returnAction.remove(uuid);
        if (key == null || message.equalsIgnoreCase("cancel")) {
            player.sendMessage(ColorUtils.colorize("&cCancelled."));
            if (back != null) back.run(); else openMainMenu(player);
            return;
        }

        EditingSession ses = sessions.get(uuid);
        if (ses == null) { openMainMenu(player); return; }

        switch (key) {
            case "displayName" -> { ses.item.setDisplayName(message.trim().isEmpty() ? null : message); player.sendMessage(ColorUtils.colorize("&aName updated.")); }
            case "lore_add" -> { List<String> l = ses.item.getLore(); if (l.size() >= 28) { player.sendMessage(ColorUtils.colorize("&cMax 28 lines.")); break; } l.add(message); ses.item.setLore(l); player.sendMessage(ColorUtils.colorize("&aLine added.")); }
            case "potion_amplifier" -> { try { int amp = Integer.parseInt(message); if (amp < 0 || amp > 127) { player.sendMessage(ColorUtils.colorize("&c0-127!")); ses.tempPotionType = null; if (back != null) back.run(); else openMainMenu(player); return; } ses.tempPotionAmplifier = amp; player.sendMessage(ColorUtils.colorize("&aAmplifier set.")); } catch (NumberFormatException e) { player.sendMessage(ColorUtils.colorize("&cInvalid number!")); } }
            case "itemmodel" -> {
                if (message.trim().isEmpty()) {
                    ses.item.setItemModel(null);
                    player.sendMessage(ColorUtils.colorize("&aItem model cleared."));
                } else if (message.contains(":")) {
                    String[] parts = message.split(":", 2);
                    ses.item.setItemModel(new NamespacedKey(parts[0], parts[1]));
                    player.sendMessage(ColorUtils.colorize("&aItem model set to &f" + message));
                } else {
                    player.sendMessage(ColorUtils.colorize("&cInvalid format! Use &fnamespace:key"));
                }
            }
            default -> {
                if (key.startsWith("lore_edit:")) { int idx = Integer.parseInt(key.split(":")[1]); List<String> l = ses.item.getLore(); if (idx>=0 && idx<l.size()) { l.set(idx, message); ses.item.setLore(l); player.sendMessage(ColorUtils.colorize("&aLine updated.")); } }
                else if (key.startsWith("attr:")) { try { double val = Double.parseDouble(message); Attribute attr = Attribute.valueOf(key.substring(5)); if (val==0) ses.item.removeAttribute(attr); else ses.item.addAttribute(attr, val); player.sendMessage(ColorUtils.colorize("&aAttribute updated.")); } catch (Exception e) { player.sendMessage(ColorUtils.colorize("&cInvalid.")); } }
                else if (key.equals("modeldata")) { try { ses.item.setCustomModelData(Integer.parseInt(message)); player.sendMessage(ColorUtils.colorize("&aModel data set.")); } catch (NumberFormatException e) { player.sendMessage(ColorUtils.colorize("&cInvalid.")); } }
                else if (key.equals("damage")) { try { ses.item.setDamage(Math.max(0, Integer.parseInt(message))); player.sendMessage(ColorUtils.colorize("&aDurability set.")); } catch (NumberFormatException e) { player.sendMessage(ColorUtils.colorize("&cInvalid.")); } }
            }
        }
        if (back != null) back.run(); else openMainMenu(player);
    }

    public void handleClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        String title = event.getView().getTitle();
        if (!isCustomItemGUI(title)) return;
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getBottomInventory())) return;
        event.setCancelled(true);

        int slot = event.getSlot();
        ClickType click = event.getClick();
        ItemStack current = event.getCurrentItem();

        if (enchantGUI != null && enchantGUI.isEnchantGUI(title)) { enchantGUI.handleClick(p, title, slot, click); return; }
        if (abilityGUI != null && abilityGUI.isAbilityGUI(title)) { abilityGUI.handleClick(p, title, slot, click); return; }

        if (title.contains("Show Particles")) particlesClick(p, slot);
        else if (title.contains("Select Level")) amplifierClick(p, slot, click);
        else if (title.contains("Choose Effect")) potionBrowserClick(p, slot, click);
        else if (title.contains("Potion Effects")) potionEffectsClick(p, slot, click);
        else if (title.contains("Armor Piece")) armorPieceClick(p, slot);
        else if (title.contains("Category")) categoryClick(p, slot, click);
        else if (title.contains("Rarity")) rarityClick(p, slot, click);
        else if (title.contains("Preview")) previewClick(p, slot);
        else if (title.contains("Item Creator")) mainClick(p, slot, click, current);
        else if (title.contains("Choose Base")) templateClick(p, slot, event.getCursor());
        else if (title.contains("Edit Item")) editMainClick(p, slot);
        else if (title.contains("Attributes")) attrClick(p, slot);
        else if (title.contains("Name & Lore")) nameLoreClick(p, slot, click);
        else if (title.contains("Flags")) flagClick(p, slot);
    }

    public void cleanupPlayer(UUID uuid) {
        sessions.remove(uuid); chatInput.remove(uuid); returnAction.remove(uuid);
        deleteMode.remove(uuid); pageCache.remove(uuid); inTransition.remove(uuid);
        potionBrowserPage.remove(uuid); rarityPage.remove(uuid); categoryPage.remove(uuid);
        if (abilityGUI != null) abilityGUI.cleanupPlayer(uuid);
        if (enchantGUI != null) enchantGUI.cleanupPlayer(uuid);
    }

    // ==================== GUI BUILDERS ====================

    private Inventory buildMainGUI(Player player) {
        int page = pageCache.getOrDefault(player.getUniqueId(), 0);
        List<AdvancedCustomItem> all = new ArrayList<>(manager.getAllItems());
        int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / ITEMS_PER_PAGE));
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, all.size());

        Inventory gui = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text(ColorUtils.colorize(MAIN_TITLE)));
        filler(gui);

        for (int i = start; i < end; i++) {
            AdvancedCustomItem item = all.get(i);
            ItemStack display = manager.buildItem(item);
            manager.storeItemId(display, item.getId());
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ColorUtils.colorize("&7&m-------------------"));
                if (item.isDisabled()) {
                    lore.add(ColorUtils.colorize("&c⚠ DISABLED"));
                    lore.add(ColorUtils.colorize("&7This item is currently disabled."));
                    lore.add(ColorUtils.colorize("&7Existing copies will become &4Broken Relics&7."));
                } else {
                    lore.add(ColorUtils.colorize("&a▶ Left → get &8| &e▶ Right → edit"));
                }
                lore.add(ColorUtils.colorize("&7&m-------------------"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            gui.setItem(ITEM_SLOTS[i - start], display);
        }

        gui.setItem(45, item(Material.ARROW, "&a← Previous", "&7Page " + (page+1) + "/" + totalPages));
        gui.setItem(46, item(Material.EMERALD_BLOCK, "&a✦ &6Create New", "&7Pick a base material"));
        gui.setItem(48, deleteToggleItem(deleteMode.getOrDefault(player.getUniqueId(), false)));
        gui.setItem(49, item(Material.BARRIER, "&c✖ Close"));
        gui.setItem(53, item(Material.ARROW, "&aNext →", "&7Page " + (page+1) + "/" + totalPages));
        return gui;
    }

    private Inventory buildEditMain(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eEdit Item &6✦")));
        filler(gui);

        gui.setItem(22, manager.buildItem(ses.item));
        gui.setItem(28, item(Material.ENCHANTED_BOOK, "&5✨ Enchantments", "&7Add custom enchants"));
        gui.setItem(29, item(Material.NAME_TAG, "&6✏ Name & Lore"));
        gui.setItem(30, item(Material.DIAMOND_SWORD, "&6⚔ Attributes"));
        gui.setItem(31, item(Material.LEATHER_CHESTPLATE, "&6🏁 Flags"));
        gui.setItem(32, item(Material.POTION, "&d🧪 Potion Effects"));
        gui.setItem(33, item(Material.BOOKSHELF, "&b📚 Category", "&7Current: &f" + ses.item.getCategory()));
        gui.setItem(34, item(Material.NETHER_STAR, "&e⭐ Rarity", "&7Current: " + ses.item.getRarity().getColorCode() + ses.item.getRarity().getDisplayName()));
        gui.setItem(39, item(Material.BLAZE_POWDER, "&6✨ Abilities", "&7Configure item abilities"));

        if (AdvancedCustomItem.isArmorCategory(ses.item.getCategory())) {
            ArmorPiece piece = ses.item.getArmorPiece();
            gui.setItem(41, item(Material.IRON_CHESTPLATE, "&bArmor Piece",
                    "&7Current: &f" + (piece == null ? "Not selected" : piece.getDisplayName()),
                    "&aClick to choose the wearable slot"));
        }

        boolean disabled = ses.item.isDisabled();
        gui.setItem(40, item(disabled ? Material.RED_DYE : Material.LIME_DYE,
                (disabled ? "&c" : "&a") + "⚡ " + (disabled ? "DISABLED" : "ENABLED"),
                "&7Click to " + (disabled ? "&aenable" : "&cdisable") + " &7this item",
                disabled ? "&7Disabled items become broken relics for players" : ""));

        gui.setItem(49, item(Material.LIME_DYE, "&a✔ Save & Close"));
        gui.setItem(50, item(Material.BARRIER, "&c✖ Cancel"));
        return gui;
    }

    private Inventory buildNameLore(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eName & Lore &6✦")));
        filler(gui);
        String name = ses.item.getDisplayName() != null ? ses.item.getDisplayName() : "&7(no custom name)";
        gui.setItem(13, item(Material.NAME_TAG, "&eCurrent Name", "&f" + ColorUtils.colorize(name), "", "&aClick to change name"));
        List<String> lore = ses.item.getLore();
        int[] lineSlots = {19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43,10,11,12,14,15,16};
        for (int i = 0; i < Math.min(lore.size(), lineSlots.length); i++) {
            ItemStack li = new ItemStack(Material.PAPER); ItemMeta m = li.getItemMeta();
            m.setDisplayName(ColorUtils.colorize("&eLine " + (i+1)));
            m.setLore(Arrays.asList(ColorUtils.colorize("&7" + ColorUtils.colorize(lore.get(i))), "", ColorUtils.colorize("&a▶ Left → edit &8| &c▶ Right → remove")));
            li.setItemMeta(m); gui.setItem(lineSlots[i], li);
        }
        gui.setItem(49, item(Material.WRITABLE_BOOK, "&a➕ Add Line"));
        gui.setItem(46, item(Material.BOOK, "&e👁 Preview Lore", "&7See how the item will look"));
        gui.setItem(53, item(Material.ARROW, "&a← Back to Editor"));
        return gui;
    }

    private Inventory buildAttr(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eAttributes &6✦")));
        filler(gui);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        for (int i = 0; i < Math.min(ATTRIBUTES.size(), slots.length); i++) {
            Attribute a = ATTRIBUTES.get(i);
            double val = ses.item.getAttributes().getOrDefault(a, 0.0);
            gui.setItem(slots[i], item(Material.PAPER, "&e" + ATTRIBUTE_DISPLAY_NAMES.getOrDefault(a, a.name()), "&7Current: &f" + (val!=0?val:"—"), "&aClick to set value"));
        }
        gui.setItem(49, item(Material.ANVIL, "&6🔧 Durability: &f" + ses.item.getDamage(), "&7Set durability loss"));
        gui.setItem(50, item(Material.COMMAND_BLOCK, "&6🔢 Model Data: &f" + ses.item.getCustomModelData(), "&7Set model data"));
        String modelText = ses.item.getItemModel() != null ?
                ses.item.getItemModel().getNamespace() + ":" + ses.item.getItemModel().getKey() : "&7(none)";
        gui.setItem(51, item(Material.PAINTING, "&6🖼 Item Model",
                "&7Current: &f" + modelText,
                "&aClick to set item model"));
        gui.setItem(53, item(Material.ARROW, "&a← Back"));
        return gui;
    }

    private Inventory buildFlags(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eFlags &6✦")));
        filler(gui);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        CustomItemFlag[] flags = CustomItemFlag.values();
        for (int i = 0; i < Math.min(flags.length, slots.length); i++) {
            boolean on = ses.item.hasFlag(flags[i]);
            gui.setItem(slots[i], toggleItem(on ? Material.LIME_DYE : Material.GRAY_DYE, "&e" + flags[i].name().toLowerCase().replace("_"," "), on));
        }
        gui.setItem(53, item(Material.ARROW, "&a← Back"));
        return gui;
    }

    private Inventory buildPotionEffects(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &ePotion Effects &6✦")));
        filler(gui);
        List<AdvancedCustomItem.PotionEffectData> effects = ses.item.getPotionEffects();
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        for (int i = 0; i < Math.min(effects.size(), slots.length); i++) {
            AdvancedCustomItem.PotionEffectData e = effects.get(i);
            String typeName = formatPotionName(e.getType());
            String level = AMPLIFIER_NAMES[Math.min(e.getAmplifier(), 9)];
            String particles = e.showParticles() ? "&a✔" : "&c✖";
            ItemStack d = new ItemStack(Material.POTION); ItemMeta m = d.getItemMeta();
            m.setDisplayName(ColorUtils.colorize("&d" + typeName + " " + level));
            m.setLore(Arrays.asList(ColorUtils.colorize("&7&m-------------------"), ColorUtils.colorize("&eEffect: &f" + typeName), ColorUtils.colorize("&eLevel: &f" + level + " &7(amp: " + e.getAmplifier() + ")"), ColorUtils.colorize("&eParticles: " + particles), "", ColorUtils.colorize("&c▶ Right-click to remove"), ColorUtils.colorize("&7&m-------------------")));
            d.setItemMeta(m); gui.setItem(slots[i], d);
        }
        gui.setItem(49, item(Material.LIME_DYE, "&a➕ Add Effect", "&7Click to browse effects"));
        gui.setItem(53, item(Material.ARROW, "&a← Back to Editor"));
        return gui;
    }

    private Inventory buildPotionBrowser(Player player, int page) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eChoose Effect &6✦")));
        filler(gui);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int totalPages = (int) Math.ceil((double) POTION_EFFECTS.size() / slots.length);
        int start = page * slots.length, end = Math.min(start + slots.length, POTION_EFFECTS.size());
        for (int i = start; i < end; i++) {
            PotionEffectType type = POTION_EFFECTS.get(i);
            boolean already = ses.item.getPotionEffects().stream().anyMatch(e -> e.getType().equals(type));
            Material icon = already ? Material.GRAY_DYE : Material.POTION;
            ItemStack d = new ItemStack(icon); ItemMeta m = d.getItemMeta();
            m.setDisplayName(ColorUtils.colorize("&d" + formatPotionName(type) + (already?" &7(already added)":"")));
            m.setLore(Arrays.asList(ColorUtils.colorize("&7&m-------------------"), ColorUtils.colorize("&eKey: &f" + type.getKey().getKey()), already ? ColorUtils.colorize("&7Already added") : ColorUtils.colorize("&a▶ Click to add"), ColorUtils.colorize("&7&m-------------------")));
            d.setItemMeta(m); gui.setItem(slots[i-start], d);
        }
        if (page>0) gui.setItem(45, item(Material.ARROW, "&a← Previous"));
        if (page<totalPages-1) gui.setItem(53, item(Material.ARROW, "&aNext →"));
        gui.setItem(49, item(Material.BARRIER, "&c✖ Back to Effects"));
        return gui;
    }

    private Inventory buildAmplifierSelector(Player player, PotionEffectType type) {
        Inventory gui = Bukkit.createInventory(null, 36, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eSelect Level &6✦")));
        filler(gui);
        int[] ls = {10,11,12,13,14,15,16};
        for (int i=0;i<7;i++) { ItemStack d=new ItemStack(Material.EXPERIENCE_BOTTLE,Math.min(i+1,64)); ItemMeta m=d.getItemMeta(); m.setDisplayName(ColorUtils.colorize("&dLevel "+AMPLIFIER_NAMES[i])); m.setLore(Arrays.asList(ColorUtils.colorize("&7Amplifier: &f"+i),ColorUtils.colorize("&a▶ Click to select"))); d.setItemMeta(m); gui.setItem(ls[i],d); }
        int[] hs={19,20,21}; for(int i=0;i<3;i++){int amp=7+i; ItemStack d=new ItemStack(Material.EXPERIENCE_BOTTLE,Math.min(amp+1,64)); ItemMeta m=d.getItemMeta(); m.setDisplayName(ColorUtils.colorize("&dLevel "+AMPLIFIER_NAMES[amp])); m.setLore(Arrays.asList(ColorUtils.colorize("&7Amplifier: &f"+amp),ColorUtils.colorize("&a▶ Click to select"))); d.setItemMeta(m); gui.setItem(hs[i],d); }
        gui.setItem(25, item(Material.OAK_SIGN, "&e✏ Custom Level", "&7Type amplifier number in chat"));
        gui.setItem(31, item(Material.BARRIER, "&c✖ Back"));
        return gui;
    }

    private Inventory buildParticlesToggle(Player player, PotionEffectType type, int amplifier) {
        Inventory gui = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eShow Particles? &6✦")));
        filler(gui); gui.setItem(11,item(Material.LIME_DYE,"&a✔ Show Particles","&7Potion particles visible")); gui.setItem(15,item(Material.GRAY_DYE,"&c✖ Hide Particles","&7No particles")); gui.setItem(22,item(Material.BARRIER,"&c✖ Back"));
        return gui;
    }

    private Inventory buildCategorySelector(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eSelect Category &6✦")));
        filler(gui); List<String> cats = ItemLoreBuilder.getCategoryNames();
        int[] cs = {10,11,12,13,14,15,16};
        for (int i=0;i<Math.min(cats.size(),cs.length);i++){boolean sel=ses.item.getCategory().equalsIgnoreCase(cats.get(i)); gui.setItem(cs[i],item(sel?Material.LIME_DYE:Material.BOOK,"&b"+cats.get(i)+(sel?" &a✔":""),"&7Click to select"));}
        gui.setItem(22, item(Material.ARROW, "&a← Back to Editor")); return gui;
    }

    private Inventory buildArmorPieceSelector(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);

        Inventory gui = Bukkit.createInventory(null, 27,
                net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6Select Armor Piece")));
        filler(gui);
        ArmorPiece[] pieces = ArmorPiece.values();
        Material[] icons = {Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS};
        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < pieces.length; i++) {
            boolean selected = ses.item.getArmorPiece() == pieces[i];
            gui.setItem(slots[i], item(selected ? Material.LIME_DYE : icons[i],
                    "&b" + pieces[i].getDisplayName() + (selected ? " &aSelected" : ""),
                    "&7Click to make this item wearable here"));
        }
        gui.setItem(22, item(Material.ARROW, "&aBack to Editor"));
        return gui;
    }

    private Inventory buildRaritySelector(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eSelect Rarity &6✦")));
        filler(gui); ItemLoreBuilder.Rarity[] rarities = ItemLoreBuilder.Rarity.values();
        int[] rs = {10,11,12,13,14,15,16};
        for (int i=0;i<Math.min(rarities.length,rs.length);i++){boolean sel=ses.item.getRarity()==rarities[i]; gui.setItem(rs[i],item(sel?Material.LIME_DYE:Material.NETHER_STAR,rarities[i].getColorCode()+rarities[i].getDisplayName()+(sel?" &a✔":""),"&7Click to select"));}
        gui.setItem(22, item(Material.ARROW, "&a← Back to Editor")); return gui;
    }

    private Inventory buildLorePreview(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return buildMainGUI(player);
        Inventory gui = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eLore Preview &6✦")));
        filler(gui); gui.setItem(22, manager.buildItem(ses.item)); gui.setItem(53, item(Material.ARROW, "&a← Back")); return gui;
    }

    private Inventory buildTemplate() {
        Inventory gui = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eChoose Base Item &6✦")));
        filler(gui); gui.setItem(13,item(Material.GRAY_STAINED_GLASS_PANE,"&7Click with an item","&7to set it as the template")); gui.setItem(22,item(Material.LIME_DYE,"&a✔ Confirm")); gui.setItem(26,item(Material.RED_DYE,"&c✖ Cancel")); return gui;
    }

    // ==================== CLICK HANDLERS ====================

    private void mainClick(Player p, int slot, ClickType click, ItemStack current) {
        int page = pageCache.getOrDefault(p.getUniqueId(), 0);
        List<AdvancedCustomItem> all = new ArrayList<>(manager.getAllItems());
        int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / ITEMS_PER_PAGE));
        boolean delMode = deleteMode.getOrDefault(p.getUniqueId(), false);

        if (slot == 45 && page > 0) { pageCache.put(p.getUniqueId(), page-1); p.openInventory(buildMainGUI(p)); return; }
        if (slot == 53 && page < totalPages-1) { pageCache.put(p.getUniqueId(), page+1); p.openInventory(buildMainGUI(p)); return; }
        if (slot == 46) { p.openInventory(buildTemplate()); return; }
        if (slot == 48) { deleteMode.put(p.getUniqueId(), !delMode); p.openInventory(buildMainGUI(p)); return; }
        if (slot == 49) { p.closeInventory(); return; }

        if (current == null || current.getType() == Material.AIR || current.getType() == Material.BLACK_STAINED_GLASS_PANE) return;
        String id = manager.getItemId(current);
        if (id == null) return;

        if (delMode) {
            manager.deleteItem(id);
            p.sendMessage(ColorUtils.colorize("&cDeleted."));
            p.openInventory(buildMainGUI(p));
            return;
        }

        if (click.isLeftClick()) {
            p.getInventory().addItem(manager.buildItemForPlayer(manager.getItem(id)));
            p.sendMessage(ColorUtils.colorize("&aItem given."));
        } else if (click.isRightClick()) {
            openEditGUI(p, id);
        }
    }

    private void templateClick(Player p, int slot, ItemStack cursor) {
        if (slot == 13) { if (cursor != null && cursor.getType() != Material.AIR) p.getOpenInventory().setItem(13, cursor.clone()); }
        else if (slot == 22) { ItemStack t = p.getOpenInventory().getItem(13); if (t == null || t.getType() == Material.AIR || t.getType() == Material.GRAY_STAINED_GLASS_PANE) { p.sendMessage(ColorUtils.colorize("&cSet a template first!")); return; } String id = "custom_" + System.currentTimeMillis(); AdvancedCustomItem item = manager.createItem(id, t); if (!AdvancedCustomItem.isNonStackableCategory(item.getCategory())) item.addFlag(CustomItemFlag.NO_INSTANCE_UUID); p.closeInventory(); openEditGUI(p, id); }
        else if (slot == 26) openMainMenu(p);
    }

    private void editMainClick(Player p, int slot) {
        EditingSession ses = sessions.get(p.getUniqueId());
        if (ses == null) return;
        switch (slot) {
            case 28 -> { if (enchantGUI != null) { inTransition.add(p.getUniqueId()); p.closeInventory(); FoliaScheduler.runEntityLater(p, plugin, () -> enchantGUI.openEnchantMenu(p, ses.item, () -> p.openInventory(buildEditMain(p))), 2L); } }
            case 29 -> { inTransition.add(p.getUniqueId()); p.openInventory(buildNameLore(p)); }
            case 30 -> { inTransition.add(p.getUniqueId()); p.openInventory(buildAttr(p)); }
            case 31 -> { inTransition.add(p.getUniqueId()); p.openInventory(buildFlags(p)); }
            case 32 -> { inTransition.add(p.getUniqueId()); p.openInventory(buildPotionEffects(p)); }
            case 33 -> { inTransition.add(p.getUniqueId()); p.openInventory(buildCategorySelector(p)); }
            case 34 -> { inTransition.add(p.getUniqueId()); p.openInventory(buildRaritySelector(p)); }
            case 39 -> { if (abilityGUI != null) { inTransition.add(p.getUniqueId()); p.closeInventory(); FoliaScheduler.runEntityLater(p, plugin, () -> abilityGUI.openAbilitiesMenu(p, ses.item, () -> p.openInventory(buildEditMain(p))), 2L); } }
            case 40 -> { ses.item.setDisabled(!ses.item.isDisabled()); inTransition.add(p.getUniqueId()); p.openInventory(buildEditMain(p)); }
            case 41 -> { if (AdvancedCustomItem.isArmorCategory(ses.item.getCategory())) { inTransition.add(p.getUniqueId()); p.openInventory(buildArmorPieceSelector(p)); } }
            case 49 -> commitAndExit(p, true);
            case 50 -> { sessions.remove(p.getUniqueId()); p.closeInventory(); openMainMenu(p); }
        }
    }

    private void nameLoreClick(Player p, int slot, ClickType click) {
        EditingSession ses = sessions.get(p.getUniqueId());
        if (ses == null) return;
        if (slot == 13) { startChatInput(p, "displayName", () -> p.openInventory(buildNameLore(p))); return; }
        if (slot == 49) { if (ses.item.getLore().size()>=28){p.sendMessage(ColorUtils.colorize("&cMax 28 lines!"));return;} startChatInput(p,"lore_add",()->p.openInventory(buildNameLore(p)));return; }
        if (slot == 46) { inTransition.add(p.getUniqueId()); p.openInventory(buildLorePreview(p)); return; }
        if (slot == 53) { inTransition.add(p.getUniqueId()); p.openInventory(buildEditMain(p)); return; }
        List<String> lore = ses.item.getLore();
        int[] ls = {19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43,10,11,12,14,15,16};
        for (int i=0;i<ls.length;i++){if(slot==ls[i]&&i<lore.size()){if(click.isLeftClick())startChatInput(p,"lore_edit:"+i,()->p.openInventory(buildNameLore(p)));else if(click.isRightClick()){lore.remove(i);ses.item.setLore(lore);p.openInventory(buildNameLore(p));}return;}}
    }

    private void attrClick(Player p, int slot) {
        EditingSession ses = sessions.get(p.getUniqueId());
        if (ses == null) return;
        if (slot == 53) { inTransition.add(p.getUniqueId()); p.openInventory(buildEditMain(p)); return; }
        if (slot == 49) { startChatInput(p, "damage", () -> p.openInventory(buildAttr(p))); return; }
        if (slot == 50) { startChatInput(p, "modeldata", () -> p.openInventory(buildAttr(p))); return; }
        if (slot == 51) { startChatInput(p, "itemmodel", () -> p.openInventory(buildAttr(p))); return; }
        int[] as = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        for (int i=0;i<as.length&&i<ATTRIBUTES.size();i++){if(slot==as[i]){startChatInput(p,"attr:"+ATTRIBUTES.get(i).name(),()->p.openInventory(buildAttr(p)));return;}}
    }

    private void flagClick(Player p, int slot) {
        if (slot == 53) { inTransition.add(p.getUniqueId()); p.openInventory(buildEditMain(p)); return; }
        EditingSession ses = sessions.get(p.getUniqueId());
        if (ses == null) return;
        int[] fs = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        CustomItemFlag[] all = CustomItemFlag.values();
        for (int i=0;i<fs.length&&i<all.length;i++){
            if(slot!=fs[i]) continue;
            if (all[i] == CustomItemFlag.NO_INSTANCE_UUID && ses.item.shouldGetInstanceUuid()) {
                p.sendMessage(ColorUtils.colorize("&cWeapons, Armor, and Tools are always non-stackable."));
                return;
            }
            ses.item.toggleFlag(all[i]);
            inTransition.add(p.getUniqueId());
            p.openInventory(buildFlags(p));
            return;
        }
    }

    private void potionEffectsClick(Player p, int slot, ClickType click) {
        EditingSession ses = sessions.get(p.getUniqueId());
        if (ses == null) return;
        if (slot == 49) { potionBrowserPage.put(p.getUniqueId(),0); inTransition.add(p.getUniqueId()); p.openInventory(buildPotionBrowser(p,0)); return; }
        if (slot == 53) { inTransition.add(p.getUniqueId()); p.openInventory(buildEditMain(p)); return; }
        int[] es = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        List<AdvancedCustomItem.PotionEffectData> effs = ses.item.getPotionEffects();
        for (int i=0;i<es.length;i++){if(slot==es[i]&&i<effs.size()&&click.isRightClick()){ses.item.removePotionEffect(i);inTransition.add(p.getUniqueId());p.openInventory(buildPotionEffects(p));return;}}
    }

    private void potionBrowserClick(Player p, int slot, ClickType click) {
        EditingSession ses = sessions.get(p.getUniqueId());
        if (ses == null) return;
        int page = potionBrowserPage.getOrDefault(p.getUniqueId(),0);
        int totalPages = (int)Math.ceil((double)POTION_EFFECTS.size()/28);
        if (slot==45&&page>0){potionBrowserPage.put(p.getUniqueId(),page-1);inTransition.add(p.getUniqueId());p.openInventory(buildPotionBrowser(p,page-1));return;}
        if (slot==53&&page<totalPages-1){potionBrowserPage.put(p.getUniqueId(),page+1);inTransition.add(p.getUniqueId());p.openInventory(buildPotionBrowser(p,page+1));return;}
        if (slot==49){potionBrowserPage.remove(p.getUniqueId());inTransition.add(p.getUniqueId());p.openInventory(buildPotionEffects(p));return;}
        int[] slots={10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        for(int i=0;i<slots.length;i++){if(slot==slots[i]&&(page*slots.length+i)<POTION_EFFECTS.size()){PotionEffectType type=POTION_EFFECTS.get(page*slots.length+i);if(ses.item.getPotionEffects().stream().anyMatch(e->e.getType().equals(type))){p.sendMessage(ColorUtils.colorize("&cAlready added!"));return;}ses.tempPotionType=type;potionBrowserPage.remove(p.getUniqueId());inTransition.add(p.getUniqueId());p.openInventory(buildAmplifierSelector(p,type));return;}}
    }

    private void amplifierClick(Player p, int slot, ClickType click) {
        EditingSession ses = sessions.get(p.getUniqueId());
        if (ses==null||ses.tempPotionType==null)return;
        if(slot==31){ses.tempPotionType=null;inTransition.add(p.getUniqueId());potionBrowserPage.put(p.getUniqueId(),0);p.openInventory(buildPotionBrowser(p,0));return;}
        if(slot==25){inTransition.add(p.getUniqueId());p.closeInventory();chatInput.put(p.getUniqueId(),"potion_amplifier");returnAction.put(p.getUniqueId(),()->{if(ses.tempPotionType!=null&&ses.tempPotionAmplifier>=0)p.openInventory(buildParticlesToggle(p,ses.tempPotionType,ses.tempPotionAmplifier));else{ses.tempPotionType=null;p.openInventory(buildPotionEffects(p));}});p.sendMessage(ColorUtils.colorize("&eType amplifier (0-127). Type &ccancel &eto go back."));return;}
        int[]ls={10,11,12,13,14,15,16};for(int i=0;i<ls.length;i++){if(slot==ls[i]){ses.tempPotionAmplifier=i;inTransition.add(p.getUniqueId());p.openInventory(buildParticlesToggle(p,ses.tempPotionType,i));return;}}
        int[]hs={19,20,21};for(int i=0;i<hs.length;i++){if(slot==hs[i]){ses.tempPotionAmplifier=7+i;inTransition.add(p.getUniqueId());p.openInventory(buildParticlesToggle(p,ses.tempPotionType,7+i));return;}}
    }

    private void particlesClick(Player p, int slot) {
        EditingSession ses = sessions.get(p.getUniqueId());
        if(ses==null||ses.tempPotionType==null)return;
        if(slot==22){ses.tempPotionType=null;inTransition.add(p.getUniqueId());p.openInventory(buildPotionEffects(p));return;}
        if(slot==11||slot==15){ses.item.addPotionEffect(new AdvancedCustomItem.PotionEffectData(ses.tempPotionType,ses.tempPotionAmplifier,slot==11));ses.tempPotionType=null;ses.tempPotionAmplifier=0;p.sendMessage(ColorUtils.colorize("&a✔ Potion effect added!"));inTransition.add(p.getUniqueId());p.openInventory(buildPotionEffects(p));}
    }

    private void categoryClick(Player p, int slot, ClickType click) {
        if(slot==22){inTransition.add(p.getUniqueId());p.openInventory(buildEditMain(p));return;}
        List<String> cats=ItemLoreBuilder.getCategoryNames();int[]cs={10,11,12,13,14,15,16};
        for(int i=0;i<cs.length&&i<cats.size();i++){
            if(slot!=cs[i]) continue;
            EditingSession ses=sessions.get(p.getUniqueId());
            if(ses==null) return;

            String category = cats.get(i);
            ses.item.setCategory(category);
            if (AdvancedCustomItem.isNonStackableCategory(category)) {
                ses.item.removeFlag(CustomItemFlag.NO_INSTANCE_UUID);
            }
            if (AdvancedCustomItem.isArmorCategory(category)) {
                p.sendMessage(ColorUtils.colorize("&aArmor selected. Choose its wearable piece."));
                inTransition.add(p.getUniqueId());
                p.openInventory(buildArmorPieceSelector(p));
                return;
            }

            ses.item.setArmorPiece(null);
            p.sendMessage(ColorUtils.colorize("&aCategory set to &b"+category));
            inTransition.add(p.getUniqueId());
            p.openInventory(buildEditMain(p));
            return;
        }
    }

    private void armorPieceClick(Player p, int slot) {
        EditingSession ses = sessions.get(p.getUniqueId());
        if (ses == null) return;
        if (slot == 22) { inTransition.add(p.getUniqueId()); p.openInventory(buildEditMain(p)); return; }

        ArmorPiece[] pieces = ArmorPiece.values();
        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < pieces.length; i++) {
            if (slot != slots[i]) continue;
            ses.item.setArmorPiece(pieces[i]);
            p.sendMessage(ColorUtils.colorize("&aArmor piece set to &b" + pieces[i].getDisplayName()));
            inTransition.add(p.getUniqueId());
            p.openInventory(buildEditMain(p));
            return;
        }
    }

    private void rarityClick(Player p, int slot, ClickType click) {
        if(slot==22){inTransition.add(p.getUniqueId());p.openInventory(buildEditMain(p));return;}
        ItemLoreBuilder.Rarity[] rarities=ItemLoreBuilder.Rarity.values();int[]rs={10,11,12,13,14,15,16};
        for(int i=0;i<rs.length&&i<rarities.length;i++){if(slot==rs[i]){EditingSession ses=sessions.get(p.getUniqueId());if(ses!=null){ses.item.setRarity(rarities[i]);p.sendMessage(ColorUtils.colorize("&aRarity set to "+rarities[i].getColorCode()+rarities[i].getDisplayName()));}inTransition.add(p.getUniqueId());p.openInventory(buildEditMain(p));return;}}
    }

    private void previewClick(Player p, int slot) { if(slot==53){inTransition.add(p.getUniqueId());p.openInventory(buildNameLore(p));} }

    // ==================== SESSION ====================

    private boolean commitSession(Player player) {
        EditingSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return false;
        if (AdvancedCustomItem.isArmorCategory(ses.item.getCategory()) && ses.item.getArmorPiece() == null) {
            player.sendMessage(ColorUtils.colorize("&cChoose a Helmet, Chestplate, Leggings, or Boots slot before saving armor."));
            inTransition.add(player.getUniqueId());
            player.openInventory(buildArmorPieceSelector(player));
            return false;
        }
        AdvancedCustomItem original = manager.getItem(ses.item.getId());
        if (original != null) {
            original.setBaseItem(ses.item.getBaseItem()); original.setDisplayName(ses.item.getDisplayName());
            original.setLore(ses.item.getLore()); original.setAttributes(ses.item.getAttributes());
            original.setFlags(ses.item.getFlags()); original.setCustomModelData(ses.item.getCustomModelData());
            original.setItemModel(ses.item.getItemModel());
            original.setDamage(ses.item.getDamage()); original.setFutureExtensions(ses.item.getFutureExtensions());
            original.setPotionEffects(ses.item.getPotionEffects()); original.setCategory(ses.item.getCategory());
            original.setArmorPiece(ses.item.getArmorPiece());
            original.setRarity(ses.item.getRarity()); original.setAbilities(ses.item.getAbilities());
            original.setEnchants(ses.item.getEnchants()); original.setDisabled(ses.item.isDisabled());
            manager.bumpVersion(ses.item.getId());
            manager.saveItems();
        }
        sessions.remove(player.getUniqueId()); chatInput.remove(player.getUniqueId());
        returnAction.remove(player.getUniqueId()); inTransition.remove(player.getUniqueId());
        return true;
    }

    private void commitAndExit(Player player, boolean close) { if (!commitSession(player)) return; if (close) player.closeInventory(); openMainMenu(player); }

    // ==================== ITEMS ====================

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack i=new ItemStack(mat); ItemMeta m=i.getItemMeta(); m.setDisplayName(ColorUtils.colorize(name));
        if(lore.length>0){List<String> l=new ArrayList<>();for(String s:lore)l.add(ColorUtils.colorize(s));m.setLore(l);}
        i.setItemMeta(m); return i;
    }
    private ItemStack toggleItem(Material mat, String name, boolean state) { return item(mat, name+" &7("+(state?"&a✔ ON":"&c✖ OFF")+")", "&7Click to toggle"); }
    private ItemStack deleteToggleItem(boolean on) { return item(on?Material.LIME_DYE:Material.RED_DYE, (on?"&a":"&c")+"🗑 Delete Mode &7("+(on?"&a✔ ON":"&c✖ OFF")+")"); }
    private ItemStack glass(Material mat) { ItemStack g=new ItemStack(mat); ItemMeta m=g.getItemMeta(); m.setDisplayName(" "); g.setItemMeta(m); return g; }
    private void filler(Inventory inv) { ItemStack f=glass(Material.BLACK_STAINED_GLASS_PANE); for(int i=0;i<inv.getSize();i++)if(inv.getItem(i)==null)inv.setItem(i,f); }
    private String formatPotionName(PotionEffectType type) { String k=type.getKey().getKey(); String[] w=k.replace("_"," ").split(" "); StringBuilder sb=new StringBuilder(); for(String s:w){if(!s.isEmpty()){sb.append(Character.toUpperCase(s.charAt(0)));if(s.length()>1)sb.append(s.substring(1));sb.append(" ");}} return sb.toString().trim(); }
    private void startChatInput(Player p, String key, Runnable ret) { inTransition.add(p.getUniqueId()); p.closeInventory(); chatInput.put(p.getUniqueId(), key); returnAction.put(p.getUniqueId(), ret); p.sendMessage(ColorUtils.colorize("&eType your input. Type &ccancel &eto go back.")); }

    private static class EditingSession {
        final AdvancedCustomItem item; PotionEffectType tempPotionType; int tempPotionAmplifier;
        EditingSession(AdvancedCustomItem item) { this.item = item; this.tempPotionType = null; this.tempPotionAmplifier = 0; }
    }
}
