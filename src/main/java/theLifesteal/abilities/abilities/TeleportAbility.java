package theLifesteal.abilities.abilities;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;

import java.util.LinkedHashMap;
import java.util.Map;

public class TeleportAbility extends ItemAbility {

    public TeleportAbility(JavaPlugin plugin) {
        super(plugin, "teleport", "Blink", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxDistance", 20);
        config.put("cooldown", 15);
        config.put("passThrough", false);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("maxDistance", new ConfigField("Max Distance", "int", 1, 100));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("passThrough", new ConfigField("Pass Through Walls", "boolean"));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int distance = data.getConfigInt("maxDistance");
        int cooldown = data.getConfigInt("cooldown");
        boolean passThrough = data.getConfigBoolean("passThrough");
        String wall = passThrough ? " &7(phases walls)" : "";
        return "&7Teleport &b" + distance + " blocks &7forward" + wall + " &8(&e" + cooldown + "s cooldown&8)";
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

        int maxDistance = data.getConfigInt("maxDistance");
        boolean passThrough = data.getConfigBoolean("passThrough");

        Location start = player.getEyeLocation();
        Vector direction = start.getDirection();
        Location destination = null;

        for (double d = 1; d <= maxDistance; d += 0.5) {
            Location check = start.clone().add(direction.clone().multiply(d));

            if (!passThrough && check.getBlock().getType().isSolid()) {
                destination = start.clone().add(direction.clone().multiply(d - 1));
                break;
            }

            if (d >= maxDistance - 0.4) {
                destination = check;
            }
        }

        if (destination == null) {
            player.sendMessage(ColorUtils.colorize("&cNo valid destination found!"));
            return false;
        }

        spawnTeleportParticles(player.getLocation());
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.8f);

        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        player.teleportAsync(destination);

        spawnTeleportParticles(destination);
        player.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);

        player.sendMessage(ColorUtils.colorize("&5✦ Teleported " + String.format("%.1f", start.distance(destination)) + " blocks!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private void spawnTeleportParticles(Location loc) {
        for (int i = 0; i < 30; i++) {
            double angle = Math.random() * Math.PI * 2;
            double radius = Math.random() * 1.5;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = Math.random() * 2;
            loc.getWorld().spawnParticle(Particle.PORTAL, loc.clone().add(x, y, z), 1, 0, 0, 0, 0.05);
        }
        for (int i = 0; i < 15; i++) {
            loc.getWorld().spawnParticle(Particle.DUST,
                    loc.clone().add(Math.random() * 2 - 1, Math.random() * 2, Math.random() * 2 - 1),
                    1, 0, 0, 0, new Particle.DustOptions(org.bukkit.Color.PURPLE, 2f));
        }
    }
}