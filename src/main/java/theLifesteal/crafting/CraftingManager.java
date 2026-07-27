package theLifesteal.crafting;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.TheLifesteal;
import theLifesteal.customitem.AdvancedCustomItem;
import theLifesteal.util.FoliaScheduler;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CraftingManager {

    private final JavaPlugin plugin;
    private final Map<String, CraftingRecipe> recipes;
    private final Map<UUID, List<CraftingProcess>> activeProcesses;
    private final NamespacedKey customItemIdKey;
    private File dataFile;
    private boolean savingInProgress = false;
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);
    private Map<UUID, List<CraftingProcessData>> pendingSnapshot = null;
    private final Object saveLock = new Object();

    public CraftingManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.recipes = new LinkedHashMap<>();
        this.activeProcesses = new ConcurrentHashMap<>();
        this.customItemIdKey = new NamespacedKey(plugin, "custom_item_id");
        this.dataFile = new File(plugin.getDataFolder(), "crafting_data.yml");
        registerDefaultRecipes();
        loadCustomRecipes();
        startCleanupTask();
    }

    public int getMaxCrafts(Player player) {
        return ((TheLifesteal) plugin).getConfigManager().getMaxCraftsForPlayer(player);
    }

    private void startCleanupTask() {
        FoliaScheduler.runGlobalTimer(plugin, () -> {
            int removed = 0;
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, List<CraftingProcess>>> iterator = activeProcesses.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, List<CraftingProcess>> entry = iterator.next();
                List<CraftingProcess> processes = entry.getValue();
                Iterator<CraftingProcess> processIterator = processes.iterator();
                while (processIterator.hasNext()) {
                    CraftingProcess process = processIterator.next();
                    if (process.isClaimed() && (now - process.getEndTime()) > 300000) {
                        processIterator.remove();
                        removed++;
                    }
                }
                if (processes.isEmpty()) iterator.remove();
            }
            if (removed > 0) { plugin.getLogger().info("Cleaned up " + removed + " old crafting processes"); forceSave(); }
        }, 6000L, 6000L);
    }

    private void registerDefaultRecipes() {}

    private void loadCustomRecipes() {
        File recipesFile = new File(plugin.getDataFolder(), "recipes.yml");
        if (!recipesFile.exists()) { plugin.getLogger().info("No recipes.yml found."); return; }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(recipesFile);
        for (String key : config.getKeys(false)) {
            try {
                ItemStack result = config.getItemStack(key + ".result");
                if (result == null) continue;

                Map<Material, Integer> materials = new LinkedHashMap<>();
                List<String> materialList = config.getStringList(key + ".materials");
                for (String matStr : materialList) {
                    String[] parts = matStr.split(":");
                    if (parts.length == 2) {
                        Material mat = Material.getMaterial(parts[0]);
                        int amount = Integer.parseInt(parts[1]);
                        if (mat != null) materials.put(mat, amount);
                    }
                }

                Map<String, Integer> customItemMaterials = new LinkedHashMap<>();
                List<String> customMatList = config.getStringList(key + ".custom-materials");
                for (String customMatStr : customMatList) {
                    String[] parts = customMatStr.split(":");
                    if (parts.length == 2) {
                        customItemMaterials.put(parts[0], Integer.parseInt(parts[1]));
                    }
                }

                long time = config.getLong(key + ".time", 60);
                String category = config.getString(key + ".category", "Misc");
                List<String> description = config.getStringList(key + ".description");
                boolean shapeless = config.getBoolean(key + ".shapeless", false);
                int xp = config.getInt(key + ".xp", 0);

                CraftingRecipe recipe = new CraftingRecipe(key, result, materials, customItemMaterials,
                        time, category, description, shapeless, xp);
                recipes.put(key, recipe);
            } catch (Exception e) { plugin.getLogger().warning("Failed to load recipe: " + key + " - " + e.getMessage()); }
        }
        plugin.getLogger().info("Loaded " + recipes.size() + " custom recipes");
    }

    public void saveRecipes() {
        File recipesFile = new File(plugin.getDataFolder(), "recipes.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, CraftingRecipe> entry : recipes.entrySet()) {
            String key = entry.getKey();
            CraftingRecipe recipe = entry.getValue();
            config.set(key + ".result", recipe.getResult());
            List<String> materialList = new ArrayList<>();
            for (Map.Entry<Material, Integer> mat : recipe.getMaterials().entrySet())
                materialList.add(mat.getKey().name() + ":" + mat.getValue());
            config.set(key + ".materials", materialList);
            List<String> customMatList = new ArrayList<>();
            for (Map.Entry<String, Integer> customMat : recipe.getCustomItemMaterials().entrySet())
                customMatList.add(customMat.getKey() + ":" + customMat.getValue());
            if (!customMatList.isEmpty()) config.set(key + ".custom-materials", customMatList);
            config.set(key + ".time", recipe.getCraftingTime());
            config.set(key + ".category", recipe.getCategory());
            config.set(key + ".description", recipe.getDescription());
            config.set(key + ".shapeless", recipe.isShapeless());
            config.set(key + ".xp", recipe.getExperienceReward());
        }
        try { config.save(recipesFile); } catch (IOException e) { plugin.getLogger().warning("Failed to save recipes: " + e.getMessage()); }
    }

    public boolean startCrafting(Player player, String recipeId) {
        CraftingRecipe recipe = recipes.get(recipeId);
        if (recipe == null) return false;

        // Block crafting disabled items
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();
        if (advancedItemManager != null) {
            String resultId = advancedItemManager.getItemId(recipe.getResult());
            if (resultId != null) {
                AdvancedCustomItem def = advancedItemManager.getItem(resultId);
                if (def != null && def.isDisabled()) {
                    player.sendMessage("§cThis item has been disabled and cannot be crafted.");
                    return false;
                }
            }
        }

        List<CraftingProcess> processes = activeProcesses.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        int max = getMaxCrafts(player);
        if (processes.size() >= max) { player.sendMessage("§cMax concurrent crafts reached! (" + max + ")"); return false; }
        if (!hasRequiredMaterials(player, recipe)) return false;
        removeMaterials(player, recipe);
        CraftingProcess process = new CraftingProcess(player.getUniqueId(), recipe);
        processes.add(process);
        scheduleSave();
        return true;
    }

    private boolean hasRequiredMaterials(Player player, CraftingRecipe recipe) {
        Map<Material, Integer> required = recipe.getMaterials();
        Map<String, Integer> requiredCustom = recipe.getCustomItemMaterials();
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();

        Map<Material, Integer> available = new HashMap<>();
        Map<String, Integer> availableCustom = new HashMap<>();

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            String customId = null;
            if (advancedItemManager != null && item.hasItemMeta()) {
                customId = item.getItemMeta().getPersistentDataContainer().get(customItemIdKey, PersistentDataType.STRING);
            }
            if (customId != null && requiredCustom.containsKey(customId))
                availableCustom.merge(customId, item.getAmount(), Integer::sum);
            else if (required.containsKey(item.getType()))
                available.merge(item.getType(), item.getAmount(), Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : requiredCustom.entrySet()) {
            if (availableCustom.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                player.sendMessage("§cMissing custom item: " + entry.getKey() + " x" + entry.getValue());
                return false;
            }
        }
        for (Map.Entry<Material, Integer> entry : required.entrySet()) {
            if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                player.sendMessage("§cMissing: " + formatMaterialName(entry.getKey()) + " x" + entry.getValue());
                return false;
            }
        }
        return true;
    }

    private void removeMaterials(Player player, CraftingRecipe recipe) {
        Map<String, Integer> requiredCustom = recipe.getCustomItemMaterials();
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();

        for (Map.Entry<String, Integer> entry : requiredCustom.entrySet()) {
            String customId = entry.getKey();
            int needed = entry.getValue();
            int removed = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item == null || removed >= needed) continue;
                String itemCustomId = null;
                if (advancedItemManager != null && item.hasItemMeta())
                    itemCustomId = item.getItemMeta().getPersistentDataContainer().get(customItemIdKey, PersistentDataType.STRING);
                if (customId.equals(itemCustomId)) {
                    int toRemove = Math.min(item.getAmount(), needed - removed);
                    item.setAmount(item.getAmount() - toRemove);
                    removed += toRemove;
                    if (item.getAmount() <= 0 && advancedItemManager != null) {
                        String instanceUuid = advancedItemManager.getInstanceUuid(item);
                        if (instanceUuid != null) advancedItemManager.removeInstance(instanceUuid);
                    }
                }
            }
        }

        for (Map.Entry<Material, Integer> entry : recipe.getMaterials().entrySet())
            player.getInventory().removeItem(new ItemStack(entry.getKey(), entry.getValue()));
    }

    public boolean cancelCrafting(Player player, int processIndex) {
        List<CraftingProcess> processes = activeProcesses.get(player.getUniqueId());
        if (processes == null || processIndex < 0 || processIndex >= processes.size()) return false;
        CraftingProcess process = processes.get(processIndex);
        if (process.isClaimed()) return false;

        Map<Material, Integer> materials = process.getRecipe().getMaterials();
        for (Map.Entry<Material, Integer> entry : materials.entrySet()) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(entry.getKey(), entry.getValue()));
            for (ItemStack item : leftover.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);
        }

        Map<String, Integer> customMaterials = process.getRecipe().getCustomItemMaterials();
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();
        if (advancedItemManager != null && !customMaterials.isEmpty()) {
            for (Map.Entry<String, Integer> entry : customMaterials.entrySet()) {
                var customItem = advancedItemManager.getItem(entry.getKey());
                if (customItem != null) {
                    for (int i = 0; i < entry.getValue(); i++) {
                        ItemStack refund = advancedItemManager.buildItemForPlayer(customItem);
                        Map<Integer, ItemStack> leftover = player.getInventory().addItem(refund);
                        for (ItemStack item : leftover.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);
                    }
                }
            }
        }

        processes.remove(processIndex);
        if (processes.isEmpty()) activeProcesses.remove(player.getUniqueId());
        scheduleSave();
        return true;
    }

    public boolean claimItem(Player player, int processIndex) {
        List<CraftingProcess> processes = activeProcesses.get(player.getUniqueId());
        if (processes == null || processIndex < 0 || processIndex >= processes.size()) return false;
        CraftingProcess process = processes.get(processIndex);
        if (!process.isCompleted()) { player.sendMessage("§cNot ready yet!"); return false; }
        if (process.isClaimed()) { player.sendMessage("§cAlready claimed!"); return false; }

        // Rebuild result from current definition if it's a custom item
        ItemStack result = process.getRecipe().getResult();
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();
        if (advancedItemManager != null) {
            String itemId = advancedItemManager.getItemId(result);
            if (itemId != null) {
                AdvancedCustomItem def = advancedItemManager.getItem(itemId);
                if (def != null && !def.isDisabled()) {
                    result = advancedItemManager.buildItemForPlayer(def);
                }
            }
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(result);
        if (!leftover.isEmpty()) { player.sendMessage("§cInventory full!"); return false; }
        process.setClaimed(true);
        player.giveExp(process.getRecipe().getExperienceReward());
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        scheduleSave();
        return true;
    }

    private void scheduleSave() {
        if (!saveQueued.compareAndSet(false, true)) return;
        FoliaScheduler.runGlobal(plugin, () -> {
            Map<UUID, List<CraftingProcessData>> snapshot = createSnapshot();
            FoliaScheduler.runAsync(plugin, () -> {
                try { saveSnapshot(snapshot); }
                finally {
                    saveQueued.set(false);
                    synchronized (saveLock) {
                        if (pendingSnapshot != null) {
                            Map<UUID, List<CraftingProcessData>> pending = pendingSnapshot;
                            pendingSnapshot = null;
                            FoliaScheduler.runAsync(plugin, () -> { try { saveSnapshot(pending); } catch (Exception e) { plugin.getLogger().warning("Save pending failed: " + e.getMessage()); } });
                        }
                    }
                }
            });
        });
    }

    private Map<UUID, List<CraftingProcessData>> createSnapshot() {
        Map<UUID, List<CraftingProcessData>> snapshot = new HashMap<>();
        for (Map.Entry<UUID, List<CraftingProcess>> entry : activeProcesses.entrySet()) {
            List<CraftingProcessData> list = new ArrayList<>();
            for (CraftingProcess process : entry.getValue()) list.add(CraftingProcessData.fromProcess(process));
            if (!list.isEmpty()) snapshot.put(entry.getKey(), list);
        }
        return snapshot;
    }

    private void saveSnapshot(Map<UUID, List<CraftingProcessData>> snapshot) {
        if (savingInProgress) { synchronized (saveLock) { pendingSnapshot = snapshot; } return; }
        savingInProgress = true;
        try {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, List<CraftingProcessData>> entry : snapshot.entrySet()) {
                List<Map<String, Object>> processList = new ArrayList<>();
                for (CraftingProcessData data : entry.getValue()) {
                    if (data.isClaimed() && System.currentTimeMillis() - data.getEndTime() > 600000) continue;
                    Map<String, Object> map = new HashMap<>();
                    map.put("recipeId", data.getRecipeId());
                    map.put("startTime", data.getStartTime());
                    map.put("endTime", data.getEndTime());
                    map.put("claimed", data.isClaimed());
                    processList.add(map);
                }
                if (!processList.isEmpty()) config.set(entry.getKey().toString(), processList);
            }
            config.save(dataFile);
        } catch (IOException e) { plugin.getLogger().warning("Save failed: " + e.getMessage()); }
        finally { savingInProgress = false; }
    }

    public void loadCraftingProcesses() {
        if (!dataFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        int loaded = 0;
        long now = System.currentTimeMillis();
        for (String uuidStr : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                List<Map<String, Object>> processList = (List<Map<String, Object>>) config.getList(uuidStr);
                if (processList == null) continue;
                List<CraftingProcess> processes = new ArrayList<>();
                for (Map<String, Object> data : processList) {
                    String recipeId = (String) data.get("recipeId");
                    CraftingRecipe recipe = recipes.get(recipeId);
                    if (recipe == null) continue;
                    long startTime = ((Number) data.get("startTime")).longValue();
                    long endTime = ((Number) data.get("endTime")).longValue();
                    boolean claimed = (Boolean) data.get("claimed");
                    if (claimed && (now - endTime) > 1800000) continue;
                    CraftingProcess process = new CraftingProcess(uuid, recipe, startTime, endTime);
                    if (claimed) process.setClaimed(true);
                    processes.add(process);
                    loaded++;
                }
                if (!processes.isEmpty()) activeProcesses.put(uuid, processes);
            } catch (IllegalArgumentException e) { plugin.getLogger().warning("Invalid UUID: " + uuidStr); }
        }
        plugin.getLogger().info("Loaded " + loaded + " crafting processes");
    }

    public void forceSave() { saveQueued.set(false); saveSnapshot(createSnapshot()); }

    public List<CraftingProcess> getPlayerProcesses(UUID playerUUID) {
        List<CraftingProcess> processes = activeProcesses.get(playerUUID);
        return processes != null ? new ArrayList<>(processes) : Collections.emptyList();
    }

    public void registerRecipe(CraftingRecipe recipe) { recipes.put(recipe.getId(), recipe); saveRecipes(); }
    public void unregisterRecipe(String id) { recipes.remove(id); saveRecipes(); }
    public CraftingRecipe getRecipe(String id) { return recipes.get(id); }
    public Collection<CraftingRecipe> getAllRecipes() { return recipes.values(); }

    public List<CraftingRecipe> getRecipesByCategory(String category) {
        return recipes.values().stream().filter(r -> r.getCategory().equalsIgnoreCase(category)).collect(Collectors.toList());
    }

    public List<String> getCategories() {
        return recipes.values().stream().map(CraftingRecipe::getCategory).distinct().collect(Collectors.toList());
    }

    /**
     * Refresh all recipe results that use custom items to match current definitions.
     */
    public int refreshRecipeResults() {
        var advancedItemManager = ((TheLifesteal) plugin).getAdvancedItemManager();
        if (advancedItemManager == null) return 0;
        return advancedItemManager.refreshRecipeResults(this);
    }

    private String formatMaterialName(Material material) { return material.name().replace("_", " ").toLowerCase(); }

    public void clearClaimedProcesses(UUID uuid) {
        List<CraftingProcess> processes = activeProcesses.get(uuid);
        if (processes != null) { processes.removeIf(CraftingProcess::isClaimed); if (processes.isEmpty()) activeProcesses.remove(uuid); scheduleSave(); }
    }
}