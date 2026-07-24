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

public class CraftingGUI {

    private final JavaPlugin plugin;
    private final CraftingManager craftingManager;
    private final CustomItemManager customItemManager;
    private final AdminCraftingGUI adminGUI;
    private final FileConfiguration config;
    private final Map<UUID, Integer> playerPages;
    private final Map<UUID, String> playerCategories;
    private final Map<UUID, String> viewingRecipe;
    private final NamespacedKey recipeIdKey; // NEW

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
        this.recipeIdKey = new NamespacedKey(plugin, "gui_recipe_id"); // NEW
    }

    public AdminCraftingGUI getAdminGUI() {
        return adminGUI;
    }

    public void openMainMenu(Player player) {
        playerPages.put(player.getUniqueId(), 0);
        playerCategories.put(player.getUniqueId(), "ALL");

        String title = ColorUtils.colorize(config.getString("crafting.gui.title",
                "&6✦ &e&lCustom Crafting Menu &6✦"));

        Inventory gui = Bukkit.createInventory(null, 54, Component.text(title));
        updateMainMenu(player, gui);
        player.openInventory(gui);
    }

    public void updateMainMenu(Player player, Inventory gui) {
        gui.clear();
        int page = playerPages.getOrDefault(player.getUniqueId(), 0);
        String category = playerCategories.getOrDefault(player.getUniqueId(), "ALL");

        List<CraftingRecipe> filteredRecipes;
        if (category.equals("ALL")) {
            filteredRecipes = new ArrayList<>(craftingManager.getAllRecipes());
        } else {
            filteredRecipes = craftingManager.getRecipesByCategory(category);
        }

        int itemsPerPage = 28;
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, filteredRecipes.size());

        int[] recipeSlots = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };

        int slotIndex = 0;
        for (int i = startIndex; i < endIndex && slotIndex < recipeSlots.length; i++) {
            CraftingRecipe recipe = filteredRecipes.get(i);
            ItemStack display = recipe.getResult().clone();
            ItemMeta meta = display.getItemMeta();

            // ---- Store recipe ID in PDC ----
            meta.getPersistentDataContainer().set(recipeIdKey, PersistentDataType.STRING, recipe.getId());

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
            lore.add(Component.text(ColorUtils.colorize("&e▶ Category: &f" + recipe.getCategory())));
            lore.add(Component.text(ColorUtils.colorize("&e▶ Crafting Time: &f" + formatTime(recipe.getCraftingTime()))));
            lore.add(Component.text(""));
            lore.add(Component.text(ColorUtils.colorize("&7Materials Required:")));
            for (Map.Entry<Material, Integer> mat : recipe.getMaterials().entrySet()) {
                lore.add(Component.text(ColorUtils.colorize("  &7• &f" +
                        formatMaterialName(mat.getKey()) + " &7x&f" + mat.getValue())));
            }
            lore.add(Component.text(""));
            lore.add(Component.text(ColorUtils.colorize("&e▶ Click to view details!")));
            lore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));

            meta.lore(lore);
            display.setItemMeta(meta);

            gui.setItem(recipeSlots[slotIndex], display);
            slotIndex++;
        }

        // Navigation buttons
        int maxPages = getMaxPages(filteredRecipes.size(), itemsPerPage);

        if (page > 0) {
            gui.setItem(45, createConfigButton("previous-page"));
        }

        if (endIndex < filteredRecipes.size()) {
            gui.setItem(53, createConfigButton("next-page"));
        }

        gui.setItem(49, createConfigButton("close-menu"));

        int activeCount = craftingManager.getPlayerProcesses(player.getUniqueId()).size();
        ItemStack activeButton = createConfigButton("active-crafts");
        ItemMeta activeMeta = activeButton.getItemMeta();
        String activeName = config.getString("crafting.gui.buttons.active-crafts.name",
                "&6⚒ Active Crafts: &e%count%");
        activeMeta.displayName(Component.text(ColorUtils.colorize(
                activeName.replace("%count%", String.valueOf(activeCount)))));
        activeButton.setItemMeta(activeMeta);
        gui.setItem(48, activeButton);

        ItemStack categoryButton = createConfigButton("category-filter");
        ItemMeta categoryMeta = categoryButton.getItemMeta();
        String categoryName = config.getString("crafting.gui.buttons.category-filter.name",
                "&dCategory: &f%category%");
        categoryMeta.displayName(Component.text(ColorUtils.colorize(
                categoryName.replace("%category%", category))));
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
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, filler.clone());
            }
        }
    }

    public void openRecipeDetails(Player player, String recipeId) {
        CraftingRecipe recipe = craftingManager.getRecipe(recipeId);
        if (recipe == null) return;

        viewingRecipe.put(player.getUniqueId(), recipeId);

        String title = ColorUtils.colorize(config.getString("crafting.gui.details-title",
                "&6✦ &eRecipe Details &6✦"));

        Inventory gui = Bukkit.createInventory(null, 54, Component.text(title));

        ItemStack result = recipe.getResult().clone();
        ItemMeta resultMeta = result.getItemMeta();
        List<Component> resultLore = new ArrayList<>();
        resultLore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
        resultLore.add(Component.text(ColorUtils.colorize("&e▶ Category: &f" + recipe.getCategory())));
        resultLore.add(Component.text(ColorUtils.colorize("&e▶ Crafting Time: &f" + formatTime(recipe.getCraftingTime()))));
        resultLore.add(Component.text(ColorUtils.colorize("&e▶ Type: &f" + (recipe.isShapeless() ? "Shapeless" : "Shaped"))));
        if (recipe.getExperienceReward() > 0) {
            resultLore.add(Component.text(ColorUtils.colorize("&e▶ XP Reward: &f" + recipe.getExperienceReward())));
        }
        resultLore.add(Component.text(""));
        if (recipe.getDescription() != null && !recipe.getDescription().isEmpty()) {
            resultLore.add(Component.text(ColorUtils.colorize("&e▶ Description:")));
            for (String desc : recipe.getDescription()) {
                resultLore.add(Component.text(ColorUtils.colorize("  &7" + desc)));
            }
        }
        resultLore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
        resultMeta.lore(resultLore);
        result.setItemMeta(resultMeta);

        gui.setItem(22, result);

        int[] matSlots = {10, 11, 12, 13, 19, 20, 21, 23, 24};
        int matIndex = 0;

        for (Map.Entry<Material, Integer> entry : recipe.getMaterials().entrySet()) {
            if (matIndex >= matSlots.length) break;

            ItemStack matItem = new ItemStack(entry.getKey(), entry.getValue());
            ItemMeta matMeta = matItem.getItemMeta();
            matMeta.displayName(Component.text(ColorUtils.colorize("&e" + formatMaterialName(entry.getKey()))));
            matMeta.lore(Arrays.asList(
                    Component.text(ColorUtils.colorize("&7Required: &f" + entry.getValue())),
                    Component.text(""),
                    Component.text(ColorUtils.colorize("&7Check your inventory for")),
                    Component.text(ColorUtils.colorize("&7this material!"))
            ));
            matItem.setItemMeta(matMeta);

            gui.setItem(matSlots[matIndex], matItem);
            matIndex++;
        }

        ItemStack craftButton = createConfigButton("start-crafting");
        ItemMeta craftMeta = craftButton.getItemMeta();
        List<Component> craftLore = new ArrayList<>();
        List<String> craftLoreConfig = config.getStringList("crafting.gui.buttons.start-crafting.lore");
        for (String line : craftLoreConfig) {
            craftLore.add(Component.text(ColorUtils.colorize(
                    line.replace("%time%", formatTime(recipe.getCraftingTime())))));
        }
        craftMeta.lore(craftLore);
        craftButton.setItemMeta(craftMeta);
        gui.setItem(31, craftButton);

        gui.setItem(49, createConfigButton("back-button"));

        ItemStack filler = createFillerItem();
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, filler.clone());
            }
        }

        player.openInventory(gui);
    }

    public void openActiveCrafts(Player player) {
        List<CraftingProcess> processes = craftingManager.getPlayerProcesses(player.getUniqueId());

        String title = ColorUtils.colorize(config.getString("crafting.gui.active-title",
                "&6✦ &eActive Crafts &6✦"));

        Inventory gui = Bukkit.createInventory(null, 54, Component.text(title));

        if (processes.isEmpty()) {
            ItemStack empty = new ItemStack(Material.GLASS_BOTTLE);
            ItemMeta emptyMeta = empty.getItemMeta();
            emptyMeta.displayName(Component.text(ColorUtils.colorize("&cNo Active Crafts")));
            emptyMeta.lore(Arrays.asList(
                    Component.text(ColorUtils.colorize("&7Start crafting in the")),
                    Component.text(ColorUtils.colorize("&7main crafting menu!"))
            ));
            empty.setItemMeta(emptyMeta);
            gui.setItem(22, empty);
        } else {
            for (int i = 0; i < Math.min(processes.size(), 28); i++) {
                CraftingProcess process = processes.get(i);
                CraftingRecipe recipe = process.getRecipe();

                ItemStack display = recipe.getResult().clone();
                ItemMeta meta = display.getItemMeta();

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
                if (process.isCompleted()) {
                    lore.add(Component.text(ColorUtils.colorize("&a✔ READY TO CLAIM!")));
                    lore.add(Component.text(""));
                    lore.add(Component.text(ColorUtils.colorize("&a▶ Left-click to collect!")));
                } else {
                    lore.add(Component.text(ColorUtils.colorize("&e▶ Time Remaining: &f" + process.getFormattedRemainingTime())));
                    double progress = process.getProgress();
                    int barLength = 20;
                    int filledChars = (int) (progress * barLength);
                    StringBuilder progressBar = new StringBuilder("&a");
                    for (int j = 0; j < barLength; j++) {
                        progressBar.append(j < filledChars ? "█" : "&7█");
                    }
                    lore.add(Component.text(ColorUtils.colorize(progressBar.toString() + " &f" + (int)(progress * 100) + "%")));
                    lore.add(Component.text(""));
                    lore.add(Component.text(ColorUtils.colorize("&c▶ Right-click to cancel")));
                }
                lore.add(Component.text(ColorUtils.colorize("&7&m-------------------")));
                meta.lore(lore);
                display.setItemMeta(meta);

                gui.setItem(i + 10, display);
            }
        }

        gui.setItem(49, createConfigButton("back-button"));

        // Clear completed button (slot 50)
        ItemStack clearButton = new ItemStack(Material.LAVA_BUCKET);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.displayName(Component.text(ColorUtils.colorize("&c✖ &4Clear Completed &c✖")));
        clearMeta.lore(Arrays.asList(
                Component.text(ColorUtils.colorize("&7Remove all claimed items")),
                Component.text(ColorUtils.colorize("&7from this list."))
        ));
        clearButton.setItemMeta(clearMeta);
        gui.setItem(50, clearButton);

        ItemStack filler = createFillerItem();
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, filler.clone());
            }
        }

        player.openInventory(gui);
    }

    public void handleClick(Player player, String title, int slot, ClickType clickType) {
        // Sub-editors
        if (title.contains("Set Crafting Time") || title.contains("Set XP Reward") || title.contains("Set Amount for")) {
            adminGUI.handleSubEditorClick(player, title, slot, clickType);
            return;
        }
        // Main menu
        if (title.contains("Custom Crafting Menu")) {
            handleMainMenuClick(player, slot, clickType);
            return;
        }
        // Recipe details
        if (title.contains("Recipe Details")) {
            handleRecipeDetailsClick(player, slot);
            return;
        }
        // Active crafts
        if (title.contains("Active Crafts")) {
            handleActiveCraftsClick(player, slot, clickType);
            return;
        }
        // Admin menu
        if (title.contains("Admin Crafting Menu")) {
            adminGUI.handleAdminMenuClick(player, slot, clickType, player.getOpenInventory().getItem(slot));
            return;
        }
        if (title.contains("Custom Items")) {
            adminGUI.handleCustomItemsClick(player, slot, clickType, player.getOpenInventory().getItem(slot));
            return;
        }
    }

    public void handleMainMenuClick(Player player, int slot, ClickType clickType) {
        ItemStack clicked = player.getOpenInventory().getItem(slot);
        if (clicked == null) return;

        int page = playerPages.getOrDefault(player.getUniqueId(), 0);

        switch (slot) {
            case 45:
                if (page > 0) {
                    playerPages.put(player.getUniqueId(), page - 1);
                    updateMainMenu(player, player.getOpenInventory().getTopInventory());
                }
                break;
            case 46:
                cycleCategories(player);
                playerPages.put(player.getUniqueId(), 0);
                updateMainMenu(player, player.getOpenInventory().getTopInventory());
                break;
            case 47:
                if (player.hasPermission("thelifesteal.admin")) {
                    FoliaScheduler.runEntityLater(player, plugin,
                            () -> adminGUI.openAdminMenu(player), 1L);
                }
                break;
            case 48:
                openActiveCrafts(player);
                break;
            case 49:
                player.closeInventory();
                break;
            case 53:
                playerPages.put(player.getUniqueId(), page + 1);
                updateMainMenu(player, player.getOpenInventory().getTopInventory());
                break;
            default:
                // ---- Fix: retrieve recipe ID from PDC ----
                if (clicked.hasItemMeta()) {
                    String recipeId = clicked.getItemMeta().getPersistentDataContainer()
                            .get(recipeIdKey, PersistentDataType.STRING);
                    if (recipeId != null) {
                        openRecipeDetails(player, recipeId);
                    }
                }
                break;
        }
    }

    public void handleRecipeDetailsClick(Player player, int slot) {
        switch (slot) {
            case 49:
                openMainMenu(player);
                break;
            case 31:
                String recipeId = viewingRecipe.get(player.getUniqueId());
                if (recipeId != null) {
                    CraftingRecipe recipe = craftingManager.getRecipe(recipeId);
                    if (recipe != null) {
                        if (craftingManager.startCrafting(player, recipeId)) {
                            player.sendMessage(ColorUtils.colorize("&a✦ Started crafting: &e" +
                                    recipe.getResult().getItemMeta().getDisplayName()));
                            player.sendMessage(ColorUtils.colorize("&7Time remaining: &f" +
                                    formatTime(recipe.getCraftingTime())));
                            player.closeInventory();
                        } else {
                            player.sendMessage(ColorUtils.colorize("&c✖ Cannot start crafting! Check materials or active crafts limit."));
                        }
                    }
                }
                break;
        }
    }

    public void handleActiveCraftsClick(Player player, int slot, ClickType clickType) {
        if (slot == 49) {
            openMainMenu(player);
            return;
        }

        int processIndex = slot - 10;
        List<CraftingProcess> processes = craftingManager.getPlayerProcesses(player.getUniqueId());

        if (processIndex >= 0 && processIndex < processes.size()) {
            CraftingProcess process = processes.get(processIndex);

            if (clickType.isRightClick()) {
                if (!process.isCompleted() && !process.isClaimed()) {
                    if (craftingManager.cancelCrafting(player, processIndex)) {
                        player.sendMessage(ColorUtils.colorize("&c✖ Craft cancelled - materials refunded!"));
                        openActiveCrafts(player);
                    }
                }
            } else if (clickType.isLeftClick()) {
                if (process.isCompleted() && !process.isClaimed()) {
                    if (craftingManager.claimItem(player, processIndex)) {
                        player.sendMessage(ColorUtils.colorize("&a✔ Item claimed successfully!"));
                        // Reopen after 1 tick to refresh
                        FoliaScheduler.runEntityLater(player, plugin, () -> openActiveCrafts(player), 1L);
                    }
                }
            }
        }
    }

    public void cycleCategories(Player player) {
        List<String> categories = new ArrayList<>();
        categories.add("ALL");
        categories.addAll(craftingManager.getCategories());

        String current = playerCategories.getOrDefault(player.getUniqueId(), "ALL");
        int currentIndex = categories.indexOf(current);
        int nextIndex = (currentIndex + 1) % categories.size();

        playerCategories.put(player.getUniqueId(), categories.get(nextIndex));
    }

    public String formatTime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }

    private String formatMaterialName(Material material) {
        return material.name().replace("_", " ").toLowerCase();
    }

    private ItemStack createConfigButton(String buttonPath) {
        String path = "crafting.gui.buttons." + buttonPath + ".";

        Material material = Material.getMaterial(
                config.getString(path + "material", "BARRIER"));
        if (material == null) material = Material.BARRIER;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(ColorUtils.colorize(
                config.getString(path + "name", "&7Button"))));

        List<String> loreConfig = config.getStringList(path + "lore");
        List<Component> lore = new ArrayList<>();
        for (String line : loreConfig) {
            lore.add(Component.text(ColorUtils.colorize(line)));
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFillerItem() {
        Material material = Material.getMaterial(
                config.getString("crafting.gui.filler.material", "BLACK_STAINED_GLASS_PANE"));
        if (material == null) material = Material.BLACK_STAINED_GLASS_PANE;

        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(ColorUtils.colorize(
                config.getString("crafting.gui.filler.name", " "))));
        filler.setItemMeta(meta);
        return filler;
    }

    private int getMaxPages(int totalItems, int itemsPerPage) {
        return (int) Math.ceil((double) totalItems / itemsPerPage);
    }

    public void removePlayer(UUID uuid) {
        playerPages.remove(uuid);
        playerCategories.remove(uuid);
        viewingRecipe.remove(uuid);
    }
}