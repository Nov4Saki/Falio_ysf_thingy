package theLifesteal.crafting;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;

public class CraftingRecipe {

    private final String id;
    private ItemStack result;
    private final Map<Material, Integer> materials;
    private final Map<String, Integer> customItemMaterials;
    private final long craftingTime;
    private final String category;
    private final List<String> description;
    private final boolean isShapeless;
    private final int experienceReward;

    public CraftingRecipe(String id, ItemStack result, Map<Material, Integer> materials,
                          long craftingTime, String category, List<String> description,
                          boolean isShapeless, int experienceReward) {
        this(id, result, materials, new LinkedHashMap<>(), craftingTime, category, description, isShapeless, experienceReward);
    }

    public CraftingRecipe(String id, ItemStack result, Map<Material, Integer> materials,
                          Map<String, Integer> customItemMaterials,
                          long craftingTime, String category, List<String> description,
                          boolean isShapeless, int experienceReward) {
        this.id = id;
        this.result = result.clone();
        this.materials = new HashMap<>(materials);
        this.customItemMaterials = new LinkedHashMap<>(customItemMaterials);
        this.craftingTime = craftingTime;
        this.category = category;
        this.description = description;
        this.isShapeless = isShapeless;
        this.experienceReward = experienceReward;
    }

    public String getId() { return id; }
    public ItemStack getResult() { return result.clone(); }
    public void setResult(ItemStack result) { this.result = result.clone(); }
    public Map<Material, Integer> getMaterials() { return new HashMap<>(materials); }
    public Map<String, Integer> getCustomItemMaterials() { return new LinkedHashMap<>(customItemMaterials); }
    public long getCraftingTime() { return craftingTime; }
    public String getCategory() { return category; }
    public List<String> getDescription() { return description; }
    public boolean isShapeless() { return isShapeless; }
    public int getExperienceReward() { return experienceReward; }

    public boolean hasCustomMaterials() {
        return !customItemMaterials.isEmpty();
    }
}