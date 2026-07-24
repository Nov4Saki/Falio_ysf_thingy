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

public class ChainLightningAbility extends ItemAbility {

    public ChainLightningAbility(JavaPlugin plugin) {
        super(plugin, "chain_lightning", "Chain Lightning", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("range", 10);
        config.put("chainRange", 5);
        config.put("maxTargets", 4);
        config.put("damage", 5.0);
        config.put("cooldown", 18);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("range", new ConfigField("Initial Range", "int", 3, 30));
        fields.put("chainRange", new ConfigField("Chain Range", "int", 2, 15));
        fields.put("maxTargets", new ConfigField("Max Targets", "int", 1, 10));
        fields.put("damage", new ConfigField("Damage", "double", 1.0, 50.0));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int maxTargets = data.getConfigInt("maxTargets");
        double damage = data.getConfigDouble("damage");
        int cooldown = data.getConfigInt("cooldown");
        int chainRange = data.getConfigInt("chainRange");
        return "&7Chain lightning between &e" + maxTargets + " &7enemies\n&7Deals &c" + formatDamage(damage) + " &7| Chains &b" + chainRange + " blocks\n&8(&e" + cooldown + "s cooldown&8)";
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

        int initialRange = data.getConfigInt("range");
        int chainRange = data.getConfigInt("chainRange");
        int maxTargets = data.getConfigInt("maxTargets");
        double damage = data.getConfigDouble("damage");

        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();

        LivingEntity firstTarget = null;
        double closestDist = initialRange;

        for (Entity entity : player.getNearbyEntities(initialRange, initialRange, initialRange)) {
            if (!(entity instanceof LivingEntity) || entity == player) continue;
            Location entityLoc = entity.getLocation();
            Vector toEntity = entityLoc.toVector().subtract(eyeLoc.toVector());
            double dist = toEntity.length();
            if (dist > closestDist) continue;
            toEntity.normalize();
            if (direction.dot(toEntity) > 0.7) {
                closestDist = dist;
                firstTarget = (LivingEntity) entity;
            }
        }

        if (firstTarget == null) {
            player.sendMessage(ColorUtils.colorize("&7No targets in range."));
            return false;
        }

        Set<LivingEntity> hitTargets = new HashSet<>();
        hitTargets.add(player);
        hitTargets.add(firstTarget);
        List<LivingEntity> chainOrder = new ArrayList<>();
        dealAbilityDamage(player, firstTarget, damage);

        LivingEntity currentTarget = firstTarget;
        while (chainOrder.size() < maxTargets) {
            LivingEntity nextTarget = findClosestTarget(currentTarget, hitTargets, chainRange);
            if (nextTarget == null) break;
            hitTargets.add(nextTarget);
            chainOrder.add(nextTarget);
            dealAbilityDamage(player, nextTarget, damage);
            currentTarget = nextTarget;
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 0.5f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.4f, 2.0f);

        Location prevLoc = player.getEyeLocation();
        for (int i = 0; i < chainOrder.size(); i++) {
            LivingEntity target = chainOrder.get(i);
            Location targetLoc = target.getLocation().add(0, 1, 0);
            final Location from = prevLoc.clone();
            final Location to = targetLoc.clone();
            final int delay = i * 5;

            FoliaScheduler.runRegionLater(to, getPlugin(), () -> {
                drawLightningArc(from, to, 16);
                to.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, to, 20, 0.3, 0.4, 0.3, 0.03);
                to.getWorld().spawnParticle(Particle.DUST, to, 10, 0.2, 0.4, 0.2,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(100, 200, 255), 1.5f));
                to.getWorld().playSound(to, Sound.ENTITY_GLOW_SQUID_HURT, 0.2f, 1.5f);
            }, delay);

            prevLoc = targetLoc;
        }

        player.sendMessage(ColorUtils.colorize("&b⚡ Chain Lightning hit &e" + chainOrder.size() + " &btargets!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private void drawLightningArc(Location from, Location to, int durationTicks) {
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        direction.normalize();

        Vector perpendicular = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        if (perpendicular.length() < 0.1) perpendicular = new Vector(1, 0, 0);

        List<Vector> pathPoints = new ArrayList<>();
        int segments = (int) (distance * 3);
        for (int i = 0; i <= segments; i++) {
            double progress = (double) i / segments;
            Vector point = from.toVector().add(direction.clone().multiply(progress * distance));
            if (i > 0 && i < segments) {
                point.add(perpendicular.clone().multiply((Math.random() - 0.5) * 1.0));
                point.add(new Vector(0, (Math.random() - 0.5) * 0.5, 0));
            }
            pathPoints.add(point);
        }

        int[] tick = new int[]{0};

        FoliaScheduler.runRegionTimer(from, getPlugin(), task -> {
            if (tick[0] >= durationTicks) {
                task.cancel();
                return;
            }

            for (int i = 0; i < pathPoints.size() - 1; i++) {
                Vector p1 = pathPoints.get(i);
                Vector p2 = pathPoints.get(i + 1);
                double segDist = p1.distance(p2);
                Vector segDir = p2.clone().subtract(p1).normalize();

                for (double d = 0; d < segDist; d += 0.4) {
                    Location point = p1.toLocation(from.getWorld()).add(segDir.clone().multiply(d));
                    point.add((Math.random() - 0.5) * 0.1, (Math.random() - 0.5) * 0.1, (Math.random() - 0.5) * 0.1);

                    from.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(150, 220, 255), 1.2f));

                    if (Math.random() < 0.3) {
                        from.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, point, 1, 0, 0, 0, 0);
                    }
                }
            }

            tick[0]++;
        }, 0L, 1L);
    }

    private LivingEntity findClosestTarget(LivingEntity source, Set<LivingEntity> exclude, int range) {
        LivingEntity closest = null;
        double closestDist = range;
        Location sourceLoc = source.getLocation();

        for (Entity entity : source.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof LivingEntity) || entity == source) continue;
            if (exclude.contains(entity)) continue;
            double dist = entity.getLocation().distance(sourceLoc);
            if (dist < closestDist) {
                closestDist = dist;
                closest = (LivingEntity) entity;
            }
        }
        return closest;
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) return (int) damage + "❤";
        return damage + "❤";
    }
}