package theLifesteal.abilities.abilities;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class CelestialHomingShotAbility extends ItemAbility {

    public CelestialHomingShotAbility(JavaPlugin plugin) {
        super(plugin, "celestial_homing_shot", "Celestial Homing Shot", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("hpCost", 4.0);
        config.put("cooldown", 10);
        config.put("cooldownScope", "ABILITY");
        config.put("damage", 8.0);
        config.put("speed", 0.8);
        config.put("homingRadius", 25.0);
        config.put("homingStrength", 0.25);
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("hpCost", new ConfigField("HP Cost (Flat)", "double", 0.0, 100.0));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("damage", new ConfigField("Damage", "double", 1.0, 100.0));
        fields.put("speed", new ConfigField("Projectile Speed", "double", 0.1, 3.0));
        fields.put("homingRadius", new ConfigField("Target Radius", "double", 5.0, 100.0));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        double hpCost = data.getConfigDouble("hpCost");
        int cooldown = data.getConfigInt("cooldown");
        double damage = data.getConfigDouble("damage");

        return "&7Fires a &b&lCelestial Star &7that homes in on enemy players\n"
                + "&7Deals &c" + formatDamage(damage) + " &7damage | Costs &c" + formatDamage(hpCost) + "❤ &7HP\n"
                + "&8(&e" + cooldown + "s ability cooldown&8)";
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        int cooldown = data.getConfigInt("cooldown");

        // Ability-wide shared cooldown check across all items with this ability
        if (cooldown > 0 && cooldownManager.isOnCooldown(player.getUniqueId(), getId(), itemId, "ABILITY")) {
            long remaining = cooldownManager.getRemainingCooldown(player.getUniqueId(), getId(), itemId, "ABILITY");
            player.sendMessage(ColorUtils.colorize("&cOn cooldown! &7(" + cooldownManager.formatCooldown(remaining) + ")"));
            return false;
        }

        double hpCost = data.getConfigDouble("hpCost");
        if (hpCost <= 0) hpCost = getRequiredHpCost(data, player);

        // Check health requirement and send feedback if HP is insufficient
        if (hpCost > 0 && !checkStrictHealthRequirement(player, hpCost)) {
            return false;
        }

        // Deduct flat HP cost
        if (hpCost > 0) {
            applySelfHealthCost(player, hpCost);
            String formattedCost = formatDamage(hpCost);
            player.sendMessage(ColorUtils.colorize("&cSacrificed &4" + formattedCost + "❤ &cHP to activate &b&lCelestial Homing Shot!"));
        }

        // Apply shared ability cooldown
        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, "ABILITY", cooldown);
        }

        // Launch celestial star homing projectile
        Location startLoc = player.getEyeLocation().clone();
        Vector initialDir = startLoc.getDirection().clone().normalize();

        double damage = data.getConfigDouble("damage");
        if (damage <= 0) damage = 8.0;
        double speed = data.getConfigDouble("speed");
        if (speed <= 0) speed = 0.8;
        double homingRadius = data.getConfigDouble("homingRadius");
        if (homingRadius <= 0) homingRadius = 25.0;
        double homingStrength = data.getConfigDouble("homingStrength");
        if (homingStrength <= 0) homingStrength = 0.25;

        player.getWorld().playSound(startLoc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.6f);

        final double finalDamage = damage;
        final double finalSpeed = speed;
        final double finalHomingRadius = homingRadius;
        final double finalHomingStrength = homingStrength;

        Location[] currentLoc = new Location[]{startLoc.clone()};
        Vector[] currentDir = new Vector[]{initialDir.clone()};
        int[] ticks = new int[]{0};

        FoliaScheduler.runRegionTimer(startLoc, getPlugin(), task -> {
            ticks[0]++;
            if (ticks[0] > 100 || !player.isOnline()) { // 5s max lifetime
                spawnFizzleEffects(currentLoc[0]);
                task.cancel();
                return;
            }

            // Homing logic: find closest valid victim player (excluding shooter)
            Player targetPlayer = findClosestVictimPlayer(player, currentLoc[0], finalHomingRadius);
            if (targetPlayer != null && targetPlayer.isOnline() && !targetPlayer.isDead()) {
                Vector toTarget = targetPlayer.getEyeLocation().subtract(0, 0.4, 0).toVector().subtract(currentLoc[0].toVector());
                if (toTarget.lengthSquared() > 0.001) {
                    toTarget.normalize();
                    currentDir[0] = currentDir[0].multiply(1.0 - finalHomingStrength).add(toTarget.multiply(finalHomingStrength)).normalize();
                }
            }

            Location nextLoc = currentLoc[0].clone().add(currentDir[0].clone().multiply(finalSpeed));

            // Check wall collision ("doesn't go through walls")
            if (currentLoc[0].getWorld() != null) {
                RayTraceResult rayTrace = currentLoc[0].getWorld().rayTraceBlocks(currentLoc[0], currentDir[0], finalSpeed, FluidCollisionMode.NEVER, true);
                if (rayTrace != null && rayTrace.getHitBlock() != null) {
                    Location hitLoc = rayTrace.getHitPosition().toLocation(currentLoc[0].getWorld());
                    spawnWallHitEffects(hitLoc);
                    task.cancel();
                    return;
                }
            }

            // Check hit collision with victim players
            Player victimHit = findHitVictimPlayer(player, nextLoc, 0.9);
            if (victimHit != null) {
                dealAbilityDamage(player, victimHit, finalDamage);
                recordAbilityDamage(player, victimHit, 15000L);
                try {
                    spawnImpactEffects(nextLoc);
                } catch (Exception ignored) {}
                task.cancel();
                return;
            }

            currentLoc[0] = nextLoc;
            try {
                spawnStarParticles(currentLoc[0], currentDir[0]);
            } catch (Exception ignored) {}
        }, 1L, 1L);

        return true;
    }

    private Player findClosestVictimPlayer(Player shooter, Location loc, double radius) {
        if (loc.getWorld() == null) return null;

        Player closest = null;
        double closestDistSq = radius * radius;

        for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (entity instanceof Player victimPlayer) {
                if (victimPlayer.getUniqueId().equals(shooter.getUniqueId())) continue; // Exclude shooter
                if (victimPlayer.isDead() || !victimPlayer.isValid() || victimPlayer.getGameMode().name().contains("SPECTATOR")) continue;

                double distSq = victimPlayer.getLocation().distanceSquared(loc);
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closest = victimPlayer;
                }
            }
        }
        return closest;
    }

    private Player findHitVictimPlayer(Player shooter, Location loc, double hitRadius) {
        if (loc.getWorld() == null) return null;

        for (Entity entity : loc.getWorld().getNearbyEntities(loc, hitRadius, hitRadius, hitRadius)) {
            if (entity instanceof Player victimPlayer) {
                if (victimPlayer.getUniqueId().equals(shooter.getUniqueId())) continue;
                if (!victimPlayer.isDead() && victimPlayer.isValid() && !victimPlayer.getGameMode().name().contains("SPECTATOR")) {
                    return victimPlayer;
                }
            }
        }
        return null;
    }

    private void spawnStarParticles(Location loc, Vector dir) {
        if (loc.getWorld() == null) return;

        // Core star particle
        loc.getWorld().spawnParticle(Particle.END_ROD, loc, 2, 0.05, 0.05, 0.05, 0.01);
        loc.getWorld().spawnParticle(Particle.FIREWORK, loc, 1, 0.02, 0.02, 0.02, 0.01);

        // Golden celestial star dust
        DustOptions goldDust = new DustOptions(Color.fromRGB(255, 215, 0), 1.2f);
        DustOptions cyanDust = new DustOptions(Color.fromRGB(135, 206, 250), 1.0f);
        loc.getWorld().spawnParticle(Particle.DUST, loc, 3, 0.1, 0.1, 0.1, goldDust);
        loc.getWorld().spawnParticle(Particle.DUST, loc, 2, 0.08, 0.08, 0.08, cyanDust);

        // Visual 4-point star geometric shape
        double size = 0.25;
        loc.getWorld().spawnParticle(Particle.DUST, loc.clone().add(size, 0, 0), 1, goldDust);
        loc.getWorld().spawnParticle(Particle.DUST, loc.clone().add(-size, 0, 0), 1, goldDust);
        loc.getWorld().spawnParticle(Particle.DUST, loc.clone().add(0, size, 0), 1, goldDust);
        loc.getWorld().spawnParticle(Particle.DUST, loc.clone().add(0, -size, 0), 1, goldDust);
    }

    private void spawnWallHitEffects(Location loc) {
        if (loc.getWorld() == null) return;

        loc.getWorld().spawnParticle(Particle.END_ROD, loc, 15, 0.2, 0.2, 0.2, 0.1);
        loc.getWorld().spawnParticle(Particle.FIREWORK, loc, 10, 0.2, 0.2, 0.2, 0.05);
        loc.getWorld().spawnParticle(Particle.EXPLOSION, loc, 1, 0.0, 0.0, 0.0, 0.0);
        loc.getWorld().playSound(loc, Sound.ITEM_TRIDENT_HIT, 1.0f, 1.4f);
    }

    private void spawnImpactEffects(Location loc) {
        if (loc.getWorld() == null) return;

        loc.getWorld().spawnParticle(Particle.CRIT, loc, 15, 0.3, 0.3, 0.3, 0.2);
        loc.getWorld().spawnParticle(Particle.END_ROD, loc, 25, 0.3, 0.3, 0.3, 0.15);
        loc.getWorld().spawnParticle(Particle.FIREWORK, loc, 20, 0.3, 0.3, 0.3, 0.1);
        loc.getWorld().playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f, 1.2f);
        loc.getWorld().playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0f, 1.8f);
    }

    private void spawnFizzleEffects(Location loc) {
        if (loc.getWorld() == null) return;

        loc.getWorld().spawnParticle(Particle.END_ROD, loc, 8, 0.1, 0.1, 0.1, 0.02);
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) {
            return String.valueOf((int) damage);
        }
        return String.format("%.1f", damage);
    }
}
