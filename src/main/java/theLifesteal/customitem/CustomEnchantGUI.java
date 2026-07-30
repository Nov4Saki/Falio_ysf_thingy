package theLifesteal.customitem;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.ColorUtils;

import java.util.*;

public class CustomEnchantGUI {

    private final JavaPlugin plugin;
    private final Map<UUID, EnchantSession> sessions;
    private final Map<UUID, String> chatInput;
    private final Map<UUID, Runnable> returnAction;
    private final Map<UUID, Boolean> inTransition;
    private final Map<UUID, Integer> browserPage;
    private ItemDisplayPacketListener packetListener;

    private static final List<Enchantment> ALL_ENCHANTMENTS;

    static {
        ALL_ENCHANTMENTS = new ArrayList<>();
        for (Enchantment ench : Enchantment.values()) {
            if (ench != null) {
                ALL_ENCHANTMENTS.add(ench);
            }
        }
        ALL_ENCHANTMENTS.sort(Comparator.comparing(e -> e.getKey().getKey()));
    }

    public CustomEnchantGUI(JavaPlugin plugin) {
        this.plugin = plugin;
        this.sessions = new HashMap<>();
        this.chatInput = new HashMap<>();
        this.returnAction = new HashMap<>();
        this.inTransition = new HashMap<>();
        this.browserPage = new HashMap<>();
    }

    public void setPacketListener(ItemDisplayPacketListener packetListener) {
        this.packetListener = packetListener;
    }

    public boolean isEnchantGUI(String title) {
        return title.contains("Enchantments") || title.contains("Choose Enchantment") || title.contains("Enchant Level");
    }

    public boolean isAwaitingInput(UUID uuid) {
        return chatInput.containsKey(uuid);
    }

    public void openEnchantMenu(Player player, AdvancedCustomItem item, Runnable onBack) {
        sessions.put(player.getUniqueId(), new EnchantSession(item, onBack));
        player.openInventory(buildEnchantMenu(player));
    }

    public void handleClick(Player player, String title, int slot, ClickType click) {
        if (title.contains("Choose Enchantment")) {
            browserClick(player, slot, click);
        } else if (title.contains("Enchant Level")) {
            levelClick(player, slot, click);
        } else if (title.contains("Enchantments")) {
            mainClick(player, slot, click);
        }
    }

    public void handleChatInput(Player player, String message) {
        UUID uuid = player.getUniqueId();
        String key = chatInput.remove(uuid);
        Runnable back = returnAction.remove(uuid);

        if (key == null || message.equalsIgnoreCase("cancel")) {
            player.sendMessage(ColorUtils.colorize("&cCancelled."));
            if (back != null) back.run();
            else openFallback(player);
            return;
        }

        EnchantSession ses = sessions.get(uuid);
        if (ses == null || ses.selectedEnchant == null) { openFallback(player); return; }

        try {
            int level = Integer.parseInt(message);
            int maxLevel = ses.selectedEnchant.getMaxLevel();
            if (level < 1 || level > Math.max(maxLevel, 10)) {
                player.sendMessage(ColorUtils.colorize("&cLevel must be between 1 and " + Math.max(maxLevel, 10) + "!"));
                if (back != null) back.run();
                else openFallback(player);
                return;
            }
            ses.item.getEnchants().put(ses.selectedEnchant, level);
            ses.selectedEnchant = null;
            // Invalidate lore cache since enchants changed
            invalidateItemCache(ses);
            player.sendMessage(ColorUtils.colorize("&a✔ Enchantment added!"));
        } catch (NumberFormatException e) {
            player.sendMessage(ColorUtils.colorize("&cInvalid number!"));
        }

        if (back != null) back.run();
        else openFallback(player);
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

    private Inventory buildEnchantMenu(Player player) {
        EnchantSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return null;

        Inventory gui = Bukkit.createInventory(null, 54,
                net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eEnchantments &6✦")));

        for (int i : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,26,27,35,36,44,45,46,47,48,50,51,52,53})
            gui.setItem(i, glass(Material.PURPLE_STAINED_GLASS_PANE));

        Map<Enchantment, Integer> enchants = ses.item.getEnchants();
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

        int idx = 0;
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            if (idx >= slots.length) break;
            Enchantment ench = entry.getKey();
            int level = entry.getValue();

            ItemStack display = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName(ColorUtils.colorize("&7" + formatEnchantName(ench) + " &5" + toRoman(level)));
            meta.setLore(Arrays.asList(
                    ColorUtils.colorize("&7&m-------------------"),
                    ColorUtils.colorize("&eEnchant: &f" + formatEnchantName(ench)),
                    ColorUtils.colorize("&eLevel: &f" + level + " &7(" + toRoman(level) + ")"),
                    ColorUtils.colorize("&eMax: &f" + ench.getMaxLevel()),
                    "",
                    ColorUtils.colorize("&c▶ Right-click to remove"),
                    ColorUtils.colorize("&7&m-------------------")
            ));
            display.setItemMeta(meta);
            gui.setItem(slots[idx], display);
            idx++;
        }

        gui.setItem(49, item(Material.LIME_DYE, "&a➕ Add Enchantment", "&7Click to browse"));
        gui.setItem(53, item(Material.ARROW, "&a← Back to Editor"));
        filler(gui);
        return gui;
    }

