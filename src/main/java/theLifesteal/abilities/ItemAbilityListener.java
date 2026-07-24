package theLifesteal.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityResurrectEvent;
import theLifesteal.abilities.abilities.CriticalStrikeAbility;
import theLifesteal.abilities.abilities.PhoenixAbility;
import theLifesteal.customitem.AdvancedCustomItem;
import theLifesteal.customitem.AdvancedCustomItemManager;
import theLifesteal.customitem.CustomItemFlag;
import theLifesteal.util.FoliaScheduler;

import java.util.List;
import java.util.Map;

public class ItemAbilityListener implements Listener {

    private final JavaPlugin plugin;
    private final ItemAbilityManager abilityManager;
    private final AdvancedCustomItemManager customItemManager;

    public ItemAbilityListener(JavaPlugin plugin, ItemAbilityManager abilityManager, AdvancedCustomItemManager customItemManager) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.customItemManager = customItemManager;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        AdvancedCustomItem customItem = customItemManager.getItemByStack(item);
        if (customItem == null) return;

        // CANCEL IMMEDIATELY — prevent vanilla use (snowball throw, shield, etc.)
        event.setCancelled(true);

        Map<ItemAbilityType, List<ItemAbilityData>> abilities = customItem.getAbilities();
        boolean success;

        if (player.isSneaking()) {
            success = abilityManager.executeShiftRightClick(player, customItem.getId(), abilities);
        } else {
            success = abilityManager.executeRightClick(player, customItem.getId(), abilities);
        }

