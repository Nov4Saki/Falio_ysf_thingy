package theLifesteal.abilities.abilities;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.LinkedHashMap;
import java.util.Map;

public class DashAbility extends ItemAbility {

    public DashAbility(JavaPlugin plugin) {
        super(plugin, "dash", "Dash", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("distance", 8);
        config.put("damage", 5.0);
        config.put("cooldown", 15);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("distance", new ConfigField("Dash Distance", "int", 1, 50));
        fields.put("damage", new ConfigField("Damage", "double", 0.0, 100.0));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int distance = data.getConfigInt("distance");
        double damage = data.getConfigDouble("damage");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Dash &b" + distance + " blocks &7dealing &c" + formatDamage(damage) + " &8(&e" + cooldown + "s cooldown&8)";
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        int cooldown = data.getConfigInt("cooldown");
        String scope = data.getConfigString("cooldownScope");
        if (scope == null || scope.isEmpty()) scope = "ITEM";

        if (cooldown > 0 && cooldownManager.isOnCooldown(player.getUniqueId(), getId(), itemId, scope)) {
            long remaining = cooldownManager.getRemainingCooldown(player.getUniqueId(), getId(), itemId, scope);
            player.sendMessage(ColorUtils.colorize("&cOn cooldown! &7(" + cooldownManager.formatCooldown(remaining) + ")"));
            return false;
        }

        int distance = data.getConfigInt("distance");
        double damage = data.getConfigDouble("damage");

        Location start = player.getLocation();
        Vector direction = start.getDirection().normalize();

        player.setVelocity(direction.multiply(2.5));
        player.getWorld().playSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.2f);

        FoliaScheduler.runEntityLater(player, getPlugin(), () -> {
            Location playerLoc = player.getLocation();
            Vector dir = start.getDirection().normalize();

            for (double d = 0; d <= distance; d += 1.0) {
                Location checkPoint = start.clone().add(dir.clone().multiply(d));
                Particle.DUST.builder()
                        .location(checkPoint)
                        .color(org.bukkit.Color.AQUA)
                        .count(1)
                        .spawn();
            }

            for (Entity entity : player.getNearbyEntities(distance, distance, distance)) {
                if (entity instanceof LivingEntity && entity != player) {
                    if (entity.getLocation().distance(start) <= distance) {
                        Vector toEntity = entity.getLocation().toVector().subtract(start.toVector()).normalize();
                        double dot = toEntity.dot(dir);
                        if (dot > 0.7) {
                            dealAbilityDamage(player, (LivingEntity) entity, damage);
                            entity.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                                    entity.getLocation().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.1);
                        }
                    }
                }
            }
        }, 2L);

        player.sendMessage(ColorUtils.colorize("&b💨 Dashed forward!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) return (int) damage + "❤";
        return damage + "❤";
    }
}