// File: src/main/java/com/animalabilities/AnimalAbilities.java
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
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * AnimalAbilities - Paper 1.21.x safe plugin
 * - Adds Owl and Copper Golem to existing code base
 * - Keep existing behavior, add two animals to GUI and abilities
 */
public class AnimalAbilities extends JavaPlugin implements Listener {

    private final Map<UUID, String> chosen = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    private File dataFile;
    private YamlConfiguration dataYml;

    @Override
    public void onEnable() {
        // load data.yml
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create data.yml: " + e.getMessage());
            }
        }
        dataYml = YamlConfiguration.loadConfiguration(dataFile);
        loadSavedChoices();

        // register events and commands
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AnimalAbilities enabled.");

        // Note: commands must be present in plugin.yml (see provided plugin.yml)
    }

    @Override
    public void onDisable() {
        saveChoices();
        getLogger().info("AnimalAbilities disabled.");
    }

    // -------------------- Persistence --------------------
    private void loadSavedChoices() {
        if (dataYml == null) dataYml = YamlConfiguration.loadConfiguration(dataFile);
        if (!dataYml.contains("players")) return;
        for (String key : dataYml.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID u = UUID.fromString(key);
                String a = dataYml.getString("players." + key + ".animal", "");
                if (!a.isEmpty()) chosen.put(u, a.toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {}
        }
        getLogger().info("Loaded " + chosen.size() + " saved animal choices.");
    }

    private void saveChoices() {
        if (dataYml == null) dataYml = new YamlConfiguration();
        dataYml.set("players", null);
        for (Map.Entry<UUID, String> e : chosen.entrySet()) {
            dataYml.set("players." + e.getKey().toString() + ".animal", e.getValue());
        }
        try {
            dataYml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Failed to save data.yml: " + ex.getMessage());
        }
    }

    // -------------------- Commands / GUI --------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("chooseanimal")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Only players may run this."); return true; }
            openChooseGui((Player) sender);
            return true;
        }
        if (cmd.equals("resetanimal")) {
            if (!(sender instanceof Player)) {
                // allow console to reset
                if (args.length != 1) { sender.sendMessage("Usage: /resetanimal <player>"); return true; }
                Player t = Bukkit.getPlayerExact(args[0]);
                if (t == null) { sender.sendMessage("Player not found."); return true; }
                resetPlayer(t);
                sender.sendMessage("Reset " + t.getName());
                return true;
            }
            Player p = (Player) sender;
            if (!p.isOp()) { p.sendMessage(ChatColor.RED + "Only OPs may use this command."); return true; }
            if (args.length != 1) { p.sendMessage(ChatColor.YELLOW + "Usage: /resetanimal <player>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { p.sendMessage(ChatColor.RED + "Player not found."); return true; }
            resetPlayer(target);
            p.sendMessage(ChatColor.GREEN + "Reset " + target.getName());
            return true;
        }

        // ability commands (exact names) - added glide and discovery
        if (Arrays.asList("pounce","focus","hover","escape","harden","gallop","soften","sting","glide","discovery").contains(cmd)) {
            if (!(sender instanceof Player)) { sender.sendMessage("Only players may use abilities."); return true; }
            useAbility((Player) sender, cmd);
            return true;
        }

        return false;
    }

    private void openChooseGui(Player p) {
        if (chosen.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset it.");
            return;
        }
        // increase inventory to 18 to fit 10 animals (2 rows)
        Inventory inv = Bukkit.createInventory(null, 18, ChatColor.DARK_GREEN + "Choose Your Animal");

        inv.setItem(0, makeItem(Material.BONE, "Wolf", "Strong and Brave!", "Strength I, Speed I"));
        inv.setItem(1, makeItem(Material.STRING, "Cat", "Agile and Alert!", "Jump Boost II, Night Vision"));
        inv.setItem(2, makeItem(Material.HONEYCOMB, "Bee", "Graceful in the air.", "Slow Falling, Jump Boost II"));
        inv.setItem(3, makeItem(Material.SWEET_BERRIES, "Fox", "Sneaky and swift.", "Speed II, Invisibility at night"));
        inv.setItem(4, makeItem(safeMaterial("SCUTE"), "Turtle", "Slow but tough.", "Water Breathing, Resistance I"));
        inv.setItem(5, makeItem(Material.SADDLE, "Horse", "Fast and powerful.", "Speed I, Jump Boost I"));
        inv.setItem(6, makeItem(Material.WHITE_WOOL, "Sheep", "Soft yet sturdy.", "Resistance I, Jump Boost I"));
        inv.setItem(7, makeItem(safeMaterial("SOUL_SOIL"), "Ant", "Small but mighty.", "Haste, Strength I"));
        // newly added
        inv.setItem(9, makeItem(Material.FEATHER, "Owl", "Silent nocturnal flyer.", "Night Vision, Slow Falling, Jump I"));
        inv.setItem(10, makeItem(safeMaterial("COPPER_INGOT"), "CopperGolem", "Curious construct.", "Speed, Haste"));

        p.openInventory(inv);
    }

    private ItemStack makeItem(Material mat, String name, String lore1, String lore2) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name);
            meta.setLore(Arrays.asList(ChatColor.WHITE + lore1, ChatColor.GREEN + lore2));
            it.setItemMeta(meta);
        }
        return it;
    }

    private Material safeMaterial(String s) {
        Material m = Material.matchMaterial(s);
        return m != null ? m : Material.BARRIER;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (!e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Choose Your Animal")) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta()) { p.closeInventory(); return; }
        String disp = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
        Set<String> valid = new HashSet<>(Arrays.asList("wolf","cat","bee","fox","turtle","horse","sheep","ant","owl","coppergolem"));
        if (!valid.contains(disp)) { p.sendMessage(ChatColor.RED + "Invalid selection."); p.closeInventory(); return; }

        // lock choice
        chosen.put(p.getUniqueId(), disp);
        saveChoices();
        applyPassiveToPlayer(p, disp);
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + ChatColor.AQUA + capitalize(disp) + ChatColor.GREEN + "!");
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0,1,0), 20);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p.closeInventory();
    }

    // -------------------- Reapply on join/respawn --------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String a = chosen.get(p.getUniqueId());
        if (a != null) applyPassiveToPlayer(p, a);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String a = chosen.get(p.getUniqueId());
            if (a != null) applyPassiveToPlayer(p, a);
        }, 20L);
    }

    private void applyPassiveToPlayer(Player p, String animal) {
        // remove any previous plugin effects (safe approach)
        for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());

        int permTicks = Integer.MAX_VALUE - 1000;

        switch (animal) {
            case "wolf":
                addPotionSafe(p, "INCREASE_DAMAGE", permTicks, 1); // Strength II
                addPotionSafe(p, "SPEED", permTicks, 0); // Speed I
                break;
            case "cat":
                addPotionSafe(p, "JUMP_BOOST", permTicks, 1); // Jump II
                addPotionSafe(p, "NIGHT_VISION", permTicks, 0);
                break;
            case "bee":
                addPotionSafe(p, "SLOW_FALLING", permTicks, 0);
                addPotionSafe(p, "JUMP_BOOST", permTicks, 1);
                break;
            case "fox":
                addPotionSafe(p, "SPEED", permTicks, 1);
                // invis at night: schedule a short task to set invis if night
                scheduleFoxInvisibility(p);
                break;
            case "turtle":
                addPotionSafe(p, "WATER_BREATHING", permTicks, 0);
                addPotionSafe(p, "DAMAGE_RESISTANCE", permTicks, 0);
                break;
            case "horse":
                addPotionSafe(p, "SPEED", permTicks, 0);
                addPotionSafe(p, "JUMP_BOOST", permTicks, 0);
                break;
            case "sheep":
                addPotionSafe(p, "DAMAGE_RESISTANCE", permTicks, 0);
                addPotionSafe(p, "JUMP_BOOST", permTicks, 0);
                break;
            case "ant":
                addPotionSafe(p, "FAST_DIGGING", permTicks, 0);
                addPotionSafe(p, "INCREASE_DAMAGE", permTicks, 0);
                break;
            case "owl":
                addPotionSafe(p, "NIGHT_VISION", permTicks, 0);
                addPotionSafe(p, "SLOW_FALLING", permTicks, 0);
                addPotionSafe(p, "JUMP_BOOST", permTicks, 0);
                break;
            case "coppergolem":
                addPotionSafe(p, "SPEED", permTicks, 0);
                addPotionSafe(p, "FAST_DIGGING", permTicks, 0);
                break;
        }
    }

    private void scheduleFoxInvisibility(Player p) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                long t = p.getWorld().getTime();
                boolean night = (t >= 13000 || t <= 2300);
                if (night) addPotionSafe(p, "INVISIBILITY", Integer.MAX_VALUE - 1000, 0);
                else p.removePotionEffect(PotionEffectType.INVISIBILITY);
                cancel();
            }
        }.runTaskLater(this, 10L);
    }

    // -------------------- Abilities --------------------
    private void useAbility(Player p, String cmd) {
        String animal = chosen.get(p.getUniqueId());
        if (animal == null) { p.sendMessage(ChatColor.RED + "You have not chosen an animal."); return; }
        if (!canUseAbilityFor(animal, cmd)) { p.sendMessage(ChatColor.RED + "Only " + capitalize(animal) + "s can use that ability."); return; }

        // per-player per-ability cooldown map
        Map<String, Long> pc = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        long until = pc.getOrDefault(cmd, 0L);
        if (now < until) {
            long left = (until - now) / 1000;
            p.sendMessage(ChatColor.YELLOW + "Ability on cooldown: " + left + "s");
            return;
        }

        // durations (seconds) and cooldowns (seconds)
        int durationSec;
        int cooldownSec;
        switch (cmd) {
            case "pounce": durationSec = 8; cooldownSec = 6 * 60; // wolf
                addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 1);
                addPotionSafe(p, "INCREASE_DAMAGE", durationSec * 20, 1);
                spawnParticlesTimed(p, Particle.CAMPFIRE_COSY_SMOKE, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Pounce activated!");
                break;
            case "focus": durationSec = 8; cooldownSec = 7 * 60; // cat
                addPotionSafe(p, "FAST_DIGGING", durationSec * 20, 1);
                addPotionSafe(p, "SPEED", durationSec * 20, 2);
                spawnParticlesTimed(p, Particle.EXPLOSION_NORMAL, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_CAT_PURR, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Focus activated!");
                break;
            case "hover": durationSec = 6; cooldownSec = 4 * 60; // bee
                addPotionSafe(p, "LEVITATION", durationSec * 20, 0);
                addPotionSafe(p, "DAMAGE_RESISTANCE", durationSec * 20, 1);
                spawnParticlesTimed(p, Particle.END_ROD, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_BEE_LOOP, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Hover activated!");
                break;
            case "escape": durationSec = 10; cooldownSec = 10 * 60; // fox
                addPotionSafe(p, "FAST_DIGGING", durationSec * 20, 1);
                addPotionSafe(p, "INVISIBILITY", durationSec * 20, 0);
                spawnParticlesTimed(p, Particle.FLAME, 40, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_FOX_SCREECH, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Escape activated!");
                break;
            case "harden": durationSec = 6; cooldownSec = 8 * 60; // turtle
                addPotionSafe(p, "DAMAGE_RESISTANCE", durationSec * 20, 2);
                addPotionSafe(p, "SLOW", durationSec * 20, 1);
                spawnParticlesTimed(p, Particle.WATER_SPLASH, 40, 5);
                p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Harden activated!");
                break;
            case "gallop": durationSec = 8; cooldownSec = 10 * 60; // horse
                addPotionSafe(p, "SPEED", durationSec * 20, 2);
                addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 1);
                spawnParticlesTimed(p, Particle.CLOUD, 35, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_HORSE_GALLOP, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Gallop activated!");
                break;
            case "soften": durationSec = 8; cooldownSec = 8 * 60; // sheep
                addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 1);
                addPotionSafe(p, "REGENERATION", durationSec * 20, 0);
                spawnParticlesTimed(p, Particle.HAPPY_VILLAGER, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_SHEEP_AMBIENT, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Soften activated!");
                break;
            case "sting": durationSec = 6; cooldownSec = 5 * 60; // ant
                addPotionSafe(p, "INCREASE_DAMAGE", durationSec * 20, 1);
                addPotionSafe(p, "SPEED", durationSec * 20, 0);
                spawnParticlesTimed(p, Particle.SOUL, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_BEE_STING, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Sting activated!");
                break;
            case "glide": // Owl ability
                durationSec = 10; cooldownSec = 30;
                addPotionSafe(p, "SPEED", durationSec * 20, 1);
                addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 1);
                spawnParticlesTimed(p, Particle.SPELL, 35, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Glide activated!");
                break;
            case "discovery": // Copper Golem ability
                durationSec = 10; cooldownSec = 45;
                addPotionSafe(p, "FAST_DIGGING", durationSec * 20, 1);
                addPotionSafe(p, "DAMAGE_RESISTANCE", durationSec * 20, 1);
                spawnParticlesTimed(p, Particle.PORTAL, 35, 5);
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Discovery activated!");
                break;
            default:
                p.sendMessage(ChatColor.RED + "Unknown ability.");
                return;
        }

        // apply cooldown
        pc.put(cmd, System.currentTimeMillis() + (long) (getCooldownSeconds(cmd) * 1000L));
    }

    private int getCooldownSeconds(String cmd) {
        return switch (cmd) {
            case "pounce" -> 6 * 60;
            case "focus" -> 7 * 60;
            case "hover" -> 4 * 60;
            case "escape" -> 10 * 60;
            case "harden" -> 8 * 60;
            case "gallop" -> 10 * 60;
            case "soften" -> 8 * 60;
            case "sting" -> 5 * 60;
            case "glide" -> 30;
            case "discovery" -> 45;
            default -> 300;
        };
    }

    private boolean canUseAbilityFor(String animal, String ability) {
        return switch (animal) {
            case "wolf" -> ability.equals("pounce");
            case "cat" -> ability.equals("focus");
            case "bee" -> ability.equals("hover");
            case "fox" -> ability.equals("escape");
            case "turtle" -> ability.equals("harden");
            case "horse" -> ability.equals("gallop");
            case "sheep" -> ability.equals("soften");
            case "ant" -> ability.equals("sting");
            case "owl" -> ability.equals("glide");
            case "coppergolem" -> ability.equals("discovery");
            default -> false;
        };
    }

    // -------------------- Helpers: safe potion, particles, sounds --------------------
    private void addPotionSafe(Player p, String name, int ticks, int amp) {
        PotionEffectType t = resolvePotionType(name);
        if (t != null) {
            try {
                p.addPotionEffect(new PotionEffect(t, ticks, amp, true, false));
            } catch (Throwable ignored) {}
        }
    }

    private PotionEffectType resolvePotionType(String name) {
        if (name == null) return null;
        String n = name.toUpperCase(Locale.ROOT).replace(' ', '_');
        Map<String, String> syn = Map.ofEntries(
                Map.entry("STRENGTH","INCREASE_DAMAGE"),
                Map.entry("JUMP","JUMP_BOOST"),
                Map.entr
