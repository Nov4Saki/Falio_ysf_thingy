package theLifesteal.abilities.abilities;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import theLifesteal.TheLifesteal;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.AbilityKillTracker;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.customitem.AdvancedCustomItem;
import theLifesteal.customitem.AdvancedCustomItemManager;

import java.util.*;

public class ReaperAbility extends ItemAbility implements Listener {

    private final NamespacedKey reaperBonusKey;

    public ReaperAbility(JavaPlugin plugin) {
        super(plugin, "reaper", "Reaper", ItemAbilityType.PASSIVE);
        this.reaperBonusKey = new NamespacedKey(plugin, "reaper_bonus_damage");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("damagePerKill", 1.0);
        config.put("maxBonusDamage", 10.0);
        config.put("cooldown", 0);
        config.put("cooldownScope", "ABILITY");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("damagePerKill", new ConfigField("Damage Per Kill", "double", 0.1, 50.0));
        fields.put("maxBonusDamage", new ConfigField("Max Bonus Damage Cap", "double", 0.0, 1000.0));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        double perKill = data.getConfigDouble("damagePerKill");
        if (perKill <= 0) perKill = 1.0;
        double maxCap = data.getConfigDouble("maxBonusDamage");
        if (maxCap <= 0) maxCap = 10.0;

        return "&7On player kill: gain &c+" + formatDamage(perKill) + " Attack Damage\n"
                + "&7Bonus Cap: &c+" + formatDamage(maxCap) + " Attack Damage\n"
                + "&8(No Cooldown)";
    }

    @Override
    public boolean execute(Player player, ItemAbilityData data, AbilityCooldownManager cooldownManager, String itemId) {
        // Passive ability — does not trigger manually on click
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null) {
            AbilityKillTracker tracker = getKillTracker();
            if (tracker != null) {
                killer = tracker.getCasterForVictim(victim.getUniqueId());
            }
        }

        if (killer == null || killer.equals(victim)) return;

        ItemStack item = killer.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return;

        if (!(getPlugin() instanceof TheLifesteal lifesteal)) return;

        AdvancedCustomItemManager manager = lifesteal.getAdvancedItemManager();
        if (manager == null) return;

        AdvancedCustomItem customItem = manager.getItemByStack(item);
        if (customItem == null) return;

        ItemAbilityData reaperData = getReaperAbilityData(customItem);
        if (reaperData == null) return;

        double damagePerKill = reaperData.getConfigDouble("damagePerKill");
        if (damagePerKill <= 0) damagePerKill = 1.0;
        double maxBonusDamage = reaperData.getConfigDouble("maxBonusDamage");
        if (maxBonusDamage <= 0) maxBonusDamage = 10.0;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        double currentBonus = 0.0;
        Double stored = meta.getPersistentDataContainer().get(reaperBonusKey, PersistentDataType.DOUBLE);
        if (stored != null) {
            currentBonus = stored;
        }

        if (currentBonus >= maxBonusDamage) return; // Cap reached

        double newBonus = Math.min(currentBonus + damagePerKill, maxBonusDamage);

        // Store the bonus in PDC (for potential future dynamic lore display)
        meta.getPersistentDataContainer().set(reaperBonusKey, PersistentDataType.DOUBLE, newBonus);

        // Calculate and apply ATTACK_DAMAGE attribute modifier
        double baseDamage = customItem.getAttributes().getOrDefault(Attribute.ATTACK_DAMAGE, 0.0);
        double totalDamage = baseDamage + newBonus;

        UUID attrUuid = UUID.nameUUIDFromBytes(("thelifesteal_attr_" + Attribute.ATTACK_DAMAGE.name().toLowerCase()).getBytes());
        meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
        AttributeModifier modifier = new AttributeModifier(
                attrUuid,
                "custom_attr_" + Attribute.ATTACK_DAMAGE.name().toLowerCase(),
                totalDamage,
                AttributeModifier.Operation.ADD_NUMBER
        );
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, modifier);

        // FIXED: Do NOT bake lore onto the item!
        // Lore is injected at packet-send time by ItemDisplayPacketListener.
        // The bonus damage is stored in PDC (reaper_bonus_damage) for future per-instance lore display.
        item.setItemMeta(meta);

        killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        killer.sendMessage(ColorUtils.colorize("&c&l✦ REAPER! &7Your weapon gained &c+" + formatDamage(damagePerKill) + " &7extra damage! &8(Current: +" + formatDamage(newBonus) + "/" + formatDamage(maxBonusDamage) + ")"));
    }

    private ItemAbilityData getReaperAbilityData(AdvancedCustomItem customItem) {
        if (customItem == null || customItem.getAbilities() == null) return null;
        for (List<ItemAbilityData> dataList : customItem.getAbilities().values()) {
            for (ItemAbilityData data : dataList) {
                if (getId().equalsIgnoreCase(data.getAbilityId())) {
                    return data;
                }
            }
        }
        return null;
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) {
            return String.valueOf((int) damage);
        }
        return String.format("%.1f", damage);
    }
}