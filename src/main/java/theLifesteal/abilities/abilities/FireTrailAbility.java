package theLifesteal.abilities.abilities;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class FireTrailAbility extends ItemAbility {

    public FireTrailAbility(JavaPlugin plugin) {
        super(plugin, "fire_trail", "Fire Trail", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("duration", 8);
        config.put("fireTicks", 60);
        config.put("damageRadius", 3);
        config.put("cooldown", 25);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("duration", new ConfigField("Trail Duration (seconds)", "int", 3, 30));
        fields.put("fireTicks", new ConfigField("Burn Duration (ticks)", "int", 20, 200));
        fields.put("damageRadius", new ConfigField("Ignite Radius", "int", 1, 10));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int duration = data.getConfigInt("duration");
        int fireTicks = data.getConfigInt("fireTicks");
        int radius = data.getConfigInt("damageRadius");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Leave &ca trail of fire &7for &e" + duration + "s\n&7Ignites enemies within &b" + radius + " blocks\n&8(&e" + cooldown + "s cooldown&8)";
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

        int duration = data.getConfigInt("duration");
        int fireTicks = data.getConfigInt("fireTicks");
        int damageRadius = data.getConfigInt("damageRadius");

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.5f);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.0f, 1.5f);

        int totalTicks = duration * 20;
        int[] ticks = new int[]{0};

        FoliaScheduler.runEntityTimer(player, getPlugin(), task -> {
            if (ticks[0] >= totalTicks || !player.isOnline()) {
                task.cancel();
                return;
            }

            Location playerLoc = player.getLocation();
            Block below = playerLoc.clone().subtract(0, 1, 0).getBlock();
            Block ground = playerLoc.getBlock();

            if (below.getType().isSolid() && !below.getType().name().contains("FIRE")) {
                if (ground.getType() == Material.AIR || ground.getType().name().contains("REPLACEABLE")
                        || ground.getType().name().contains("GRASS") || ground.getType().name().contains("SNOW")) {
                    final Block fireBlock = ground;
                    fireBlock.setType(Material.FIRE);
                    FoliaScheduler.runRegionLater(fireBlock.getLocation(), getPlugin(), () -> {
                        if (fireBlock.getType() == Material.FIRE) {
                            fireBlock.setType(Material.AIR);
                        }
                    }, 60L);
                }
            }

            for (int i = 0; i < 8; i++) {
                double angle = Math.random() * Math.PI * 2;
                double radius = 1.5;
                double x = Math.cos(angle) * radius;
                double z = Math.sin(angle) * radius;
                double y = Math.random() * 2;
                player.getWorld().spawnParticle(Particle.FLAME, playerLoc.clone().add(x, y, z), 1, 0, 0, 0, 0.02);
                player.getWorld().spawnParticle(Particle.SMOKE, playerLoc.clone().add(x * 0.5, y, z * 0.5), 1, 0, 0, 0, 0.01);
            }

            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI * 2 / 12;
                double x = Math.cos(angle) * 1.2;
                double z = Math.sin(angle) * 1.2;
                player.getWorld().spawnParticle(Particle.FLAME, playerLoc.clone().add(x, 0.1, z), 1, 0, 0, 0, 0.01);
                player.getWorld().spawnParticle(Particle.DUST, playerLoc.clone().add(x, 0.1, z), 1, 0, 0, 0,
                        new Particle.DustOptions(org.bukkit.Color.ORANGE, 1.5f));
            }

            for (Entity entity : player.getNearbyEntities(damageRadius, damageRadius, damageRadius)) {
                if (entity instanceof LivingEntity && entity != player) {
                    LivingEntity target = (LivingEntity) entity;
                    double dist = target.getLocation().distance(playerLoc);
                    if (dist <= damageRadius) {
                        recordAbilityDamage(player, target, (fireTicks * 50L) + 10000L);
                        target.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
                        target.setFireTicks(fireTicks);
                        target.getWorld().spawnParticle(Particle.FLAME,
                                target.getLocation().add(0, 1, 0), 5, 0.3, 0.5, 0.3, 0.05);
                    }
                }
            }

            ticks[0]++;
        }, 0L, 1L);

        player.sendMessage(ColorUtils.colorize("&c🔥 Fire Trail activated for " + duration + "s!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }
}