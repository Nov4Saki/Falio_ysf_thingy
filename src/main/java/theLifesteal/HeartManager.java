package theLifesteal;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Sound;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;

public class HeartManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final NamespacedKey heartKey;

    public HeartManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configManager = ((TheLifesteal) plugin).getConfigManager();
        this.heartKey = new NamespacedKey(plugin, "half_heart_item");
    }

    public ItemStack createHeartItem(int amount) {
        ItemStack item = new ItemStack(configManager.getHeartMaterial(), amount);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) return item;

        meta.displayName(Component.text(ColorUtils.colorize(configManager.getHeartDisplayName())));

        List<Component> loreComponents = new ArrayList<>();
        for (String line : configManager.getHeartLore()) {
            loreComponents.add(Component.text(ColorUtils.colorize(line)));
        }
        meta.lore(loreComponents);

        if (configManager.isHeartGlow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        if (configManager.getHeartCustomModelData() > 0) {
            meta.setCustomModelData(configManager.getHeartCustomModelData());
        }

        meta.getPersistentDataContainer().set(heartKey, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public boolean isHeartItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        if (!item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(heartKey, PersistentDataType.BOOLEAN);
    }

    // Returns true if health was increased, false if already at cap
    public boolean applyHeartEffect(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return false;

        double currentBase = maxHealth.getBaseValue();
        double cap = configManager.getMaxHealthCap();
        double added = configManager.getHalfHeartValue();
        double newHealth = Math.min(currentBase + added, cap);

        if (newHealth == currentBase) {
            player.sendMessage(ColorUtils.colorize("&cYou are already at maximum health!"));
            return false;
        }

        maxHealth.setBaseValue(newHealth);

        if (configManager.isHeartUseSoundEnabled()) {
            player.playSound(player.getLocation(),
                    Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        }

        player.sendMessage(ColorUtils.colorize(
                configManager.getMessage("heart-gained")
                        .replace("%amount%", String.valueOf(newHealth - currentBase))
        ));
        return true;
    }

    // Returns true if a heart was actually dropped (health decreased and chance succeeded)
    public boolean removeHeartOnDeath(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return false;

        double currentMax = maxHealth.getBaseValue();
        double minHealth = configManager.getMinimumMaxHealth();
        double loss = configManager.getHeartValue();

        if (currentMax <= minHealth) {
            return false;
        }

        double newMax = Math.max(currentMax - loss, minHealth);
        maxHealth.setBaseValue(newMax);

        // Only drop old heart if drop-hearts-on-death is enabled
        if (configManager.shouldDropHeartsOnDeath() && Math.random() < configManager.getDropChance()) {
            ItemStack heartDrop = createHeartItem(1);
            player.getWorld().dropItemNaturally(player.getLocation(), heartDrop);
            return true;
        }
        return false;
    }

    // Check if a player can withdraw a given amount of hearts (won't go below minimum)
    public boolean canWithdrawHearts(Player player, int amount) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return false;

        double currentMax = maxHealth.getBaseValue();
        double minHealth = configManager.getMinimumMaxHealth();
        double loss = amount * configManager.getHalfHeartValue(); // each withdrawn heart = 1 HP point

        return (currentMax - loss) >= minHealth;
    }

    // Perform the withdrawal (call only after canWithdrawHearts returns true)
    public void withdrawHearts(Player player, int amount) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return;

        double currentMax = maxHealth.getBaseValue();
        double loss = amount * configManager.getHalfHeartValue();
        double newMax = Math.max(currentMax - loss, configManager.getMinimumMaxHealth());
        maxHealth.setBaseValue(newMax);

        // Adjust current health if it exceeds new max
        if (player.getHealth() > maxHealth.getValue()) {
            player.setHealth(maxHealth.getValue());
        }

        if (configManager.isHeartWithdrawSoundEnabled()) {
            player.playSound(player.getLocation(),
                    Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
        }
    }

    // Set a player's max health directly (admin command)
    public void setMaxHealth(Player player, double amount) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return;

        double capped = Math.min(amount, configManager.getMaxHealthCap());
        double finalValue = Math.max(capped, configManager.getMinimumMaxHealth());
        maxHealth.setBaseValue(finalValue);

        if (player.getHealth() > maxHealth.getValue()) {
            player.setHealth(maxHealth.getValue());
        }
    }
}