    private Inventory buildBrowser(Player player, int page) {
        EnchantSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return null;

        Inventory gui = Bukkit.createInventory(null, 54,
                net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eChoose Enchantment &6✦")));

        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int start = page * slots.length;
        int end = Math.min(start + slots.length, ALL_ENCHANTMENTS.size());

        for (int i = start; i < end; i++) {
            Enchantment ench = ALL_ENCHANTMENTS.get(i);
            boolean alreadyHas = ses.item.getEnchants().containsKey(ench);

            Material icon = alreadyHas ? Material.GRAY_DYE : Material.ENCHANTED_BOOK;
            String suffix = alreadyHas ? " &7(already added)" : "";

            ItemStack display = new ItemStack(icon);
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName(ColorUtils.colorize("&7" + formatEnchantName(ench) + suffix));
            meta.setLore(Arrays.asList(
                    ColorUtils.colorize("&7&m-------------------"),
                    ColorUtils.colorize("&eKey: &f" + ench.getKey().getKey()),
                    ColorUtils.colorize("&eMax Level: &f" + ench.getMaxLevel()),
                    alreadyHas ? ColorUtils.colorize("&7Already on this item") : ColorUtils.colorize("&a▶ Click to add"),
                    ColorUtils.colorize("&7&m-------------------")
            ));
            display.setItemMeta(meta);
            gui.setItem(slots[i - start], display);
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) ALL_ENCHANTMENTS.size() / slots.length));
        if (page > 0) gui.setItem(45, item(Material.ARROW, "&a← Previous"));
        if (page < totalPages - 1) gui.setItem(53, item(Material.ARROW, "&aNext →"));
        gui.setItem(49, item(Material.BARRIER, "&c✖ Back"));

