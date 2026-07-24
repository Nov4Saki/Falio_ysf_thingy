package theLifesteal.abilities.abilities;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
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

import java.util.LinkedHashMap;
import java.util.Map;

public class BlinkAbility extends ItemAbility {

    public BlinkAbility(JavaPlugin plugin) {
        super(plugin, "blink", "Shadow Step", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("range", 15);
        config.put("strengthAmplifier", 0);
        config.put("strengthDuration", 5);
        config.put("invisibilityDuration", 3);
        config.put("cooldown", 25);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("range", new ConfigField("Max Range", "int", 5, 50));
        fields.put("strengthAmplifier", new ConfigField("Strength Level (0=I)", "int", 0, 5));
        fields.put("strengthDuration", new ConfigField("Strength Duration (s)", "int", 1, 30));
        fields.put("invisibilityDuration", new ConfigField("Invisibility Duration (s)", "int", 1, 30));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int range = data.getConfigInt("range");
        int strAmp = data.getConfigInt("strengthAmplifier");
        int strDur = data.getConfigInt("strengthDuration");
        int invDur = data.getConfigInt("invisibilityDuration");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Teleport behind target within &b" + range + " blocks\n&7Gain &cStrength " + toRoman(strAmp + 1) + " &7+ Invisibility\n&8(&e" + cooldown + "s cooldown&8)";
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
        int strengthAmplifier = data.getConfigInt("strengthAmplifier");
        int strengthDuration = data.getConfigInt("strengthDuration");
        int invisibilityDuration = data.getConfigInt("invisibilityDuration");

        Player target = getTargetPlayer(player, range);
        if (target == null) {
            player.sendMessage(ColorUtils.colorize("&cNo player found in range!"));
            return false;
        }

        Location startLoc = player.getLocation().clone();
        Location targetLoc = target.getLocation().clone();
        Vector direction = targetLoc.getDirection().normalize();
        Location behind = targetLoc.clone().add(direction.clone().multiply(-2));
        behind.setY(targetLoc.getY());

        if (behind.getBlock().getType().isSolid() || behind.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
            behind = targetLoc.clone();
        }

        // Face the target
        Vector faceDir = targetLoc.toVector().subtract(behind.toVector());
        faceDir.setY(0);
        behind.setDirection(faceDir.normalize());

        spawnShadowParticles(startLoc);
        player.getWorld().playSound(startLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.8f);

        player.teleportAsync(behind);

        spawnShadowParticles(behind);
        player.getWorld().playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, strengthDuration * 20, strengthAmplifier, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, invisibilityDuration * 20, 0, false, false, true));

        target.getWorld().spawnParticle(Particle.FLASH, target.getEyeLocation(), 1, 0, 0, 0, 0);

        player.sendMessage(ColorUtils.colorize("&5🖤 Shadow Stepped behind &d" + target.getName() + "&5!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private Player getTargetPlayer(Player player, int range) {
        Player target = null;
        double closestDistance = range;
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();

        for (Entity entity : player.getNearbyEntities(range, range, range)) {
            if (entity instanceof Player && entity != player) {
                Vector toEntity = entity.getLocation().toVector().subtract(eyeLoc.toVector());
                double distance = toEntity.length();
                toEntity.normalize();
                double dot = direction.dot(toEntity);
                if (dot > 0.86 && distance < closestDistance) {
                    if (hasLineOfSight(player, (Player) entity, distance)) {
                        closestDistance = distance;
                        target = (Player) entity;
                    }
                }
            }
        }
        return target;
    }

    private boolean hasLineOfSight(Player player, Player target, double distance) {
        Location start = player.getEyeLocation();
        Vector direction = target.getEyeLocation().toVector().subtract(start.toVector()).normalize();
        for (double d = 0; d <= distance; d += 0.5) {
            Location check = start.clone().add(direction.clone().multiply(d));
            if (check.getBlock().getType().isSolid() &&
                    !check.getBlock().getType().name().contains("GLASS") &&
                    !check.getBlock().getType().name().contains("LEAVES")) {
                return false;
            }
        }
        return true;
    }

    private void spawnShadowParticles(Location loc) {
        for (int i = 0; i < 30; i++) {
            double angle = Math.random() * Math.PI * 2;
            double radius = Math.random() * 1.5;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = Math.random() * 2;
            Particle.SMOKE.builder().location(loc.clone().add(x, y, z)).count(1).extra(0.01).spawn();
        }
        for (int i = 0; i < 20; i++) {
            double angle = Math.random() * Math.PI * 2;
            double radius = Math.random() * 1.2;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = Math.random() * 2;
            Particle.DUST.builder().location(loc.clone().add(x, y, z)).color(org.bukkit.Color.PURPLE).count(1).spawn();
        }
        for (int i = 0; i < 10; i++) {
            double angle = Math.random() * Math.PI * 2;
            double radius = Math.random() * 1.0;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = Math.random() * 2;
            Particle.PORTAL.builder().location(loc.clone().add(x, y, z)).count(1).extra(0.05).spawn();
        }
    }

    private String toRoman(int num) {
        String[] romans = {"", "I", "II", "III", "IV", "V", "VI"};
        if (num > 0 && num < romans.length) return romans[num];
        return String.valueOf(num);
    }
}