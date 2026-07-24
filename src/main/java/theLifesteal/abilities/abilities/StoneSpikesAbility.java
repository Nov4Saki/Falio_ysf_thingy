package theLifesteal.abilities.abilities;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import theLifesteal.ColorUtils;
import theLifesteal.abilities.AbilityCooldownManager;
import theLifesteal.abilities.ItemAbility;
import theLifesteal.abilities.ItemAbilityData;
import theLifesteal.abilities.ItemAbilityType;
import theLifesteal.util.FoliaScheduler;

import java.util.*;

public class StoneSpikesAbility extends ItemAbility {

    public StoneSpikesAbility(JavaPlugin plugin) {
        super(plugin, "stone_spikes", "Stone Spikes", ItemAbilityType.RIGHT_CLICK);
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("range", 8);
        config.put("coneWidth", 5);
        config.put("rows", 3);
        config.put("damage", 4.0);
        config.put("launchPower", 0.6);
        config.put("cooldown", 15);
        config.put("cooldownScope", "ITEM");
        return config;
    }

    @Override
    public Map<String, ConfigField> getConfigFields() {
        Map<String, ConfigField> fields = new LinkedHashMap<>();
        fields.put("range", new ConfigField("Range", "int", 3, 20));
        fields.put("coneWidth", new ConfigField("Cone Width", "int", 2, 10));
        fields.put("rows", new ConfigField("Rows", "int", 1, 5));
        fields.put("damage", new ConfigField("Damage", "double", 0.5, 50.0));
        fields.put("launchPower", new ConfigField("Launch Power", "double", 0.1, 2.0));
        fields.put("cooldown", new ConfigField("Cooldown (seconds)", "int", 0, 3600));
        fields.put("cooldownScope", new ConfigField("Cooldown Scope", "string"));
        return fields;
    }

