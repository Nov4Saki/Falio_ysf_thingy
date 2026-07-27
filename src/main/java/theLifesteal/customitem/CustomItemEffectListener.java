package theLifesteal.customitem;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class CustomItemEffectListener implements Listener {

    private final JavaPlugin plugin;
    private final AdvancedCustomItemManager manager;
    private final Map<UUID, Set<PotionEffectType>> activeEffects;
    private FoliaScheduler.TaskHandle refreshTask;

    public CustomItemEffectListener(JavaPlugin plugin, AdvancedCustomItemManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.activeEffects = new HashMap<>();
        startRefreshTask();
    }

    private void startRefreshTask() {
        refreshTask = FoliaScheduler.runGlobalTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                FoliaScheduler.runEntity(player, plugin, () -> refreshPlayerEffects(player));
            }
        }, 20L, 20L);
    }

    private void refreshPlayerEffects(Player player) {
        UUID uuid = player.getUniqueId();

        Map<PotionEffectType, AdvancedCustomItem.PotionEffectData> desiredEffects = new LinkedHashMap<>();

        // Check main hand
        addItemEffects(player.getInventory().getItemInMainHand(), desiredEffects);
        // Check off hand
        addItemEffects(player.getInventory().getItemInOffHand(), desiredEffects);
        // Check armor slots
        for (ItemStack armorPiece : player.getInventory().getArmorContents()) {
            addItemEffects(armorPiece, desiredEffects);
        }

        Set<PotionEffectType> oldEffects = activeEffects.getOrDefault(uuid, new HashSet<>());

        // Apply desired effects with infinite duration (refreshed every second)
        for (AdvancedCustomItem.PotionEffectData effectData : desiredEffects.values()) {
            PotionEffect effect = new PotionEffect(
                    effectData.getType(),
                    40, // 2 seconds - refreshed every 1 second so it never expires
                    effectData.getAmplifier(),
                    false, // ambient
                    effectData.showParticles(), // particles
                    true // icon
            );
            player.addPotionEffect(effect);
        }

        // Remove effects that are no longer desired
        for (PotionEffectType type : oldEffects) {
            if (!desiredEffects.containsKey(type)) {
                player.removePotionEffect(type);
            }
        }

        activeEffects.put(uuid, desiredEffects.keySet());
    }

    private void addItemEffects(ItemStack item, Map<PotionEffectType, AdvancedCustomItem.PotionEffectData> effects) {
        if (item == null || item.getType().isAir()) return;

        String itemId = manager.getItemId(item);
        if (itemId == null) return;

        AdvancedCustomItem customItem = manager.getItem(itemId);
        if (customItem == null) return;

        List<AdvancedCustomItem.PotionEffectData> itemEffects = customItem.getPotionEffects();
        if (itemEffects.isEmpty()) return;

        for (AdvancedCustomItem.PotionEffectData effectData : itemEffects) {
            PotionEffectType type = effectData.getType();
            AdvancedCustomItem.PotionEffectData existing = effects.get(type);
            if (existing == null || effectData.getAmplifier() > existing.getAmplifier()) {
                effects.put(type, effectData);
            }
        }
    }

    public void clearPlayerEffects(Player player) {
        UUID uuid = player.getUniqueId();
        Set<PotionEffectType> effects = activeEffects.remove(uuid);
        if (effects != null) {
            for (PotionEffectType type : effects) {
                player.removePotionEffect(type);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FoliaScheduler.runEntityLater(player, plugin, () -> refreshPlayerEffects(player), 5L);
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        FoliaScheduler.runEntityLater(player, plugin, () -> refreshPlayerEffects(player), 1L);
    }

    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        FoliaScheduler.runEntityLater(player, plugin, () -> refreshPlayerEffects(player), 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayerEffects(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        clearPlayerEffects(event.getPlayer());
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearPlayerEffects(player);
        }
    }
}