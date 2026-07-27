package theLifesteal;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import theLifesteal.crafting.CraftingGUI;
import theLifesteal.crafting.RecipeBookItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private final TheLifesteal plugin;
    private final ConfigManager configManager;
    private final HeartManager heartManager;
    private final RecipeBookItem recipeBookItem;
    private final CraftingGUI craftingGUI;

    public CommandHandler(TheLifesteal plugin, RecipeBookItem recipeBookItem, CraftingGUI craftingGUI) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.heartManager = plugin.getHeartManager();
        this.recipeBookItem = recipeBookItem;
        this.craftingGUI = craftingGUI;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase()) {
            case "withdrawhearts": return handleWithdrawCommand(sender, args);
            case "setmaxhp": return handleSetMaxHPCommand(sender, args);
            case "craft": return handleCraftCommand(sender, args);
            case "recipebook": return handleRecipeBookCommand(sender, args);
            case "customitem": return handleCustomItemCommand(sender, args);
            case "ci": return handleCustomItemCommand(sender, args);
            case "reloaditems": return handleReloadItemsCommand(sender, args);
        }
        return false;
    }

    private boolean handleCustomItemCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("player-only"))); return true; }
        if (!player.hasPermission("thelifesteal.admin")) { player.sendMessage(ColorUtils.colorize(configManager.getMessage("no-permission"))); return true; }
        plugin.getCustomItemGUI().openMainMenu(player);
        return true;
    }

    private boolean handleReloadItemsCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("thelifesteal.admin")) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("no-permission"))); return true; }

        var updateManager = plugin.getItemUpdateManager();
        if (updateManager == null) { sender.sendMessage(ColorUtils.colorize("&cItem Update Manager not available.")); return true; }

        if (args.length == 0) {
            int updated = updateManager.refreshAllPlayers();
            sender.sendMessage(ColorUtils.colorize("&a⟳ Refreshed items for all online players. &7(" + updated + " items updated)"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "enable" -> { updateManager.setEnabled(true); sender.sendMessage(ColorUtils.colorize("&a✔ Item updates enabled.")); }
            case "disable" -> { updateManager.setEnabled(false); sender.sendMessage(ColorUtils.colorize("&c✖ Item updates disabled.")); }
            case "onjoin" -> {
                boolean current = updateManager.isOnJoin();
                updateManager.setOnJoin(!current);
                sender.sendMessage(ColorUtils.colorize("&eOn-join updates: " + (!current ? "&aENABLED" : "&cDISABLED")));
            }
            case "onworldchange" -> {
                boolean current = updateManager.isOnWorldChange();
                updateManager.setOnWorldChange(!current);
                sender.sendMessage(ColorUtils.colorize("&eOn-world-change updates: " + (!current ? "&aENABLED" : "&cDISABLED")));
            }
            case "status" -> {
                sender.sendMessage(ColorUtils.colorize("&6&lItem Update Status:"));
                sender.sendMessage(ColorUtils.colorize("&e  Enabled: " + (updateManager.isEnabled() ? "&aYes" : "&cNo")));
                sender.sendMessage(ColorUtils.colorize("&e  On Join: " + (updateManager.isOnJoin() ? "&aYes" : "&cNo")));
                sender.sendMessage(ColorUtils.colorize("&e  On World Change: " + (updateManager.isOnWorldChange() ? "&aYes" : "&cNo")));
            }
            case "purge" -> {
                if (args.length < 2) { sender.sendMessage(ColorUtils.colorize("&cUsage: /reloaditems purge <itemId>")); return true; }
                String itemId = args[1];
                int purged = updateManager.purgeItem(itemId);
                sender.sendMessage(ColorUtils.colorize("&c🗑 Purged &4" + purged + " &cinstances of &f" + itemId + "&c."));
            }
            default -> {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { sender.sendMessage(ColorUtils.colorize("&cPlayer not found: " + args[0])); return true; }
                int updated = updateManager.refreshSinglePlayer(target);
                sender.sendMessage(ColorUtils.colorize("&a⟳ Refreshed items for &e" + target.getName() + "&a. &7(" + updated + " items updated)"));
            }
        }
        return true;
    }

    private boolean handleWithdrawCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("player-only"))); return true; }
        if (!player.hasPermission("thelifesteal.withdraw")) { player.sendMessage(ColorUtils.colorize(configManager.getMessage("no-permission"))); return true; }
        if (args.length != 1) { player.sendMessage(ColorUtils.colorize("&cUsage: /withdrawhearts <amount>")); return true; }

        int amount;
        try { amount = Integer.parseInt(args[0]); } catch (NumberFormatException e) { player.sendMessage(ColorUtils.colorize(configManager.getMessage("invalid-amount"))); return true; }
        if (amount <= 0) { player.sendMessage(ColorUtils.colorize(configManager.getMessage("invalid-amount"))); return true; }
        if (!heartManager.canWithdrawHearts(player, amount)) { player.sendMessage(ColorUtils.colorize(configManager.getMessage("min-health-reached"))); return true; }

        ItemStack heartItem = heartManager.createHeartItem(amount);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(heartItem);
        int given = amount;
        if (!leftovers.isEmpty()) {
            int leftoverAmount = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            given = amount - leftoverAmount;
            if (given <= 0) { player.sendMessage(ColorUtils.colorize(configManager.getMessage("inventory-full"))); return true; }
            player.sendMessage(ColorUtils.colorize(configManager.getMessage("heart-withdrawn").replace("%amount%", String.valueOf(given))));
            player.sendMessage(ColorUtils.colorize("&e" + leftoverAmount + " items didn't fit in your inventory!"));
        } else {
            player.sendMessage(ColorUtils.colorize(configManager.getMessage("heart-withdrawn").replace("%amount%", String.valueOf(amount))));
        }
        heartManager.withdrawHearts(player, given);
        return true;
    }

    private boolean handleSetMaxHPCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("thelifesteal.admin")) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("no-permission"))); return true; }

        Player target; double amount;
        if (args.length == 1) {
            if (!(sender instanceof Player player)) { sender.sendMessage(ColorUtils.colorize("&cUsage: /setmaxhp <player> <amount>")); return true; }
            target = player;
            try { amount = Double.parseDouble(args[0]); } catch (NumberFormatException e) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("invalid-amount"))); return true; }
        } else if (args.length == 2) {
            target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("player-not-found"))); return true; }
            try { amount = Double.parseDouble(args[1]); } catch (NumberFormatException e) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("invalid-amount"))); return true; }
        } else { sender.sendMessage(ColorUtils.colorize("&cUsage: /setmaxhp [player] <amount>")); return true; }

        if (amount > configManager.getMaxHealthCap()) { amount = configManager.getMaxHealthCap(); sender.sendMessage(ColorUtils.colorize("&cValue capped at server maximum: " + configManager.getMaxHealthCap())); }
        if (amount < configManager.getMinimumMaxHealth()) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("min-health-reached"))); return true; }
        heartManager.setMaxHealth(target, amount);

        if (target.equals(sender)) sender.sendMessage(ColorUtils.colorize(configManager.getMessage("health-set-self").replace("%amount%", String.valueOf(amount))));
        else sender.sendMessage(ColorUtils.colorize(configManager.getMessage("health-set").replace("%player%", target.getName()).replace("%amount%", String.valueOf(amount))));
        return true;
    }

    private boolean handleCraftCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("player-only"))); return true; }
        if (!player.hasPermission("thelifesteal.craft")) { player.sendMessage(ColorUtils.colorize(configManager.getMessage("no-permission"))); return true; }
        craftingGUI.openMainMenu(player);
        return true;
    }

    private boolean handleRecipeBookCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("thelifesteal.admin")) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("no-permission"))); return true; }

        Player target;
        if (args.length == 0 && sender instanceof Player player) target = player;
        else if (args.length == 1) {
            target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { sender.sendMessage(ColorUtils.colorize(configManager.getMessage("player-not-found"))); return true; }
        } else { sender.sendMessage(ColorUtils.colorize("&cUsage: /recipebook [player]")); return true; }

        int slot = recipeBookItem.getSlot();
        target.getInventory().setItem(slot, recipeBookItem.createRecipeBook());
        target.sendMessage(ColorUtils.colorize("&a✦ You received a new Recipe Book!"));
        if (target != sender) sender.sendMessage(ColorUtils.colorize("&a✦ Gave a Recipe Book to " + target.getName()));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        switch (command.getName().toLowerCase()) {
            case "withdrawhearts" -> { if (args.length == 1) { completions.add("1"); completions.add("2"); completions.add("5"); completions.add("10"); completions.add("20"); } }
            case "setmaxhp" -> { if (args.length == 1) completions.addAll(plugin.getServer().getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList())); else if (args.length == 2) { completions.add("20"); completions.add("30"); completions.add("40"); completions.add("60"); } }
            case "recipebook" -> { if (args.length == 1) completions.addAll(plugin.getServer().getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList())); }
            case "reloaditems" -> {
                if (args.length == 1) {
                    completions.addAll(plugin.getServer().getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
                    completions.add("enable"); completions.add("disable"); completions.add("onjoin");
                    completions.add("onworldchange"); completions.add("status"); completions.add("purge");
                } else if (args.length == 2 && args[0].equalsIgnoreCase("purge")) {
                    var manager = plugin.getAdvancedItemManager();
                    if (manager != null) completions.addAll(manager.getAllItems().stream().map(i -> i.getId()).collect(Collectors.toList()));
                }
            }
            case "craft", "customitem", "ci" -> {}
        }

        return completions.stream().filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase())).collect(Collectors.toList());
    }
}