    @Override
    public String buildLore(ItemAbilityData data) {
        int range = data.getConfigInt("range");
        int rows = data.getConfigInt("rows");
        double damage = data.getConfigDouble("damage");
        int cooldown = data.getConfigInt("cooldown");
        return "&7Summon &8stone spikes &7in &e" + rows + " rows\n&7Deals &c" + formatDamage(damage) + " &7& launches enemies &8(&e" + cooldown + "s cooldown&8)";
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

        int range = data.getConfigInt("range");
        int coneWidth = data.getConfigInt("coneWidth");
        int rows = data.getConfigInt("rows");
        double damage = data.getConfigDouble("damage");
        double launchPower = data.getConfigDouble("launchPower");

        Location start = player.getLocation();
        Vector direction = start.getDirection().normalize();
        Vector perpendicular = new Vector(-direction.getZ(), 0, direction.getX()).normalize();

        player.getWorld().playSound(start, Sound.BLOCK_STONE_BREAK, 1.5f, 0.3f);
        player.getWorld().playSound(start, Sound.ENTITY_IRON_GOLEM_HURT, 1.0f, 0.4f);
        player.getWorld().playSound(start, Sound.BLOCK_DEEPSLATE_BREAK, 1.2f, 0.2f);

        Set<Entity> hitEntities = new HashSet<>();

        for (int row = 0; row < rows; row++) {
            double rowDistance = 2.0 + (row * 2.0);
            int spikesInRow = coneWidth - row;
            if (spikesInRow < 1) spikesInRow = 1;

            for (int s = 0; s < spikesInRow; s++) {
                double offset = (s - (spikesInRow - 1) / 2.0) * 1.5;
                final Location spikeLoc = start.clone()
                        .add(direction.clone().multiply(rowDistance))
                        .add(perpendicular.clone().multiply(offset));

                Location groundLoc = spikeLoc.clone();
                Block groundBlock = groundLoc.getBlock();
                if (!groundBlock.getType().isSolid()) {
                    Block below = groundBlock;
                    for (int y = 0; y < 5; y++) {
                        below = below.getRelative(BlockFace.DOWN);
                        if (below.getType().isSolid()) {
                            groundLoc = below.getLocation().add(0, 1, 0);
                            groundBlock = groundLoc.getBlock();
                            break;
                        }
                    }
                }

                Material original = groundBlock.getType();
                if (groundBlock.getType() == Material.AIR || groundBlock.getType().name().contains("REPLACEABLE")
                        || groundBlock.getType().name().contains("GRASS") || groundBlock.getType().name().contains("SNOW")
                        || groundBlock.getType().name().contains("WATER") || groundBlock.getType().name().contains("TALL")
                        || groundBlock.getType().name().contains("FLOWER") || groundBlock.getType().name().contains("DEAD")) {

                    groundBlock.setType(Material.STONE);
                    final Block finalGroundBlock = groundBlock;
                    final Material finalOriginal = original;

                    Block aboveBlock = groundLoc.clone().add(0, 1, 0).getBlock();
                    Material aboveOriginal = aboveBlock.getType();
                    if (aboveBlock.getType() == Material.AIR || aboveBlock.getType().name().contains("REPLACEABLE")) {
                        aboveBlock.setType(Material.COBBLESTONE_WALL);
                        final Block finalAboveBlock = aboveBlock;
                        final Material finalAboveOriginal = aboveOriginal;
                        FoliaScheduler.runRegionLater(finalAboveBlock.getLocation(), getPlugin(), () -> {
                            if (finalAboveBlock.getType() == Material.COBBLESTONE_WALL) {
                                finalAboveBlock.setType(finalAboveOriginal);
                            }
                        }, 25L);
                    }

                    FoliaScheduler.runRegionLater(finalGroundBlock.getLocation(), getPlugin(), () -> {
                        if (finalGroundBlock.getType() == Material.STONE) {
                            finalGroundBlock.setType(finalOriginal);
                        }
                    }, 25L);

                    Location particleLoc = groundLoc.clone().add(0, 0.5, 0);
                    final Location finalParticleLoc = particleLoc.clone();

                    groundLoc.getWorld().spawnParticle(Particle.BLOCK,
                            particleLoc, 30, 0.3, 0.5, 0.3, 0.2, Material.STONE.createBlockData());
                    groundLoc.getWorld().spawnParticle(Particle.BLOCK,
                            particleLoc.clone().add(0, 0.8, 0), 15, 0.2, 0.3, 0.2, 0.1, Material.COBBLESTONE.createBlockData());
                    groundLoc.getWorld().spawnParticle(Particle.DUST,
                            particleLoc, 20, 0.3, 0.5, 0.3,
                            new Particle.DustOptions(org.bukkit.Color.GRAY, 2f));
                    groundLoc.getWorld().spawnParticle(Particle.CLOUD,
                            particleLoc, 8, 0.3, 0.2, 0.3, 0.05);

                    int[] ticks = new int[]{0};

                    FoliaScheduler.runRegionTimer(finalParticleLoc, getPlugin(), task -> {
                        if (ticks[0] >= 15) {
                            task.cancel();
                            return;
                        }
                        finalParticleLoc.getWorld().spawnParticle(Particle.DUST,
                                finalParticleLoc.clone().add(
                                        Math.random() * 0.6 - 0.3,
                                        Math.random() * 0.4,
                                        Math.random() * 0.6 - 0.3),
                                1, 0, 0, 0,
                                new Particle.DustOptions(org.bukkit.Color.GRAY, 1.5f));
                        finalParticleLoc.getWorld().spawnParticle(Particle.BLOCK,
                                finalParticleLoc.clone().add(
                                        Math.random() * 0.4 - 0.2,
                                        Math.random() * 0.3,
                                        Math.random() * 0.4 - 0.2),
                                1, 0, 0, 0, Material.COBBLESTONE.createBlockData());
                        ticks[0]++;
                    }, 0L, 1L);

                    for (Entity entity : groundLoc.getWorld().getNearbyEntities(groundLoc, 1.5, 2.5, 1.5)) {
                        if (entity instanceof LivingEntity && entity != player && !hitEntities.contains(entity)) {
                            hitEntities.add(entity);
                            LivingEntity target = (LivingEntity) entity;

                            dealAbilityDamage(player, target, damage, 15000L);
                            Vector launchVec = new Vector(0, launchPower, 0);
                            target.setVelocity(target.getVelocity().add(launchVec));

                            entity.getWorld().spawnParticle(Particle.CRIT,
                                    entity.getLocation().add(0, 1.5, 0),
                                    15, 0.3, 0.3, 0.3, 0.1);
                            entity.getWorld().spawnParticle(Particle.BLOCK,
                                    entity.getLocation().add(0, 1, 0),
                                    10, 0.3, 0.3, 0.3, Material.STONE.createBlockData());
                            entity.getWorld().playSound(entity.getLocation(),
                                    Sound.BLOCK_STONE_HIT, 0.8f, 0.8f);
                            entity.getWorld().playSound(entity.getLocation(),
                                    Sound.ENTITY_PLAYER_HURT, 0.5f, 1.5f);
                        }
                    }
                }
            }
        }

        for (int i = 0; i < 15; i++) {
            player.getWorld().spawnParticle(Particle.CLOUD,
                    start.clone().add(
                            Math.random() * 2 - 1,
                            0.1,
                            Math.random() * 2 - 1),
                    1, 0, 0, 0, 0.02);
        }

        player.sendMessage(ColorUtils.colorize("&7🪨 Stone Spikes erupted!"));

        if (cooldown > 0) {
            cooldownManager.setCooldown(player.getUniqueId(), getId(), itemId, scope, cooldown);
        }
        return true;
    }

    private String formatDamage(double damage) {
        if (damage == Math.floor(damage)) return (int) damage + "❤";
        return damage + "❤";
    }
}