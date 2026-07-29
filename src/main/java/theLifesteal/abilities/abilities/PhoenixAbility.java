package theLifesteal.abilities.abilities;

import org.bukkit.Color;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;

import java.util.LinkedHashMap;
import java.util.Map;

public class PhoenixAbility extends ItemAbility {

    public PhoenixAbility(JavaPlugin plugin) {
        super(plugin, "phoenix", "Phoenix", ItemAbilityType.PASSIVE);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("hpCostPct", 0.0);
        config.put("cooldown", 120);
        config.put("cooldownScope", "GLOBAL");
        config.put("burnDuration", 5);
        config.put("burnRadius", 5.0);
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("hpCostPct", new ConfigField("HP Cost (%)", "double", 0.0, 100.0));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 86400));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        fields.put("burnDuration", new ConfigField("Burn Duration (s)", "int", 1, 60));
        fields.put("burnRadius", new ConfigField("Burn Radius (blocks)", "double", 1.0, 30.0));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        double hpCostPct = data.getConfigDouble("hpCostPct");
        int cooldown = data.getConfigInt("cooldown");
        double radius = data.getConfigDouble("burnRadius");
        int burnDuration = data.getConfigInt("burnDuration");

        StringBuilder sb = new StringBuilder();
        sb.append("&7Revives you once before breaking when taking fatal damage.\n");
        sb.append("&7Consumes &c50% &7of item max durability on trigger.\n");
        sb.append("&7Burns nearby enemies within &e").append(radius).append(" blocks &7for &c").append(burnDuration).append("s&7.\n");
        if (hpCostPct > 0) {
            sb.append("&7Costs &c").append((int) hpCostPct).append("% Max HP&7. ");
        }
        sb.append("&7Cooldown: &e").append(cooldown).append("s\n");
        sb.append("&c⚠ Warning: Item breaks if durability is below 50%!");
        return sb.toString();
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        return false; // Passive ability triggered on death/resurrect
    }

    /**
     * Trigger Phoenix passive revival when player suffers fatal damage.
     * @return true if resurrection succeeded, false if on cooldown, durability < 50%, or failed.
     */
    public boolean triggerPhoenix(Player player, ItemStack item, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        if (player == null || item == null || data == null) return false;

        int cooldown = data.getConfigInt("cooldown");
        String scope = data.getConfigString("cooldownScope");
        if (scope == null || scope.isEmpty()) scope = "GLOBAL";

        if (cooldown > 0 && cooldownManager.isOnCooldown(player.getUniqueId(), getId(), itemId, scope)) {
            long remaining = cooldownManager.getRemainingCooldown(player.getUniqueId(), getId(), itemId, scope);
            player.sendMessage(ColorUtils.colorize("&c[Phoenix] Revival failed! Phoenix ability is on cooldown (" + cooldownManager.formatCooldown(remaining) + " remaining)."));
            return false; // Cooldown active, let vanilla death or standard totem handle it
        }

        double requiredHp = getRequiredHpCost(data, player);
        if (requiredHp > 0) {
            double maxHp = player.getAttribute(Attribute.MAX_HEALTH) != null ? player.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0;
            if (maxHp <= requiredHp) {
                player.sendMessage(ColorUtils.colorize("&cYou don't have enough HP to use Phoenix! &7(Requires strictly more than &c" + String.format("%.1f", requiredHp) + "❤&7 Max HP)"));
                return false;
            }
        }

        // Durability check
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable) {
            int maxDurability = item.getType().getMaxDurability();
            if (maxDurability > 0) {
                int currentDamage = damageable.getDamage();
                int remainingDurability = maxDurability - currentDamage;
                double ratio = (double) remainingDurability / maxDurability;

                if (ratio < 0.5) {
                    // Less than 50% durability: BREAK ITEM BUT STILL REVIVE PLAYER
                    item.setAmount(item.getAmount() - 1);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    player.sendMessage(ColorUtils.colorize("&c[Phoenix] Your item had less than 50% durability and broke, but Phoenix revived you!"));
                } else {
                    // Lose 50% of max durability
                    int damageToAdd = (int) Math.ceil(maxDurability * 0.5);
                    int newDamage = currentDamage + damageToAdd;
                    damageable.setDamage(newDamage);
                    item.setData(DataComponentTypes.DAMAGE, newDamage);

                    if (newDamage >= maxDurability) {
                        item.setAmount(item.getAmount() - 1);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                        player.sendMessage(ColorUtils.colorize("&c[Phoenix] Your item broke after consuming 50% durability, but Phoenix revived you!"));
                    } else {
                        player.sendMessage(ColorUtils.colorize("&6🔥 &ePhoenix revived you! &7Item consumed 50% durability. Nearby entities ignited."));
                    }
                }
            } else {
                // Non-damageable single stack item
                item.setAmount(item.getAmount() - 1);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                player.sendMessage(ColorUtils.colorize("&6🔥 &ePhoenix revived you! &7Item broke on revive."));
            }
        }

        // Apply global cooldown
        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }

        // Visual Totem Effect & Sounds
        player.playEffect(EntityEffect.TOTEM_RESURRECT);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.8f, 1.2f);

        // Spawn rich fire particles
        Location loc = player.getLocation();
        for (int i = 0; i < 35; i++) {
            double rx = (Math.random() - 0.5) * 2.5;
            double ry = Math.random() * 2.2;
            double rz = (Math.random() - 0.5) * 2.5;
            player.getWorld().spawnParticle(Particle.FLAME, loc.clone().add(rx, ry, rz), 1, 0, 0.05, 0, 0.08);
            player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(rx, ry, rz), 1, 0, 0.05, 0, 0.08);
            player.getWorld().spawnParticle(Particle.LAVA, loc.clone().add(rx, ry, rz), 1);
            player.getWorld().spawnParticle(Particle.DUST, loc.clone().add(rx, ry, rz), 1, 0, 0, 0, 0.0,
                    new Particle.DustOptions(Color.fromRGB(255, 120, 0), 1.8f));
        }

        // Burn nearby entities
        double radius = data.getConfigDouble("burnRadius");
        int burnSeconds = data.getConfigInt("burnDuration");
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity victim && !victim.getUniqueId().equals(player.getUniqueId())) {
                victim.setFireTicks(burnSeconds * 20);
                recordAbilityDamage(player, victim);
            }
        }

        // Apply HP cost deduction post-revive if hpCostPct > 0
        if (requiredHp > 0) {
            double postHealth = Math.max(1.0, player.getHealth() - requiredHp);
            player.setHealth(postHealth);
        }

        return true;
    }
}
