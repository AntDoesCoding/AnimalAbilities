package com.animalabilities;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class AnimalAbilities extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, String> playerAnimals = new HashMap<>();
    private final Map<UUID, Long> abilityCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("chooseanimal")).setExecutor(this);
        Objects.requireNonNull(getCommand("ability")).setExecutor(this);
        Objects.requireNonNull(getCommand("resetanimal")).setExecutor(this);
        getLogger().info("AnimalAbilities enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("AnimalAbilities disabled!");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (playerAnimals.containsKey(player.getUniqueId())) {
            applyPassiveEffects(player, playerAnimals.get(player.getUniqueId()));
        }
    }

    private void applyPassiveEffects(Player player, String animal) {
        clearEffects(player);

        switch (animal.toLowerCase()) {
            case "wolf":
                addEffect(player, PotionEffectType.SPEED, Integer.MAX_VALUE, 0);
                addEffect(player, PotionEffectType.INCREASE_DAMAGE, Integer.MAX_VALUE, 0);
                break;
            case "cat":
                addEffect(player, PotionEffectType.JUMP, Integer.MAX_VALUE, 1);
                addEffect(player, PotionEffectType.SPEED, Integer.MAX_VALUE, 1);
                break;
            case "bee":
                addEffect(player, PotionEffectType.LEVITATION, Integer.MAX_VALUE, 0);
                addEffect(player, PotionEffectType.SPEED, Integer.MAX_VALUE, 2);
                break;
            case "fox":
                addEffect(player, PotionEffectType.SPEED, Integer.MAX_VALUE, 1);
                addEffect(player, PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0);
                break;
            case "turtle":
                addEffect(player, PotionEffectType.WATER_BREATHING, Integer.MAX_VALUE, 0);
                addEffect(player, PotionEffectType.SLOW, Integer.MAX_VALUE, 0);
                break;
            case "horse":
                addEffect(player, PotionEffectType.SPEED, Integer.MAX_VALUE, 1);
                addEffect(player, PotionEffectType.JUMP, Integer.MAX_VALUE, 1);
                break;
            case "sheep":
                addEffect(player, PotionEffectType.REGENERATION, Integer.MAX_VALUE, 0);
                break;
            case "ant":
                addEffect(player, PotionEffectType.SPEED, Integer.MAX_VALUE, 1);
                addEffect(player, PotionEffectType.INCREASE_DAMAGE, Integer.MAX_VALUE, 0);
                break;
            case "owl":
                addEffect(player, PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0);
                addEffect(player, PotionEffectType.SLOW_FALLING, Integer.MAX_VALUE, 0);
                addEffect(player, PotionEffectType.JUMP, Integer.MAX_VALUE, 0);
                break;
            case "coppergolem":
                addEffect(player, PotionEffectType.SPEED, Integer.MAX_VALUE, 0);
                addEffect(player, PotionEffectType.FAST_DIGGING, Integer.MAX_VALUE, 0);
                break;
            default:
                player.sendMessage(ChatColor.RED + "Unknown animal: " + animal);
                break;
        }
    }

    private void addEffect(Player player, PotionEffectType type, int duration, int amplifier) {
        PotionEffect effect = new PotionEffect(type, duration, amplifier, true, false);
        player.addPotionEffect(effect);
    }

    private void clearEffects(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    private void useAbility(Player player) {
        String animal = playerAnimals.get(player.getUniqueId());
        if (animal == null) {
            player.sendMessage(ChatColor.RED + "You don’t have an animal selected!");
            return;
        }

        long now = System.currentTimeMillis();
        if (abilityCooldowns.containsKey(player.getUniqueId())) {
            long remaining = abilityCooldowns.get(player.getUniqueId()) - now;
            if (remaining > 0) {
                long minutes = remaining / 60000;
                player.sendMessage(ChatColor.YELLOW + "Your ability is on cooldown for " + minutes + " more minutes!");
                return;
            }
        }

        switch (animal.toLowerCase()) {
            case "wolf":
                addEffect(player, PotionEffectType.INCREASE_DAMAGE, 400, 2);
                addEffect(player, PotionEffectType.SPEED, 400, 1);
                break;
            case "cat":
                addEffect(player, PotionEffectType.SPEED, 400, 2);
                addEffect(player, PotionEffectType.JUMP, 400, 2);
                break;
            case "bee":
                addEffect(player, PotionEffectType.LEVITATION, 200, 0);
                addEffect(player, PotionEffectType.SPEED, 400, 2);
                break;
            case "fox":
                addEffect(player, PotionEffectType.INVISIBILITY, 400, 0);
                break;
            case "turtle":
                addEffect(player, PotionEffectType.DAMAGE_RESISTANCE, 400, 2);
                break;
            case "horse":
                addEffect(player, PotionEffectType.SPEED, 400, 3);
                addEffect(player, PotionEffectType.JUMP, 400, 2);
                break;
            case "sheep":
                addEffect(player, PotionEffectType.REGENERATION, 400, 2);
                break;
            case "ant":
                addEffect(player, PotionEffectType.SPEED, 400, 3);
                addEffect(player, PotionEffectType.INCREASE_DAMAGE, 400, 1);
                break;
            case "owl":
                addEffect(player, PotionEffectType.SPEED, 400, 1);
                addEffect(player, PotionEffectType.JUMP, 400, 1);
                break;
            case "coppergolem":
                addEffect(player, PotionEffectType.FAST_DIGGING, 400, 1);
                addEffect(player, PotionEffectType.DAMAGE_RESISTANCE, 400, 1);
                break;
            default:
                player.sendMessage(ChatColor.RED + "This animal has no ability.");
                return;
        }

        player.sendMessage(ChatColor.GREEN + "You used your animal ability!");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        abilityCooldowns.put(player.getUniqueId(), now + (20 * 60 * 1000)); // 20 minutes cooldown
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this!");
            return true;
        }

        Player player = (Player) sender;

        switch (cmd.getName().toLowerCase()) {
            case "chooseanimal":
                if (args.length == 0) {
                    player.sendMessage(ChatColor.RED + "Usage: /chooseanimal <animal>");
                    return true;
                }
                String chosenAnimal = args[0].toLowerCase();
                playerAnimals.put(player.getUniqueId(), chosenAnimal);
                applyPassiveEffects(player, chosenAnimal);
                player.sendMessage(ChatColor.GREEN + "You have chosen the animal: " + chosenAnimal);
                break;

            case "ability":
                useAbility(player);
                break;

            case "resetanimal":
                if (args.length == 0) {
                    playerAnimals.remove(player.getUniqueId());
                    clearEffects(player);
                    player.sendMessage(ChatColor.YELLOW + "Your animal has been reset.");
                } else {
                    if (!player.isOp()) {
                        player.sendMessage(ChatColor.RED + "You don’t have permission to reset others!");
                        return true;
                    }
                    if (args[0].equalsIgnoreCase("@a")) {
                        for (Player target : Bukkit.getOnlinePlayers()) {
                            playerAnimals.remove(target.getUniqueId());
                            clearEffects(target);
                            target.sendMessage(ChatColor.YELLOW + "Your animal has been reset by an admin.");
                        }
                        player.sendMessage(ChatColor.GREEN + "All player animals have been reset.");
                    } else {
                        Player target = Bukkit.getPlayer(args[0]);
                        if (target != null) {
                            playerAnimals.remove(target.getUniqueId());
                            clearEffects(target);
                            target.sendMessage(ChatColor.YELLOW + "Your animal has been reset by an admin.");
                            player.sendMessage(ChatColor.GREEN + "You reset " + target.getName() + "’s animal.");
                        } else {
                            player.sendMessage(ChatColor.RED + "Player not found.");
                        }
                    }
                }
                break;
        }
        return true;
    }
} // ✅ FINAL closing bracket — file now ends properly
