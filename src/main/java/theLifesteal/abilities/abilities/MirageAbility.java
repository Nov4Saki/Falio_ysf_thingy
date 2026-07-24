package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MirageAbility extends ItemAbility implements Listener {

    private final Map<UUID, MirageSession> activeSessions = new ConcurrentHashMap<>();
    private final Set<UUID> activeCloneUuids = ConcurrentHashMap.newKeySet();

    public MirageAbility(JavaPlugin plugin) {
        super(plugin, "mirage", "Mirage", ItemAbilityType.RIGHT_CLICK);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("duration", 5);
        config.put("darknessRadius", 10.0);
        config.put("darknessDuration", 5);
        config.put("cooldown", 30);
        config.put("cooldownScope", "ABILITY");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("duration", new ConfigField("Duration (seconds)", "int", 1, 60));
        fields.put("darknessRadius", new ConfigField("Darkness Radius (blocks)", "double", 1.0, 50.0));
        fields.put("darknessDuration", new ConfigField("Darkness Duration (sec)", "int", 1, 60));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int duration = data.getConfigInt("duration");
        double radius = data.getConfigDouble("darknessRadius");
        int darknessDur = data.getConfigInt("darknessDuration");
        int cooldown = data.getConfigInt("cooldown");

        return "&7Spawn &e3 fake clones &7that mirror your movement for &b" + duration + "s&7\n" +
                "&7Applies &8Darkness &7to players within &e" + (int) radius + "m &7for &b" + darknessDur + "s\n" +
                "&8(&e" + cooldown + "s cooldown&8)";
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        int cooldown = data.getConfigInt("cooldown");
        String scope = data.getConfigString("cooldownScope");
        if (scope == null || scope.isEmpty()) scope = "ABILITY";

        if (cooldown > 0 && cooldownManager.isOnCooldown(player.getUniqueId(), getId(), itemId, scope)) {
            long remaining = cooldownManager.getRemainingCooldown(player.getUniqueId(), getId(), itemId, scope);
            player.sendMessage(ColorUtils.colorize("&cOn cooldown! &7(" + cooldownManager.formatCooldown(remaining) + ")"));
            return false;
        }

        UUID playerUuid = player.getUniqueId();

        // Clean up any existing session for this player
        cleanupPlayer(playerUuid);

        int durationSec = data.getConfigInt("duration");
        double darknessRadius = data.getConfigDouble("darknessRadius");
        int darknessDuration = data.getConfigInt("darknessDuration");

        // Build Player Head for clones
        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            playerHead.setItemMeta(skullMeta);
        }

        List<Skeleton> clones = new ArrayList<>();

        // Offsets relative to facing direction (front, front-right vertex, front-left vertex)
        Vector[] relativeOffsets = new Vector[] {
                new Vector(0, 0, 2.0),     // Front
                new Vector(1.5, 0, 1.5),   // Front-Right (middle vertex on a square)
                new Vector(-1.5, 0, 1.5)   // Front-Left (middle vertex on a square: a little front + left)
        };

        Location playerLoc = player.getLocation();

        for (int i = 0; i < 3; i++) {
            Location spawnLoc = calculateOffsetLocation(playerLoc, relativeOffsets[i]);
            Skeleton skeleton = playerLoc.getWorld().spawn(spawnLoc, Skeleton.class, s -> {
                s.setCustomName(ColorUtils.colorize("&7" + player.getName()));
                s.setCustomNameVisible(true);
                s.setPersistent(false);
                s.setRemoveWhenFarAway(true);
                s.setAI(false); // Disable AI so clones don't target/attack independently

                EntityEquipment equipment = s.getEquipment();
                if (equipment != null) {
                    equipment.setHelmet(playerHead);
                    equipment.setHelmetDropChance(0.0f); // Skeletons don't drop head after death
                    equipment.setItemInMainHand(null);
                    equipment.setItemInMainHandDropChance(0.0f);
                    equipment.setChestplate(null);
                    equipment.setChestplateDropChance(0.0f);
                    equipment.setLeggings(null);
                    equipment.setLeggingsDropChance(0.0f);
                    equipment.setBoots(null);
                    equipment.setBootsDropChance(0.0f);
                }
            });

            clones.add(skeleton);
            activeCloneUuids.add(skeleton.getUniqueId());

            // Visual & Sound effect on clone spawn
            skeleton.getWorld().spawnParticle(Particle.SMOKE, skeleton.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.05);
            skeleton.getWorld().spawnParticle(Particle.WITCH, skeleton.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.05);
        }

        player.getWorld().playSound(playerLoc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.0f);
        player.getWorld().playSound(playerLoc, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.2f);

        // Apply Darkness effect to nearby players
        if (darknessRadius > 0 && darknessDuration > 0) {
            PotionEffect darknessEffect = new PotionEffect(PotionEffectType.DARKNESS, darknessDuration * 20, 0);
            for (Entity entity : player.getWorld().getNearbyEntities(playerLoc, darknessRadius, darknessRadius, darknessRadius)) {
                if (entity instanceof Player targetPlayer && !targetPlayer.getUniqueId().equals(playerUuid)) {
                    targetPlayer.addPotionEffect(darknessEffect);
                    targetPlayer.sendMessage(ColorUtils.colorize("&8👥 You have been shrouded in darkness by " + player.getName() + "'s Mirage!"));
                }
            }
        }

        MirageSession session = new MirageSession(clones);

        // Repeating task to update clone positions relative to player every tick
        int maxTicks = durationSec * 20;
        int[] ticksElapsed = new int[]{0};

        FoliaScheduler.TaskHandle trackingTask = FoliaScheduler.runEntityTimer(player, getPlugin(), task -> {
            ticksElapsed[0]++;

            if (ticksElapsed[0] >= maxTicks || !player.isOnline() || player.isDead()) {
                cleanupPlayer(playerUuid);
                task.cancel();
                return;
            }

            Location currentLoc = player.getLocation();

            for (int i = 0; i < clones.size(); i++) {
                Skeleton clone = clones.get(i);
                if (clone != null && clone.isValid()) {
                    Location targetLoc = calculateOffsetLocation(currentLoc, relativeOffsets[i]);
                    clone.teleportAsync(targetLoc);

                    // Subtle particles following clones
                    if (ticksElapsed[0] % 5 == 0) {
                        clone.getWorld().spawnParticle(Particle.WITCH, clone.getLocation().add(0, 1, 0), 2, 0.2, 0.3, 0.2, 0.01);
                    }
                }
            }
        }, 0L, 1L);

        session.task = trackingTask;
        activeSessions.put(playerUuid, session);

        player.sendMessage(ColorUtils.colorize("&a👥 Mirage activated! Spawning 3 illusion clones."));

        if (cooldown > 0) {
            cooldownManager.setCooldown(playerUuid, getId(), itemId, scope, cooldown);
        }

        return true;
    }

    /**
     * Calculate target location from base location given relative X/Z offsets (where offset.getZ() is forward).
     */
    private Location calculateOffsetLocation(Location baseLoc, Vector relativeOffset) {
        float yaw = baseLoc.getYaw();
        double yawRad = Math.toRadians(yaw);

        // Direction vectors based on player yaw
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        double offsetX = (relativeOffset.getZ() * forwardX) + (relativeOffset.getX() * rightX);
        double offsetZ = (relativeOffset.getZ() * forwardZ) + (relativeOffset.getX() * rightZ);

        Location loc = baseLoc.clone().add(offsetX, 0, offsetZ);
        loc.setYaw(yaw);
        loc.setPitch(baseLoc.getPitch());
        return loc;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCloneDamage(EntityDamageEvent event) {
        if (activeCloneUuids.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true); // Clones don't take damage or drop loot
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCloneDamageEntity(EntityDamageByEntityEvent event) {
        if (activeCloneUuids.contains(event.getDamager().getUniqueId())) {
            event.setCancelled(true); // Clones don't deal damage
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCloneTarget(EntityTargetEvent event) {
        if (activeCloneUuids.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    public void cleanupPlayer(UUID playerUuid) {
        MirageSession session = activeSessions.remove(playerUuid);
        if (session != null) {
            if (session.task != null) {
                session.task.cancel();
            }
            for (Skeleton clone : session.clones) {
                if (clone != null && clone.isValid()) {
                    activeCloneUuids.remove(clone.getUniqueId());
                    clone.getWorld().spawnParticle(Particle.SMOKE, clone.getLocation().add(0, 1, 0), 10, 0.2, 0.4, 0.2, 0.05);
                    clone.remove();
                }
            }
        }
    }

    private static class MirageSession {
        final List<Skeleton> clones;
        FoliaScheduler.TaskHandle task;

        MirageSession(List<Skeleton> clones) {
            this.clones = clones;
        }
    }
}
