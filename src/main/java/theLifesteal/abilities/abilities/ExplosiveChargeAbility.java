package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;
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

public class ExplosiveChargeAbility extends ItemAbility implements Listener {

    private final Map<UUID, ChargeSession> chargeSessions;
    private final Map<UUID, Location> chargePositions;

    public ExplosiveChargeAbility(JavaPlugin plugin) {
        super(plugin, "explosive_charge", "Explosive Charge", ItemAbilityType.RIGHT_CLICK);
        this.chargeSessions = new HashMap<>();
        this.chargePositions = new HashMap<>();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("chargeTime", 4);
        config.put("explosionRadius", 8);
        config.put("maxDamage", 20.0);
        config.put("knockbackPower", 3.0);
        config.put("blindnessDuration", 3);
        config.put("selfDamage", false);
        config.put("cooldown", 45);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("chargeTime", new ConfigField("Charge Time (seconds)", "int", 2, 15));
        fields.put("explosionRadius", new ConfigField("Explosion Radius", "int", 3, 20));
        fields.put("maxDamage", new ConfigField("Max Damage", "double", 5.0, 100.0));
        fields.put("knockbackPower", new ConfigField("Knockback Power", "double", 0.5, 8.0));
        fields.put("blindnessDuration", new ConfigField("Blindness Duration (s)", "int", 0, 10));
        fields.put("selfDamage", new ConfigField("Self Damage", "boolean"));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int chargeTime = data.getConfigInt("chargeTime");
        double maxDmg = data.getConfigDouble("maxDamage");
        int radius = data.getConfigInt("explosionRadius");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Hold to charge an explosion over &e" + chargeTime + "s\n&7Deals up to &c" + formatDamage(maxDmg) + " &7in &b" + radius + " block radius\n&8(&e" + cooldown + "s cooldown&8)";
    }

    @Override
    public double getRequiredHpCost(ItemAbilityData data, Player player) {
        if (data == null || player == null) return 0.0;
        if (data.getConfigBoolean("selfDamage")) {
            double maxDamage = data.getConfigDouble("maxDamage");
            return maxDamage * 0.3;
        }
        return 0.0;
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

        if (chargeSessions.containsKey(uuid)) return true;

        int chargeTime = data.getConfigInt("chargeTime");
        int explosionRadius = data.getConfigInt("explosionRadius");
        double maxDamage = data.getConfigDouble("maxDamage");
        double knockbackPower = data.getConfigDouble("knockbackPower");
        int blindnessDuration = data.getConfigInt("blindnessDuration");
        boolean selfDamage = data.getConfigBoolean("selfDamage");

        if (selfDamage) {
            double selfCost = maxDamage * 0.3;
            if (!checkStrictHealthRequirement(player, selfCost)) {
                return false;
            }
        }

        int totalTicks = chargeTime * 20;

        final Location chargeLocation = player.getLocation().clone();
        chargePositions.put(uuid, chargeLocation);
        player.setWalkSpeed(0f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, totalTicks + 40, -10, false, false, false));

        BossBar bossBar = Bukkit.createBossBar(
                ColorUtils.colorize("&7💣 Charging... &f0%"),
                BarColor.RED, BarStyle.SOLID);
        bossBar.addPlayer(player);
        bossBar.setProgress(0.0);

        ChargeSession session = new ChargeSession();
        session.chargeLocation = chargeLocation;
        chargeSessions.put(uuid, session);

        player.sendMessage(ColorUtils.colorize("&7💣 Charging... Hold your item!"));

        NamespacedKey customItemKey = new NamespacedKey(getPlugin(), "custom_item_id");

        int[] tick = new int[]{0};
        int[] offTick = new int[]{0};

