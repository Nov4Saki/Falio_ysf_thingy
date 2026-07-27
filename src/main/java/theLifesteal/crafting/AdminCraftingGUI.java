package theLifesteal.crafting;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import theLifesteal.ColorUtils;
import theLifesteal.TheLifesteal;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class AdminCraftingGUI {

    private final JavaPlugin plugin;
    private final CraftingManager craftingManager;
    private final CustomItemManager customItemManager;
    private final FileConfiguration config;
    private final Map<UUID, RecipeEditSession> editSessions;
    private final Map<UUID, Inventory> openEditors;
    private final NamespacedKey recipeIdKey;
    private final NamespacedKey customItemIdKey;

    private enum SubEditorType { TIME, XP, MATERIAL, CUSTOM_ITEM_MATERIAL }
    private static class SubEditorContext {
        SubEditorType type;
        Material material;
        String customItemId;
        RecipeEditSession session;
        Inventory inventory;
    }
    private final Map<UUID, SubEditorContext> subEditorContexts = new HashMap<>();

    private static final int RESULT_SLOT = 4;
    private static final int[] MATERIAL_SLOTS = {19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private static final int TIME_SLOT = 47;
    private static final int XP_SLOT = 48;
    private static final int CATEGORY_SLOT = 46;
    private static final int SAVE_SLOT = 52;
    private static final int CANCEL_SLOT = 53;

    public AdminCraftingGUI(JavaPlugin plugin, CraftingManager craftingManager,
                            CustomItemManager customItemManager, FileConfiguration config) {
        this.plugin = plugin;
        this.craftingManager = craftingManager;
        this.customItemManager = customItemManager;
        this.config = config;
        this.editSessions = new HashMap<>();
        this.openEditors = new HashMap<>();
        this.recipeIdKey = new NamespacedKey(plugin, "recipe_id");
        this.customItemIdKey = new NamespacedKey(plugin, "custom_item_id");
    }

    // ---------- Admin Main Menu ----------
    public void openAdminMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54,
                Component.text(ColorUtils.colorize("&c⚙ &4&lAdmin Crafting Menu &c⚙")));

        List<CraftingRecipe> allRecipes = new ArrayList<>(craftingManager.getAllRecipes());
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

        for (int i = 0; i < Math.min(allRecipes.size(), slots.length); i++) {
            CraftingRecipe recipe = allRecipes.get(i);
            ItemStack display = recipe.getResult().clone();
            ItemMeta meta = display.getItemMeta();
            meta.getPersistentDataContainer().set(recipeIdKey, PersistentDataType.STRING, recipe.getId());
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.addAll(Arrays.asList(
                    Component.text(""),
                    Component.text(ColorUtils.colorize("&7&m-------------------")),
                    Component.text(ColorUtils.colorize("&eID: &f" + recipe.getId())),
                    Component.text(ColorUtils.colorize("&eCategory: &f" + recipe.getCategory())),
                    Component.text(ColorUtils.colorize("&eTime: &f" + recipe.getCraftingTime() + "s")),
                    Component.text(ColorUtils.colorize("&a▶ Left-click to edit")),
                    Component.text(ColorUtils.colorize("&c▶ Right-click to delete"))
            ));
            meta.lore(lore);
            display.setItemMeta(meta);
            gui.setItem(slots[i], display);
        }

        gui.setItem(45, createItem(Material.ARROW, "&a← Back"));
        gui.setItem(49, createItem(Material.EMERALD_BLOCK, "&a✦ &6&lAdd New Recipe &a✦", "&7Click to create a new recipe"));
        gui.setItem(50, createItem(Material.CRAFTING_TABLE, "&6⟳ &eRefresh Recipe Items", "&7Rebuild all recipe results", "&7using current item definitions", "", "&eClick after editing custom items"));

        ItemStack filler = createFiller();
        for (int i = 0; i < 54; i++) if (gui.getItem(i) == null) gui.setItem(i, filler);
        player.openInventory(gui);
    }

    public void handleAdminMenuClick(Player player, int slot, ClickType clickType, ItemStack clicked) {
        if (slot == 45) { player.closeInventory(); FoliaScheduler.runEntityLater(player, plugin, () -> player.performCommand("craft"), 2L); return; }
        if (slot == 49) { String id = "recipe_" + System.currentTimeMillis(); player.closeInventory(); FoliaScheduler.runEntityLater(player, plugin, () -> openRecipeEditor(player, id, null), 2L); return; }
        if (slot == 50) { int updated = craftingManager.refreshRecipeResults(); player.sendMessage(ColorUtils.colorize("&a⟳ Refreshed " + updated + " recipe item(s).")); openAdminMenu(player); return; }
        if (clicked != null && clicked.hasItemMeta() && clicked.getType() != Material.BLACK_STAINED_GLASS_PANE) {
            String recipeId = clicked.getItemMeta().getPersistentDataContainer().get(recipeIdKey, PersistentDataType.STRING);
            if (recipeId != null) {
                CraftingRecipe recipe = craftingManager.getRecipe(recipeId);
                if (recipe == null) { openAdminMenu(player); return; }
                if (clickType.isRightClick()) { craftingManager.unregisterRecipe(recipeId); player.sendMessage(ColorUtils.colorize("&c✖ Recipe deleted!")); player.closeInventory(); openAdminMenu(player); return; }
                else { player.closeInventory(); FoliaScheduler.runEntityLater(player, plugin, () -> openRecipeEditor(player, recipeId, null), 2L); return; }
            }
        }
    }

    // ---------- Recipe Editor ----------
    public void openRecipeEditor(Player player, String recipeId, ItemStack initialResult) {
        CraftingRecipe existing = craftingManager.getRecipe(recipeId);
        RecipeEditSession session = new RecipeEditSession(recipeId);
        if (existing != null) {
            session.setResult(existing.getResult());
            session.setMaterials(new LinkedHashMap<>(existing.getMaterials()));
            session.setCustomItemMaterials(new LinkedHashMap<>(existing.getCustomItemMaterials()));
            session.setCraftingTime(existing.getCraftingTime());
            session.setCategory(existing.getCategory());
            session.setExperienceReward(existing.getExperienceReward());
        } else if (initialResult != null) { session.setResult(initialResult); }
        editSessions.put(player.getUniqueId(), session);
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(ColorUtils.colorize("&c⚙ &4Recipe Editor &c⚙")));
        openEditors.put(player.getUniqueId(), inv);
        refreshEditorInventory(player, session, inv);
        player.openInventory(inv);
    }

    private void refreshEditorInventory(Player player, RecipeEditSession session, Inventory inv) {
        inv.clear();
        if (session.getResult() != null) inv.setItem(RESULT_SLOT, session.getResult().clone());
        else inv.setItem(RESULT_SLOT, createPlaceholder(Material.GRAY_STAINED_GLASS_PANE, "&7Click to place result here"));

        int slotIndex = 0;
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();

        // Custom item materials first
        for (Map.Entry<String, Integer> entry : session.getCustomItemMaterials().entrySet()) {
            if (slotIndex >= MATERIAL_SLOTS.length) break;
            int slot = MATERIAL_SLOTS[slotIndex];
            String customItemId = entry.getKey();
            int amount = entry.getValue();
            ItemStack displayItem;
            if (advancedItemManager != null) {
                var advItem = advancedItemManager.getItem(customItemId);
                displayItem = advItem != null ? advancedItemManager.buildItem(advItem) : new ItemStack(Material.BARRIER);
            } else displayItem = new ItemStack(Material.BARRIER);
            displayItem.setAmount(Math.min(amount, 64));
            ItemMeta meta = displayItem.getItemMeta();
            meta.displayName(Component.text(ColorUtils.colorize("&d" + customItemId.replace("_", " "))));
            meta.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7Custom Item: &f" + customItemId)), Component.text(ColorUtils.colorize("&7Amount: &f" + amount)), Component.text(ColorUtils.colorize("&a▶ Click to adjust amount")), Component.text(ColorUtils.colorize("&c▶ Right-click to remove"))));
            displayItem.setItemMeta(meta);
            inv.setItem(slot, displayItem);
            slotIndex++;
        }

        // Vanilla materials
        List<Map.Entry<Material, Integer>> materialList = new ArrayList<>(session.getMaterials().entrySet());
        for (int i = 0; i < materialList.size() && slotIndex < MATERIAL_SLOTS.length; i++) {
            int slot = MATERIAL_SLOTS[slotIndex];
            Map.Entry<Material, Integer> entry = materialList.get(i);
            ItemStack mat = new ItemStack(entry.getKey(), Math.min(entry.getValue(), 64));
            ItemMeta meta = mat.getItemMeta();
            meta.displayName(Component.text(ColorUtils.colorize("&e" + formatMaterialName(entry.getKey()))));
            meta.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7Amount: &f" + entry.getValue())), Component.text(ColorUtils.colorize("&a▶ Click to adjust amount")), Component.text(ColorUtils.colorize("&c▶ Right-click to remove"))));
            mat.setItemMeta(meta);
            inv.setItem(slot, mat);
            slotIndex++;
        }

        for (int i = slotIndex; i < MATERIAL_SLOTS.length; i++)
            inv.setItem(MATERIAL_SLOTS[i], createPlaceholder(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty material slot", "&7Click with item in hand to add"));

        inv.setItem(TIME_SLOT, createTimeButton(session));
        inv.setItem(XP_SLOT, createXPButton(session));
        inv.setItem(CATEGORY_SLOT, createCategoryButton(session));
        inv.setItem(SAVE_SLOT, createItem(Material.LIME_DYE, "&a✔ Save Recipe"));
        inv.setItem(CANCEL_SLOT, createItem(Material.RED_DYE, "&c✖ Cancel"));

        ItemStack filler = createFiller();
        for (int j = 0; j < 54; j++) if (inv.getItem(j) == null) inv.setItem(j, filler);
        openEditors.put(player.getUniqueId(), inv);
    }

    private ItemStack createPlaceholder(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat); ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize(name)));
        List<Component> loreList = new ArrayList<>(); for (String line : lore) loreList.add(Component.text(ColorUtils.colorize(line)));
        meta.lore(loreList); item.setItemMeta(meta); return item;
    }

    // ---------- Editor Click Handling ----------
    public void handleEditorClick(Player player, int slot, ClickType clickType, ItemStack cursor, ItemStack current) {
        RecipeEditSession session = editSessions.get(player.getUniqueId());
        if (session == null) return;

        if (slot == RESULT_SLOT) {
            if (cursor != null && cursor.getType() != Material.AIR) { session.setResult(cursor.clone()); player.sendMessage(ColorUtils.colorize("&a✔ Result item updated!")); refreshEditor(player, session); }
            else if (current != null && current.getType() != Material.AIR && current.getType() != Material.BARRIER && !current.getType().name().contains("GLASS_PANE")) { player.setItemOnCursor(current.clone()); session.setResult(null); refreshEditor(player, session); }
            return;
        }

        for (int i = 0; i < MATERIAL_SLOTS.length; i++) { if (slot == MATERIAL_SLOTS[i]) { handleMaterialSlot(player, session, i, clickType, cursor); return; } }

        if (slot == TIME_SLOT) { openTimeEditor(player, session); return; }
        if (slot == XP_SLOT) { openXPEditor(player, session); return; }
        if (slot == CATEGORY_SLOT) { String[] cats = {"Weapons","Armor","Tools","Items","Food","Blocks","Misc"}; String cur = session.getCategory(); int idx = Arrays.asList(cats).indexOf(cur); idx = (idx+1)%cats.length; session.setCategory(cats[idx]); refreshEditor(player, session); return; }
        if (slot == SAVE_SLOT) { saveRecipe(player, session); return; }
        if (slot == CANCEL_SLOT) { editSessions.remove(player.getUniqueId()); openEditors.remove(player.getUniqueId()); player.closeInventory(); FoliaScheduler.runEntityLater(player, plugin, () -> openAdminMenu(player), 2L); }
    }

    private int getTotalMaterialCount(RecipeEditSession session) { return session.getMaterials().size() + session.getCustomItemMaterials().size(); }

    private void handleMaterialSlot(Player player, RecipeEditSession session, int index, ClickType clickType, ItemStack cursor) {
        int totalMaterials = getTotalMaterialCount(session);
        int customCount = session.getCustomItemMaterials().size();

        if (index < totalMaterials) {
            if (index < customCount) {
                List<String> customKeys = new ArrayList<>(session.getCustomItemMaterials().keySet());
                String customItemId = customKeys.get(index);
                if (clickType.isRightClick()) { session.removeCustomItemMaterial(customItemId); refreshEditor(player, session); }
                else if (clickType.isLeftClick()) { openCustomItemMaterialAmountEditor(player, session, customItemId); }
            } else {
                int materialIndex = index - customCount;
                List<Material> materials = new ArrayList<>(session.getMaterials().keySet());
                Material mat = materials.get(materialIndex);
                if (clickType.isRightClick()) { session.removeMaterial(mat); refreshEditor(player, session); }
                else if (clickType.isLeftClick()) { openMaterialAmountEditor(player, session, mat); }
            }
        } else {
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (totalMaterials >= MATERIAL_SLOTS.length) { player.sendMessage(ColorUtils.colorize("&cCannot add more than " + MATERIAL_SLOTS.length + " materials!")); return; }
                String customItemId = null;
                if (cursor.hasItemMeta()) customItemId = cursor.getItemMeta().getPersistentDataContainer().get(customItemIdKey, PersistentDataType.STRING);
                if (customItemId != null) {
                    if (session.getCustomItemMaterials().containsKey(customItemId)) { player.sendMessage(ColorUtils.colorize("&cAlready in recipe!")); return; }
                    session.addCustomItemMaterial(customItemId, 1); player.sendMessage(ColorUtils.colorize("&d✔ Custom item added: " + customItemId));
                } else { session.addMaterial(cursor.getType(), 1); player.sendMessage(ColorUtils.colorize("&a✔ Material added: " + formatMaterialName(cursor.getType()))); }
                refreshEditor(player, session);
            }
        }
    }

    // ---------- Sub-Editors ----------
    private void openTimeEditor(Player player, RecipeEditSession session) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(ColorUtils.colorize("&e⏰ Set Crafting Time")));
        long t = session.getCraftingTime();
        inv.setItem(10, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+1s", 1)); inv.setItem(11, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+10s", 10));
        inv.setItem(12, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+60s", 60)); inv.setItem(13, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+30m", 1800));
        inv.setItem(14, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+1h", 3600)); inv.setItem(19, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-1s", -1));
        inv.setItem(20, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-10s", -10)); inv.setItem(21, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-60s", -60));
        inv.setItem(22, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-30m", -1800)); inv.setItem(23, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-1h", -3600));
        inv.setItem(26, createItem(Material.CLOCK, "&eCurrent: " + formatTime(t))); inv.setItem(25, createItem(Material.ARROW, "&a← Back"));
        ItemStack filler = createFiller(); for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
        SubEditorContext ctx = new SubEditorContext(); ctx.type = SubEditorType.TIME; ctx.session = session; ctx.inventory = inv;
        subEditorContexts.put(player.getUniqueId(), ctx); player.openInventory(inv);
    }

    private void openXPEditor(Player player, RecipeEditSession session) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(ColorUtils.colorize("&a⭐ Set XP Reward")));
        int xp = session.getExperienceReward();
        inv.setItem(10, makeXPButton(Material.LIME_STAINED_GLASS_PANE, "&a+250 XP", 250)); inv.setItem(11, makeXPButton(Material.LIME_STAINED_GLASS_PANE, "&a+1k XP", 1000));
        inv.setItem(12, makeXPButton(Material.LIME_STAINED_GLASS_PANE, "&a+5k XP", 5000)); inv.setItem(13, makeXPButton(Material.LIME_STAINED_GLASS_PANE, "&a+10k XP", 10000));
        inv.setItem(19, makeXPButton(Material.RED_STAINED_GLASS_PANE, "&c-250 XP", -250)); inv.setItem(20, makeXPButton(Material.RED_STAINED_GLASS_PANE, "&c-1k XP", -1000));
        inv.setItem(21, makeXPButton(Material.RED_STAINED_GLASS_PANE, "&c-5k XP", -5000)); inv.setItem(22, makeXPButton(Material.RED_STAINED_GLASS_PANE, "&c-10k XP", -10000));
        inv.setItem(26, createItem(Material.EXPERIENCE_BOTTLE, "&aCurrent: " + xp + " XP")); inv.setItem(25, createItem(Material.ARROW, "&a← Back"));
        ItemStack filler = createFiller(); for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
        SubEditorContext ctx = new SubEditorContext(); ctx.type = SubEditorType.XP; ctx.session = session; ctx.inventory = inv;
        subEditorContexts.put(player.getUniqueId(), ctx); player.openInventory(inv);
    }

    private void openMaterialAmountEditor(Player player, RecipeEditSession session, Material mat) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(ColorUtils.colorize("&eSet Amount for " + formatMaterialName(mat))));
        int current = session.getMaterials().get(mat);
        inv.setItem(10, makeAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+1", mat, 1)); inv.setItem(11, makeAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+4", mat, 4));
        inv.setItem(12, makeAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+16", mat, 16)); inv.setItem(13, makeAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+64", mat, 64));
        inv.setItem(19, makeAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-1", mat, -1)); inv.setItem(20, makeAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-4", mat, -4));
        inv.setItem(21, makeAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-16", mat, -16)); inv.setItem(22, makeAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-64", mat, -64));
        ItemStack display = new ItemStack(mat, Math.min(current, 64)); ItemMeta meta = display.getItemMeta(); meta.displayName(Component.text(ColorUtils.colorize("&eCurrent: " + current))); display.setItemMeta(meta);
        inv.setItem(26, display); inv.setItem(25, createItem(Material.ARROW, "&a← Back"));
        ItemStack filler = createFiller(); for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
        SubEditorContext ctx = new SubEditorContext(); ctx.type = SubEditorType.MATERIAL; ctx.material = mat; ctx.session = session; ctx.inventory = inv;
        subEditorContexts.put(player.getUniqueId(), ctx); player.openInventory(inv);
    }

    private void openCustomItemMaterialAmountEditor(Player player, RecipeEditSession session, String customItemId) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(ColorUtils.colorize("&dSet Amount for " + customItemId)));
        int current = session.getCustomItemMaterials().get(customItemId);
        inv.setItem(10, makeCustomAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+1", customItemId, 1)); inv.setItem(11, makeCustomAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+4", customItemId, 4));
        inv.setItem(12, makeCustomAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+16", customItemId, 16)); inv.setItem(13, makeCustomAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+64", customItemId, 64));
        inv.setItem(19, makeCustomAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-1", customItemId, -1)); inv.setItem(20, makeCustomAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-4", customItemId, -4));
        inv.setItem(21, makeCustomAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-16", customItemId, -16)); inv.setItem(22, makeCustomAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-64", customItemId, -64));
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();
        ItemStack display; if (advancedItemManager != null) { var advItem = advancedItemManager.getItem(customItemId); display = advItem != null ? advancedItemManager.buildItem(advItem) : new ItemStack(Material.BARRIER); } else display = new ItemStack(Material.BARRIER);
        display.setAmount(Math.min(current, 64)); ItemMeta meta = display.getItemMeta(); meta.displayName(Component.text(ColorUtils.colorize("&dCurrent: " + current))); display.setItemMeta(meta);
        inv.setItem(26, display); inv.setItem(25, createItem(Material.ARROW, "&a← Back"));
        ItemStack filler = createFiller(); for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
        SubEditorContext ctx = new SubEditorContext(); ctx.type = SubEditorType.CUSTOM_ITEM_MATERIAL; ctx.customItemId = customItemId; ctx.session = session; ctx.inventory = inv;
        subEditorContexts.put(player.getUniqueId(), ctx); player.openInventory(inv);
    }

    // ---------- Sub-editor click handling ----------
    public void handleSubEditorClick(Player player, String title, int slot, ClickType clickType) {
        SubEditorContext ctx = subEditorContexts.get(player.getUniqueId());
        if (ctx == null) return;
        RecipeEditSession session = ctx.session;
        Inventory subInv = ctx.inventory;

        Runnable updateDisplay = () -> {
            if (subInv != null && player.getOpenInventory().getTopInventory().equals(subInv)) {
                switch (ctx.type) {
                    case TIME -> subInv.setItem(26, createItem(Material.CLOCK, "&eCurrent: " + formatTime(session.getCraftingTime())));
                    case XP -> subInv.setItem(26, createItem(Material.EXPERIENCE_BOTTLE, "&aCurrent: " + session.getExperienceReward() + " XP"));
                    case MATERIAL -> { if (ctx.material != null) { int cur = session.getMaterials().getOrDefault(ctx.material, 0); ItemStack d = new ItemStack(ctx.material, Math.min(cur, 64)); ItemMeta m = d.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize("&eCurrent: " + cur))); d.setItemMeta(m); subInv.setItem(26, d); } }
                    case CUSTOM_ITEM_MATERIAL -> { if (ctx.customItemId != null) { int cur = session.getCustomItemMaterials().getOrDefault(ctx.customItemId, 0); var aim = ((TheLifesteal) plugin).getAdvancedItemManager(); ItemStack d; if (aim != null) { var ai = aim.getItem(ctx.customItemId); d = ai != null ? aim.buildItem(ai) : new ItemStack(Material.BARRIER); } else d = new ItemStack(Material.BARRIER); d.setAmount(Math.min(cur, 64)); ItemMeta m = d.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize("&dCurrent: " + cur))); d.setItemMeta(m); subInv.setItem(26, d); } }
                }
            }
        };

        if (title.contains("Set Crafting Time")) {
            int[] adj = {10,11,12,13,14,19,20,21,22,23}; long[] del = {1,10,60,1800,3600,-1,-10,-60,-1800,-3600};
            for (int i = 0; i < adj.length; i++) { if (slot == adj[i]) { session.setCraftingTime(Math.max(1, session.getCraftingTime()+del[i])); updateDisplay.run(); return; } }
            if (slot == 25) { player.closeInventory(); subEditorContexts.remove(player.getUniqueId()); FoliaScheduler.runEntityLater(player, plugin, () -> { Inventory inv = openEditors.get(player.getUniqueId()); if (inv != null) { refreshEditorInventory(player, session, inv); player.openInventory(inv); } }, 2L); }
        } else if (title.contains("Set XP Reward")) {
            int[] adj = {10,11,12,13,19,20,21,22}; int[] del = {250,1000,5000,10000,-250,-1000,-5000,-10000};
            for (int i = 0; i < adj.length; i++) { if (slot == adj[i]) { session.setExperienceReward(Math.max(0, session.getExperienceReward()+del[i])); updateDisplay.run(); return; } }
            if (slot == 25) { player.closeInventory(); subEditorContexts.remove(player.getUniqueId()); FoliaScheduler.runEntityLater(player, plugin, () -> { Inventory inv = openEditors.get(player.getUniqueId()); if (inv != null) { refreshEditorInventory(player, session, inv); player.openInventory(inv); } }, 2L); }
        } else if (title.contains("Set Amount for")) {
            int[] adj = {10,11,12,13,19,20,21,22}; int[] del = {1,4,16,64,-1,-4,-16,-64};
            if (ctx.type == SubEditorType.CUSTOM_ITEM_MATERIAL && ctx.customItemId != null) {
                for (int i = 0; i < adj.length; i++) { if (slot == adj[i]) { int cur = session.getCustomItemMaterials().getOrDefault(ctx.customItemId, 0); int n = Math.max(0, cur+del[i]); if (n<=0) session.removeCustomItemMaterial(ctx.customItemId); else session.addCustomItemMaterial(ctx.customItemId, n); updateDisplay.run(); return; } }
            } else if (ctx.type == SubEditorType.MATERIAL && ctx.material != null) {
                for (int i = 0; i < adj.length; i++) { if (slot == adj[i]) { int cur = session.getMaterials().getOrDefault(ctx.material, 0); int n = Math.max(0, cur+del[i]); if (n<=0) session.removeMaterial(ctx.material); else session.addMaterial(ctx.material, n); updateDisplay.run(); return; } }
            }
            if (slot == 25) { player.closeInventory(); subEditorContexts.remove(player.getUniqueId()); FoliaScheduler.runEntityLater(player, plugin, () -> { Inventory inv = openEditors.get(player.getUniqueId()); if (inv != null) { refreshEditorInventory(player, session, inv); player.openInventory(inv); } }, 2L); }
        }
    }
    public void handleCustomItemsClick(Player player, int slot, ClickType clickType, ItemStack clicked) {
        // Placeholder — custom items are managed through /customitem GUI
        player.sendMessage(ColorUtils.colorize("&cCustom Items menu not fully implemented yet."));
    }

    // ---------- Save ----------
    private void saveRecipe(Player player, RecipeEditSession session) {
        if (session.getResult() == null) { player.sendMessage(ColorUtils.colorize("&cSet a result item first!")); return; }
        if (session.getMaterials().isEmpty() && session.getCustomItemMaterials().isEmpty()) { player.sendMessage(ColorUtils.colorize("&cAdd at least one material!")); return; }
        CraftingRecipe recipe = new CraftingRecipe(session.getRecipeId(), session.getResult(), session.getMaterials(), session.getCustomItemMaterials(), session.getCraftingTime(), session.getCategory(), new ArrayList<>(), false, session.getExperienceReward());
        craftingManager.registerRecipe(recipe);
        editSessions.remove(player.getUniqueId()); openEditors.remove(player.getUniqueId());
        player.closeInventory(); player.sendMessage(ColorUtils.colorize("&a✔ Recipe saved!"));
    }

    private void refreshEditor(Player player, RecipeEditSession session) { Inventory inv = openEditors.get(player.getUniqueId()); if (inv != null) refreshEditorInventory(player, session, inv); }

    // ---------- Helpers ----------
    private ItemStack createItem(Material mat, String name, String... lore) { ItemStack i = new ItemStack(mat); ItemMeta m = i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize(name))); if (lore.length>0){ List<Component> l=new ArrayList<>(); for(String s:lore) l.add(Component.text(ColorUtils.colorize(s))); m.lore(l); } i.setItemMeta(m); return i; }
    private ItemStack createFiller() { Material m = Material.getMaterial(config.getString("crafting.gui.filler.material","BLACK_STAINED_GLASS_PANE")); if(m==null)m=Material.BLACK_STAINED_GLASS_PANE; ItemStack f=new ItemStack(m); ItemMeta fm=f.getItemMeta(); fm.displayName(Component.text(" ")); f.setItemMeta(fm); return f; }
    private ItemStack createTimeButton(RecipeEditSession s) { long t=s.getCraftingTime(); ItemStack i=new ItemStack(Material.CLOCK); ItemMeta m=i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize("&e⏰ Crafting Time: &f"+formatTime(t)))); m.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7&m-------------------")),Component.text(ColorUtils.colorize("&a▶ Left-click: Open time controls")),Component.text(ColorUtils.colorize("&7Current: "+formatTime(t))),Component.text(ColorUtils.colorize("&7&m-------------------")))); i.setItemMeta(m); return i; }
    private ItemStack createXPButton(RecipeEditSession s) { int x=s.getExperienceReward(); ItemStack i=new ItemStack(Material.EXPERIENCE_BOTTLE); ItemMeta m=i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize("&a⭐ XP Reward: &f"+x))); m.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7&m-------------------")),Component.text(ColorUtils.colorize("&a▶ Left-click: Open XP controls")),Component.text(ColorUtils.colorize("&7Current: "+x+" XP")),Component.text(ColorUtils.colorize("&7&m-------------------")))); i.setItemMeta(m); return i; }
    private ItemStack createCategoryButton(RecipeEditSession s) { ItemStack i=new ItemStack(Material.BOOKSHELF); ItemMeta m=i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize("&d📚 Category: &f"+s.getCategory()))); m.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7&m-------------------")),Component.text(ColorUtils.colorize("&a▶ Left-click: Cycle category")),Component.text(ColorUtils.colorize("&7&m-------------------")))); i.setItemMeta(m); return i; }
    private ItemStack makeTimeButton(Material mat, String name, long delta) { ItemStack i=new ItemStack(mat); ItemMeta m=i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize(name))); i.setItemMeta(m); return i; }
    private ItemStack makeXPButton(Material mat, String name, int delta) { ItemStack i=new ItemStack(mat); ItemMeta m=i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize(name))); i.setItemMeta(m); return i; }
    private ItemStack makeAmountButton(Material mat, String name, Material target, int delta) { ItemStack i=new ItemStack(mat); ItemMeta m=i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize(name))); m.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7Material: "+formatMaterialName(target))))); i.setItemMeta(m); return i; }
    private ItemStack makeCustomAmountButton(Material mat, String name, String customId, int delta) { ItemStack i=new ItemStack(mat); ItemMeta m=i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize(name))); m.lore(Arrays.asList(Component.text(ColorUtils.colorize("&dCustom Item: "+customId)))); i.setItemMeta(m); return i; }
    private String formatTime(long s) { long h=s/3600,m=(s%3600)/60,sec=s%60; if(h>0)return h+"h "+m+"m "+sec+"s"; if(m>0)return m+"m "+sec+"s"; return sec+"s"; }
    private String formatMaterialName(Material mat) { return mat.name().replace("_"," ").toLowerCase(); }
    public void cleanupPlayer(UUID uuid) { editSessions.remove(uuid); openEditors.remove(uuid); subEditorContexts.remove(uuid); }
}