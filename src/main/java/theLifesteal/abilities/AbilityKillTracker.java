package theLifesteal.abilities;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AbilityKillTracker implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, AbilityAttribution> victimAttributions = new ConcurrentHashMap<>();

    public AbilityKillTracker(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startCleanupTask(); // NEW: periodic cleanup
    }

    /**
     * NEW: Periodically clean up expired attributions to prevent memory leak.
     * Runs every 30 seconds (600 ticks).
     */
    private void startCleanupTask() {
        theLifesteal.util.FoliaScheduler.runGlobalTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            victimAttributions.entrySet().removeIf(entry -> {
                AbilityAttribution attr = entry.getValue();
                return attr == null || attr.isExpired();
            });
        }, 600L, 600L);
    }

    /**
     * Record ability damage or status effect applied to a victim by a casting player.
     * Default attribution duration: 15 seconds (15000 ms).
     */
    public void recordAbilityDamage(Player caster, LivingEntity victim, String abilityId) {
        recordAbilityDamage(caster, victim, abilityId, 15000L);
    }

    /**
     * Record ability damage or status effect applied to a victim by a casting player with custom duration.
     */
    public void recordAbilityDamage(Player caster, LivingEntity victim, String abilityId, long durationMs) {
        if (caster == null || victim == null) return;
        if (caster.getUniqueId().equals(victim.getUniqueId())) return;

        long now = System.currentTimeMillis();
        long expireTime = now + durationMs;

        victimAttributions.put(victim.getUniqueId(),
                new AbilityAttribution(caster.getUniqueId(), abilityId, now, expireTime));
    }

    /**
     * Retrieve the recent casting player for a victim, if attribution is still valid and player is online.
     */
    public Player getCasterForVictim(UUID victimId) {
        if (victimId == null) return null;
        AbilityAttribution attribution = victimAttributions.get(victimId);
        if (attribution == null) return null;

        if (attribution.isExpired()) {
            victimAttributions.remove(victimId);
            return null;
        }

        Player caster = Bukkit.getPlayer(attribution.getCasterId());
        if (caster != null && caster.isOnline()) {
            return caster;
        }
        return null;
    }

    /**
     * Retrieve the active attribution object for a victim, if still valid.
     */
    public AbilityAttribution getAttributionForVictim(UUID victimId) {
        if (victimId == null) return null;
        AbilityAttribution attribution = victimAttributions.get(victimId);
        if (attribution == null) return null;

        if (attribution.isExpired()) {
            victimAttributions.remove(victimId);
            return null;
        }
        return attribution;
    }

    /**
     * Clear victim tracking data.
     */
    public void clearVictim(UUID victimId) {
        if (victimId != null) {
            victimAttributions.remove(victimId);
        }
    }

    /**
     * Automatically update last player damager attribution whenever a player damages another entity.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof LivingEntity victim) {
            if (!attacker.getUniqueId().equals(victim.getUniqueId())) {
                recordAbilityDamage(attacker, victim, "pvp_damage", 15000L);
            }
        }
    }

    public static class AbilityAttribution {
        private final UUID casterId;
        private final String abilityId;
        private final long timestamp;
        private final long expireTime;

        public AbilityAttribution(UUID casterId, String abilityId, long timestamp, long expireTime) {
            this.casterId = casterId;
            this.abilityId = abilityId;
            this.timestamp = timestamp;
            this.expireTime = expireTime;
        }

        public UUID getCasterId() { return casterId; }
        public String getAbilityId() { return abilityId; }
        public long getTimestamp() { return timestamp; }
        public long getExpireTime() { return expireTime; }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
}