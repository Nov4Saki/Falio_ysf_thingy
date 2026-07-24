package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class WitheringStrikeAbility extends ItemAbility {

    private final Map<UUID, WitherData> activeWithers;
    private final Set<UUID> ignoreDamage;

    public WitheringStrikeAbility(JavaPlugin plugin) {
        super(plugin, "withering_strike", "Withering Strike", ItemAbilityType.ON_HIT);
        this.activeWithers = new HashMap<>();
        this.ignoreDamage = new HashSet<>();
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("chance", 25);
        config.put("witherDuration", 5);
        config.put("witherAmplifier", 1);
        config.put("maxStacks", 3);
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
        fields.put("witherDuration", new ConfigField("Wither Duration (s)", "int", 1, 15));
        fields.put("witherAmplifier", new ConfigField("Wither Level (0=I)", "int", 0, 5));
        fields.put("maxStacks", new ConfigField("Max Stacks", "int", 1, 10));
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
        int duration = data.getConfigInt("witherDuration");
        int amplifier = data.getConfigInt("witherAmplifier");
        int maxStacks = data.getConfigInt("maxStacks");
        return "&7Apply &8Wither " + toRoman(amplifier + 1) + " &7on hit\n&8(&b" + chance + "%&7, &8" + duration + "s&7, stacks up to &8" + maxStacks + "x&8)";
    }

    @Override
    public boolean onHitExecute(Player attacker, LivingEntity victim, ItemAbilityData data,
                                AbilityCooldownManager cooldownManager, String itemId, double baseDamage) {
        if (ignoreDamage.contains(victim.getUniqueId())) return false;

        int chance = data.getConfigInt("chance");
        if (Math.random() * 100 >= chance) return false;

        int duration = data.getConfigInt("witherDuration");
        int amplifier = data.getConfigInt("witherAmplifier");
        int maxStacks = data.getConfigInt("maxStacks");

        UUID victimId = victim.getUniqueId();
        WitherData witherData = activeWithers.get(victimId);

        int newStacks;
        if (witherData == null || witherData.isExpired()) {
            newStacks = 1;
        } else {
            newStacks = Math.min(witherData.stacks + 1, maxStacks);
        }

        // Higher stacks = higher amplifier + longer duration
        int effectiveAmplifier = Math.min(amplifier + newStacks - 1, 5);
        int effectiveDuration = duration + (newStacks - 1) * 2;

        if (witherData != null && witherData.task != null) {
            witherData.task.cancel();
        }

        final int finalStacks = newStacks;

        FoliaScheduler.TaskHandle task = FoliaScheduler.runEntityLater(victim, getPlugin(), () -> {
            WitherData wd = activeWithers.get(victimId);
            if (wd != null && wd.stacks == finalStacks) {
                activeWithers.remove(victimId);
            }
        }, effectiveDuration * 20L);

        recordAbilityDamage(attacker, victim, (effectiveDuration * 1000L) + 10000L);
        victim.addPotionEffect(new PotionEffect(
                PotionEffectType.WITHER, effectiveDuration * 20, effectiveAmplifier, false, true, true));

        witherData = new WitherData(newStacks, task, System.currentTimeMillis() + (effectiveDuration * 1000L));
        activeWithers.put(victimId, witherData);

        // Wither particles
        for (int i = 0; i < 12; i++) {
            victim.getWorld().spawnParticle(Particle.DUST,
                    victim.getLocation().add(0, 1.5, 0),
                    1, 0.3, 0.5, 0.3,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(40, 40, 40), 1.5f));
        }
        for (int i = 0; i < 6; i++) {
            victim.getWorld().spawnParticle(Particle.SMOKE,
                    victim.getLocation().add(
                            Math.random() * 0.6 - 0.3,
                            1.0 + Math.random() * 1.2,
                            Math.random() * 0.6 - 0.3),
                    1, 0, 0, 0, 0.03);
        }
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.4f, 1.0f);

        attacker.sendMessage(ColorUtils.colorize("&8☠ Withering Strike! &7(Stack &8" + newStacks + "&7/&8" + maxStacks + "&7)"));
        return true;
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        return false;
    }

    private String toRoman(int num) {
        String[] romans = {"", "I", "II", "III", "IV", "V", "VI"};
        if (num > 0 && num < romans.length) return romans[num];
        return String.valueOf(num);
    }

    private static class WitherData {
        int stacks;
        FoliaScheduler.TaskHandle task;
        long expireTime;

        WitherData(int stacks, FoliaScheduler.TaskHandle task, long expireTime) {
            this.stacks = stacks;
            this.task = task;
            this.expireTime = expireTime;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
}