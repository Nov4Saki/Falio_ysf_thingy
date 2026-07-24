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
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class AdminCraftingGUI {

    private final JavaPlugin plugin;
    private final CraftingManager craftingManager;
    private final CustomItemManager customItemManager;
    private final FileConfiguration config;
    private final Map<UUID, RecipeEditSession> editSessions;
    private final Map<UUID, Inventory> openEditors;
    private final NamespacedKey recipeIdKey; // new

    // Sub-editor state
    private enum SubEditorType { TIME, XP, MATERIAL }
    private static class SubEditorContext {
        SubEditorType type;
        Material material;
        RecipeEditSession session;
        Inventory inventory;
    }
    private final Map<UUID, SubEditorContext> subEditorContexts = new HashMap<>();

    // Inventory layout constants
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
        this.recipeIdKey = new NamespacedKey(plugin, "recipe_id"); // unique key
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

            // Store recipe ID in PDC
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

        ItemStack newBtn = createItem(Material.EMERALD_BLOCK, "&a✦ &6&lAdd New Recipe &a✦",
                "&7Click to create a new recipe",
                "&7(you can place result item later)");
        gui.setItem(49, newBtn);
        gui.setItem(45, createItem(Material.ARROW, "&a← Back"));

        ItemStack filler = createFiller();
        for (int i = 0; i < 54; i++) if (gui.getItem(i) == null) gui.setItem(i, filler);

        player.openInventory(gui);
    }

    public void handleAdminMenuClick(Player player, int slot, ClickType clickType, ItemStack clicked) {
        if (slot == 45) {
            player.closeInventory();
            FoliaScheduler.runEntityLater(player, plugin, () -> player.performCommand("craft"), 2L);
            return;
        }
        if (slot == 49) {
            String id = "recipe_" + System.currentTimeMillis();
            player.closeInventory();
            FoliaScheduler.runEntityLater(player, plugin, () -> openRecipeEditor(player, id, null), 2L);
            return;
        }
        if (clicked != null && clicked.hasItemMeta() && clicked.getType() != Material.BLACK_STAINED_GLASS_PANE) {
            // Retrieve recipe ID from PDC
            String recipeId = clicked.getItemMeta().getPersistentDataContainer().get(recipeIdKey, PersistentDataType.STRING);
            if (recipeId != null) {
                CraftingRecipe recipe = craftingManager.getRecipe(recipeId);
                if (recipe == null) {
                    // maybe recipe was deleted, refresh
                    openAdminMenu(player);
                    return;
                }
                if (clickType.isRightClick()) {
                    // Instant delete
                    craftingManager.unregisterRecipe(recipeId);
                    player.sendMessage(ColorUtils.colorize("&c✖ Recipe deleted!"));
                    player.closeInventory();
                    openAdminMenu(player);
                    return;
                } else {
                    // Edit
                    player.closeInventory();
                    FoliaScheduler.runEntityLater(player, plugin, () -> openRecipeEditor(player, recipeId, null), 2L);
                    return;
                }
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
            session.setCraftingTime(existing.getCraftingTime());
            session.setCategory(existing.getCategory());
            session.setExperienceReward(existing.getExperienceReward());
        } else if (initialResult != null) {
            session.setResult(initialResult);
        }

        editSessions.put(player.getUniqueId(), session);

        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text(ColorUtils.colorize("&c⚙ &4Recipe Editor &c⚙")));
        openEditors.put(player.getUniqueId(), inv);

        refreshEditorInventory(player, session, inv);
        player.openInventory(inv);
    }

    private void refreshEditorInventory(Player player, RecipeEditSession session, Inventory inv) {
        inv.clear();

        // Result slot – with placeholder
        if (session.getResult() != null) {
            inv.setItem(RESULT_SLOT, session.getResult().clone());
        } else {
            inv.setItem(RESULT_SLOT, createPlaceholder(Material.GRAY_STAINED_GLASS_PANE,
                    "&7Click to place result here",
                    "&7(Pick from your inventory and click)"));
        }

        // Material slots – show existing + placeholders for empty ones
        List<Map.Entry<Material, Integer>> materialList = new ArrayList<>(session.getMaterials().entrySet());
        for (int i = 0; i < MATERIAL_SLOTS.length; i++) {
            int slot = MATERIAL_SLOTS[i];
            if (i < materialList.size()) {
                Map.Entry<Material, Integer> entry = materialList.get(i);
                ItemStack mat = new ItemStack(entry.getKey(), Math.min(entry.getValue(), 64));
                ItemMeta meta = mat.getItemMeta();
                meta.displayName(Component.text(ColorUtils.colorize("&e" + formatMaterialName(entry.getKey()))));
                meta.lore(Arrays.asList(
                        Component.text(ColorUtils.colorize("&7Amount: &f" + entry.getValue())),
                        Component.text(ColorUtils.colorize("&a▶ Click to adjust amount")),
                        Component.text(ColorUtils.colorize("&c▶ Right-click to remove"))
                ));
                mat.setItemMeta(meta);
                inv.setItem(slot, mat);
            } else {
                inv.setItem(slot, createPlaceholder(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "&7Empty material slot",
                        "&7Click with material in hand to add"));
            }
        }

        // Control buttons
        inv.setItem(TIME_SLOT, createTimeButton(session));
        inv.setItem(XP_SLOT, createXPButton(session));
        inv.setItem(CATEGORY_SLOT, createCategoryButton(session));
        inv.setItem(SAVE_SLOT, createItem(Material.LIME_DYE, "&a✔ Save Recipe"));
        inv.setItem(CANCEL_SLOT, createItem(Material.RED_DYE, "&c✖ Cancel"));

        ItemStack filler = createFiller();
        for (int j = 0; j < 54; j++) {
            if (inv.getItem(j) == null) inv.setItem(j, filler);
        }

        openEditors.put(player.getUniqueId(), inv);
    }

    private ItemStack createPlaceholder(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize(name)));
        List<Component> loreList = new ArrayList<>();
        for (String line : lore) {
            loreList.add(Component.text(ColorUtils.colorize(line)));
        }
        meta.lore(loreList);
        item.setItemMeta(meta);
        return item;
    }

    // ---------- Editor Click Handling ----------
    public void handleEditorClick(Player player, int slot, ClickType clickType, ItemStack cursor, ItemStack current) {
        RecipeEditSession session = editSessions.get(player.getUniqueId());
        if (session == null) return;

        // Result slot
        if (slot == RESULT_SLOT) {
            if (cursor != null && cursor.getType() != Material.AIR) {
                session.setResult(cursor.clone());
                player.sendMessage(ColorUtils.colorize("&a✔ Result item updated!"));
                refreshEditor(player, session);
            } else if (current != null && current.getType() != Material.AIR && current.getType() != Material.BARRIER
                    && !current.getType().name().contains("GLASS_PANE")) {
                player.setItemOnCursor(current.clone());
                session.setResult(null);
                refreshEditor(player, session);
            }
            return;
        }

        // Material slots
        for (int i = 0; i < MATERIAL_SLOTS.length; i++) {
            if (slot == MATERIAL_SLOTS[i]) {
                handleMaterialSlot(player, session, i, clickType, cursor);
                return;
            }
        }

        // Time, XP, Category, Save, Cancel...
        if (slot == TIME_SLOT) {
            openTimeEditor(player, session);
            return;
        }
        if (slot == XP_SLOT) {
            openXPEditor(player, session);
            return;
        }
        if (slot == CATEGORY_SLOT) {
            String[] cats = {"Weapons", "Armor", "Tools", "Items", "Food", "Blocks", "Misc"};
            String cur = session.getCategory();
            int idx = Arrays.asList(cats).indexOf(cur);
            idx = (idx + 1) % cats.length;
            session.setCategory(cats[idx]);
            refreshEditor(player, session);
            return;
        }
        if (slot == SAVE_SLOT) {
            saveRecipe(player, session);
            return;
        }
        if (slot == CANCEL_SLOT) {
            editSessions.remove(player.getUniqueId());
            openEditors.remove(player.getUniqueId());
            player.closeInventory();
            FoliaScheduler.runEntityLater(player, plugin, () -> openAdminMenu(player), 2L);
            return;
        }
    }

    private void handleMaterialSlot(Player player, RecipeEditSession session, int index, ClickType clickType, ItemStack cursor) {
        List<Material> materials = new ArrayList<>(session.getMaterials().keySet());

        if (index < materials.size()) {
            Material mat = materials.get(index);
            if (clickType.isRightClick()) {
                session.removeMaterial(mat);
                refreshEditor(player, session);
            } else if (clickType.isLeftClick()) {
                openMaterialAmountEditor(player, session, mat);
            }
        } else {
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (session.getMaterials().size() >= MATERIAL_SLOTS.length) {
                    player.sendMessage(ColorUtils.colorize("&cCannot add more than " + MATERIAL_SLOTS.length + " materials!"));
                    return;
                }
                session.addMaterial(cursor.getType(), 1);
                refreshEditor(player, session);
            }
        }
    }

    // ---------- Sub-Editors (with +1/-1 and fillers) ----------
    private void openTimeEditor(Player player, RecipeEditSession session) {
        Inventory inv = Bukkit.createInventory(null, 27,
                Component.text(ColorUtils.colorize("&e⏰ Set Crafting Time")));

        long t = session.getCraftingTime();
        // Row 1: + values
        inv.setItem(10, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+1s", 1));
        inv.setItem(11, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+10s", 10));
        inv.setItem(12, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+60s", 60));
        inv.setItem(13, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+30m", 1800));
        inv.setItem(14, makeTimeButton(Material.LIME_STAINED_GLASS_PANE, "&a+1h", 3600));
        // Row 2: - values
        inv.setItem(19, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-1s", -1));
        inv.setItem(20, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-10s", -10));
        inv.setItem(21, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-60s", -60));
        inv.setItem(22, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-30m", -1800));
        inv.setItem(23, makeTimeButton(Material.RED_STAINED_GLASS_PANE, "&c-1h", -3600));

        // Current time display (centered)
        inv.setItem(26, createItem(Material.CLOCK, "&eCurrent: " + formatTime(t)));
        // Back button
        inv.setItem(25, createItem(Material.ARROW, "&a← Back"));

        // Fill empty spaces with glass panes to balance layout
        ItemStack filler = createFiller();
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        // Store context
        SubEditorContext ctx = new SubEditorContext();
        ctx.type = SubEditorType.TIME;
        ctx.session = session;
        ctx.inventory = inv;
        subEditorContexts.put(player.getUniqueId(), ctx);

        player.openInventory(inv);
    }

    private void openXPEditor(Player player, RecipeEditSession session) {
        Inventory inv = Bukkit.createInventory(null, 27,
                Component.text(ColorUtils.colorize("&a⭐ Set XP Reward")));

        int xp = session.getExperienceReward();
        // Row 1: + values
        inv.setItem(10, makeXPButton(Material.LIME_STAINED_GLASS_PANE, "&a+250 XP", 250));
        inv.setItem(11, makeXPButton(Material.LIME_STAINED_GLASS_PANE, "&a+1k XP", 1000));
        inv.setItem(12, makeXPButton(Material.LIME_STAINED_GLASS_PANE, "&a+5k XP", 5000));
        inv.setItem(13, makeXPButton(Material.LIME_STAINED_GLASS_PANE, "&a+10k XP", 10000));
        // Row 2: - values
        inv.setItem(19, makeXPButton(Material.RED_STAINED_GLASS_PANE, "&c-250 XP", -250));
        inv.setItem(20, makeXPButton(Material.RED_STAINED_GLASS_PANE, "&c-1k XP", -1000));
        inv.setItem(21, makeXPButton(Material.RED_STAINED_GLASS_PANE, "&c-5k XP", -5000));
        inv.setItem(22, makeXPButton(Material.RED_STAINED_GLASS_PANE, "&c-10k XP", -10000));

        // Current XP display
        inv.setItem(26, createItem(Material.EXPERIENCE_BOTTLE, "&aCurrent: " + xp + " XP"));
        inv.setItem(25, createItem(Material.ARROW, "&a← Back"));

        ItemStack filler = createFiller();
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        SubEditorContext ctx = new SubEditorContext();
        ctx.type = SubEditorType.XP;
        ctx.session = session;
        ctx.inventory = inv;
        subEditorContexts.put(player.getUniqueId(), ctx);

        player.openInventory(inv);
    }

    private void openMaterialAmountEditor(Player player, RecipeEditSession session, Material mat) {
        Inventory inv = Bukkit.createInventory(null, 27,
                Component.text(ColorUtils.colorize("&eSet Amount for " + formatMaterialName(mat))));

        int current = session.getMaterials().get(mat);
        // Row 1: + values
        inv.setItem(10, makeAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+1", mat, 1));
        inv.setItem(11, makeAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+4", mat, 4));
        inv.setItem(12, makeAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+16", mat, 16));
        inv.setItem(13, makeAmountButton(Material.LIME_STAINED_GLASS_PANE, "&a+64", mat, 64));
        // Row 2: - values
        inv.setItem(19, makeAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-1", mat, -1));
        inv.setItem(20, makeAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-4", mat, -4));
        inv.setItem(21, makeAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-16", mat, -16));
        inv.setItem(22, makeAmountButton(Material.RED_STAINED_GLASS_PANE, "&c-64", mat, -64));

        // Current amount display
        ItemStack display = new ItemStack(mat, Math.min(current, 64));
        ItemMeta meta = display.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize("&eCurrent amount: " + current)));
        display.setItemMeta(meta);
        inv.setItem(26, display);
        inv.setItem(25, createItem(Material.ARROW, "&a← Back"));

        ItemStack filler = createFiller();
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        SubEditorContext ctx = new SubEditorContext();
        ctx.type = SubEditorType.MATERIAL;
        ctx.material = mat;
        ctx.session = session;
        ctx.inventory = inv;
        subEditorContexts.put(player.getUniqueId(), ctx);

        player.openInventory(inv);
    }

    // ---------- Sub-editor click handling (stay open, only back closes) ----------
    public void handleSubEditorClick(Player player, String title, int slot, ClickType clickType) {
        SubEditorContext ctx = subEditorContexts.get(player.getUniqueId());
        if (ctx == null) return;
        RecipeEditSession session = ctx.session;
        Inventory subInv = ctx.inventory;

        // Helper to update the display value
        Runnable updateDisplay = () -> {
            // Update the display item in the sub-editor inventory
            if (subInv != null && player.getOpenInventory().getTopInventory().equals(subInv)) {
                switch (ctx.type) {
                    case TIME:
                        subInv.setItem(26, createItem(Material.CLOCK, "&eCurrent: " + formatTime(session.getCraftingTime())));
                        break;
                    case XP:
                        subInv.setItem(26, createItem(Material.EXPERIENCE_BOTTLE, "&aCurrent: " + session.getExperienceReward() + " XP"));
                        break;
                    case MATERIAL:
                        Material mat = ctx.material;
                        int cur = session.getMaterials().getOrDefault(mat, 0);
                        ItemStack display = new ItemStack(mat, Math.min(cur, 64));
                        ItemMeta meta = display.getItemMeta();
                        meta.displayName(Component.text(ColorUtils.colorize("&eCurrent amount: " + cur)));
                        display.setItemMeta(meta);
                        subInv.setItem(26, display);
                        break;
                }
                // Also update the main button items if they show values (time button shows time in main editor)
                // Not needed for sub-editor.
            }
        };

        if (title.contains("Set Crafting Time")) {
            // Check if slot is a time adjustment button (slots 10-14, 19-23)
            int[] adjustSlots = {10,11,12,13,14,19,20,21,22,23};
            long[] deltas = {1,10,60,1800,3600,-1,-10,-60,-1800,-3600};
            for (int i = 0; i < adjustSlots.length; i++) {
                if (slot == adjustSlots[i]) {
                    long newTime = Math.max(1, session.getCraftingTime() + deltas[i]);
                    session.setCraftingTime(newTime);
                    updateDisplay.run();
                    return; // stay in GUI
                }
            }
            if (slot == 25) { // Back
                player.closeInventory();
                subEditorContexts.remove(player.getUniqueId());
                // Reopen main editor
                FoliaScheduler.runEntityLater(player, plugin, () -> {
                    Inventory inv = openEditors.get(player.getUniqueId());
                    if (inv != null) {
                        refreshEditorInventory(player, session, inv);
                        player.openInventory(inv);
                    }
                }, 2L);
            }
        }
        else if (title.contains("Set XP Reward")) {
            int[] adjustSlots = {10,11,12,13,19,20,21,22};
            int[] deltas = {250,1000,5000,10000,-250,-1000,-5000,-10000};
            for (int i = 0; i < adjustSlots.length; i++) {
                if (slot == adjustSlots[i]) {
                    int newXP = Math.max(0, session.getExperienceReward() + deltas[i]);
                    session.setExperienceReward(newXP);
                    updateDisplay.run();
                    return;
                }
            }
            if (slot == 25) {
                player.closeInventory();
                subEditorContexts.remove(player.getUniqueId());
                FoliaScheduler.runEntityLater(player, plugin, () -> {
                    Inventory inv = openEditors.get(player.getUniqueId());
                    if (inv != null) {
                        refreshEditorInventory(player, session, inv);
                        player.openInventory(inv);
                    }
                }, 2L);
            }
        }
        else if (title.contains("Set Amount for")) {
            if (ctx.material == null) return;
            int[] adjustSlots = {10,11,12,13,19,20,21,22};
            int[] deltas = {1,4,16,64,-1,-4,-16,-64};
            for (int i = 0; i < adjustSlots.length; i++) {
                if (slot == adjustSlots[i]) {
                    int cur = session.getMaterials().getOrDefault(ctx.material, 0);
                    int newAmount = Math.max(0, cur + deltas[i]);
                    if (newAmount <= 0) session.removeMaterial(ctx.material);
                    else session.addMaterial(ctx.material, newAmount);
                    updateDisplay.run();
                    return;
                }
            }
            if (slot == 25) {
                player.closeInventory();
                subEditorContexts.remove(player.getUniqueId());
                FoliaScheduler.runEntityLater(player, plugin, () -> {
                    Inventory inv = openEditors.get(player.getUniqueId());
                    if (inv != null) {
                        refreshEditorInventory(player, session, inv);
                        player.openInventory(inv);
                    }
                }, 2L);
            }
        }
    }

    // ---------- Save and Utilities ----------
    private void saveRecipe(Player player, RecipeEditSession session) {
        if (session.getResult() == null) {
            player.sendMessage(ColorUtils.colorize("&cSet a result item first!"));
            return;
        }
        if (session.getMaterials().isEmpty()) {
            player.sendMessage(ColorUtils.colorize("&cAdd at least one material!"));
            return;
        }
        ItemStack newResult = session.getResult();
        for (CraftingRecipe existing : craftingManager.getAllRecipes()) {
            // Skip if it's the same recipe we're editing (by ID)
            if (existing.getId().equals(session.getRecipeId())) continue;
            if (newResult.isSimilar(existing.getResult())) {
                player.sendMessage(ColorUtils.colorize("&cA recipe with the same output already exists!"));
                return;
            }
        }

        CraftingRecipe recipe = new CraftingRecipe(
                session.getRecipeId(), session.getResult(), session.getMaterials(),
                session.getCraftingTime(), session.getCategory(), new ArrayList<>(), false,
                session.getExperienceReward()
        );
        craftingManager.registerRecipe(recipe);
        editSessions.remove(player.getUniqueId());
        openEditors.remove(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(ColorUtils.colorize("&a✔ Recipe saved!"));
    }

    private void refreshEditor(Player player, RecipeEditSession session) {
        Inventory inv = openEditors.get(player.getUniqueId());
        if (inv != null) {
            refreshEditorInventory(player, session, inv);
        }
    }

    public void syncEditorFromInventory(Player player, Inventory inv) {
        RecipeEditSession session = editSessions.get(player.getUniqueId());
        if (session != null) {
            refreshEditorInventory(player, session, inv);
        }
    }

    // ---------- Custom Items Menu (placeholder) ----------
    public void openCustomItemsMenu(Player player) {
        player.sendMessage(ColorUtils.colorize("&cCustom Items menu not fully implemented yet."));
    }

    public void handleCustomItemsClick(Player player, int slot, ClickType clickType, ItemStack clicked) {
        // placeholder
    }

    // ---------- Helpers ----------
    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize(name)));
        if (lore.length > 0) {
            List<Component> list = new ArrayList<>();
            for (String s : lore) list.add(Component.text(ColorUtils.colorize(s)));
            meta.lore(list);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFiller() {
        Material mat = Material.getMaterial(config.getString("crafting.gui.filler.material", "BLACK_STAINED_GLASS_PANE"));
        if (mat == null) mat = Material.BLACK_STAINED_GLASS_PANE;
        ItemStack f = new ItemStack(mat);
        ItemMeta m = f.getItemMeta();
        m.displayName(Component.text(" "));
        f.setItemMeta(m);
        return f;
    }

    private ItemStack createTimeButton(RecipeEditSession session) {
        long time = session.getCraftingTime();
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize("&e⏰ Crafting Time: &f" + formatTime(time))));
        meta.lore(Arrays.asList(
                Component.text(ColorUtils.colorize("&7&m-------------------")),
                Component.text(ColorUtils.colorize("&a▶ Left-click: Open time controls")),
                Component.text(ColorUtils.colorize("&7Current: " + formatTime(time))),
                Component.text(ColorUtils.colorize("&7&m-------------------"))
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createXPButton(RecipeEditSession session) {
        int xp = session.getExperienceReward();
        ItemStack item = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize("&a⭐ XP Reward: &f" + xp)));
        meta.lore(Arrays.asList(
                Component.text(ColorUtils.colorize("&7&m-------------------")),
                Component.text(ColorUtils.colorize("&a▶ Left-click: Open XP controls")),
                Component.text(ColorUtils.colorize("&7Current: " + xp + " XP")),
                Component.text(ColorUtils.colorize("&7&m-------------------"))
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCategoryButton(RecipeEditSession session) {
        ItemStack item = new ItemStack(Material.BOOKSHELF);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize("&d📚 Category: &f" + session.getCategory())));
        meta.lore(Arrays.asList(
                Component.text(ColorUtils.colorize("&7&m-------------------")),
                Component.text(ColorUtils.colorize("&a▶ Left-click: Cycle category")),
                Component.text(ColorUtils.colorize("&7&m-------------------"))
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeTimeButton(Material mat, String name, long delta) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize(name)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeXPButton(Material mat, String name, int delta) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize(name)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeAmountButton(Material mat, String name, Material targetMat, int delta) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize(name)));
        meta.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7Material: " + formatMaterialName(targetMat)))));
        item.setItemMeta(meta);
        return item;
    }

    private String formatTime(long seconds) {
        long h = seconds/3600, m = (seconds%3600)/60, s = seconds%60;
        if (h>0) return h+"h "+m+"m "+s+"s";
        if (m>0) return m+"m "+s+"s";
        return s+"s";
    }

    private String formatMaterialName(Material mat) {
        return mat.name().replace("_"," ").toLowerCase();
    }

    // ----- Cleanup on quit -----
    public void cleanupPlayer(UUID uuid) {
        editSessions.remove(uuid);
        openEditors.remove(uuid);
        subEditorContexts.remove(uuid);
    }
}