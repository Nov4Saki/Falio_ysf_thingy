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

public class CraftingGUI {

    private final JavaPlugin plugin;
    private final CraftingManager craftingManager;
    private final CustomItemManager customItemManager;
    private final AdminCraftingGUI adminGUI;
    private final FileConfiguration config;
    private final Map<UUID, Integer> playerPages;
    private final Map<UUID, String> playerCategories;
    private final Map<UUID, String> viewingRecipe;
    private final NamespacedKey recipeIdKey;

    public CraftingGUI(JavaPlugin plugin, CraftingManager craftingManager,
                       FileConfiguration config, CustomItemManager customItemManager) {
        this.plugin = plugin;
        this.craftingManager = craftingManager;
        this.customItemManager = customItemManager;
        this.config = config;
        this.adminGUI = new AdminCraftingGUI(plugin, craftingManager, customItemManager, config);
        this.playerPages = new HashMap<>();
        this.playerCategories = new HashMap<>();
        this.viewingRecipe = new HashMap<>();
        this.recipeIdKey = new NamespacedKey(plugin, "gui_recipe_id");
    }

    public AdminCraftingGUI getAdminGUI() { return adminGUI; }

    public void openMainMenu(Player player) {
        playerPages.put(player.getUniqueId(), 0);
        playerCategories.put(player.getUniqueId(), "ALL");
        String title = ColorUtils.colorize(config.getString("crafting.gui.title", "&6✦ &e&lCustom Crafting Menu &6✦"));
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(title));
        updateMainMenu(player, gui);
        player.openInventory(gui);
    }

    public void updateMainMenu(Player player, Inventory gui) {
        gui.clear();
        int page = playerPages.getOrDefault(player.getUniqueId(), 0);
        String category = playerCategories.getOrDefault(player.getUniqueId(), "ALL");

        List<CraftingRecipe> filteredRecipes;
        if (category.equals("ALL")) filteredRecipes = new ArrayList<>(craftingManager.getAllRecipes());
        else filteredRecipes = craftingManager.getRecipesByCategory(category);

        int itemsPerPage = 28;
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, filteredRecipes.size());

        int[] recipeSlots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

        int slotIndex = 0;
        for (int i = startIndex; i < endIndex && slotIndex < recipeSlots.length; i++) {
            CraftingRecipe recipe = filteredRecipes.get(i);
            ItemStack display = recipe.getResult().clone();
            ItemMeta meta = display.getItemMeta();

            meta.getPersistentDataContainer().set(recipeIdKey, PersistentDataType.STRING, recipe.getId());

            // Clean preview: only show category, time, and a click hint — no item lore mixed in
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(""));
            lore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
            lore.add(Component.text(ColorUtils.colorize("&e▶ Category: &f" + recipe.getCategory())));
            lore.add(Component.text(ColorUtils.colorize("&e▶ Crafting Time: &f" + formatTime(recipe.getCraftingTime()))));
            lore.add(Component.text(""));
            lore.add(Component.text(ColorUtils.colorize("&e▶ Click to view full details!")));
            lore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
            meta.lore(lore);
            display.setItemMeta(meta);

            gui.setItem(recipeSlots[slotIndex], display);
            slotIndex++;
        }

        int maxPages = getMaxPages(filteredRecipes.size(), itemsPerPage);
        if (page > 0) gui.setItem(45, createConfigButton("previous-page"));
        if (endIndex < filteredRecipes.size()) gui.setItem(53, createConfigButton("next-page"));
        gui.setItem(49, createConfigButton("close-menu"));

        int activeCount = craftingManager.getPlayerProcesses(player.getUniqueId()).size();
        ItemStack activeButton = createConfigButton("active-crafts");
        ItemMeta activeMeta = activeButton.getItemMeta();
        String activeName = config.getString("crafting.gui.buttons.active-crafts.name", "&6⚒ Active Crafts: &e%count%");
        activeMeta.displayName(Component.text(ColorUtils.colorize(activeName.replace("%count%", String.valueOf(activeCount)))));
        activeButton.setItemMeta(activeMeta);
        gui.setItem(48, activeButton);

        ItemStack categoryButton = createConfigButton("category-filter");
        ItemMeta categoryMeta = categoryButton.getItemMeta();
        String categoryName = config.getString("crafting.gui.buttons.category-filter.name", "&dCategory: &f%category%");
        categoryMeta.displayName(Component.text(ColorUtils.colorize(categoryName.replace("%category%", category))));
        categoryButton.setItemMeta(categoryMeta);
        gui.setItem(46, categoryButton);

        if (player.hasPermission("thelifesteal.admin")) {
            ItemStack adminButton = new ItemStack(Material.REDSTONE_TORCH);
            ItemMeta adminMeta = adminButton.getItemMeta();
            adminMeta.displayName(Component.text(ColorUtils.colorize("&c⚙ &4&lAdmin Menu &c⚙")));
            adminMeta.lore(Arrays.asList(
                    Component.text(ColorUtils.colorize("&7&m-------------------")),
                    Component.text(ColorUtils.colorize("&c▶ Manage Recipes")),
                    Component.text(ColorUtils.colorize("&c▶ View Custom Items")),
                    Component.text(ColorUtils.colorize("&7&m-------------------"))
            ));
            adminButton.setItemMeta(adminMeta);
            gui.setItem(47, adminButton);
        }

        ItemStack filler = createFillerItem();
        for (int i = 0; i < gui.getSize(); i++) if (gui.getItem(i) == null) gui.setItem(i, filler.clone());
    }

    public void openRecipeDetails(Player player, String recipeId) {
        CraftingRecipe recipe = craftingManager.getRecipe(recipeId);
        if (recipe == null) return;

        viewingRecipe.put(player.getUniqueId(), recipeId);

        String title = ColorUtils.colorize(config.getString("crafting.gui.details-title", "&6✦ &eRecipe Details &6✦"));
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(title));

        // Result item with its FULL original lore intact
        ItemStack result = recipe.getResult().clone();
        ItemMeta resultMeta = result.getItemMeta();
        List<Component> resultLore = resultMeta.hasLore() ? new ArrayList<>(resultMeta.lore()) : new ArrayList<>();

        // Add crafting info below the item's own lore
        resultLore.add(Component.text(""));
        resultLore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
        resultLore.add(Component.text(ColorUtils.colorize("&e▶ Category: &f" + recipe.getCategory())));
        resultLore.add(Component.text(ColorUtils.colorize("&e▶ Crafting Time: &f" + formatTime(recipe.getCraftingTime()))));
        if (recipe.getExperienceReward() > 0) resultLore.add(Component.text(ColorUtils.colorize("&e▶ XP Reward: &f" + recipe.getExperienceReward())));
        if (recipe.getDescription() != null && !recipe.getDescription().isEmpty()) {
            resultLore.add(Component.text(""));
            for (String desc : recipe.getDescription()) resultLore.add(Component.text(ColorUtils.colorize("  &7" + desc)));
        }
        resultLore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
        resultMeta.lore(resultLore);
        result.setItemMeta(resultMeta);
        gui.setItem(22, result);

        // Material slots
        int[] matSlots = {10,11,12,13,19,20,21,23,24};
        int matIndex = 0;
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();

        for (Map.Entry<String, Integer> entry : recipe.getCustomItemMaterials().entrySet()) {
            if (matIndex >= matSlots.length) break;
            ItemStack matItem;
            if (advancedItemManager != null) {
                var customItem = advancedItemManager.getItem(entry.getKey());
                matItem = customItem != null ? advancedItemManager.buildItem(customItem) : new ItemStack(Material.BARRIER);
            } else matItem = new ItemStack(Material.BARRIER);
            matItem.setAmount(Math.min(entry.getValue(), 64));
            ItemMeta matMeta = matItem.getItemMeta();
            matMeta.displayName(Component.text(ColorUtils.colorize("&d" + entry.getKey())));
            matMeta.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7Required: &f" + entry.getValue())), Component.text(""), Component.text(ColorUtils.colorize("&7Custom item required"))));
            matItem.setItemMeta(matMeta);
            gui.setItem(matSlots[matIndex], matItem);
            matIndex++;
        }

        for (Map.Entry<Material, Integer> entry : recipe.getMaterials().entrySet()) {
            if (matIndex >= matSlots.length) break;
            ItemStack matItem = new ItemStack(entry.getKey(), entry.getValue());
            ItemMeta matMeta = matItem.getItemMeta();
            matMeta.displayName(Component.text(ColorUtils.colorize("&e" + formatMaterialName(entry.getKey()))));
            matMeta.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7Required: &f" + entry.getValue())), Component.text(""), Component.text(ColorUtils.colorize("&7Check your inventory"))));
            matItem.setItemMeta(matMeta);
            gui.setItem(matSlots[matIndex], matItem);
            matIndex++;
        }

        ItemStack craftButton = createConfigButton("start-crafting");
        ItemMeta craftMeta = craftButton.getItemMeta();
        List<Component> craftLore = new ArrayList<>();
        for (String line : config.getStringList("crafting.gui.buttons.start-crafting.lore"))
            craftLore.add(Component.text(ColorUtils.colorize(line.replace("%time%", formatTime(recipe.getCraftingTime())))));
        craftMeta.lore(craftLore);
        craftButton.setItemMeta(craftMeta);
        gui.setItem(31, craftButton);
        gui.setItem(49, createConfigButton("back-button"));

        ItemStack filler = createFillerItem();
        for (int i = 0; i < gui.getSize(); i++) if (gui.getItem(i) == null) gui.setItem(i, filler.clone());
        player.openInventory(gui);
    }

    public void openActiveCrafts(Player player) {
        List<CraftingProcess> processes = craftingManager.getPlayerProcesses(player.getUniqueId());
        String title = ColorUtils.colorize(config.getString("crafting.gui.active-title", "&6✦ &eActive Crafts &6✦"));
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(title));

        if (processes.isEmpty()) {
            ItemStack empty = new ItemStack(Material.GLASS_BOTTLE); ItemMeta em = empty.getItemMeta();
            em.displayName(Component.text(ColorUtils.colorize("&cNo Active Crafts"))); em.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7Start crafting in the main menu!")))); empty.setItemMeta(em); gui.setItem(22, empty);
        } else {
            for (int i = 0; i < Math.min(processes.size(), 28); i++) {
                CraftingProcess process = processes.get(i); CraftingRecipe recipe = process.getRecipe();
                ItemStack display = recipe.getResult().clone(); ItemMeta meta = display.getItemMeta();
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
                if (process.isCompleted()) { lore.add(Component.text(ColorUtils.colorize("&a✔ READY TO CLAIM!"))); lore.add(Component.text("")); lore.add(Component.text(ColorUtils.colorize("&a▶ Left-click to collect!"))); }
                else {
                    lore.add(Component.text(ColorUtils.colorize("&e▶ Time Remaining: &f" + process.getFormattedRemainingTime())));
                    double progress = process.getProgress(); int barLength = 20; int filled = (int)(progress*barLength);
                    StringBuilder bar = new StringBuilder("&a"); for (int j=0;j<barLength;j++) bar.append(j<filled?"█":"&7█");
                    lore.add(Component.text(ColorUtils.colorize(bar + " &f" + (int)(progress*100) + "%")));
                    lore.add(Component.text("")); lore.add(Component.text(ColorUtils.colorize("&c▶ Right-click to cancel")));
                }
                lore.add(Component.text(ColorUtils.colorize("&7&m-------------------"))); meta.lore(lore); display.setItemMeta(meta);
                gui.setItem(i+10, display);
            }
        }

        gui.setItem(49, createConfigButton("back-button"));
        ItemStack clearBtn = new ItemStack(Material.LAVA_BUCKET); ItemMeta cm = clearBtn.getItemMeta();
        cm.displayName(Component.text(ColorUtils.colorize("&c✖ &4Clear Completed &c✖"))); cm.lore(Arrays.asList(Component.text(ColorUtils.colorize("&7Remove all claimed items")))); clearBtn.setItemMeta(cm); gui.setItem(50, clearBtn);

        ItemStack filler = createFillerItem();
        for (int i = 0; i < gui.getSize(); i++) if (gui.getItem(i) == null) gui.setItem(i, filler.clone());
        player.openInventory(gui);
    }

    public void handleClick(Player player, String title, int slot, ClickType clickType) {
        if (title.contains("Set Crafting Time") || title.contains("Set XP Reward") || title.contains("Set Amount for")) { adminGUI.handleSubEditorClick(player, title, slot, clickType); return; }
        if (title.contains("Custom Crafting Menu")) { handleMainMenuClick(player, slot, clickType); return; }
        if (title.contains("Recipe Details")) { handleRecipeDetailsClick(player, slot); return; }
        if (title.contains("Active Crafts")) { handleActiveCraftsClick(player, slot, clickType); return; }
        if (title.contains("Admin Crafting Menu")) { adminGUI.handleAdminMenuClick(player, slot, clickType, player.getOpenInventory().getItem(slot)); return; }
        if (title.contains("Custom Items")) { adminGUI.handleCustomItemsClick(player, slot, clickType, player.getOpenInventory().getItem(slot)); return; }
    }

    public void handleMainMenuClick(Player player, int slot, ClickType clickType) {
        ItemStack clicked = player.getOpenInventory().getItem(slot);
        if (clicked == null) return;
        int page = playerPages.getOrDefault(player.getUniqueId(), 0);
        switch (slot) {
            case 45: if (page>0) { playerPages.put(player.getUniqueId(), page-1); updateMainMenu(player, player.getOpenInventory().getTopInventory()); } break;
            case 46: cycleCategories(player); playerPages.put(player.getUniqueId(), 0); updateMainMenu(player, player.getOpenInventory().getTopInventory()); break;
            case 47: if (player.hasPermission("thelifesteal.admin")) FoliaScheduler.runEntityLater(player, plugin, () -> adminGUI.openAdminMenu(player), 1L); break;
            case 48: openActiveCrafts(player); break;
            case 49: player.closeInventory(); break;
            case 53: playerPages.put(player.getUniqueId(), page+1); updateMainMenu(player, player.getOpenInventory().getTopInventory()); break;
            default: if (clicked.hasItemMeta()) { String id = clicked.getItemMeta().getPersistentDataContainer().get(recipeIdKey, PersistentDataType.STRING); if (id != null) openRecipeDetails(player, id); } break;
        }
    }

    public void handleRecipeDetailsClick(Player player, int slot) {
        if (slot == 49) { openMainMenu(player); return; }
        if (slot == 31) {
            String recipeId = viewingRecipe.get(player.getUniqueId());
            if (recipeId != null) {
                CraftingRecipe recipe = craftingManager.getRecipe(recipeId);
                if (recipe != null) {
                    if (craftingManager.startCrafting(player, recipeId)) {
                        player.sendMessage(ColorUtils.colorize("&a✦ Started crafting: &e" + recipe.getResult().getItemMeta().getDisplayName()));
                        player.sendMessage(ColorUtils.colorize("&7Time remaining: &f" + formatTime(recipe.getCraftingTime())));
                        player.closeInventory();
                    } else player.sendMessage(ColorUtils.colorize("&c✖ Cannot start crafting! Check materials or active crafts limit."));
                }
            }
        }
    }

    public void handleActiveCraftsClick(Player player, int slot, ClickType clickType) {
        if (slot == 49) { openMainMenu(player); return; }
        if (slot == 50) { craftingManager.clearClaimedProcesses(player.getUniqueId()); openActiveCrafts(player); return; }
        int idx = slot - 10;
        List<CraftingProcess> processes = craftingManager.getPlayerProcesses(player.getUniqueId());
        if (idx >= 0 && idx < processes.size()) {
            CraftingProcess process = processes.get(idx);
            if (clickType.isRightClick()) { if (!process.isCompleted() && !process.isClaimed()) { if (craftingManager.cancelCrafting(player, idx)) { player.sendMessage(ColorUtils.colorize("&c✖ Craft cancelled - materials refunded!")); openActiveCrafts(player); } } }
            else if (clickType.isLeftClick()) { if (process.isCompleted() && !process.isClaimed()) { if (craftingManager.claimItem(player, idx)) { player.sendMessage(ColorUtils.colorize("&a✔ Item claimed successfully!")); FoliaScheduler.runEntityLater(player, plugin, () -> openActiveCrafts(player), 1L); } } }
        }
    }

    public void cycleCategories(Player player) {
        List<String> cats = new ArrayList<>(); cats.add("ALL"); cats.addAll(craftingManager.getCategories());
        String cur = playerCategories.getOrDefault(player.getUniqueId(), "ALL");
        int idx = cats.indexOf(cur); idx = (idx+1) % cats.size();
        playerCategories.put(player.getUniqueId(), cats.get(idx));
    }

    public String formatTime(long s) { long h=s/3600,m=(s%3600)/60,sec=s%60; if(h>0)return h+"h "+m+"m "+sec+"s"; if(m>0)return m+"m "+sec+"s"; return sec+"s"; }
    private String formatMaterialName(Material m) { return m.name().replace("_"," ").toLowerCase(); }

    private ItemStack createConfigButton(String path) {
        String p = "crafting.gui.buttons."+path+"."; Material mat = Material.getMaterial(config.getString(p+"material","BARRIER")); if(mat==null)mat=Material.BARRIER;
        ItemStack i = new ItemStack(mat); ItemMeta m = i.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize(config.getString(p+"name","&7Button"))));
        List<String> lc = config.getStringList(p+"lore"); List<Component> l = new ArrayList<>(); for(String s:lc) l.add(Component.text(ColorUtils.colorize(s))); m.lore(l); i.setItemMeta(m); return i;
    }

    private ItemStack createFillerItem() {
        Material mat = Material.getMaterial(config.getString("crafting.gui.filler.material","BLACK_STAINED_GLASS_PANE")); if(mat==null)mat=Material.BLACK_STAINED_GLASS_PANE;
        ItemStack f = new ItemStack(mat); ItemMeta m = f.getItemMeta(); m.displayName(Component.text(ColorUtils.colorize(config.getString("crafting.gui.filler.name"," ")))); f.setItemMeta(m); return f;
    }

    private int getMaxPages(int total, int per) { return (int)Math.ceil((double)total/per); }
    public void removePlayer(UUID uuid) { playerPages.remove(uuid); playerCategories.remove(uuid); viewingRecipe.remove(uuid); }
}