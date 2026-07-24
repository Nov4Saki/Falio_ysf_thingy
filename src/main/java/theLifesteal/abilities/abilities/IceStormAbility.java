package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
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

import java.util.*;

public class IceStormAbility extends ItemAbility {

    public IceStormAbility(JavaPlugin plugin) {
        super(plugin, "ice_storm", "Ice Storm", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("radius", 8);
        config.put("duration", 6);
        config.put("iceBlockDamage", 8.0);
        config.put("iceBlocksPerWave", 6);
        config.put("waveInterval", 20);
        config.put("stunDuration", 2);
        config.put("stunAmplifier", 5);
        config.put("cooldown", 50);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("radius", new ConfigField("Storm Radius", "int", 3, 20));
        fields.put("duration", new ConfigField("Duration (seconds)", "int", 2, 15));
        fields.put("iceBlockDamage", new ConfigField("Ice Block Damage", "double", 1.0, 30.0));
        fields.put("iceBlocksPerWave", new ConfigField("Ice Blocks per Wave", "int", 1, 20));
        fields.put("waveInterval", new ConfigField("Ticks Between Waves", "int", 5, 60));
        fields.put("stunDuration", new ConfigField("Stun Duration (s)", "int", 1, 10));
        fields.put("stunAmplifier", new ConfigField("Stun Intensity (0-10)", "int", 0, 10));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int radius = data.getConfigInt("radius");
        int duration = data.getConfigInt("duration");
        double dmg = data.getConfigDouble("iceBlockDamage");
        int blocks = data.getConfigInt("iceBlocksPerWave");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Create a &bblizzard &7costing &c50% HP\n&7Drops &b" + blocks + " ice blocks &7dealing &b" + formatDamage(dmg) + " &7& stuns\n&b" + radius + " block radius &7| &e" + duration + "s &8(&e" + cooldown + "s cooldown&8)";
    }

    @Override
    public double getRequiredHpCost(ItemAbilityData data, Player player) {
        if (player == null) return 0.0;
        double maxHP = player.getAttribute(Attribute.MAX_HEALTH) != null 
                ? player.getAttribute(Attribute.MAX_HEALTH).getValue() 
                : 20.0;
        return maxHP * 0.5;
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

        double cost = getRequiredHpCost(data, player);

        if (!checkStrictHealthRequirement(player, cost)) {
            return false;
        }

        applySelfHealthCost(player, cost);

        int radius = data.getConfigInt("radius");
        int duration = data.getConfigInt("duration");
        double iceBlockDamage = data.getConfigDouble("iceBlockDamage");
        int iceBlocksPerWave = data.getConfigInt("iceBlocksPerWave");
        int waveInterval = data.getConfigInt("waveInterval");
        int stunDuration = data.getConfigInt("stunDuration");
        int stunAmplifier = data.getConfigInt("stunAmplifier");

        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();
        Location stormCenter = eyeLoc.clone();

        for (double d = 0; d <= 15; d += 0.5) {
            Location check = eyeLoc.clone().add(direction.clone().multiply(d));
            if (check.getBlock().getType().isSolid()) {
                stormCenter = check.clone().add(0, 1, 0);
                break;
            }
            if (d >= 14.5) {
                stormCenter = check;
            }
        }

        final Location center = stormCenter.clone();
        final int totalTicks = duration * 20;

        center.getWorld().playSound(center, Sound.WEATHER_RAIN_ABOVE, 2.0f, 1.0f);
        center.getWorld().playSound(center, Sound.ENTITY_BREEZE_WIND_BURST, 1.5f, 0.5f);

        // Blood sacrifice particles at player
        for (int i = 0; i < 12; i++) {
            player.getWorld().spawnParticle(Particle.DUST,
                    player.getLocation().add(0, 1.5, 0),
                    1, 0.3, 0.5, 0.3,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(180, 0, 0), 2f));
        }

        int[] tick = new int[]{0};
        boolean[] cleaning = new boolean[]{false};
        int[] cleanTick = new int[]{0};

        FoliaScheduler.runRegionTimer(center, getPlugin(), task -> {
            if (!cleaning[0] && tick[0] < totalTicks) {
                double progress = (double) tick[0] / totalTicks;
                double currentRadius = radius * (0.3 + progress * 0.7);

                // OPTIMIZED: 15 snowflakes (was 40)
                for (int i = 0; i < 15; i++) {
                    double angle = Math.random() * Math.PI * 2;
                    double dist = Math.random() * currentRadius;
                    double x = Math.cos(angle) * dist;
                    double z = Math.sin(angle) * dist;
                    double y = 1 + Math.random() * 8;
                    center.getWorld().spawnParticle(Particle.SNOWFLAKE,
                            center.clone().add(x, y, z), 1, 0, 0, 0, 0.05);
                }

                // OPTIMIZED: 8 wind swirl (was 20)
                for (int i = 0; i < 8; i++) {
                    double angle = (tick[0] * 0.3) + (i * Math.PI * 2 / 8);
                    double dist = currentRadius * 0.8;
                    double x = Math.cos(angle) * dist;
                    double z = Math.sin(angle) * dist;
                    double y = 1 + Math.random() * 4;
                    center.getWorld().spawnParticle(Particle.CLOUD,
                            center.clone().add(x, y, z), 1, 0, 0, 0, 0.03);
                }

                // OPTIMIZED: 6 ground frost (was 15), use BLOCK ice data for impact
                for (int i = 0; i < 6; i++) {
                    double angle = Math.random() * Math.PI * 2;
                    double dist = Math.random() * currentRadius;
                    double x = Math.cos(angle) * dist;
                    double z = Math.sin(angle) * dist;
                    center.getWorld().spawnParticle(Particle.BLOCK,
                            center.clone().add(x, 0.1, z), 1, 0, 0, 0,
                            Material.ICE.createBlockData());
                }

                // Drop ice blocks in waves
                if (tick[0] % waveInterval == 0) {
                    for (int i = 0; i < iceBlocksPerWave; i++) {
                        double angle = Math.random() * Math.PI * 2;
                        double dist = Math.random() * currentRadius * 0.8;
                        double x = Math.cos(angle) * dist;
                        double z = Math.sin(angle) * dist;
                        Location dropLoc = center.clone().add(x, 10, z);

                        FallingBlock iceBlock = center.getWorld().spawnFallingBlock(dropLoc, Material.PACKED_ICE.createBlockData());
                        iceBlock.setDropItem(false);
                        iceBlock.setHurtEntities(true);
                        iceBlock.setDamagePerBlock((float) iceBlockDamage);
                        iceBlock.setMaxDamage((int) (iceBlockDamage * 3));

                        FoliaScheduler.runRegionTimer(iceBlock.getLocation(), getPlugin(), innerTask -> {
                            if (iceBlock.isDead() || !iceBlock.isValid() || iceBlock.isOnGround()) {
                                iceBlock.getWorld().spawnParticle(Particle.BLOCK,
                                        iceBlock.getLocation(), 10, 0.3, 0.3, 0.3,
                                        Material.ICE.createBlockData());
                                iceBlock.getWorld().playSound(iceBlock.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 1.0f);
                                iceBlock.remove();
                                innerTask.cancel();
                                return;
                            }
                            // OPTIMIZED: 1 snowflake trailing (was 3)
                            iceBlock.getWorld().spawnParticle(Particle.SNOWFLAKE,
                                    iceBlock.getLocation(), 1, 0.2, 0.2, 0.2, 0.03);
                        }, 0L, 2L);
                    }
                }

                // Stun nearby entities
                for (Entity entity : center.getWorld().getNearbyEntities(center, currentRadius, 8, currentRadius)) {
                    if (!(entity instanceof LivingEntity) || entity == player) continue;
                    LivingEntity target = (LivingEntity) entity;
                    double dist = target.getLocation().distance(center);
                    if (dist <= currentRadius) {
                        recordAbilityDamage(player, target, 15000L);
                        target.addPotionEffect(new PotionEffect(
                                PotionEffectType.SLOWNESS, 40, stunAmplifier, false, true, true));
                        target.addPotionEffect(new PotionEffect(
                                PotionEffectType.WEAKNESS, 40, 2, false, true, true));
                        target.addPotionEffect(new PotionEffect(
                                PotionEffectType.MINING_FATIGUE, 40, 2, false, true, true));

                        if (tick[0] % 10 == 0) {
                            target.getWorld().spawnParticle(Particle.SNOWFLAKE,
                                    target.getLocation().add(0, 1.5, 0),
                                    5, 0.3, 0.5, 0.3, 0.03);
                        }
                    }
                }

                if (tick[0] % 15 == 0) {
                    center.getWorld().playSound(center, Sound.WEATHER_RAIN_ABOVE, 0.5f, 0.3f + (float)progress * 0.5f);
                }

                tick[0]++;
                if (tick[0] >= totalTicks) {
                    cleaning[0] = true;
                }
                return;
            }

            if (cleaning[0]) {
                cleanTick[0]++;
                if (cleanTick[0] >= 30) {
                    int cleanRadius = radius + 1;
                    for (int x = -cleanRadius; x <= cleanRadius; x++) {
                        for (int y = -3; y <= 12; y++) {
                            for (int z = -cleanRadius; z <= cleanRadius; z++) {
                                Location checkLoc = center.clone().add(x, y, z);
                                Material type = checkLoc.getBlock().getType();
                                if (type == Material.PACKED_ICE || type == Material.ICE || type == Material.BLUE_ICE) {
                                    checkLoc.getBlock().setType(Material.AIR);
                                }
                            }
                        }
                    }
                    // OPTIMIZED: 30 snowflakes cleanup (was 50)
                    center.getWorld().spawnParticle(Particle.SNOWFLAKE, center, 30, radius, 3, radius, 0.1);
                    center.getWorld().playSound(center, Sound.BLOCK_GLASS_BREAK, 2.0f, 0.5f);
                    task.cancel();
                }
            }
        }, 0L, 1L);

        player.sendMessage(ColorUtils.colorize("&b❄ Ice Storm unleashed!"));

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