package com.animalabilities;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class AnimalAbilities extends JavaPlugin implements Listener {

    // saved player -> animal (wolf,cat,bee,fox,sheep,horse)
    private final Map<UUID, String> playerAnimal = new HashMap<>();

    // cooldowns: player -> ability -> untilMillis
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    private File dataFile;
    private YamlConfiguration dataYml;

    @Override
    public void onEnable() {
        // create data file
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
            saveResource("data.yml", false); // creates empty file from jar if provided (not necessary)
            try {
                dataFile.createNewFile();
            } catch (IOException ignored) {}
        }
        dataYml = YamlConfiguration.loadConfiguration(dataFile);
        loadSavedAnimals();

        // register events and commands
        getServer().getPluginManager().registerEvents(this, this);

        // choose gui command
        getCommand("chooseanimal").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players.");
                return true;
            }
            Player p = (Player) sender;
            openChooseGui(p);
            return true;
        });

        // reset command (op only)
        getCommand("resetanimal").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can run this from in-game. Use console /resetanimal <player> as needed.");
                return true;
            }
            Player p = (Player) sender;
            if (!p.isOp()) {
                p.sendMessage(ChatColor.RED + "Only operators can reset animals.");
                return true;
            }
            if (args.length != 1) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /resetanimal <player>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                p.sendMessage(ChatColor.RED + "Player not found or not online.");
                return true;
            }
            resetPlayerAnimal(target);
            p.sendMessage(ChatColor.GREEN + "Reset " + target.getName() + "'s animal.");
            return true;
        });

        // register ability commands: exact names required by you
        registerAbilityCommand("pounce", "wolf");
        registerAbilityCommand("agilesprint", "cat");
        registerAbilityCommand("flutterfly", "bee");
        registerAbilityCommand("fastescape", "fox");
        registerAbilityCommand("woolguard", "sheep");
        registerAbilityCommand("fastgallop", "horse");

        getLogger().info("AnimalAbilities enabled.");
    }

    @Override
    public void onDisable() {
        saveDataFile();
        getLogger().info("AnimalAbilities disabled, data saved.");
    }

    /* -------------------------
       DATA: load / save
       ------------------------- */
    private void loadSavedAnimals() {
        if (dataYml == null) dataYml = YamlConfiguration.loadConfiguration(dataFile);
        if (!dataYml.contains("players")) return;
        for (String key : dataYml.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID u = UUID.fromString(key);
                String a = dataYml.getString("players." + key + ".animal");
                if (a != null && !a.isEmpty()) playerAnimal.put(u, a.toLowerCase());
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveDataFile() {
        if (dataYml == null) dataYml = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : playerAnimal.entrySet()) {
            dataYml.set("players." + e.getKey().toString() + ".animal", e.getValue());
        }
        try {
            dataYml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Failed to save data.yml: " + ex.getMessage());
        }
    }

    /* -------------------------
       GUI: Choose animal
       ------------------------- */
    private void openChooseGui(Player p) {
        // locked: if they already chose
        if (playerAnimal.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Contact an OP to reset.");
            return;
        }

        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Choose Your Animal");

        // populate: slots arbitrary but nice ordering
        gui.setItem(1, createItem(Material.BONE, ChatColor.GRAY + "Wolf", List.of("Strength II, Speed I", "Ability: Pounce (Jump II + Saturation)", "Cooldown: 6 min")));
        gui.setItem(2, createItem(Material.STRING, ChatColor.GRAY + "Cat", List.of("Speed II, Haste I", "Ability: Agile Sprint (Speed III + Slow Falling)", "Cooldown: 7 min")));
        gui.setItem(3, createItem(Material.HONEYCOMB, ChatColor.GRAY + "Bee", List.of("Jump Boost II, Slow Falling I", "Ability: Flutter Fly (Levitation + Resistance)", "Cooldown: 4 min")));
        gui.setItem(4, createItem(Material.WHITE_WOOL, ChatColor.GRAY + "Sheep", List.of("Resistance I, Jump Boost I", "Ability: Wool Guard (Resistance II + Regen I)", "Cooldown: 8 min")));
        gui.setItem(5, createItem(Material.SWEET_BERRIES, ChatColor.GRAY + "Fox", List.of("Speed II, Night Vision", "Ability: Fast Escape (Haste II + Invisibility)", "Cooldown: 10 min")));
        gui.setItem(6, createItem(Material.SADDLE, ChatColor.GRAY + "Horse", List.of("Speed II, Jump Boost I", "Ability: Fast Gallop (Speed III + Jump II)", "Cooldown: 10 min")));

        p.openInventory(gui);
    }

    private ItemStack createItem(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Choose Your Animal")) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player)) return;
            Player p = (Player) e.getWhoClicked();
            if (e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta()) return;

            // already chosen check
            if (playerAnimal.containsKey(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset.");
                p.closeInventory();
                return;
            }

            String disp = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()).toLowerCase();
            String animal = switch (disp) {
                case "wolf" -> "wolf";
                case "cat" -> "cat";
                case "bee" -> "bee";
                case "sheep" -> "sheep";
                case "fox" -> "fox";
                case "horse" -> "horse";
                default -> null;
            };
            if (animal == null) {
                p.sendMessage(ChatColor.RED + "Invalid selection.");
                p.closeInventory();
                return;
            }

            // lock and save
            playerAnimal.put(p.getUniqueId(), animal);
            saveDataFile();
            applyPassive(p, animal);
            p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + animal.substring(0,1).toUpperCase() + animal.substring(1) + "!");
            p.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, p.getLocation().add(0,1,0), 20);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            p.closeInventory();
        }
    }

    /* -------------------------
       Apply passive / reapply on join & respawn
       ------------------------- */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String animal = playerAnimal.get(p.getUniqueId());
        if (animal != null) applyPassive(p, animal);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = e.getPlayer();
            String animal = playerAnimal.get(p.getUniqueId());
            if (animal != null) applyPassive(p, animal);
        }, 20L);
    }

    private void applyPassive(Player p, String animal) {
        // Clear plugin-added effects only (safe approach: clear all and reapply)
        p.getActivePotionEffects().forEach(pe -> p.removePotionEffect(pe.getType()));

        // durations are ticks (use large number for permanent)
        int perm = Integer.MAX_VALUE - 1000;

        switch (animal) {
            case "wolf":
                // Strength II (amplifier 1), Speed I (amp 0)
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, perm, 1, true, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, perm, 0, true, false));
                break;
            case "cat":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, perm, 1, true, false)); // Speed II
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, perm, 0, true, false)); // Haste I
                break;
            case "bee":
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, perm, 1, true, false)); // Jump II
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, perm, 0, true, false)); // Slow Falling I
                break;
            case "fox":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, perm, 1, true, false)); // Speed II
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, perm, 0, true, false)); // Night Vision
                break;
            case "sheep":
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, perm, 0, true, false)); // Resistance I
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, perm, 0, true, false)); // Jump I
                break;
            case "horse":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, perm, 1, true, false)); // Speed II
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, perm, 0, true, false)); // Jump I
                break;
        }
    }

    /* -------------------------
       Abilities: register commands and logic
       ------------------------- */

    private void registerAbilityCommand(String commandName, String requiredAnimal) {
        // ensure command exists in plugin.yml (we will include it there)
        getCommand(commandName).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players.");
                return true;
            }
            Player p = (Player) sender;
            UUID id = p.getUniqueId();
            String chosen = playerAnimal.get(id);
            if (chosen == null) {
                p.sendMessage(ChatColor.RED + "You have not chosen an animal yet.");
                return true;
            }
            if (!chosen.equalsIgnoreCase(requiredAnimal)) {
                p.sendMessage(ChatColor.RED + "Only " + capitalize(requiredAnimal) + "s can use this ability.");
                return true;
            }

            // cooldown check
            long now = System.currentTimeMillis();
            Map<String, Long> pcd = cooldowns.computeIfAbsent(id, k -> new HashMap<>());
            long until = pcd.getOrDefault(commandName, 0L);
            if (now < until) {
                long left = (until - now) / 1000;
                p.sendMessage(ChatColor.GRAY + "Ability on cooldown: " + left + "s.");
                return true;
            }

            // run ability effect
            runAbility(commandName, p);

            // set cooldown
            int cd = getCooldownFor(commandName); // seconds
            pcd.put(commandName, now + cd * 1000L);
            return true;
        });
    }

    private void runAbility(String cmd, Player p) {
        Location loc = p.getLocation();
        switch (cmd) {
            case "pounce": // wolf: jump boost 2, saturation 1 (6 min)
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 20 * 8, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 20 * 8, 0));
                p.getWorld().spawnParticle(Particle.CRIT, loc.add(0,1,0), 30);
                p.playSound(loc, Sound.ENTITY_WOLF_GROWL, 1f, 1.0f);
                p.sendMessage(ChatColor.GREEN + "Pounce activated!");
                break;

            case "agilesprint": // cat: speed 3, slow falling (7 min)
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 8, 0));
                p.getWorld().spawnParticle(Particle.CLOUD, loc.add(0,1,0), 35);
                p.playSound(loc, Sound.ENTITY_CAT_PURR, 1f, 1.0f);
                p.sendMessage(ChatColor.GREEN + "Agile Sprint activated!");
                break;

            case "flutterfly": // bee: levitation, resistance (4 min)
                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20 * 5, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 20 * 6, 0));
                p.getWorld().spawnParticle(Particle.HEART, loc.add(0,1,0), 25);
                p.playSound(loc, Sound.ENTITY_BEE_LOOP, 1f, 1.0f);
                p.sendMessage(ChatColor.GREEN + "Flutter Fly activated!");
                break;

            case "fastescape": // fox: haste 2, invisibility (10 min)
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, 20 * 12, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 12, 0));
                p.getWorld().spawnParticle(Particle.SPELL, loc.add(0,1,0), 40);
                p.playSound(loc, Sound.ENTITY_FOX_AMBIENT, 1f, 1.0f);
                p.sendMessage(ChatColor.GREEN + "Fast Escape activated!");
                break;

            case "woolguard": // sheep: resistance 2, regeneration 1 (8 min)
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 20 * 8, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 8, 0));
                p.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc.add(0,1,0), 30);
                p.playSound(loc, Sound.ENTITY_SHEEP_AMBIENT, 1f, 1.0f);
                p.sendMessage(ChatColor.GREEN + "Wool Guard activated!");
                break;

            case "fastgallop": // horse: speed 3, jump 2 (10 min)
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 20 * 8, 1));
                p.getWorld().spawnParticle(Particle.CRIT_MAGIC, loc.add(0,1,0), 30);
                p.playSound(loc, Sound.ENTITY_HORSE_GALLOP, 1f, 1.0f);
                p.sendMessage(ChatColor.GREEN + "Fast Gallop activated!");
                break;

            default:
                p.sendMessage(ChatColor.RED + "Unknown ability.");
        }
    }

    private int getCooldownFor(String commandName) {
        switch (commandName) {
            case "pounce": return 6 * 60;
            case "agilesprint": return 7 * 60;
            case "flutterfly": return 4 * 60;
            case "fastescape": return 10 * 60;
            case "woolguard": return 8 * 60;
            case "fastgallop": return 10 * 60;
            default: return 300;
        }
    }

    /* -------------------------
       Reset helper
       ------------------------- */
    private void resetPlayerAnimal(Player target) {
        if (playerAnimal.containsKey(target.getUniqueId())) {
            playerAnimal.remove(target.getUniqueId());
            saveDataFile();
        }
        // also remove potion effects we applied
        for (PotionEffect pe : target.getActivePotionEffects()) {
            target.removePotionEffect(pe.getType());
        }
        target.sendMessage(ChatColor.RED + "Your animal has been reset by an admin. Use /chooseanimal to pick again.");
    }

    /* -------------------------
       Utility
       ------------------------- */
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
  }
