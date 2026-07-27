package theLifesteal.crafting;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public class RecipeEditSession {

    private final String recipeId;
    private ItemStack result;
    private Map<Material, Integer> materials;
    private Map<String, Integer> customItemMaterials;
    private long craftingTime;
    private String category;
    private int experienceReward;

    public RecipeEditSession(String recipeId) {
        this.recipeId = recipeId;
        this.materials = new LinkedHashMap<>();
        this.customItemMaterials = new LinkedHashMap<>();
        this.craftingTime = 60;
        this.category = "Misc";
        this.experienceReward = 0;
    }

    public String getRecipeId() { return recipeId; }

    public ItemStack getResult() { return result; }
    public void setResult(ItemStack result) { this.result = result; }

    public Map<Material, Integer> getMaterials() { return materials; }
    public void setMaterials(Map<Material, Integer> materials) { this.materials = new LinkedHashMap<>(materials); }

    public Map<String, Integer> getCustomItemMaterials() { return customItemMaterials; }
    public void setCustomItemMaterials(Map<String, Integer> customItemMaterials) {
        this.customItemMaterials = new LinkedHashMap<>(customItemMaterials);
    }

    public void addMaterial(Material material, int amount) { materials.put(material, amount); }
    public void addCustomItemMaterial(String customItemId, int amount) { customItemMaterials.put(customItemId, amount); }
    public void removeMaterial(Material material) { materials.remove(material); }
    public void removeCustomItemMaterial(String customItemId) { customItemMaterials.remove(customItemId); }

    public long getCraftingTime() { return craftingTime; }
    public void setCraftingTime(long craftingTime) { this.craftingTime = Math.max(1, craftingTime); }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public int getExperienceReward() { return experienceReward; }
    public void setExperienceReward(int experienceReward) { this.experienceReward = Math.max(0, experienceReward); }
}