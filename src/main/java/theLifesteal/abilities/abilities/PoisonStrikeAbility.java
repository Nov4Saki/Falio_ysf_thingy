package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
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

public class PoisonStrikeAbility extends ItemAbility {

    private final Map<UUID, PoisonData> activePoisons;
    private final Set<UUID> processingDamage;

    public PoisonStrikeAbility(JavaPlugin plugin) {
        super(plugin, "poison_strike", "Poison Strike", ItemAbilityType.ON_HIT);
        this.activePoisons = new HashMap<>();
        this.processingDamage = new HashSet<>();
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("chance", 20);
        config.put("poisonDuration", 5);
        config.put("damagePerSecond", 2.0);
        config.put("damagePerStack", 0.8);
        config.put("maxStacks", 5);
        config.put("trigger_on", "FULL_ATTACK");
        config.put("affectPlayers", true);
        config.put("affectMobs", true);
        config.put("affectPassive", true);
        config.put("affectHostile", true);
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("chance", new ConfigField("Chance (%)", "int", 1, 100));
        fields.put("poisonDuration", new ConfigField("Poison Duration (s)", "int", 1, 30));
        fields.put("damagePerSecond", new ConfigField("Damage per Second", "double", 0.5, 20.0));
        fields.put("damagePerStack", new ConfigField("Extra DPS per Stack", "double", 0.1, 10.0));
        fields.put("maxStacks", new ConfigField("Max Stacks", "int", 1, 20));
        fields.put("trigger_on", new ConfigField("Trigger On", "string"));
        fields.put("affectPlayers", new ConfigField("Affect Players", "boolean"));
        fields.put("affectMobs", new ConfigField("Affect Mobs", "boolean"));
        fields.put("affectPassive", new ConfigField("Affect Passive Mobs", "boolean"));
        fields.put("affectHostile", new ConfigField("Affect Hostile Mobs", "boolean"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int chance = data.getConfigInt("chance");
        int duration = data.getConfigInt("poisonDuration");
        double dps = data.getConfigDouble("damagePerSecond");
        double perStack = data.getConfigDouble("damagePerStack");
        int maxStacks = data.getConfigInt("maxStacks");
        double maxDps = dps + (perStack * (maxStacks - 1));
        return "&7Apply &astacking poison &7on hit\n&8(&b" + chance + "%&7, &a" + duration + "s&7, &2" + String.format("%.1f", dps) + " &7DPS)\n&7Max: &2" + String.format("%.1f", maxDps) + " DPS &7at &a" + maxStacks + " stacks";
    }

    @Override
    public boolean onHitExecute(Player attacker, LivingEntity victim, ItemAbilityData data,
                                AbilityCooldownManager cooldownManager, String itemId, double baseDamage) {
        if (processingDamage.contains(victim.getUniqueId())) return false;
        if (victim instanceof Player && !data.getConfigBoolean("affectPlayers")) return false;
        if (!(victim instanceof Player) && !data.getConfigBoolean("affectMobs")) return false;

        int chance = data.getConfigInt("chance");
        if (Math.random() * 100 >= chance) return false;

        int duration = data.getConfigInt("poisonDuration");
        double tempDps = data.getConfigDouble("damagePerSecond");
        if (tempDps == 0.0 && data.getConfig().containsKey("baseDamagePerTick")) {
            tempDps = data.getConfigDouble("baseDamagePerTick") * 2.0;
        }
        final double damagePerSecond = tempDps;
        final double damagePerStack = data.getConfigDouble("damagePerStack");
        int maxStacks = data.getConfigInt("maxStacks");

        UUID victimId = victim.getUniqueId();
        PoisonData existing = activePoisons.get(victimId);

        int newStacks;
        if (existing == null || existing.isExpired()) {
            newStacks = 1;
        } else {
            newStacks = Math.min(existing.stacks + 1, maxStacks);
        }

        double totalDps = damagePerSecond + (damagePerStack * (newStacks - 1));

        if (existing != null && existing.task != null) {
            existing.task.cancel();
        }

        final int finalStacks = newStacks;
        final double finalDps = totalDps;
        final double damagePerTick = finalDps / 2.0;

        int[] ticks = new int[]{0};
        int maxTicks = duration * 20;
        int[] damageTicks = new int[]{0};

        FoliaScheduler.TaskHandle task = FoliaScheduler.runEntityTimer(victim, getPlugin(), taskHandle -> {
            PoisonData pd = activePoisons.get(victimId);
            if (pd == null || pd.stacks != finalStacks) return;

            if (ticks[0] >= maxTicks || victim.isDead() || !victim.isValid()) {
                if (pd.stacks == finalStacks) {
                    pd.stacks = 0; // Mark expired, don't remove
                    if (victim.isValid() && !victim.isDead()) {
                        victim.sendMessage(ColorUtils.colorize("&a☠ Poison has worn off"));
                    }
                }
                taskHandle.cancel();
                return;
            }

            if (ticks[0] % 10 == 0 && ticks[0] > 0) {
                processingDamage.add(victimId);
                dealAbilityDamage(attacker, victim, damagePerTick, (duration * 1000L) + 10000L);
                FoliaScheduler.runEntityLater(victim, getPlugin(), () -> processingDamage.remove(victimId), 1L);
                damageTicks[0]++;

                victim.getWorld().spawnParticle(Particle.DUST,
                        victim.getLocation().add(0, 1.5, 0),
                        3, 0.2, 0.4, 0.2,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 180, 0), 1.2f));

                if (damageTicks[0] % 4 == 0 && victim instanceof Player) {
                    victim.sendMessage(ColorUtils.colorize("&a☠ Poison tick &7-&c" + String.format("%.1f", damagePerTick) + "❤ &7(&a" + finalStacks + " stacks&7, &c" + String.format("%.1f", finalDps) + " DPS&7)"));
                }
            }

            if (ticks[0] % 5 == 0) {
                for (int i = 0; i < 2; i++) {
                    victim.getWorld().spawnParticle(Particle.DUST,
                            victim.getLocation().add(
                                    Math.random() * 0.4 - 0.2,
                                    0.8 + Math.random() * 1.2,
                                    Math.random() * 0.4 - 0.2),
                            1, 0, 0, 0,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(50, 200, 50), 0.8f));
                }
            }

            ticks[0]++;
        }, 0L, 1L);

