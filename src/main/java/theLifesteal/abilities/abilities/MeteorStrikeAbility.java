package theLifesteal.abilities.abilities;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MeteorStrikeAbility extends ItemAbility {

    public MeteorStrikeAbility(JavaPlugin plugin) {
        super(plugin, "meteor_strike", "Meteor Strike", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("delayTicks", 40);
        config.put("damage", 18.0);
        config.put("radius", 6.0);
        config.put("range", 30);
        config.put("fireDuration", 6);
        config.put("hpCostPercent", 80);
        config.put("cooldown", 35);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("delayTicks", new ConfigField("Delay Before Drop (ticks)", "int", 10, 100));
        fields.put("damage", new ConfigField("Impact Damage", "double", 0.0, 200.0));
        fields.put("radius", new ConfigField("Explosion Radius", "double", 2.0, 20.0));
        fields.put("range", new ConfigField("Cast Range", "int", 5, 100));
        fields.put("fireDuration", new ConfigField("Burn Duration (s)", "int", 0, 30));
        fields.put("hpCostPercent", new ConfigField("Max HP Cost (%)", "int", 0, 100));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        double radius = data.getConfigDouble("radius");
        double damage = data.getConfigDouble("damage");
        int delaySeconds = data.getConfigInt("delayTicks") / 20;
        int hpCostPercent = data.getConfigInt("hpCostPercent");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Costs &c" + hpCostPercent + "% Max HP &7to summon a 7-block &c&lMeteor &7after &e" + delaySeconds + "s\n"
                + "&7Deals &c" + formatDamage(damage) + " &7in a &b" + (int) radius + " block radius & ignites targets\n"
                + "&8(&e" + cooldown + "s cooldown&8)";
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        int cooldown = data.getConfigInt("cooldown");
        String scope = data.getConfigString("cooldownScope");
        if (scope == null || scope.isEmpty()) scope = "ITEM";

        // Cooldown Check
        if (cooldown > 0 && cooldownManager.isOnCooldown(player.getUniqueId(), getId(), itemId, scope)) {
            long remaining = cooldownManager.getRemainingCooldown(player.getUniqueId(), getId(), itemId, scope);
            player.sendMessage(ColorUtils.colorize("&cOn cooldown! &7(" + cooldownManager.formatCooldown(remaining) + ")"));
            return false;
        }

        // HP Cost Check: % of current max health
        int hpCostPercent = data.getConfigInt("hpCostPercent");
        if (hpCostPercent <= 0) hpCostPercent = 80;

        double hpCost = getRequiredHpCost(data, player);
        if (hpCost <= 0) {
            double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) != null
                    ? player.getAttribute(Attribute.MAX_HEALTH).getValue()
                    : player.getMaxHealth();
            hpCost = maxHealth * (hpCostPercent / 100.0);
        }

        if (!checkStrictHealthRequirement(player, hpCost)) {
            return false;
        }

        // Deduct 80% Max HP cost
        applySelfHealthCost(player, hpCost);
        player.sendMessage(ColorUtils.colorize("&cSacrificed &4" + (int) hpCost + "❤ &c(" + hpCostPercent + "% Max HP) to trigger &c&lMeteor Strike!"));

        int range = data.getConfigInt("range");
        World world = player.getWorld();

        // Raytrace to find target impact ground location
        RayTraceResult rayTrace = world.rayTraceBlocks(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        Location targetLoc;
        if (rayTrace != null && rayTrace.getHitBlock() != null) {
            targetLoc = rayTrace.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
        } else {
            targetLoc = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(range));
            targetLoc.setY(world.getHighestBlockYAt(targetLoc) + 1.0);
        }

        final int delayTicks = data.getConfigInt("delayTicks");
        final double damage = data.getConfigDouble("damage");
        final double radius = data.getConfigDouble("radius");
        final int fireDuration = data.getConfigInt("fireDuration");

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.2f, 0.6f);

        // Phase 1: Target warning indicator at target location
        Location impactLoc = targetLoc.clone();
        int[] warningTicks = new int[]{0};

        FoliaScheduler.runRegionTimer(impactLoc, getPlugin(), task -> {
            warningTicks[0]++;
            if (warningTicks[0] >= delayTicks) {
                spawnAndDropDiagonalMeteor(player, impactLoc, damage, radius, fireDuration);
                task.cancel();
                return;
            }

            // Target ring (Particle count = 4)
            double angleStep = Math.PI / 2;
            double currentRotation = warningTicks[0] * 0.15;

            for (int i = 0; i < 4; i++) {
                double angle = i * angleStep + currentRotation;
                double x = Math.cos(angle) * (radius * 0.7);
                double z = Math.sin(angle) * (radius * 0.7);

                impactLoc.getWorld().spawnParticle(Particle.DUST,
                        impactLoc.clone().add(x, 0.1, z),
                        1, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 80, 0), 1.5f));

                impactLoc.getWorld().spawnParticle(Particle.FLAME,
                        impactLoc.clone().add(x, 0.1, z), 1, 0, 0, 0, 0.01);
            }

            if (warningTicks[0] % 8 == 0) {
                impactLoc.getWorld().playSound(impactLoc, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 0.6f + (warningTicks[0] * 0.02f));
            }
        }, 0L, 1L);

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private void spawnAndDropDiagonalMeteor(Player player, Location targetLoc, double damage, double radius, int fireDuration) {
        World world = targetLoc.getWorld();
        if (world == null) return;

        // Diagonal origin: offset 12 blocks horizontally (X & Z) and 18 blocks high (Y)
        Location spawnSkyLoc = targetLoc.clone().add(12.0, 18.0, 12.0);

        // 7 Falling Blocks geometry offsets (1 core + 6 surrounding)
        Vector[] offsets = new Vector[] {
                new Vector(0, 0, 0),      // Core
                new Vector(0.8, 0, 0),    // East
                new Vector(-0.8, 0, 0),   // West
                new Vector(0, 0, 0.8),    // South
                new Vector(0, 0, -0.8),   // North
                new Vector(0, 0.8, 0),    // Top
                new Vector(0, -0.8, 0)    // Bottom
        };

        Material[] materials = new Material[] {
                Material.MAGMA_BLOCK,   // Core
                Material.NETHERRACK,    // East
                Material.NETHERRACK,    // West
                Material.OBSIDIAN,      // South
                Material.OBSIDIAN,      // North
                Material.BLACKSTONE,    // Top
                Material.BLACKSTONE     // Bottom
        };

        // Smooth physical velocity vector (~0.60 blocks per tick = smooth 60 FPS client motion)
        Vector diagonalVelocity = targetLoc.toVector().subtract(spawnSkyLoc.toVector()).normalize().multiply(0.60);

        List<FallingBlock> meteorBlocks = new ArrayList<>();

        for (int i = 0; i < offsets.length; i++) {
            Location blockSpawnLoc = spawnSkyLoc.clone().add(offsets[i]);
            FallingBlock fb = world.spawnFallingBlock(blockSpawnLoc, materials[i].createBlockData());
            fb.setDropItem(false);
            fb.setCancelDrop(true);
            fb.setHurtEntities(false);
            fb.setGravity(false);
            fb.setVelocity(diagonalVelocity);
            meteorBlocks.add(fb);
        }

        world.playSound(spawnSkyLoc, Sound.ENTITY_WITHER_SHOOT, 1.2f, 0.6f);

        // Native physics descent task with client interpolation
        int[] descentTicks = new int[]{0};
        Location currentCoreLoc = spawnSkyLoc.clone();

        FoliaScheduler.runRegionTimer(targetLoc, getPlugin(), task -> {
            descentTicks[0]++;
            currentCoreLoc.add(diagonalVelocity);

            // Keep velocity synchronized across all 7 blocks for 60 FPS client rendering
            for (int i = 0; i < meteorBlocks.size(); i++) {
                FallingBlock fb = meteorBlocks.get(i);
                if (fb != null && fb.isValid()) {
                    fb.setVelocity(diagonalVelocity);

                    // Safety check: if entity drifts away from cluster position, re-sync position
                    Location expectedLoc = currentCoreLoc.clone().add(offsets[i]);
                    if (fb.getLocation().distanceSquared(expectedLoc) > 1.5) {
                        fb.teleportAsync(expectedLoc);
                        fb.setVelocity(diagonalVelocity);
                    }
                }
            }

            // Particles (Intensity capped at 4)
            world.spawnParticle(Particle.FLAME, currentCoreLoc, 4, 0.4, 0.4, 0.4, 0.02);
            world.spawnParticle(Particle.LAVA, currentCoreLoc, 4, 0.4, 0.4, 0.4, 0.01);
            world.spawnParticle(Particle.LARGE_SMOKE, currentCoreLoc, 4, 0.4, 0.4, 0.4, 0.01);
            world.spawnParticle(Particle.FIREWORK, currentCoreLoc, 4, 0.3, 0.3, 0.3, 0.02);

            if (descentTicks[0] % 4 == 0) {
                world.playSound(currentCoreLoc, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.7f);
            }

            // Check impact when reaching target location
            if (currentCoreLoc.distance(targetLoc) <= 1.2 || currentCoreLoc.getY() <= targetLoc.getY() || descentTicks[0] > 70) {
                // CRITICAL: Immediately remove all 7 falling blocks so they completely disappear
                for (FallingBlock fb : meteorBlocks) {
                    if (fb != null && fb.isValid()) {
                        fb.remove();
                    }
                }

                // Trigger single impact explosion
                executeImpact(player, targetLoc, damage, radius, fireDuration);
                task.cancel();
            }
        }, 0L, 1L);
    }

    private void executeImpact(Player player, Location impactLoc, double damage, double radius, int fireDuration) {
        World world = impactLoc.getWorld();
        if (world == null) return;

        // Sound effects
        world.playSound(impactLoc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.5f, 0.8f);
        world.playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);

        // Explosion particles (Intensity capped at 4)
        world.spawnParticle(Particle.EXPLOSION, impactLoc.clone().add(0, 0.5, 0), 4, 0.4, 0.4, 0.4, 0.05);
        world.spawnParticle(Particle.FLAME, impactLoc.clone().add(0, 0.5, 0), 4, 0.4, 0.4, 0.4, 0.05);
        world.spawnParticle(Particle.LAVA, impactLoc.clone().add(0, 0.5, 0), 4, 0.4, 0.4, 0.4, 0.05);
        world.spawnParticle(Particle.FIREWORK, impactLoc.clone().add(0, 0.5, 0), 4, 0.4, 0.4, 0.4, 0.05);

        // Area Damage, Ignition & Stronger Knockback: EXCLUDE the casting player
        for (Entity entity : world.getNearbyEntities(impactLoc, radius, radius + 3, radius)) {
            if (entity instanceof LivingEntity living) {
                // Caster is immune
                if (entity.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }

                double dist = entity.getLocation().distance(impactLoc);
                if (dist <= radius) {
                    dealAbilityDamage(player, living, damage, (fireDuration * 1000L) + 15000L);

                    // Set target players / entities on fire
                    if (fireDuration > 0) {
                        living.setFireTicks(fireDuration * 20);
                    }

                    // Stronger knockback away from epicenter (1.8 horizontal, 0.65 vertical)
                    Vector knockback = entity.getLocation().toVector().subtract(impactLoc.toVector()).normalize().multiply(1.8).setY(0.65);
                    entity.setVelocity(knockback);
                }
            }
        }
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) return (int) damage + "❤";
        return damage + "❤";
    }
}
