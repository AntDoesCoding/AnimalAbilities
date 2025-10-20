package com.animalabilities;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class AnimalAbilities extends JavaPlugin implements Listener {

    private final Map<UUID, String> playerAnimal = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        loadData();
        getLogger().info("AnimalAbilities plugin enabled!");
    }

    @Override
    public void onDisable() {
        saveData();
        getLogger().info("AnimalAbilities plugin disabled!");
    }

    private void loadData() {
        FileConfiguration config = getConfig();
        for (String key : config.getKeys(false)) {
            UUID uuid = UUID.fromString(key);
            String animal = config.getString(key);
            playerAnimal.put(uuid, animal);
        }
    }

    private void saveData() {
        FileConfiguration config = getConfig();
        config.getKeys(false).forEach(config::set);
        for (UUID uuid : playerAnimal.keySet()) {
            config.set(uuid.toString(), playerAnimal.get(uuid));
        }
        saveConfig();
    }

    // GUI Menu
    private void openAnimalMenu(Player player) {
        if (playerAnimal.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You’ve already chosen an animal!");
            return;
        }

        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Choose Your Animal");

        addItem(gui, 0, Material.BONE, ChatColor.GRAY + "Wolf");
        addItem(gui, 1, Material.STRING, ChatColor.YELLOW + "Cat");
        addItem(gui, 2, Material.HONEYCOMB, ChatColor.GOLD + "Bee");
        addItem(gui, 3, Material.SWEET_BERRIES, ChatColor.RED + "Fox");
        addItem(gui, 4, Material.WHITE_WOOL, ChatColor.WHITE + "Sheep");
        addItem(gui, 5, Material.SADDLE, ChatColor.DARK_GRAY + "Horse");

        player.openInventory(gui);
    }

    private void addItem(Inventory inv, int slot, Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Choose Your Animal")) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String animal = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        playerAnimal.put(player.getUniqueId(), animal);
        saveData();

        player.closeInventory();
        player.sendMessage(ChatColor.GREEN + "You chose: " + ChatColor.YELLOW + animal);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        applyPassiveEffects(player, animal);
    }

    private void applyPassiveEffects(Player player, String animal) {
        player.getActivePotionEffects().clear();
        switch (animal.toLowerCase()) {
            case "wolf" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, true, false));
            }
            case "cat" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, Integer.MAX_VALUE, 0, true, false));
            }
            case "bee" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, Integer.MAX_VALUE, 0, true, false));
            }
            case "fox" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0, false, false));
            }
            case "sheep" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 0, true, false));
            }
            case "horse" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 0, true, false));
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        if (playerAnimal.containsKey(player.getUniqueId())) {
            applyPassiveEffects(player, playerAnimal.get(player.getUniqueId()));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player player = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (playerAnimal.containsKey(player.getUniqueId())) {
                applyPassiveEffects(player, playerAnimal.get(player.getUniqueId()));
            }
        }, 20L);
    }

    private boolean onAbilityUse(Player player, String animal, String abilityName, int cooldownSeconds, Runnable abilityAction) {
        UUID id = player.getUniqueId();
        long current = System.currentTimeMillis();

        if (!playerAnimal.containsKey(id) || !playerAnimal.get(id).equalsIgnoreCase(animal)) {
            player.sendMessage(ChatColor.RED + "You cannot use this ability!");
            return true;
        }

        if (cooldowns.containsKey(id) && (current - cooldowns.get(id)) < cooldownSeconds * 1000L) {
            long remaining = ((cooldowns.get(id) + cooldownSeconds * 1000L) - current) / 1000;
            player.sendMessage(ChatColor.RED + "Ability on cooldown: " + remaining + "s remaining");
            return true;
        }

        cooldowns.put(id, current);
        abilityAction.run();
        player.sendMessage(ChatColor.GREEN + "Used " + abilityName + "!");
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 20);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        switch (cmd.getName().toLowerCase()) {
            case "chooseanimal" -> openAnimalMenu(player);
            case "resetanimal" -> {
                if (!player.isOp()) {
                    player.sendMessage(ChatColor.RED + "Only OPs can use this command!");
                    return true;
                }
                if (args.length == 0) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /resetanimal <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "Player not found!");
                    return true;
                }
                playerAnimal.remove(target.getUniqueId());
                saveData();
                target.sendMessage(ChatColor.RED + "Your animal has been reset by an admin!");
                player.sendMessage(ChatColor.GREEN + "You reset " + target.getName() + "'s animal.");
            }
            case "pounce" -> onAbilityUse(player, "wolf", "Pounce", 360, () -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 100, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 100, 0));
            });
            case "agilesprint" -> onAbilityUse(player, "cat", "Agile Sprint", 420, () -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 140, 2));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 140, 0));
            });
            case "flutterfly" -> onAbilityUse(player, "bee", "Flutter Fly", 240, () -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 100, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 100, 0));
            });
            case "fastescape" -> onAbilityUse(player, "fox", "Fast Escape", 600, () -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 200, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0));
            });
            case "woolguard" -> onAbilityUse(player, "sheep", "Wool Guard", 480, () -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 200, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 0));
            });
            case "fastgallop" -> onAbilityUse(player, "horse", "Fast Gallop", 600, () -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 2));
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 200, 1));
            });
        }
        return true;
    }
}
