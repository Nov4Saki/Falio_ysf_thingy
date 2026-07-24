package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class BloodBoltsAbility extends ItemAbility {

    private final Map<UUID, BurstSession> burstSessions;

    public BloodBoltsAbility(JavaPlugin plugin) {
        super(plugin, "blood_bolts", "Blood Bolts", ItemAbilityType.RIGHT_CLICK);
        this.burstSessions = new HashMap<>();
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("projectiles", 5);
        config.put("burstDelay", 4);
        config.put("range", 15);
        config.put("damage", 3.0);
        config.put("selfDamage", 2.0);
        config.put("cooldown", 20);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("projectiles", new ConfigField("Projectiles", "int", 1, 20));
        fields.put("burstDelay", new ConfigField("Ticks Between Shots", "int", 2, 30));
        fields.put("range", new ConfigField("Range", "int", 5, 30));
        fields.put("damage", new ConfigField("Damage per Bolt", "double", 1.0, 20.0));
        fields.put("selfDamage", new ConfigField("Self Damage", "double", 0.0, 20.0));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int projectiles = data.getConfigInt("projectiles");
        double damage = data.getConfigDouble("damage");
        double selfDmg = data.getConfigDouble("selfDamage");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Fire &e" + projectiles + " &cblood bolts &7in bursts\n&7Deals &c" + formatDamage(damage) + " each &7| Costs &4" + formatDamage(selfDmg) + " &7health\n&8(&e" + cooldown + "s cooldown&8)";
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

        UUID uuid = player.getUniqueId();

        if (burstSessions.containsKey(uuid)) return true;

        int totalProjectiles = data.getConfigInt("projectiles");
        int burstDelay = data.getConfigInt("burstDelay");
        int range = data.getConfigInt("range");
        double damage = data.getConfigDouble("damage");
        double selfDamage = data.getConfigDouble("selfDamage");

        if (selfDamage > 0) {
            if (!checkStrictHealthRequirement(player, selfDamage)) return false;
            applySelfHealthCost(player, selfDamage);
        }

        for (int i = 0; i < 20; i++) {
            player.getWorld().spawnParticle(Particle.DUST,
                    player.getLocation().add(0, 1.5, 0),
                    1, 0.3, 0.5, 0.3,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(180, 0, 0), 2f));
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.6f);

        BurstSession session = new BurstSession();
        burstSessions.put(uuid, session);

        NamespacedKey customItemKey = new NamespacedKey(getPlugin(), "custom_item_id");
        int[] shot = new int[]{0};

        FoliaScheduler.TaskHandle task = FoliaScheduler.runEntityTimer(player, getPlugin(), taskHandle -> {
            boolean holding = false;
            if (player.getInventory().getItemInMainHand() != null &&
                    player.getInventory().getItemInMainHand().hasItemMeta() &&
                    player.getInventory().getItemInMainHand().getItemMeta().getPersistentDataContainer()
                            .has(customItemKey, PersistentDataType.STRING)) {
                holding = true;
            }

            if (!holding || !player.isOnline()) {
                burstSessions.remove(uuid);
                player.sendMessage(ColorUtils.colorize("&7Burst cancelled."));
                taskHandle.cancel();
                return;
            }

            if (shot[0] >= totalProjectiles) {
                burstSessions.remove(uuid);
                taskHandle.cancel();
                return;
            }

            Location start = player.getEyeLocation();
            Vector direction = start.getDirection().normalize();
            direction.add(new Vector(
                    (Math.random() - 0.5) * 0.15,
                    (Math.random() - 0.5) * 0.1,
                    (Math.random() - 0.5) * 0.15));
            direction.normalize();

            spawnBolt(start.clone(), direction, range, damage, player);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WITCH_THROW, 0.3f, 1.0f + (shot[0] * 0.1f));

            shot[0]++;
        }, 0L, burstDelay);

        session.task = task;

        player.sendMessage(ColorUtils.colorize("&c🩸 Firing blood bolts!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private void spawnBolt(Location start, Vector direction, int range, double damage, Player player) {
        Location current = start.clone();
        double[] traveled = new double[]{0.0};

        FoliaScheduler.runRegionTimer(start, getPlugin(), task -> {
            if (traveled[0] >= range) {
                task.cancel();
                return;
            }

            current.add(direction.clone().multiply(1.5));
            traveled[0] += 1.5;

            current.getWorld().spawnParticle(Particle.DUST,
                    current, 1, 0, 0, 0,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(220, 30, 30), 1f));

            for (Entity entity : current.getWorld().getNearbyEntities(current, 0.6, 0.6, 0.6)) {
                if (!(entity instanceof LivingEntity) || entity == player) continue;
                LivingEntity target = (LivingEntity) entity;
                dealAbilityDamage(player, target, damage);

                for (int i = 0; i < 10; i++) {
                    target.getWorld().spawnParticle(Particle.DUST,
                            target.getLocation().add(0, 1.5, 0).add(Math.random() * 0.8 - 0.4, Math.random() * 0.8, Math.random() * 0.8 - 0.4),
                            1, 0, 0, 0,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 0, 0), 1.5f));
                }
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.4f, 1.0f);
                task.cancel();
                return;
            }

            if (current.getBlock().getType().isSolid()) {
                task.cancel();
            }
        }, 0L, 1L);
    }

    public void cleanupPlayer(UUID uuid) {
        BurstSession session = burstSessions.remove(uuid);
        if (session != null && session.task != null) {
            session.task.cancel();
        }
    }

    private static class BurstSession {
        FoliaScheduler.TaskHandle task;
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) return (int) damage + "❤";
        return damage + "❤";
    }
}