        PoisonData poisonData = new PoisonData(newStacks, task, System.currentTimeMillis() + (duration * 1000L), totalDps);
        activePoisons.put(victimId, poisonData);

        for (int i = 0; i < 10; i++) {
            victim.getWorld().spawnParticle(Particle.DUST,
                    victim.getLocation().add(0, 1.5, 0),
                    1, 0.3, 0.5, 0.3,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 200, 0), 2f));
        }
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_SPIDER_HURT, 0.5f, 1.0f);

        attacker.sendMessage(ColorUtils.colorize("&a☠ Poison &2" + newStacks + "x &7| &c" + String.format("%.1f", totalDps) + " DPS &7| &a" + duration + "s"));

        if (victim instanceof Player) {
            victim.sendMessage(ColorUtils.colorize("&a☠ You've been poisoned! &7(&c" + String.format("%.1f", totalDps) + " DPS &7| &a" + duration + "s&7)"));
        }

        return true;
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        return false;
    }

    private static class PoisonData {
        int stacks;
        FoliaScheduler.TaskHandle task;
        long expireTime;
        double dps;

        PoisonData(int stacks, FoliaScheduler.TaskHandle task, long expireTime, double dps) {
            this.stacks = stacks;
            this.task = task;
            this.expireTime = expireTime;
            this.dps = dps;
        }

        boolean isExpired() {
            return stacks == 0 || System.currentTimeMillis() > expireTime;
        }
    }
}