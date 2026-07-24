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

import java.util.*;

public class AirSlashAbility extends ItemAbility {

    public AirSlashAbility(JavaPlugin plugin) {
        super(plugin, "air_slash", "Air Slash", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("range", 10);
        config.put("arcWidth", 3);
        config.put("damage", 6.0);
        config.put("cooldown", 12);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("range", new ConfigField("Range", "int", 3, 30));
        fields.put("arcWidth", new ConfigField("Arc Width", "int", 1, 8));
        fields.put("damage", new ConfigField("Damage", "double", 1.0, 50.0));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int range = data.getConfigInt("range");
        int width = data.getConfigInt("arcWidth");
        double damage = data.getConfigDouble("damage");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Fire an air slash dealing &c" + formatDamage(damage) + "\n&7Range &b" + range + " &7| Width &b" + width + " &8(&e" + cooldown + "s cooldown&8)";
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

        int range = data.getConfigInt("range");
        int arcWidth = data.getConfigInt("arcWidth");
        double damage = data.getConfigDouble("damage");

        Location start = player.getEyeLocation().clone();
        Vector direction = start.getDirection().normalize();

        player.getWorld().playSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);
        player.getWorld().playSound(start, Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.5f);

        Set<Entity> hitEntities = new HashSet<>();
        double[] traveled = new double[]{0.0};

        FoliaScheduler.runRegionTimer(start, getPlugin(), task -> {
            if (traveled[0] >= range) {
                task.cancel();
                return;
            }

            Location current = start.clone().add(direction.clone().multiply(traveled[0]));

            for (int i = 0; i < arcWidth * 2; i++) {
                double offset = (i - arcWidth) * 0.5;
                Vector perpendicular = getPerpendicular(direction);
                Location particleLoc = current.clone().add(perpendicular.clone().multiply(offset));

                double curveOffset = Math.sin(traveled[0] / range * Math.PI) * 0.3;
                particleLoc.add(0, curveOffset, 0);

                Particle.DUST.builder()
                        .location(particleLoc)
                        .color(org.bukkit.Color.WHITE)
                        .count(1)
                        .spawn();
                Particle.DUST.builder()
                        .location(particleLoc.clone().add(0, 0.3, 0))
                        .color(org.bukkit.Color.AQUA)
                        .count(1)
                        .spawn();
            }

            for (int i = 0; i < 5; i++) {
                Vector perp = getPerpendicular(direction);
                double swirlOffset = Math.random() * arcWidth * 1.5 - arcWidth * 0.75;
                double heightOffset = Math.random() * 1.5;
                Location swirlLoc = current.clone()
                        .add(perp.clone().multiply(swirlOffset))
                        .add(0, heightOffset, 0);
                current.getWorld().spawnParticle(Particle.CLOUD, swirlLoc, 1, 0, 0, 0, 0.02);
            }

            for (Entity entity : current.getWorld().getNearbyEntities(current, arcWidth, 3, arcWidth)) {
                if (entity instanceof LivingEntity && entity != player && !hitEntities.contains(entity)) {
                    Location entityLoc = entity.getLocation();
                    Vector toEntity = entityLoc.toVector().subtract(current.toVector());

                    double distAlong = toEntity.dot(direction);
                    Vector perpDist = toEntity.clone().subtract(direction.clone().multiply(distAlong));

                    if (distAlong >= -1 && distAlong <= 1 && perpDist.length() <= arcWidth + 0.5) {
                        hitEntities.add(entity);
                        dealAbilityDamage(player, (LivingEntity) entity, damage);

                        entity.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                                entity.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
                        entity.getWorld().spawnParticle(Particle.CRIT,
                                entity.getLocation().add(0, 1.5, 0), 10, 0.3, 0.5, 0.3, 0.1);
                        entity.getWorld().playSound(entity.getLocation(),
                                Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.5f);

                        Vector knockback = direction.clone().multiply(0.5);
                        entity.setVelocity(entity.getVelocity().add(knockback));
                    }
                }
            }

            traveled[0] += 0.5;
        }, 0L, 1L);

        player.sendMessage(ColorUtils.colorize("&b🌬 Air Slash!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private Vector getPerpendicular(Vector vec) {
        Vector perp = new Vector(-vec.getZ(), 0, vec.getX()).normalize();
        if (perp.length() < 0.1) {
            perp = new Vector(1, 0, 0);
        }
        return perp;
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) return (int) damage + "❤";
        return damage + "❤";
    }
}