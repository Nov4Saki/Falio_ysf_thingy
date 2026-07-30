package theLifesteal.abilities;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;
import theLifesteal.customitem.AdvancedCustomItem;
import theLifesteal.customitem.ItemDisplayPacketListener;

import java.util.*;

public class ItemAbilityGUI {

    private final JavaPlugin plugin;
    private final ItemAbilityManager abilityManager;
    private final Map<UUID, AbilityEditSession> sessions;
    private final Map<UUID, String> chatInput;
    private final Map<UUID, Runnable> returnAction;
    private final Map<UUID, Boolean> inTransition;
    private final Map<UUID, Integer> browserPage;
    private ItemDisplayPacketListener packetListener;

    public ItemAbilityGUI(JavaPlugin plugin, ItemAbilityManager abilityManager) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.sessions = new HashMap<>();
        this.chatInput = new HashMap<>();
        this.returnAction = new HashMap<>();
        this.inTransition = new HashMap<>();
        this.browserPage = new HashMap<>();
    }

    public void setPacketListener(ItemDisplayPacketListener packetListener) {
        this.packetListener = packetListener;
    }

    public boolean isAbilityGUI(String title) {
        return title.contains("Abilities") || title.contains("Choose Ability") ||
                title.contains("Config:") || title.contains("Ability Slots");
    }

    public boolean isAwaitingInput(UUID uuid) {
        return chatInput.containsKey(uuid);
    }

    public AbilityEditSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public void openAbilitiesMenu(Player player, AdvancedCustomItem item, Runnable onBack) {
        sessions.put(player.getUniqueId(), new AbilityEditSession(item, onBack));
        player.openInventory(buildAbilitiesMenu(player));
    }

    public void handleClick(Player player, String title, int slot, ClickType click) {
        if (title.contains("Choose Ability")) {
            browserClick(player, slot, click);
        } else if (title.contains("Config:")) {
            configClick(player, slot, click, title);
        } else if (title.contains("Ability Slots")) {
            slotsClick(player, slot, click);
        }
    }

    public void handleChatInput(Player player, String message) {
        UUID uuid = player.getUniqueId();
        String key = chatInput.remove(uuid);
        Runnable back = returnAction.remove(uuid);

        if (key == null || message.equalsIgnoreCase("cancel")) {
            player.sendMessage(ColorUtils.colorize("&cCancelled."));
            if (back != null) back.run();
            else openAbilitiesMenuFallback(player);
            return;
        }

        AbilityEditSession ses = sessions.get(uuid);
        if (ses == null) { openAbilitiesMenuFallback(player); return; }

        ItemAbilityData data = ses.editingData;
        if (data == null) return;

        ItemAbility ability = abilityManager.getAbility(data.getAbilityId());
        if (ability == null) return;

        ItemAbility.ConfigField field = ability.getConfigFields().get(key);
        if (field == null) return;

        try {
            switch (field.getType()) {
                case "int":
                    int intVal = Integer.parseInt(message);
                    if (intVal < field.getMin() || intVal > field.getMax()) {
                        player.sendMessage(ColorUtils.colorize("&cValue must be between " + (int) field.getMin() + " and " + (int) field.getMax() + "!"));
                        if (back != null) back.run();
                        else openAbilitiesMenuFallback(player);
                        return;
                    }
                    data.setConfigValue(key, intVal);
                    break;
                case "double":
                    double doubleVal = Double.parseDouble(message);
                    if (doubleVal < field.getMin() || doubleVal > field.getMax()) {
                        player.sendMessage(ColorUtils.colorize("&cValue must be between " + field.getMin() + " and " + field.getMax() + "!"));
                        if (back != null) back.run();
                        else openAbilitiesMenuFallback(player);
                        return;
                    }
                    data.setConfigValue(key, doubleVal);
                    break;
                case "boolean":
                    boolean boolVal = message.equalsIgnoreCase("true") || message.equalsIgnoreCase("yes") || message.equalsIgnoreCase("1");
                    data.setConfigValue(key, boolVal);
                    break;
                case "string":
                    data.setConfigValue(key, message);
                    break;
            }
            player.sendMessage(ColorUtils.colorize("&aValue updated!"));
            // Invalidate lore cache since ability config changed
            invalidateItemCache(ses);
        } catch (NumberFormatException e) {
            player.sendMessage(ColorUtils.colorize("&cInvalid number!"));
        }

        if (back != null) back.run();
        else openAbilitiesMenuFallback(player);
    }

    public void saveOnClose(Player player) {
        UUID uuid = player.getUniqueId();
        if (inTransition.remove(uuid) != null) return;
        sessions.remove(uuid);
        chatInput.remove(uuid);
        returnAction.remove(uuid);
        inTransition.remove(uuid);
        browserPage.remove(uuid);
    }

    public void cleanupPlayer(UUID uuid) {
        sessions.remove(uuid);
        chatInput.remove(uuid);
        returnAction.remove(uuid);
        inTransition.remove(uuid);
        browserPage.remove(uuid);
    }

    // ==================== GUI BUILDERS ====================

    private Inventory buildAbilitiesMenu(Player player) {
        AbilityEditSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return null;

        Inventory gui = Bukkit.createInventory(null, 54,
                net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eAbility Slots &6✦")));

        for (int i : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,26,27,35,36,44,45,46,47,48,49,50,51,52,53})
            gui.setItem(i, glass(Material.BLUE_STAINED_GLASS_PANE));

        // Right-Click slot (slot 20)
        ItemStack rcItem = buildSlotItem(player, ItemAbilityType.RIGHT_CLICK, ses.item);
        gui.setItem(20, rcItem);

        // Shift Right-Click slot (slot 22)
        ItemStack srcItem = buildSlotItem(player, ItemAbilityType.SHIFT_RIGHT_CLICK, ses.item);
        gui.setItem(22, srcItem);

        // On-Hit slots (slots 29-33)
        List<ItemAbilityData> onHitAbilities = ses.item.getAbilities().get(ItemAbilityType.ON_HIT);
        int[] onHitSlots = {29, 30, 31, 32, 33};
        for (int i = 0; i < onHitSlots.length; i++) {
            if (i < onHitAbilities.size()) {
                gui.setItem(onHitSlots[i], buildExistingAbilityItem(onHitAbilities.get(i)));
            } else if (i == onHitAbilities.size()) {
                gui.setItem(onHitSlots[i], item(Material.LIME_DYE, "&a➕ Add On-Hit Ability", "&7Click to add"));
            } else {
                gui.setItem(onHitSlots[i], glass(Material.GRAY_STAINED_GLASS_PANE));
            }
        }

        // Passive slots (slots 38-42)
        List<ItemAbilityData> passiveAbilities = ses.item.getAbilities().get(ItemAbilityType.PASSIVE);
        int[] passiveSlots = {38, 39, 40, 41, 42};
        for (int i = 0; i < passiveSlots.length; i++) {
            if (i < passiveAbilities.size()) {
                gui.setItem(passiveSlots[i], buildExistingAbilityItem(passiveAbilities.get(i)));
            } else if (i == passiveAbilities.size()) {
                gui.setItem(passiveSlots[i], item(Material.LIME_DYE, "&a➕ Add Passive Ability", "&7Click to add"));
            } else {
                gui.setItem(passiveSlots[i], glass(Material.GRAY_STAINED_GLASS_PANE));
            }
        }

        gui.setItem(49, item(Material.ARROW, "&a← Back"));
        filler(gui);
        return gui;
    }

    private ItemStack buildSlotItem(Player player, ItemAbilityType type, AdvancedCustomItem item) {
        List<ItemAbilityData> abilities = item.getAbilities().get(type);
        if (abilities == null || abilities.isEmpty()) {
            Material icon = type == ItemAbilityType.RIGHT_CLICK ? Material.IRON_SWORD : Material.IRON_HOE;
            String action = type == ItemAbilityType.RIGHT_CLICK ? "Right-Click" : "Shift Right-Click";
            return item(icon, "&e" + action + " Ability", "&7No ability set", "&aClick to add one");
        } else {
            ItemAbilityData data = abilities.get(0);
            ItemAbility ability = abilityManager.getAbility(data.getAbilityId());
            if (ability == null) {
                return item(Material.BARRIER, "&cUnknown Ability");
            }
            Material icon = type == ItemAbilityType.RIGHT_CLICK ? Material.IRON_SWORD : Material.IRON_HOE;
            ItemStack display = new ItemStack(icon);
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName(ColorUtils.colorize("&e" + type.getDisplayName() + " Ability"));
            List<String> lore = new ArrayList<>();
            lore.add(ColorUtils.colorize("&7&m-------------------"));
            lore.add(ColorUtils.colorize("&f" + ability.getDisplayName()));
            lore.add(ColorUtils.colorize("&7" + ability.buildLore(data)));
            lore.add("");
            lore.add(ColorUtils.colorize("&a▶ Left → configure"));
            lore.add(ColorUtils.colorize("&c▶ Right → remove"));
            lore.add(ColorUtils.colorize("&7&m-------------------"));
            meta.setLore(lore);
            display.setItemMeta(meta);
            return display;
        }
    }

    private ItemStack buildExistingAbilityItem(ItemAbilityData data) {
        ItemAbility ability = abilityManager.getAbility(data.getAbilityId());
        if (ability == null) return item(Material.BARRIER, "&cUnknown Ability");

        ItemStack display = new ItemStack(Material.BOOK);
        ItemMeta meta = display.getItemMeta();
        meta.setDisplayName(ColorUtils.colorize("&f" + ability.getDisplayName()));
        List<String> lore = new ArrayList<>();
        lore.add(ColorUtils.colorize("&7&m-------------------"));
        lore.add(ColorUtils.colorize("&7" + ability.buildLore(data)));
        lore.add("");
        lore.add(ColorUtils.colorize("&a▶ Left → configure"));
        lore.add(ColorUtils.colorize("&c▶ Right → remove"));
        lore.add(ColorUtils.colorize("&7&m-------------------"));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private Inventory buildBrowser(Player player, ItemAbilityType type, int page) {
        AbilityEditSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return null;

        List<ItemAbility> available = abilityManager.getAbilitiesByType(type);
        Inventory gui = Bukkit.createInventory(null, 54,
                net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eChoose Ability &6✦")));

        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int start = page * slots.length;
        int end = Math.min(start + slots.length, available.size());

        for (int i = start; i < end; i++) {
            ItemAbility ability = available.get(i);
            boolean alreadyUsed = false;
            for (List<ItemAbilityData> list : ses.item.getAbilities().values()) {
                for (ItemAbilityData d : list) {
                    if (d.getAbilityId().equals(ability.getId())) {
                        alreadyUsed = true;
                        break;
                    }
                }
                if (alreadyUsed) break;
            }

            Material icon = alreadyUsed ? Material.GRAY_DYE : Material.BOOK;
            String suffix = alreadyUsed ? " &7(already added)" : "";

            ItemStack display = new ItemStack(icon);
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName(ColorUtils.colorize("&f" + ability.getDisplayName() + suffix));
            meta.setLore(Arrays.asList(
                    ColorUtils.colorize("&7&m-------------------"),
                    ColorUtils.colorize("&7Type: &e" + ability.getType().getDisplayName()),
                    alreadyUsed ? ColorUtils.colorize("&7Already on this item") : ColorUtils.colorize("&a▶ Click to add"),
                    ColorUtils.colorize("&7&m-------------------")
            ));
            display.setItemMeta(meta);
            gui.setItem(slots[i - start], display);
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) available.size() / slots.length));
        if (page > 0) gui.setItem(45, item(Material.ARROW, "&a← Previous"));
        if (page < totalPages - 1) gui.setItem(53, item(Material.ARROW, "&aNext →"));
        gui.setItem(49, item(Material.BARRIER, "&c✖ Back"));

        filler(gui);
        return gui;
    }

    private Inventory buildConfigEditor(Player player, ItemAbilityData data) {
        AbilityEditSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return null;

        ItemAbility ability = abilityManager.getAbility(data.getAbilityId());
        if (ability == null) return null;

        String title = ColorUtils.colorize("&6✦ &eConfig: " + ability.getDisplayName() + " &6✦");
        Inventory gui = Bukkit.createInventory(null, 36,
                net.kyori.adventure.text.Component.text(title));

        Map<String, ItemAbility.ConfigField> fields = ability.getConfigFields();
        int[] fieldSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int idx = 0;

        for (Map.Entry<String, ItemAbility.ConfigField> entry : fields.entrySet()) {
            if (idx >= fieldSlots.length) break;
            String key = entry.getKey();
            ItemAbility.ConfigField field = entry.getValue();

            Object value = data.getConfigValue(key);
            Material icon = Material.PAPER;
            if (field.getType().equals("boolean")) {
                boolean b = (boolean) value;
                icon = b ? Material.LIME_DYE : Material.GRAY_DYE;
            }

            ItemStack display = new ItemStack(icon);
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName(ColorUtils.colorize("&e" + field.getDisplayName()));
            meta.setLore(Arrays.asList(
                    ColorUtils.colorize("&7Current: &f" + value),
                    ColorUtils.colorize("&7Type: &f" + field.getType()),
                    ColorUtils.colorize("&a▶ Click to change")
            ));
            display.setItemMeta(meta);
            gui.setItem(fieldSlots[idx], display);
            idx++;
        }

        gui.setItem(31, item(Material.ARROW, "&a← Back to Slots"));
        filler(gui);
        return gui;
    }

    // ==================== CLICK HANDLERS ====================

    private void slotsClick(Player player, int slot, ClickType click) {
        AbilityEditSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return;

        if (slot == 49) {
            inTransition.put(player.getUniqueId(), true);
            player.closeInventory();
            ses.onBack.run();
            return;
        }

        if (slot == 20) {
            handleSlotClick(player, ItemAbilityType.RIGHT_CLICK, click);
        } else if (slot == 22) {
            handleSlotClick(player, ItemAbilityType.SHIFT_RIGHT_CLICK, click);
        } else {
            int[] onHitSlots = {29, 30, 31, 32, 33};
            List<ItemAbilityData> onHitList = ses.item.getAbilities().get(ItemAbilityType.ON_HIT);
            for (int i = 0; i < onHitSlots.length; i++) {
                if (slot == onHitSlots[i]) {
                    if (i < onHitList.size()) {
                        if (click.isRightClick()) {
                            onHitList.remove(i);
                            invalidateItemCache(ses);
                            inTransition.put(player.getUniqueId(), true);
                            player.openInventory(buildAbilitiesMenu(player));
                        } else {
                            ses.editingData = onHitList.get(i);
                            inTransition.put(player.getUniqueId(), true);
                            player.openInventory(buildConfigEditor(player, onHitList.get(i)));
                        }
                    } else if (i == onHitList.size()) {
                        ses.browsingType = ItemAbilityType.ON_HIT;
                        browserPage.put(player.getUniqueId(), 0);
                        inTransition.put(player.getUniqueId(), true);
                        player.openInventory(buildBrowser(player, ItemAbilityType.ON_HIT, 0));
                    }
                    return;
                }
            }

            int[] passiveSlots = {38, 39, 40, 41, 42};
            List<ItemAbilityData> passiveList = ses.item.getAbilities().get(ItemAbilityType.PASSIVE);
            for (int i = 0; i < passiveSlots.length; i++) {
                if (slot == passiveSlots[i]) {
                    if (i < passiveList.size()) {
                        if (click.isRightClick()) {
                            passiveList.remove(i);
                            invalidateItemCache(ses);
                            inTransition.put(player.getUniqueId(), true);
                            player.openInventory(buildAbilitiesMenu(player));
                        } else {
                            ses.editingData = passiveList.get(i);
                            inTransition.put(player.getUniqueId(), true);
                            player.openInventory(buildConfigEditor(player, passiveList.get(i)));
                        }
                    } else if (i == passiveList.size()) {
                        ses.browsingType = ItemAbilityType.PASSIVE;
                        browserPage.put(player.getUniqueId(), 0);
                        inTransition.put(player.getUniqueId(), true);
                        player.openInventory(buildBrowser(player, ItemAbilityType.PASSIVE, 0));
                    }
                    return;
                }
            }
        }
    }

    private void handleSlotClick(Player player, ItemAbilityType type, ClickType click) {
        AbilityEditSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return;

        List<ItemAbilityData> list = ses.item.getAbilities().get(type);
        if (list == null || list.isEmpty()) {
            ses.browsingType = type;
            browserPage.put(player.getUniqueId(), 0);
            inTransition.put(player.getUniqueId(), true);
            player.openInventory(buildBrowser(player, type, 0));
        } else {
            if (click.isRightClick()) {
                list.clear();
                invalidateItemCache(ses);
                inTransition.put(player.getUniqueId(), true);
                player.openInventory(buildAbilitiesMenu(player));
            } else {
                ses.editingData = list.get(0);
                inTransition.put(player.getUniqueId(), true);
                player.openInventory(buildConfigEditor(player, list.get(0)));
            }
        }
    }

    private void browserClick(Player player, int slot, ClickType click) {
        AbilityEditSession ses = sessions.get(player.getUniqueId());
        if (ses == null || ses.browsingType == null) return;

        int page = browserPage.getOrDefault(player.getUniqueId(), 0);
        List<ItemAbility> available = abilityManager.getAbilitiesByType(ses.browsingType);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

        if (slot == 45 && page > 0) {
            browserPage.put(player.getUniqueId(), page - 1);
            inTransition.put(player.getUniqueId(), true);
            player.openInventory(buildBrowser(player, ses.browsingType, page - 1));
            return;
        }
        if (slot == 53) {
            int totalPages = Math.max(1, (int) Math.ceil((double) available.size() / slots.length));
            if (page < totalPages - 1) {
                browserPage.put(player.getUniqueId(), page + 1);
                inTransition.put(player.getUniqueId(), true);
                player.openInventory(buildBrowser(player, ses.browsingType, page + 1));
            }
            return;
        }
        if (slot == 49) {
            inTransition.put(player.getUniqueId(), true);
            player.openInventory(buildAbilitiesMenu(player));
            return;
        }

        int startIdx = page * slots.length;
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i] && (startIdx + i) < available.size()) {
                ItemAbility ability = available.get(startIdx + i);

                boolean alreadyUsed = false;
                for (List<ItemAbilityData> list : ses.item.getAbilities().values()) {
                    for (ItemAbilityData d : list) {
                        if (d.getAbilityId().equals(ability.getId())) {
                            alreadyUsed = true;
                            break;
                        }
                    }
                    if (alreadyUsed) break;
                }

                // Only block if it's already in the SAME type slot
                List<ItemAbilityData> existingInType = ses.item.getAbilities().get(ses.browsingType);
                boolean alreadyInThisSlot = false;
                for (ItemAbilityData d : existingInType) {
                    if (d.getAbilityId().equals(ability.getId())) {
                        alreadyInThisSlot = true;
                        break;
                    }
                }
                if (alreadyInThisSlot) {
                    player.sendMessage(ColorUtils.colorize("&cThis ability is already in this slot!"));
                    return;
                }

                ItemAbilityData data = new ItemAbilityData(ability.getId(), ses.browsingType);
                data.setConfig(new LinkedHashMap<>(ability.getDefaultConfig()));
                ses.item.getAbilities().get(ses.browsingType).add(data);

                // Invalidate lore cache since abilities changed
                invalidateItemCache(ses);

                player.sendMessage(ColorUtils.colorize("&a✔ Ability added: " + ability.getDisplayName()));
                inTransition.put(player.getUniqueId(), true);
                player.openInventory(buildAbilitiesMenu(player));
                return;
            }
        }
    }

    private void configClick(Player player, int slot, ClickType click, String title) {
        AbilityEditSession ses = sessions.get(player.getUniqueId());
        if (ses == null || ses.editingData == null) return;

        if (slot == 31) {
            ses.editingData = null;
            inTransition.put(player.getUniqueId(), true);
            player.openInventory(buildAbilitiesMenu(player));
            return;
        }

        ItemAbility ability = abilityManager.getAbility(ses.editingData.getAbilityId());
        if (ability == null) return;

        Map<String, ItemAbility.ConfigField> fields = ability.getConfigFields();
        int[] fieldSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int idx = 0;

        for (Map.Entry<String, ItemAbility.ConfigField> entry : fields.entrySet()) {
            if (idx >= fieldSlots.length) break;
            if (slot == fieldSlots[idx]) {
                String key = entry.getKey();
                ItemAbility.ConfigField field = entry.getValue();

                if (field.getType().equals("boolean")) {
                    boolean current = ses.editingData.getConfigBoolean(key);
                    ses.editingData.setConfigValue(key, !current);
                    invalidateItemCache(ses);
                    inTransition.put(player.getUniqueId(), true);
                    player.openInventory(buildConfigEditor(player, ses.editingData));
                } else {
                    inTransition.put(player.getUniqueId(), true);
                    player.closeInventory();
                    chatInput.put(player.getUniqueId(), key);
                    returnAction.put(player.getUniqueId(), () -> {
                        player.openInventory(buildConfigEditor(player, ses.editingData));
                    });
                    player.sendMessage(ColorUtils.colorize("&eEnter value for &f" + field.getDisplayName() + "&e. Type &ccancel &eto go back."));
                }
                return;
            }
            idx++;
        }
    }

    private void openAbilitiesMenuFallback(Player player) {
        AbilityEditSession ses = sessions.get(player.getUniqueId());
        if (ses != null) {
            player.openInventory(buildAbilitiesMenu(player));
        }
    }

    /**
     * Invalidates the lore cache for the item being edited so the packet
     * interceptor picks up the changes immediately.
     */
    private void invalidateItemCache(AbilityEditSession ses) {
        if (packetListener != null && ses != null && ses.item != null) {
            packetListener.invalidateCache(ses.item.getId());
        }
    }

    // ==================== ITEMS ====================

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName(ColorUtils.colorize(name));
        if (lore.length > 0) {
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(ColorUtils.colorize(s));
            m.setLore(l);
        }
        i.setItemMeta(m);
        return i;
    }

    private ItemStack glass(Material mat) {
        ItemStack g = new ItemStack(mat);
        ItemMeta m = g.getItemMeta();
        m.setDisplayName(" ");
        g.setItemMeta(m);
        return g;
    }

    private void filler(Inventory inv) {
        ItemStack f = glass(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, f);
    }

    // ==================== INNER CLASS ====================

    public static class AbilityEditSession {
        final AdvancedCustomItem item;
        final Runnable onBack;
        ItemAbilityData editingData;
        ItemAbilityType browsingType;

        AbilityEditSession(AdvancedCustomItem item, Runnable onBack) {
            this.item = item;
            this.onBack = onBack;
        }
    }
}