        filler(gui);
        return gui;
    }

    private Inventory buildLevelSelector(Player player, Enchantment ench) {
        EnchantSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return null;

        int maxLevel = Math.max(ench.getMaxLevel(), 10);

        Inventory gui = Bukkit.createInventory(null, 36,
                net.kyori.adventure.text.Component.text(ColorUtils.colorize("&6✦ &eEnchant Level &6✦")));

        int[] levelSlots = {10,11,12,13,14,15,16,19,20,21};
        for (int i = 0; i < Math.min(maxLevel, levelSlots.length); i++) {
            int level = i + 1;
            ItemStack display = new ItemStack(Material.EXPERIENCE_BOTTLE, Math.min(level, 64));
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName(ColorUtils.colorize("&5Level " + toRoman(level)));
            meta.setLore(Arrays.asList(
                    ColorUtils.colorize("&7Level: &f" + level),
                    ColorUtils.colorize("&a▶ Click to select")
            ));
            display.setItemMeta(meta);
            gui.setItem(levelSlots[i], display);
        }

        gui.setItem(25, item(Material.OAK_SIGN, "&e✏ Custom Level", "&7Type level in chat"));
        gui.setItem(31, item(Material.BARRIER, "&c✖ Back"));
        filler(gui);
        return gui;
    }

    // ==================== CLICK HANDLERS ====================

    private void mainClick(Player player, int slot, ClickType click) {
        EnchantSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return;

        if (slot == 49) {
            browserPage.put(player.getUniqueId(), 0);
            inTransition.put(player.getUniqueId(), true);
            player.openInventory(buildBrowser(player, 0));
            return;
        }
        if (slot == 53) {
            inTransition.put(player.getUniqueId(), true);
            player.closeInventory();
            ses.onBack.run();
            return;
        }

        Map<Enchantment, Integer> enchants = ses.item.getEnchants();
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int idx = 0;
        for (Enchantment ench : new ArrayList<>(enchants.keySet())) {
            if (idx >= slots.length) break;
            if (slot == slots[idx] && click.isRightClick()) {
                enchants.remove(ench);
                // Invalidate lore cache since enchantments changed
                invalidateItemCache(ses);
                inTransition.put(player.getUniqueId(), true);
                player.openInventory(buildEnchantMenu(player));
                return;
            }
            idx++;
        }
    }

    private void browserClick(Player player, int slot, ClickType click) {
        EnchantSession ses = sessions.get(player.getUniqueId());
        if (ses == null) return;

        int page = browserPage.getOrDefault(player.getUniqueId(), 0);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

        if (slot == 45 && page > 0) {
            browserPage.put(player.getUniqueId(), page - 1);
            inTransition.put(player.getUniqueId(), true);
            player.openInventory(buildBrowser(player, page - 1));
            return;
        }
        if (slot == 53) {
            int totalPages = Math.max(1, (int) Math.ceil((double) ALL_ENCHANTMENTS.size() / slots.length));
            if (page < totalPages - 1) {
                browserPage.put(player.getUniqueId(), page + 1);
                inTransition.put(player.getUniqueId(), true);
                player.openInventory(buildBrowser(player, page + 1));
            }
            return;
        }
        if (slot == 49) {
            inTransition.put(player.getUniqueId(), true);
            player.openInventory(buildEnchantMenu(player));
            return;
        }

        int startIdx = page * slots.length;
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i] && (startIdx + i) < ALL_ENCHANTMENTS.size()) {
                Enchantment ench = ALL_ENCHANTMENTS.get(startIdx + i);
                if (ses.item.getEnchants().containsKey(ench)) {
                    player.sendMessage(ColorUtils.colorize("&cThis enchantment is already on the item!"));
                    return;
                }
                ses.selectedEnchant = ench;
                inTransition.put(player.getUniqueId(), true);
                player.openInventory(buildLevelSelector(player, ench));
                return;
            }
        }
    }

    private void levelClick(Player player, int slot, ClickType click) {
        EnchantSession ses = sessions.get(player.getUniqueId());
        if (ses == null || ses.selectedEnchant == null) return;

        if (slot == 31) {
            ses.selectedEnchant = null;
            inTransition.put(player.getUniqueId(), true);
            player.openInventory(buildBrowser(player, browserPage.getOrDefault(player.getUniqueId(), 0)));
            return;
        }
        if (slot == 25) {
            inTransition.put(player.getUniqueId(), true);
            player.closeInventory();
            chatInput.put(player.getUniqueId(), "level");
            returnAction.put(player.getUniqueId(), () -> {
                player.openInventory(buildEnchantMenu(player));
            });
            player.sendMessage(ColorUtils.colorize("&eEnter level (1-" + Math.max(ses.selectedEnchant.getMaxLevel(), 10) + "). Type &ccancel &eto go back."));
            return;
        }

        int maxLevel = Math.max(ses.selectedEnchant.getMaxLevel(), 10);
        int[] levelSlots = {10,11,12,13,14,15,16,19,20,21};
        for (int i = 0; i < Math.min(maxLevel, levelSlots.length); i++) {
            if (slot == levelSlots[i]) {
                Map<Enchantment, Integer> enchants = ses.item.getEnchants();
                enchants.put(ses.selectedEnchant, i + 1);
                ses.item.setEnchants(enchants);
                ses.selectedEnchant = null;
                // Invalidate lore cache since enchantments changed
                invalidateItemCache(ses);
                player.sendMessage(ColorUtils.colorize("&a✔ Enchantment added!"));
                inTransition.put(player.getUniqueId(), true);
                player.openInventory(buildEnchantMenu(player));
                return;
            }
        }
    }

    private void openFallback(Player player) {
        EnchantSession ses = sessions.get(player.getUniqueId());
        if (ses != null) {
            player.openInventory(buildEnchantMenu(player));
        }
    }

    /**
     * Invalidates the lore cache for the item being edited so the packet
     * interceptor picks up the changes immediately.
     */
    private void invalidateItemCache(EnchantSession ses) {
        if (packetListener != null && ses != null && ses.item != null) {
            packetListener.invalidateCache(ses.item.getId());
        }
    }

    // ==================== UTILS ====================

    private String formatEnchantName(Enchantment ench) {
        String key = ench.getKey().getKey();
        String[] words = key.replace("_", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1));
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }

    private String toRoman(int num) {
        String[] romans = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
                "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"};
        if (num > 0 && num < romans.length) return romans[num];
        return String.valueOf(num);
    }

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

    private static class EnchantSession {
        final AdvancedCustomItem item;
        final Runnable onBack;
        Enchantment selectedEnchant;

        EnchantSession(AdvancedCustomItem item, Runnable onBack) {
            this.item = item;
            this.onBack = onBack;
        }
    }
}