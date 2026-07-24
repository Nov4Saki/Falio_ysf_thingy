package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
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

public class PoisonDaggerAbility extends ItemAbility {

    private final Map<UUID, PoisonData> activePoisons;
    private final Map<UUID, ChargeData> chargeDataMap;
    private final Set<UUID> ignoreDamage;

    public PoisonDaggerAbility(JavaPlugin plugin) {
        super(plugin, "poison_dagger", "Poison Dagger", ItemAbilityType.RIGHT_CLICK);
        this.activePoisons = new HashMap<>();
        this.chargeDataMap = new HashMap<>();
        this.ignoreDamage = new HashSet<>();
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("projectiles", 3);
        config.put("spread", 15);
        config.put("poisonDuration", 5);
        config.put("damagePerSecond", 1.5);
        config.put("damagePerStack", 0.6);
        config.put("maxStacks", 5);
        config.put("range", 12);
        config.put("maxCharges", 3);
        config.put("chargeCooldown", 8);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("projectiles", new ConfigField("Projectiles per Charge", "int", 1, 10));
        fields.put("spread", new ConfigField("Spread Angle", "int", 5, 45));
        fields.put("poisonDuration", new ConfigField("Poison Duration (s)", "int", 1, 30));
        fields.put("damagePerSecond", new ConfigField("Damage per Second", "double", 0.5, 20.0));
        fields.put("damagePerStack", new ConfigField("Extra DPS per Stack", "double", 0.1, 10.0));
        fields.put("maxStacks", new ConfigField("Max Stacks", "int", 1, 20));
        fields.put("range", new ConfigField("Range", "int", 5, 30));
        fields.put("maxCharges", new ConfigField("Max Charges", "int", 1, 10));
        fields.put("chargeCooldown", new ConfigField("Cooldown per Charge (s)", "int", 1, 120));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int projectiles = data.getConfigInt("projectiles");
        int maxCharges = data.getConfigInt("maxCharges");
        int chargeCooldown = data.getConfigInt("chargeCooldown");
        double baseDps = data.getConfigDouble("damagePerSecond");
        double perStack = data.getConfigDouble("damagePerStack");
        int maxStacks = data.getConfigInt("maxStacks");
        int duration = data.getConfigInt("poisonDuration");
        double maxDps = baseDps + (perStack * (maxStacks - 1));
        return "&7Throw &e" + projectiles + " &astoxic daggers\n&7&o" + maxCharges + " charges &8| &e" + chargeCooldown + "s &8per charge\n&7&o" + String.format("%.1f", baseDps) + " &a+" + String.format("%.1f", perStack) + "/stack DPS &8| &2" + maxStacks + "x &7max\n&7&o" + String.format("%.1f", maxDps) + " DPS &7at max &8| &a" + duration + "s";
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        int maxCharges = data.getConfigInt("maxCharges");
        int chargeCooldown = data.getConfigInt("chargeCooldown");
        String scope = data.getConfigString("cooldownScope");
        if (scope == null || scope.isEmpty()) scope = "ITEM";

        UUID uuid = player.getUniqueId();
        ChargeData chargeData = chargeDataMap.computeIfAbsent(uuid, k -> new ChargeData());

        if (chargeData.cooldowns.length != maxCharges) {
            long[] newArr = new long[maxCharges];
            int copyCount = Math.min(chargeData.cooldowns.length, maxCharges);
            System.arraycopy(chargeData.cooldowns, 0, newArr, 0, copyCount);
            chargeData.cooldowns = newArr;
        }

        long now = System.currentTimeMillis();
        int chargeIndex = -1;

        for (int i = 0; i < chargeData.cooldowns.length; i++) {
            if (chargeData.cooldowns[i] <= now) {
                chargeIndex = i;
                break;
            }
        }

        if (chargeIndex == -1) {
            long soonest = Long.MAX_VALUE;
            for (long cd : chargeData.cooldowns) {
                if (cd < soonest) soonest = cd;
            }
            long remaining = Math.max(0, (soonest - now) / 1000);
            player.sendMessage(ColorUtils.colorize("&cNo charges! &7Next charge in &e" + remaining + "s"));
            return false;
        }

        chargeData.cooldowns[chargeIndex] = now + (chargeCooldown * 1000L);

        int projectiles = data.getConfigInt("projectiles");
        int spread = data.getConfigInt("spread");
        int poisonDuration = data.getConfigInt("poisonDuration");
        double tempDps = data.getConfigDouble("damagePerSecond");
        if (tempDps == 0.0 && data.getConfig().containsKey("baseDamagePerTick")) {
            tempDps = data.getConfigDouble("baseDamagePerTick") * 2.0;
        }
        final double damagePerSecond = tempDps;
        final double damagePerStack = data.getConfigDouble("damagePerStack");
        int maxStacks = data.getConfigInt("maxStacks");
        int range = data.getConfigInt("range");

        Location start = player.getEyeLocation();
        Vector baseDirection = start.getDirection().normalize();
        Vector perpendicular = new Vector(-baseDirection.getZ(), 0, baseDirection.getX()).normalize();

        player.getWorld().playSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);
        player.getWorld().playSound(start, Sound.ENTITY_LLAMA_SPIT, 0.8f, 1.0f);

