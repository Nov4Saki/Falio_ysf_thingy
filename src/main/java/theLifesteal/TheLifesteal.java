package theLifesteal;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import theLifesteal.abilities.*;
import theLifesteal.abilities.abilities.*;
import theLifesteal.crafting.*;
import theLifesteal.customitem.AdvancedCustomItemManager;
import theLifesteal.customitem.CustomEnchantGUI;
import theLifesteal.customitem.CustomItemGUI;
import theLifesteal.customitem.CustomItemListener;
import theLifesteal.customitem.CustomItemRestrictionListener;
import theLifesteal.customitem.CustomItemEffectListener;
import theLifesteal.customitem.ItemUpdateManager;
import theLifesteal.customitem.ItemModelPreservationListener;

import java.util.UUID;
import java.util.logging.Level;

public final class TheLifesteal extends JavaPlugin implements Listener {

    private static TheLifesteal instance;
    private ConfigManager configManager;
    private HeartManager heartManager;
    private DeathListener deathListener;
    private HeartUseListener heartUseListener;
    private CommandHandler commandHandler;
    private RecipeManager recipeManager;

    // Crafting system
    private RecipeBookItem recipeBookItem;
    private CraftingManager craftingManager;
    private CraftingGUI craftingGUI;
    private RecipeBookListener recipeBookListener;
    private CustomItemManager customItemManager;

    // Custom Item System
    private AdvancedCustomItemManager advancedItemManager;
    private CustomItemGUI customItemGUI;
    private CustomItemListener customItemListener;
    private CustomItemEffectListener customItemEffectListener;
    private CustomEnchantGUI customEnchantGUI;
    private ItemUpdateManager itemUpdateManager;

    // Ability System
    private ItemAbilityManager abilityManager;
    private ItemAbilityGUI abilityGUI;
    private ItemAbilityListener abilityListener;
    private TotemProtectionManager totemProtectionManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        try { saveResource("recipes.yml", false); } catch (IllegalArgumentException e) { getLogger().info("No default recipes.yml found."); }
        try { saveResource("custom_items.yml", false); } catch (IllegalArgumentException e) { getLogger().info("No default custom_items.yml found."); }

        this.configManager = new ConfigManager(this);
        this.heartManager = new HeartManager(this);

        // Ability system
        this.abilityManager = new ItemAbilityManager(this);
        registerAbilities();

        // Custom item system
        this.customItemManager = new CustomItemManager(this);
        this.advancedItemManager = new AdvancedCustomItemManager(this);
        this.advancedItemManager.setAbilityManager(abilityManager);
        this.advancedItemManager.loadItems();
        advancedItemManager.registerDefaultItems(getConfig());

        // Item update manager (self-registers as Listener)
        this.itemUpdateManager = new ItemUpdateManager(this, advancedItemManager);
        getServer().getPluginManager().registerEvents(
                new ItemModelPreservationListener(this, advancedItemManager, itemUpdateManager), this);

        this.customItemGUI = new CustomItemGUI(this, advancedItemManager);
        this.abilityGUI = new ItemAbilityGUI(this, abilityManager);
        this.customItemGUI.setAbilityGUI(abilityGUI);

        this.customEnchantGUI = new CustomEnchantGUI(this);
        this.customItemGUI.setEnchantGUI(customEnchantGUI);

        this.customItemListener = new CustomItemListener(this, customItemGUI, abilityGUI);
        getServer().getPluginManager().registerEvents(customItemListener, this);
        getServer().getPluginManager().registerEvents(new CustomItemRestrictionListener(this), this);

        this.customItemEffectListener = new CustomItemEffectListener(this, advancedItemManager);
        getServer().getPluginManager().registerEvents(customItemEffectListener, this);

        this.totemProtectionManager = new TotemProtectionManager(this);
        this.abilityListener = new ItemAbilityListener(this, abilityManager, advancedItemManager);
        getServer().getPluginManager().registerEvents(abilityListener, this);

        getLogger().info("§a✓ Advanced Custom Item System initialized");
        getLogger().info("§a✓ Custom Item Potion Effects initialized");
        getLogger().info("§a✓ Item Ability System initialized");
        getLogger().info("§a✓ Custom Enchant System initialized");
        getLogger().info("§a✓ Item Update Manager initialized (event-driven)");

