package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class CriticalStrikeAbility extends ItemAbility {

    private final Map<UUID, Integer> hitCounters;
    private final Map<UUID, BossBar> bossBars;
    private final Map<UUID, FoliaScheduler.TaskHandle> resetTasks;
    private final Map<UUID, Long> lastHitTime;
    private final Set<UUID> processingDamage;

    public CriticalStrikeAbility(JavaPlugin plugin) {
        super(plugin, "critical_strike", "Critical Strike", ItemAbilityType.ON_HIT);
        this.hitCounters = new HashMap<>();
        this.bossBars = new HashMap<>();
        this.resetTasks = new HashMap<>();
        this.lastHitTime = new HashMap<>();
        this.processingDamage = new HashSet<>();
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("hitInterval", 5);
        config.put("multiplier", 2.0);
        config.put("resetTime", 8);
        config.put("affectPlayers", true);
        config.put("affectMobs", true);
        config.put("trigger_on", "FULL_ATTACK");
        config.put("affectPassive", true);
        config.put("affectHostile", true);
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("hitInterval", new ConfigField("Every Nth Hit", "int", 1, 50));
        fields.put("multiplier", new ConfigField("Damage Multiplier", "double", 1.1, 20.0));
        fields.put("resetTime", new ConfigField("Reset After (seconds)", "int", 3, 60));
        fields.put("affectPlayers", new ConfigField("Affect Players", "boolean"));
        fields.put("affectMobs", new ConfigField("Affect Mobs", "boolean"));
        fields.put("trigger_on", new ConfigField("Trigger On", "string"));
        fields.put("affectPassive", new ConfigField("Affect Passive Mobs", "boolean"));
        fields.put("affectHostile", new ConfigField("Affect Hostile Mobs", "boolean"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int interval = data.getConfigInt("hitInterval");
        double multiplier = data.getConfigDouble("multiplier");
        int resetTime = data.getConfigInt("resetTime");
        return "&7Every &e" + interval + getSuffix(interval) + " &7hit deals &c" + String.format("%.1f", multiplier) + "x damage\n&7Resets after &e" + resetTime + "s &7of no hits";
    }

    @Override
    public boolean onHitExecute(Player attacker, LivingEntity victim, ItemAbilityData data,
                                AbilityCooldownManager cooldownManager, String itemId, double baseDamage,
                                org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        // Prevent recursive infinite damage loop
        if (processingDamage.contains(victim.getUniqueId())) return false;

        boolean isPlayer = victim instanceof Player;
        if (isPlayer && !data.getConfigBoolean("affectPlayers")) return false;
        if (!isPlayer && !data.getConfigBoolean("affectMobs")) return false;

        int hitInterval = data.getConfigInt("hitInterval");
        double multiplier = data.getConfigDouble("multiplier");
        int resetTime = data.getConfigInt("resetTime");

        UUID uuid = attacker.getUniqueId();

        // Increment counter
        int currentHit = hitCounters.getOrDefault(uuid, 0) + 1;
        hitCounters.put(uuid, currentHit);
        lastHitTime.put(uuid, System.currentTimeMillis());

        // Cancel existing reset task
        cancelResetTask(uuid);

        // Schedule reset after config time of no hits
        final long hitTime = System.currentTimeMillis();
        FoliaScheduler.TaskHandle task = FoliaScheduler.runEntityLater(attacker, getPlugin(), () -> {
            if (!lastHitTime.containsKey(uuid)) return;
            if (lastHitTime.get(uuid) <= hitTime) {
                hitCounters.put(uuid, 0);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    updateBossBar(p, 0, hitInterval);
                    removeBossBar(p);
                } else {
                    hitCounters.remove(uuid);
                    lastHitTime.remove(uuid);
                    cancelResetTask(uuid);
                    BossBar bar = bossBars.remove(uuid);
                    if (bar != null) bar.removeAll();
                }
            }
        }, resetTime * 20L);
        if (task != null) {
            resetTasks.put(uuid, task);
        }

        // Update bossbar display
        updateBossBar(attacker, currentHit, hitInterval);

        // Check if this hit should crit
        if (currentHit >= hitInterval) {
            processingDamage.add(victim.getUniqueId());

            double bonusDamage = baseDamage * (multiplier - 1.0);
            recordAbilityDamage(attacker, victim);
            if (event != null) {
                event.setDamage(event.getDamage() + bonusDamage);
            } else {
                dealAbilityDamage(attacker, victim, bonusDamage);
            }

            victim.getWorld().spawnParticle(Particle.ENCHANTED_HIT,
                    victim.getLocation().add(0, 1.5, 0), 25, 0.4, 0.8, 0.4, 0.1);
            victim.getWorld().spawnParticle(Particle.CRIT,
                    victim.getLocation().add(0, 1.5, 0), 15, 0.3, 0.5, 0.3, 0.1);
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.2f);
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 2.0f);

            attacker.sendMessage(ColorUtils.colorize("&c⚔ Critical Strike! &7(" + String.format("%.1f", multiplier) + "x damage)"));

            hitCounters.put(uuid, 0);
            updateBossBar(attacker, 0, hitInterval);
            removeBossBar(attacker);
            cancelResetTask(uuid);

            final UUID victimId = victim.getUniqueId();
            FoliaScheduler.runEntityLater(victim, getPlugin(), () -> processingDamage.remove(victimId), 1L);
        }

        return true;
    }

    private void updateBossBar(Player player, int currentHits, int hitInterval) {
        UUID uuid = player.getUniqueId();
        BossBar bar = bossBars.get(uuid);

        if (bar == null) {
            bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
            bar.addPlayer(player);
            bar.setVisible(true);
            bossBars.put(uuid, bar);
        }

        double progress = (double) currentHits / hitInterval;

        if (currentHits == 0) {
            bar.setTitle(ColorUtils.colorize("&a✔ Crit Ready!"));
            bar.setProgress(1.0);
            bar.setColor(BarColor.GREEN);
        } else {
            int remaining = hitInterval - currentHits;
            bar.setTitle(ColorUtils.colorize("&c⚔ Crit: &f" + currentHits + "&7/&f" + hitInterval + " &7(" + remaining + " more)"));
            bar.setProgress(Math.min(1.0, Math.max(0.0, progress)));
            bar.setColor(BarColor.RED);
        }
    }

    private void cancelResetTask(UUID uuid) {
        FoliaScheduler.TaskHandle task = resetTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    public void removeBossBar(Player player) {
        UUID uuid = player.getUniqueId();
        BossBar bar = bossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
        cancelResetTask(uuid);
        hitCounters.remove(uuid);
        lastHitTime.remove(uuid);
    }

    public void cleanup() {
        for (UUID uuid : new HashSet<>(bossBars.keySet())) {
            BossBar bar = bossBars.remove(uuid);
            if (bar != null) bar.removeAll();
        }
        for (FoliaScheduler.TaskHandle task : resetTasks.values()) {
            task.cancel();
        }
        hitCounters.clear();
        resetTasks.clear();
        lastHitTime.clear();
        processingDamage.clear();
    }

    private String getSuffix(int num) {
        if (num % 100 >= 11 && num % 100 <= 13) return "th";
        return switch (num % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        return false; // ON_HIT abilities don't use execute()
    }
}