        for (int i = 0; i < projectiles; i++) {
            double spreadAngle = (i - (projectiles - 1) / 2.0) * (spread / (double) projectiles);
            double radians = Math.toRadians(spreadAngle);

            Vector direction = baseDirection.clone()
                    .add(perpendicular.clone().multiply(Math.sin(radians)))
                    .normalize();

            Location current = start.clone();
            double[] traveled = new double[]{0.0};

            FoliaScheduler.runRegionTimer(start, getPlugin(), task -> {
                if (traveled[0] >= range) {
                    task.cancel();
                    return;
                }

                current.add(direction.clone().multiply(0.8));
                traveled[0] += 0.8;

                current.getWorld().spawnParticle(Particle.DUST,
                        current, 1, 0.08, 0.08, 0.08,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 180, 0), 1.2f));
                current.getWorld().spawnParticle(Particle.DUST,
                        current.clone().add(0, 0.1, 0),
                        1, 0.05, 0.05, 0.05,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(80, 255, 80), 0.8f));

                for (Entity entity : current.getWorld().getNearbyEntities(current, 1, 1, 1)) {
                    if (entity instanceof LivingEntity && entity != player) {
                        LivingEntity target = (LivingEntity) entity;
                        applyStackingPoison(player, target, poisonDuration, damagePerSecond, damagePerStack, maxStacks);
                        task.cancel();
                        return;
                    }
                }

                if (current.getBlock().getType().isSolid()) {
                    current.getWorld().spawnParticle(Particle.DUST,
                            current, 5, 0.2, 0.2, 0.2,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 150, 0), 0.8f));
                    task.cancel();
                }
            }, 0L, 1L);
        }

        int available = countAvailableCharges(chargeData);
        player.sendMessage(ColorUtils.colorize("&a🗡 Dagger thrown! &7(&e" + available + " &7charges left)"));
        return true;
    }

    private int countAvailableCharges(ChargeData chargeData) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (long cd : chargeData.cooldowns) {
            if (cd <= now) count++;
        }
        return count;
    }

    private void applyStackingPoison(Player attacker, LivingEntity victim, int duration, double damagePerSecond,
                                     double damagePerStack, int maxStacks) {
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
                    pd.stacks = 0;
                    if (victim.isValid() && !victim.isDead()) {
                        victim.sendMessage(ColorUtils.colorize("&a☠ Poison has worn off"));
                    }
                }
                taskHandle.cancel();
                return;
            }

            if (ticks[0] % 10 == 0 && ticks[0] > 0) {
                ignoreDamage.add(victimId);
                dealAbilityDamage(attacker, victim, damagePerTick, (duration * 1000L) + 10000L);
                FoliaScheduler.runEntityLater(victim, getPlugin(), () -> ignoreDamage.remove(victimId), 1L);
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
                            victim.getLocation().add(Math.random() * 0.8 - 0.4, Math.random() * 1.5, Math.random() * 0.8 - 0.4),
                            1, 0, 0, 0,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 220, 0), 0.8f));
                }
            }

            ticks[0]++;
        }, 0L, 1L);

        long expireTime = System.currentTimeMillis() + (duration * 1000L);
        activePoisons.put(victimId, new PoisonData(finalStacks, task, expireTime, finalDps));

        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_SPIDER_HURT, 0.5f, 1.0f);

        attacker.sendMessage(ColorUtils.colorize("&a🗡 Poison &2" + newStacks + "x &7| &c" + String.format("%.1f", totalDps) + " DPS &7| &a" + duration + "s"));

        if (victim instanceof Player) {
            victim.sendMessage(ColorUtils.colorize("&a☠ You've been poisoned! &7(&c" + String.format("%.1f", totalDps) + " DPS &7| &a" + duration + "s&7)"));
        }
    }

    public void cleanupPlayer(UUID uuid) {
        chargeDataMap.remove(uuid);
    }

    private static class ChargeData {
        long[] cooldowns;
        ChargeData() { this.cooldowns = new long[3]; }
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