        if (success && customItem.hasFlag(CustomItemFlag.CONSUMABLE)) {
            consumeItem(player);
        }
    }
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        FoliaScheduler.runEntityLater(player, plugin, () -> {
            ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
            AdvancedCustomItem customItem = customItemManager.getItemByStack(newItem);
            if (customItem == null || !hasCriticalStrikeAbility(customItem)) {
                CriticalStrikeAbility critAbility = getCriticalStrikeAbility();
                if (critAbility != null) {
                    critAbility.removeBossBar(player);
                }
            }
        }, 2L);
    }

    private boolean hasCriticalStrikeAbility(AdvancedCustomItem item) {
        if (item == null) return false;
        List<ItemAbilityData> onHit = item.getAbilities().get(ItemAbilityType.ON_HIT);
        if (onHit == null) return false;
        for (ItemAbilityData data : onHit) {
            if (data.getAbilityId().equals("critical_strike")) return true;
        }
        return false;
    }

    private CriticalStrikeAbility getCriticalStrikeAbility() {
        ItemAbility ability = abilityManager.getAbility("critical_strike");
        if (ability instanceof CriticalStrikeAbility) {
            return (CriticalStrikeAbility) ability;
        }
        return null;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        CriticalStrikeAbility critAbility = getCriticalStrikeAbility();
        if (critAbility != null) {
            critAbility.removeBossBar(event.getEntity());
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        ItemStack item = attacker.getInventory().getItemInMainHand();
        AdvancedCustomItem customItem = customItemManager.getItemByStack(item);
        if (customItem == null) return;

        Map<ItemAbilityType, List<ItemAbilityData>> abilities = customItem.getAbilities();
        java.util.List<ItemAbilityData> attackAbilities = new java.util.ArrayList<>();
        if (abilities.get(ItemAbilityType.ON_HIT) != null) attackAbilities.addAll(abilities.get(ItemAbilityType.ON_HIT));
        if (abilities.get(ItemAbilityType.PASSIVE) != null) attackAbilities.addAll(abilities.get(ItemAbilityType.PASSIVE));
        if (attackAbilities.isEmpty()) return;

        boolean isFullAttack = attacker.getAttackCooldown() >= 0.99f;
        boolean isCritical = isFullAttack && attacker.getFallDistance() > 0.0f && !attacker.isOnGround();
        boolean isPlayer = victim instanceof Player;

        if (isPlayer && plugin instanceof theLifesteal.TheLifesteal lifesteal) {
            TotemProtectionManager totemMgr = lifesteal.getTotemProtectionManager();
            if (totemMgr != null) {
                Player victimPlayer = (Player) victim;
                if (totemMgr.isTotemProtected(victimPlayer)) {
                    return;
                }
                if (totemMgr.hasTotem(victimPlayer) && (victimPlayer.getHealth() - event.getFinalDamage() <= 0)) {
                    return;
                }
            }
        }

        boolean isPassive = victim instanceof Animals || victim instanceof WaterMob || victim instanceof Ambient;
        boolean isHostile = victim instanceof Monster;

        boolean anyTriggered = false;

        for (ItemAbilityData data : attackAbilities) {
            String triggerOn = data.getConfigString("trigger_on");
            if (triggerOn == null || triggerOn.isEmpty()) triggerOn = "ALL";

            boolean shouldTrigger = switch (triggerOn.toUpperCase()) {
                case "ALL" -> true;
                case "FULL_ATTACK" -> isFullAttack;
                case "CRITICAL" -> isCritical;
                case "FULL_OR_CRIT" -> isFullAttack || isCritical;
                default -> true;
            };

            if (!shouldTrigger) continue;

            if (isPlayer && !data.getConfigBoolean("affectPlayers")) continue;
            if (isPassive && !data.getConfigBoolean("affectPassive")) continue;
            if (isHostile && !data.getConfigBoolean("affectHostile")) continue;

            ItemAbility ability = abilityManager.getAbility(data.getAbilityId());
            if (ability != null) {
                boolean triggered = ability.onHitExecute(attacker, victim, data,
                        abilityManager.getCooldownManager(), customItem.getId(), event.getDamage(), event);
                if (triggered) {
                    anyTriggered = true;
                }
            }
        }

        if (anyTriggered && customItem.hasFlag(CustomItemFlag.CONSUMABLE)) {
            consumeItem(attacker);
        }
    }

    /**
     * Consume one item from the player's main hand.
     * Removes instance UUID from tracking, plays break sound, deletes item.
     */
    private void consumeItem(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null) return;

        String instanceUuid = customItemManager.getInstanceUuid(item);
        if (instanceUuid != null) {
            customItemManager.removeInstance(instanceUuid);
        }

        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f);

        item.setAmount(item.getAmount() - 1);

        if (item.getAmount() <= 0) {
            player.getInventory().setItemInMainHand(null);
        }

        player.sendMessage(ColorUtils.colorize("&7✦ Item consumed!"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack[] equippedItems = getEquippedItems(player);

        for (ItemStack item : equippedItems) {
            if (item == null || item.getType().isAir()) continue;
            AdvancedCustomItem customItem = customItemManager.getItemByStack(item);
            if (customItem == null) continue;

            Map<ItemAbilityType, List<ItemAbilityData>> abilities = customItem.getAbilities();
            List<ItemAbilityData> passiveAbilities = abilities.get(ItemAbilityType.PASSIVE);
            if (passiveAbilities == null) continue;

            for (ItemAbilityData data : passiveAbilities) {
                if (data.getAbilityId().equalsIgnoreCase("phoenix")) {
                    ItemAbility ability = abilityManager.getAbility("phoenix");
                    if (ability instanceof PhoenixAbility phoenix) {
                        boolean revived = phoenix.triggerPhoenix(player, item, data, abilityManager.getCooldownManager(), customItem.getId());
                        if (revived) {
                            event.setCancelled(false);
                        }
                        return;
                    }
                }
            }
        }
    }

    private ItemStack[] getEquippedItems(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        ItemStack[] items = new ItemStack[armor.length + 2];
        items[0] = mainHand;
        items[1] = offHand;
        System.arraycopy(armor, 0, items, 2, armor.length);
        return items;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        abilityManager.getCooldownManager().cleanupExpired(event.getPlayer().getUniqueId());
        CriticalStrikeAbility critAbility = getCriticalStrikeAbility();
        if (critAbility != null) {
            critAbility.removeBossBar(event.getPlayer());
        }
    }
}