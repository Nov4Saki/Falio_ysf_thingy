package theLifesteal.abilities.abilities;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.LinkedHashMap;
import java.util.Map;

public class GroundSlamAbility extends ItemAbility {

    public GroundSlamAbility(JavaPlugin plugin) {
        super(plugin, "ground_slam", "Ground Slam", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("launchHeight", 5);
        config.put("slamRadius", 8);
        config.put("lightningStrikes", 5);
        config.put("strikeDelay", 4);
        config.put("damage", 8.0);
        config.put("slownessDuration", 4);
        config.put("slownessAmplifier", 1);
        config.put("cooldown", 30);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("launchHeight", new ConfigField("Launch Height", "int", 3, 20));
        fields.put("slamRadius", new ConfigField("Slam Radius", "int", 3, 30));
        fields.put("lightningStrikes", new ConfigField("Lightning Strikes", "int", 1, 20));
        fields.put("strikeDelay", new ConfigField("Ticks Between Strikes", "int", 1, 40));
        fields.put("damage", new ConfigField("Damage per Strike", "double", 0.0, 100.0));
        fields.put("slownessDuration", new ConfigField("Slowness Duration (s)", "int", 1, 30));
        fields.put("slownessAmplifier", new ConfigField("Slowness Level (0=I)", "int", 0, 5));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int radius = data.getConfigInt("slamRadius");
        double damage = data.getConfigDouble("damage");
        int strikes = data.getConfigInt("lightningStrikes");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Leap up & slam down with &e" + strikes + " &7lightning strikes\n&7Deals &c" + formatDamage(damage) + " &7in &b" + radius + " block radius & slows\n&8(&e" + cooldown + "s cooldown&8)";
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

        int launchHeight = data.getConfigInt("launchHeight");
        final int slamRadius = data.getConfigInt("slamRadius");
        final int lightningStrikes = data.getConfigInt("lightningStrikes");
        final int strikeDelay = data.getConfigInt("strikeDelay");
        final double damage = data.getConfigDouble("damage");
        final int slownessDuration = data.getConfigInt("slownessDuration");
        final int slownessAmplifier = data.getConfigInt("slownessAmplifier");

        Location checkLoc = player.getLocation();
        for (int y = 1; y <= 6; y++) {
            if (checkLoc.clone().add(0, y, 0).getBlock().getType().isSolid()) {
                player.sendMessage(ColorUtils.colorize("&cNot enough space above you!"));
                return false;
            }
        }

        player.setVelocity(new Vector(0, launchHeight * 0.4, 0));
        Location launchLoc = player.getLocation().clone();
        player.getWorld().playSound(launchLoc, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.6f);

        // Launch particles — 15 flame (was 25)
        for (int i = 0; i < 15; i++) {
            player.getWorld().spawnParticle(Particle.FLAME,
                    launchLoc.clone().add(Math.random() * 0.8 - 0.4, i * 0.25, Math.random() * 0.8 - 0.4),
                    1, 0, 0, 0, 0.01);
        }

        int[] ticks = new int[]{0};

        FoliaScheduler.runEntityTimer(player, getPlugin(), task -> {
            ticks[0]++;

            if (player.isOnGround() && ticks[0] > 5) {
                executeSlam(player, slamRadius, damage, lightningStrikes, strikeDelay,
                        slownessDuration, slownessAmplifier);
                task.cancel();
                return;
            }

            if (ticks[0] > 200) {
                task.cancel();
                return;
            }

            // Falling particles — 2 sparks (was 4)
            if (player.getVelocity().getY() < 0 && !player.isOnGround()) {
                Location airLoc = player.getLocation();
                for (int i = 0; i < 2; i++) {
                    player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                            airLoc.clone().add(Math.random() * 1.0 - 0.5, Math.random() * 1.0 - 0.5, Math.random() * 1.0 - 0.5),
                            1, 0, 0, 0, 0);
                }
            }
        }, 0L, 1L);

        player.sendMessage(ColorUtils.colorize("&e⚡ Ground Slam!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private void executeSlam(Player player, int slamRadius, double damage, int lightningStrikes,
                             int strikeDelay, int slownessDuration, int slownessAmplifier) {
        Location groundLoc = player.getLocation().clone();

        player.getWorld().playSound(groundLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f);
        player.getWorld().playSound(groundLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.3f);

        // OPTIMIZED: Pre-calculated rings — 3 rings × 16 angles = 48 dust (was 5 rings × 36 = 180)
        for (int ring = 0; ring < 3; ring++) {
            double ringR = ring * 2.0;
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI * 2 / 16;
                double x = Math.cos(angle) * ringR;
                double z = Math.sin(angle) * ringR;
                groundLoc.getWorld().spawnParticle(Particle.DUST,
                        groundLoc.clone().add(x, 0.1, z),
                        1, 0, 0, 0, new Particle.DustOptions(org.bukkit.Color.YELLOW, 2f));
            }
        }

        // OPTIMIZED: 30 cloud (was 80)
        for (int i = 0; i < 30; i++) {
            double angle = Math.random() * Math.PI * 2;
            double dist = Math.random() * slamRadius;
            double x = Math.cos(angle) * dist;
            double z = Math.sin(angle) * dist;
            groundLoc.getWorld().spawnParticle(Particle.CLOUD,
                    groundLoc.clone().add(x, 0.1, z), 1, 0, 0.1, 0, 0.04);
        }

        // Explosion burst — 2 (was 3)
        groundLoc.getWorld().spawnParticle(Particle.EXPLOSION,
                groundLoc.clone().add(0, 0.5, 0), 2, 0.5, 0.5, 0.5, 0.1);

        // Lightning strikes with delay
        for (int strike = 0; strike < lightningStrikes; strike++) {
            final long delay = (long) strike * strikeDelay;
            FoliaScheduler.runRegionLater(groundLoc, getPlugin(), () -> {
                for (Entity entity : player.getNearbyEntities(slamRadius, 5, slamRadius)) {
                    if (entity instanceof LivingEntity && entity != player) {
                        double dist = entity.getLocation().distance(groundLoc);
                        if (dist <= slamRadius) {
                            Location strikeLoc = entity.getLocation().clone();
                            strikeLoc.getWorld().strikeLightningEffect(strikeLoc);
                            dealAbilityDamage(player, (LivingEntity) entity, damage, 15000L);

                            ((LivingEntity) entity).addPotionEffect(new PotionEffect(
                                    PotionEffectType.SLOWNESS, slownessDuration * 20,
                                    slownessAmplifier, false, true, true));

                            // OPTIMIZED: 8 sparks + 1 flash (was 20 sparks)
                            for (int j = 0; j < 8; j++) {
                                strikeLoc.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                                        strikeLoc.clone().add(Math.random() * 2 - 1, Math.random() * 2, Math.random() * 2 - 1),
                                        1, 0, 0, 0, 0);
                            }
                            strikeLoc.getWorld().spawnParticle(Particle.FLASH,
                                    strikeLoc.clone().add(0, 1, 0), 1, 0, 0, 0, 0);
                        }
                    }
                }
            }, delay);
        }

        // Ground rumble at feet — 8 cloud (was 15)
        for (int i = 0; i < 8; i++) {
            player.getWorld().spawnParticle(Particle.CLOUD,
                    player.getLocation().add(Math.random() * 1.5 - 0.75, 0.1, Math.random() * 1.5 - 0.75),
                    1, 0, 0, 0, 0.03);
        }
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) return (int) damage + "❤";
        return damage + "❤";
    }
}