        FoliaScheduler.TaskHandle task = FoliaScheduler.runEntityTimer(player, getPlugin(), taskHandle -> {
            if (!player.isOnline()) {
                cancelCharge(player, bossBar);
                taskHandle.cancel();
                return;
            }

            boolean holdingItem = false;
            if (player.getInventory().getItemInMainHand() != null &&
                    player.getInventory().getItemInMainHand().hasItemMeta() &&
                    player.getInventory().getItemInMainHand().getItemMeta().getPersistentDataContainer()
                            .has(customItemKey, PersistentDataType.STRING)) {
                holdingItem = true;
            }

            if (!holdingItem) {
                offTick[0]++;
                if (offTick[0] >= 30) {
                    cancelCharge(player, bossBar);
                    player.sendMessage(ColorUtils.colorize("&cCharge cancelled - you let go!"));
                    taskHandle.cancel();
                    return;
                }
                if (offTick[0] == 10) {
                    player.sendMessage(ColorUtils.colorize("&eRe-equip the item to keep charging!"));
                }
                return;
            } else {
                offTick[0] = 0;
            }

            if (tick[0] >= totalTicks) {
                bossBar.removeAll();
                chargeSessions.remove(uuid);
                chargePositions.remove(uuid);
                explode(player, maxDamage, explosionRadius, knockbackPower, blindnessDuration, selfDamage);
                resetPlayer(player);
                taskHandle.cancel();
                return;
            }

            double progress = (double) tick[0] / totalTicks;
            int percent = (int) (progress * 100);

            bossBar.setProgress(Math.min(1.0, progress));
            if (progress < 0.5) {
                bossBar.setTitle(ColorUtils.colorize("&7💣 Charging... &f" + percent + "%"));
                bossBar.setColor(BarColor.YELLOW);
            } else if (progress < 0.85) {
                bossBar.setTitle(ColorUtils.colorize("&6💣 Building... &f" + percent + "%"));
                bossBar.setColor(BarColor.RED);
            } else {
                bossBar.setTitle(ColorUtils.colorize("&c💥 ABOUT TO EXPLODE! &f" + percent + "%"));
                bossBar.setColor(BarColor.RED);
            }

            Location loc = player.getLocation().add(0, 1, 0);

            org.bukkit.Color color;
            if (progress < 0.3) {
                double p = progress / 0.3;
                color = org.bukkit.Color.fromRGB((int)(40 + p * 215), (int)(40 + p * 125), (int)(40 + p * 0));
            } else if (progress < 0.6) {
                double p = (progress - 0.3) / 0.3;
                color = org.bukkit.Color.fromRGB(255, (int)(165 + p * 90), (int)(p * 255));
            } else {
                double p = (progress - 0.6) / 0.4;
                color = org.bukkit.Color.fromRGB(255, (int)(255 - p * 155), (int)(255 - p * 155));
            }

            // OPTIMIZED PARTICLES: 3 orbiting + 2 sparks
            double orbitRadius = 1.0 + progress * 1.2;
            double orbitSpeed = 0.3 + progress * 3.0;

            for (int i = 0; i < 3; i++) {
                double angle = (tick[0] * orbitSpeed * 0.3 + i * Math.PI * 2 / 3);
                double x = Math.cos(angle) * orbitRadius;
                double z = Math.sin(angle) * orbitRadius;
                double y = Math.sin(tick[0] * 0.3 + i) * 0.5;

                loc.getWorld().spawnParticle(Particle.DUST,
                        loc.clone().add(x, y, z),
                        1, 0, 0, 0,
                        new Particle.DustOptions(color, 2f + (float)progress));
            }

            for (int i = 0; i < 2; i++) {
                loc.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                        loc.clone().add(
                                (Math.random() - 0.5) * 1.2,
                                Math.random() * 1.5,
                                (Math.random() - 0.5) * 1.2),
                        1, 0, 0, 0, 0);
            }

            if (tick[0] % 20 == 0 && progress < 0.5) {
                player.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.5f + (float)progress);
            }
            if (progress > 0.7 && tick[0] % 8 == 0) {
                player.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 0.3f);
            }

            tick[0]++;
        }, 0L, 1L);

        session.task = task;
        session.bossBar = bossBar;

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location anchor = chargePositions.get(uuid);

        if (anchor == null) return;

        Location to = event.getTo();
        Location from = event.getFrom();

        boolean movedX = to.getX() != from.getX();
        boolean movedZ = to.getZ() != from.getZ();

        if (movedX || movedZ) {
            Location corrected = from.clone();
            corrected.setYaw(to.getYaw());
            corrected.setPitch(to.getPitch());
            event.setTo(corrected);
        }
    }

    private void cancelCharge(Player player, BossBar bossBar) {
        UUID uuid = player.getUniqueId();
        ChargeSession session = chargeSessions.remove(uuid);
        if (session != null && session.task != null) {
            session.task.cancel();
        }
        if (bossBar != null) {
            bossBar.removeAll();
        }
        chargePositions.remove(uuid);
        resetPlayer(player);
    }

    private void explode(Player player, double damage, int radius, double knockbackPower,
                         int blindnessDuration, boolean selfDamage) {
        Location center = player.getLocation();

        player.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.5f);
        player.getWorld().playSound(center, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.5f, 0.3f);

        player.getWorld().spawnParticle(Particle.EXPLOSION, center.add(0, 1, 0), 3, 1, 1, 1, 0.2);

        // OPTIMIZED: Single shockwave — 24 angles × 3 rings = 72 particles (was 192)
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2 / 24;
            for (int ring = 0; ring < 3; ring++) {
                double ringR = ring * 2.5;
                double x = Math.cos(angle) * ringR;
                double z = Math.sin(angle) * ringR;
                center.getWorld().spawnParticle(Particle.DUST,
                        center.clone().add(x, 0.2, z), 1, 0, 0, 0,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 160, 40), 3f));
            }
        }

        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity)) continue;
            if (entity == player && !selfDamage) continue;

            LivingEntity target = (LivingEntity) entity;
            double dist = target.getLocation().distance(center);
            if (dist <= radius) {
                double damageMod = 1.0 - (dist / radius);
                dealAbilityDamage(player, target, damage * damageMod);

                Vector dir = target.getLocation().toVector().subtract(center.toVector()).normalize();
                dir.setY(0.5);
                target.setVelocity(dir.multiply(knockbackPower * (1.0 - dist / radius + 0.3)));

                if (target instanceof Player && blindnessDuration > 0) {
                    ((Player) target).addPotionEffect(new PotionEffect(
                            PotionEffectType.BLINDNESS, blindnessDuration * 20, 0, false, true, true));
                }
            }
        }

        if (selfDamage) {
            double selfCost = damage * 0.3;
            if (checkStrictHealthRequirement(player, selfCost)) {
                applySelfHealthCost(player, selfCost);
            }
        }

        // OPTIMIZED: 5 smoke every 3 ticks for 30 ticks (was 10 every 2 ticks for 40)
        int[] t = new int[]{0};
        FoliaScheduler.runRegionTimer(center, getPlugin(), task -> {
            if (t[0] >= 30) { task.cancel(); return; }
            for (int i = 0; i < 5; i++) {
                center.getWorld().spawnParticle(Particle.CLOUD,
                        center.clone().add(Math.random() * radius * 0.5 - radius * 0.25, Math.random() * 2, Math.random() * radius * 0.5 - radius * 0.25),
                        1, 0, 0, 0, 0.03);
            }
            t[0]++;
        }, 0L, 3L);

        player.sendMessage(ColorUtils.colorize("&c💥 BOOM!"));
    }

    private void resetPlayer(Player player) {
        player.setWalkSpeed(0.2f);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
    }

    public void cleanupPlayer(UUID uuid) {
        ChargeSession session = chargeSessions.remove(uuid);
        if (session != null) {
            if (session.task != null) session.task.cancel();
            if (session.bossBar != null) session.bossBar.removeAll();
        }
        chargePositions.remove(uuid);
    }

    private static class ChargeSession {
        FoliaScheduler.TaskHandle task;
        BossBar bossBar;
        Location chargeLocation;
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) return (int) damage + "❤";
        return damage + "❤";
    }
}