        // Register listeners
        this.deathListener = new DeathListener(this);
        this.heartUseListener = new HeartUseListener(this);
        getServer().getPluginManager().registerEvents(deathListener, this);
        getServer().getPluginManager().registerEvents(heartUseListener, this);
        getServer().getPluginManager().registerEvents(this, this);

        // Crafting system
        initializeCraftingSystem();

        // Commands
        this.commandHandler = new CommandHandler(this, recipeBookItem, craftingGUI);
        registerCommands();

        // Recipes
        this.recipeManager = new RecipeManager(this);
        recipeManager.registerRecipes();

        craftingManager.loadCraftingProcesses();

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (craftingGUI != null) {
                    craftingGUI.removePlayer(uuid);
                    if (craftingGUI.getAdminGUI() != null) craftingGUI.getAdminGUI().cleanupPlayer(uuid);
                }
                if (customItemGUI != null) customItemGUI.cleanupPlayer(uuid);
                if (abilityGUI != null) abilityGUI.cleanupPlayer(uuid);
                if (customEnchantGUI != null) customEnchantGUI.cleanupPlayer(uuid);
            }
        }, this);

        getLogger().log(Level.INFO, "§c❤ §aLifesteal Plugin v2.0 enabled for 1.21.11! §c❤");
        getLogger().log(Level.INFO, "§6⚒ §eCustom Crafting System loaded! §6⚒");
        getLogger().log(Level.INFO, "§d✨ §eCustom Items Manager loaded! §d✨");
        getLogger().log(Level.INFO, "§d🧪 §ePotion Effects System loaded! §d🧪");
        getLogger().log(Level.INFO, "§6✨ §eAbility System loaded! §6✨");
        getLogger().log(Level.INFO, "§5✨ §eEnchant System loaded! §5✨");
        getLogger().log(Level.INFO, "§a⟳ §eItem Update System loaded (verify-on-use)! §a⟳");
    }

    private void registerAbilities() {
        abilityManager.registerAbility(new HealingAbility(this));
        abilityManager.registerAbility(new StrengthAbility(this));
        abilityManager.registerAbility(new TeleportAbility(this));
        abilityManager.registerAbility(new DrainLifeAbility(this));
        abilityManager.registerAbility(new DashAbility(this));
        abilityManager.registerAbility(new BlinkAbility(this));
        abilityManager.registerAbility(new GroundSlamAbility(this));
        abilityManager.registerAbility(new FreezingStrikeAbility(this));
        abilityManager.registerAbility(new FireTrailAbility(this));
        abilityManager.registerAbility(new GravityPullAbility(this));
        abilityManager.registerAbility(new CriticalStrikeAbility(this));
        abilityManager.registerAbility(new AirSlashAbility(this));
        abilityManager.registerAbility(new StoneSpikesAbility(this));
        abilityManager.registerAbility(new PoisonStrikeAbility(this));
        abilityManager.registerAbility(new PoisonDaggerAbility(this));
        abilityManager.registerAbility(new ChainLightningAbility(this));
        abilityManager.registerAbility(new WitheringStrikeAbility(this));
        abilityManager.registerAbility(new ExplosiveChargeAbility(this));
        abilityManager.registerAbility(new TornadoAbility(this));
        abilityManager.registerAbility(new BerserkAbility(this));
        abilityManager.registerAbility(new BloodBoltsAbility(this));
        abilityManager.registerAbility(new IceStormAbility(this));
        abilityManager.registerAbility(new LifeConsumeAbility(this));
        abilityManager.registerAbility(new MeteorStrikeAbility(this));
        abilityManager.registerAbility(new PhoenixAbility(this));
        abilityManager.registerAbility(new PlasmaBlastAbility(this));
        abilityManager.registerAbility(new PierceArmorAbility(this));
        abilityManager.registerAbility(new MirageAbility(this));
        abilityManager.registerAbility(new ReaperAbility(this));
        abilityManager.registerAbility(new CelestialHomingShotAbility(this));
        getLogger().info("§a✓ Registered " + abilityManager.getAllAbilities().size() + " abilities");
    }

    private void initializeCraftingSystem() {
        NamespacedKey bookKey = new NamespacedKey(this, "recipe_book");
        this.recipeBookItem = new RecipeBookItem(bookKey, getConfig());
        this.craftingManager = new CraftingManager(this);
        this.craftingGUI = new CraftingGUI(this, craftingManager, getConfig(), customItemManager);
        this.recipeBookListener = new RecipeBookListener(this, recipeBookItem, craftingGUI, craftingManager);
        getServer().getPluginManager().registerEvents(recipeBookListener, this);
        getLogger().info("§a✓ Recipe Book System initialized");
        getLogger().info("§a✓ Crafting Manager initialized");
        getLogger().info("§a✓ Crafting GUI initialized");
    }

    private void registerCommands() {
        if (getCommand("withdrawhearts") != null) { getCommand("withdrawhearts").setExecutor(commandHandler); getCommand("withdrawhearts").setTabCompleter(commandHandler); }
        if (getCommand("setmaxhp") != null) { getCommand("setmaxhp").setExecutor(commandHandler); getCommand("setmaxhp").setTabCompleter(commandHandler); }
        if (getCommand("craft") != null) { getCommand("craft").setExecutor(commandHandler); getCommand("craft").setTabCompleter(commandHandler); }
        if (getCommand("recipebook") != null) { getCommand("recipebook").setExecutor(commandHandler); getCommand("recipebook").setTabCompleter(commandHandler); }
        if (getCommand("customitem") != null) { getCommand("customitem").setExecutor(commandHandler); getCommand("customitem").setTabCompleter(commandHandler); }
        if (getCommand("reloaditems") != null) { getCommand("reloaditems").setExecutor(commandHandler); getCommand("reloaditems").setTabCompleter(commandHandler); }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (craftingGUI != null) {
            craftingGUI.removePlayer(uuid);
            if (craftingGUI.getAdminGUI() != null) craftingGUI.getAdminGUI().cleanupPlayer(uuid);
        }
        if (customItemGUI != null) customItemGUI.cleanupPlayer(uuid);
        if (abilityGUI != null) abilityGUI.cleanupPlayer(uuid);
        if (customEnchantGUI != null) customEnchantGUI.cleanupPlayer(uuid);
        if (abilityManager != null && abilityManager.getCooldownManager() != null) abilityManager.getCooldownManager().cleanupExpired(uuid);
        if (abilityManager.getAbility("explosive_charge") instanceof ExplosiveChargeAbility exp) exp.cleanupPlayer(uuid);
        if (totemProtectionManager != null) totemProtectionManager.clearPlayer(uuid);
    }

    public AdvancedCustomItemManager getAdvancedItemManager() { return advancedItemManager; }
    public CustomItemGUI getCustomItemGUI() { return customItemGUI; }
    public ItemAbilityManager getAbilityManager() { return abilityManager; }
    public ItemAbilityGUI getAbilityGUI() { return abilityGUI; }
    public TotemProtectionManager getTotemProtectionManager() { return totemProtectionManager; }
    public ItemUpdateManager getItemUpdateManager() { return itemUpdateManager; }

    @Override
    public void onDisable() {
        if (craftingManager != null) craftingManager.forceSave();
        if (advancedItemManager != null) advancedItemManager.saveItems();
        if (customItemEffectListener != null) customItemEffectListener.shutdown();
        if (abilityManager != null) {
            CriticalStrikeAbility critAbility = (CriticalStrikeAbility) abilityManager.getAbility("critical_strike");
            if (critAbility != null) critAbility.cleanup();
        }
        getLogger().log(Level.INFO, "§c❤ §eLifesteal Plugin disabled. Goodbye! §c❤");
    }

    public static TheLifesteal getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public HeartManager getHeartManager() { return heartManager; }
    public RecipeBookItem getRecipeBookItem() { return recipeBookItem; }
    public CraftingManager getCraftingManager() { return craftingManager; }
    public CraftingGUI getCraftingGUI() { return craftingGUI; }
    public CustomItemManager getCustomItemManager() { return customItemManager; }
}
