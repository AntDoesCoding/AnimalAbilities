package com.animalabilities;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
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

import java.util.*;

/**
 * Final corrected AnimalAbilities plugin class.
 * Uses PotionEffectType.getByName(...) with null checks (no valueOf).
 */
public class AnimalAbilities extends JavaPlugin implements Listener {

    private final Map<UUID, String> playerAnimal = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSavedAnimals();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AnimalAbilities enabled");
    }

    @Override
    public void onDisable() {
        saveAnimals();
        getLogger().info("AnimalAbilities disabled");
    }

    /* -------------------------
       Data persistence
       ------------------------- */
    private void loadSavedAnimals() {
        FileConfiguration cfg = getConfig();
        if (!cfg.isConfigurationSection("players")) return;
        for (String key : cfg.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID u = UUID.fromString(key);
                String a = cfg.getString("players." + key + ".animal", "");
                if (!a.isEmpty()) playerAnimal.put(u, a.toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {}
        }
    }

    private void saveAnimals() {
        FileConfiguration cfg = getConfig();
        cfg.set("players", null);
        for (Map.Entry<UUID, String> e : playerAnimal.entrySet()) {
            cfg.set("players." + e.getKey().toString() + ".animal", e.getValue());
        }
        saveConfig();
    }

    /* -------------------------
       Commands & GUI
       ------------------------- */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("chooseanimal")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
            openChooseGui(p);
            return true;
        }

        if (name.equals("resetanimal")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Use console: /resetanimal <player>"); return true; }
            if (!p.isOp()) { p.sendMessage(ChatColor.RED + "Only OPs can run this."); return true; }
            if (args.length != 1) { p.sendMessage(ChatColor.YELLOW + "Usage: /resetanimal <player>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { p.sendMessage(ChatColor.RED + "Player not found."); return true; }
            resetPlayerAnimal(target);
            p.sendMessage(ChatColor.GREEN + "Reset " + target.getName());
            return true;
        }

        // ability commands (exact names: pounce, focus, hover, escape, harden, gallop, soften, sting)
        if (name.equals("pounce") || name.equals("focus") || name.equals("hover") ||
            name.equals("escape") || name.equals("harden") || name.equals("gallop") ||
            name.equals("soften") || name.equals("sting")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
            useAbility(p, name);
            return true;
        }

        return false;
    }

    private void openChooseGui(Player p) {
        if (playerAnimal.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset.");
            return;
        }

        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Choose Your Animal");
        gui.setItem(0, createItem(Material.BONE, "Wolf", List.of("Strong and Brave!", "Strength I, Speed I")));
        gui.setItem(1, createItem(Material.STRING, "Cat", List.of("Agile and Alert!", "Jump Boost II, Night Vision")));
        gui.setItem(2, createItem(Material.HONEYCOMB, "Bee", List.of("Graceful in Air", "Slow Falling I, Jump Boost II")));
        gui.setItem(3, createItem(Material.SWEET_BERRIES, "Fox", List.of("Sneaky and Swift", "Speed II, Night Vision at night")));
        // SCUTE is present on modern APIs; fall back if not available
        Material turtleMat = Material.matchMaterial("SCUTE") != null ? Material.matchMaterial("SCUTE") : Material.SLIME_BALL;
        gui.setItem(4, createItem(turtleMat, "Turtle", List.of("Tough and Calm", "Water Breathing, Resistance I")));
        gui.setItem(5, createItem(Material.SADDLE, "Horse", List.of("Fast and Powerful", "Speed I, Jump Boost I")));
        gui.setItem(6, createItem(Material.WHITE_WOOL, "Sheep", List.of("Soft but Strong", "Resistance I, Jump Boost I")));
        Material antMat = Material.matchMaterial("SOUL_SOIL") != null ? Material.SOUL_SOIL : Material.COAL;
        gui.setItem(7, createItem(antMat, "Ant", List.of("Tiny but Mighty", "Haste, Strength I")));

        p.openInventory(gui);
    }

    private ItemStack createItem(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name);
            List<String> colored = new ArrayList<>();
            for (String s : lore) colored.add(ChatColor.WHITE + s);
            meta.setLore(colored);
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView().getTitle() == null) return;
        if (!e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Choose Your Animal")) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta()) { p.closeInventory(); return; }

        if (playerAnimal.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset.");
            p.closeInventory();
            return;
        }

        String disp = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
        Set<String> valid = Set.of("wolf","cat","bee","fox","turtle","horse","sheep","ant");
        if (!valid.contains(disp)) {
            p.sendMessage(ChatColor.RED + "Invalid selection.");
            p.closeInventory();
            return;
        }

        playerAnimal.put(p.getUniqueId(), disp);
        saveAnimals();
        applyPassive(p, disp);
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + ChatColor.AQUA + capitalize(disp) + ChatColor.GREEN + "!");
        spawnParticlesTimed(p, Particle.HAPPY_VILLAGER, 20, 2);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p.closeInventory();
    }

    /* -------------------------
       Reapply passives on join/respawn
       ------------------------- */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String a = playerAnimal.get(p.getUniqueId());
        if (a != null) applyPassive(p, a);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String a = playerAnimal.get(p.getUniqueId());
            if (a != null) applyPassive(p, a);
        }, 20L);
    }

    private void applyPassive(Player p, String animal) {
        // remove only plugin-style effects (safe: clear all and reapply)
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
        int permTicks = Integer.MAX_VALUE - 1000;

        switch (animal) {
            case "wolf":
                safeAddPotion(p, "INCREASE_DAMAGE", permTicks, 0); // Strength I
                safeAddPotion(p, "SPEED", permTicks, 0); // Speed I
                break;
            case "cat":
                safeAddPotion(p, "JUMP_BOOST", permTicks, 1); // Jump II
                safeAddPotion(p, "NIGHT_VISION", permTicks, 0);
                break;
            case "bee":
                safeAddPotion(p, "SLOW_FALLING", permTicks, 0);
                safeAddPotion(p, "JUMP_BOOST", permTicks, 1);
                break;
            case "fox":
                safeAddPotion(p, "SPEED", permTicks, 1);
                // night invisibility: schedule once
                scheduleFoxInvis(p);
                break;
            case "turtle":
                safeAddPotion(p, "WATER_BREATHING", permTicks, 0);
                safeAddPotion(p, "DAMAGE_RESISTANCE", permTicks, 0);
                break;
            case "horse":
                safeAddPotion(p, "SPEED", permTicks, 0);
                safeAddPotion(p, "JUMP_BOOST", permTicks, 0);
                break;
            case "sheep":
                safeAddPotion(p, "DAMAGE_RESISTANCE", permTicks, 0);
                safeAddPotion(p, "JUMP_BOOST", permTicks, 0);
                break;
            case "ant":
                safeAddPotion(p, "FAST_DIGGING", permTicks, 0);
                safeAddPotion(p, "INCREASE_DAMAGE", permTicks, 0);
                break;
        }
    }

    private void scheduleFoxInvis(Player p) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                long time = p.getWorld().getTime();
                boolean night = time >= 13000 || time <= 2300;
                if (night) safeAddPotion(p, "INVISIBILITY", Integer.MAX_VALUE - 1000, 0);
                else p.removePotionEffect(PotionEffectType.getByName("INVISIBILITY"));
                cancel();
            }
        }.runTaskLater(this, 10L);
    }

    private void safeAddPotion(Player p, String name, int durationTicks, int amp) {
        try {
            PotionEffectType type = PotionEffectType.getByName(name);
            if (type != null) p.addPotionEffect(new PotionEffect(type, durationTicks, amp, true, false));
        } catch (Throwable ignored) {}
    }

    /* -------------------------
       Abilities
       ------------------------- */
    private void useAbility(Player p, String ability) {
        String chosen = playerAnimal.get(p.getUniqueId());
        if (chosen == null) { p.sendMessage(ChatColor.RED + "You haven't chosen an animal yet."); return; }
        if (!canUseAbilityForAnimal(ability, chosen)) { p.sendMessage(ChatColor.RED + "Only " + capitalize(chosen) + "s can use that."); return; }

        // cooldown per player+ability
        Map<String, Long> pcs = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        long until = pcs.getOrDefault(ability, 0L);
        if (now < until) {
            p.sendMessage(ChatColor.YELLOW + "Ability on cooldown: " + ((until - now) / 1000) + "s");
            return;
        }

        // run ability and set cooldown
        runAbility(ability, p);
        pcs.put(ability, now + (getCooldownFor(ability) * 1000L));
    }

    private boolean canUseAbilityForAnimal(String ability, String animal) {
        return switch (ability) {
            case "pounce" -> animal.equals("wolf");
            case "focus" -> animal.equals("cat");
            case "hover" -> animal.equals("bee");
            case "escape" -> animal.equals("fox");
            case "harden" -> animal.equals("turtle");
            case "gallop" -> animal.equals("horse");
            case "soften" -> animal.equals("sheep");
            case "sting" -> animal.equals("ant");
            default -> false;
        };
    }

    private void runAbility(String ability, Player p) {
        switch (ability) {
            case "pounce":
                safeAddPotion(p, "JUMP_BOOST", 20 * 6, 1);
                safeAddPotion(p, "INCREASE_DAMAGE", 20 * 6, 1);
                spawnParticlesTimed(p, Particle.CAMPFIRE_COSY_SMOKE, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Pounce activated!");
                break;
            case "focus":
                safeAddPotion(p, "FAST_DIGGING", 20 * 8, 1);
                safeAddPotion(p, "SPEED", 20 * 8, 2);
                spawnParticlesTimed(p, Particle.EXPLOSION, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_CAT_PURR, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Focus activated!");
                break;
            case "hover":
                safeAddPotion(p, "LEVITATION", 20 * 5, 0);
                safeAddPotion(p, "DAMAGE_RESISTANCE", 20 * 6, 1);
                spawnParticlesTimed(p, Particle.END_ROD, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_BEE_LOOP, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Hover activated!");
                break;
            case "escape":
                safeAddPotion(p, "FAST_DIGGING", 20 * 10, 1);
                safeAddPotion(p, "SPEED", 20 * 10, 2);
                spawnParticlesTimed(p, Particle.FLAME, 40, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_FOX_SCREECH, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Escape activated!");
                break;
            case "harden":
                safeAddPotion(p, "DAMAGE_RESISTANCE", 20 * 8, 2);
                safeAddPotion(p, "SLOW", 20 * 6, 1);
                spawnParticlesTimed(p, Particle.SPLASH, 30, 5);
                p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Harden activated!");
                break;
            case "gallop":
                safeAddPotion(p, "SPEED", 20 * 10, 2);
                safeAddPotion(p, "JUMP_BOOST", 20 * 10, 1);
                spawnParticlesTimed(p, Particle.CLOUD, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_HORSE_GALLOP, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Gallop activated!");
                break;
            case "soften":
                safeAddPotion(p, "JUMP_BOOST", 20 * 8, 1);
                safeAddPotion(p, "REGENERATION", 20 * 8, 0);
                spawnParticlesTimed(p, Particle.HAPPY_VILLAGER, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_SHEEP_AMBIENT, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Soften activated!");
                break;
            case "sting":
                safeAddPotion(p, "INCREASE_DAMAGE", 20 * 6, 1);
                safeAddPotion(p, "SPEED", 20 * 6, 0);
                spawnParticlesTimed(p, Particle.SOUL, 30, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_BEE_STING, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Sting activated!");
                break;
            default:
                p.sendMessage(ChatColor.RED + "Unknown ability.");
        }
    }

    private int getCooldownFor(String ability) {
        return switch (ability) {
            case "pounce" -> 6 * 60;
            case "focus" -> 7 * 60;
            case "hover" -> 4 * 60;
            case "escape" -> 10 * 60;
            case "harden" -> 8 * 60;
            case "gallop" -> 10 * 60;
            case "soften" -> 8 * 60;
            case "sting" -> 5 * 60;
            default -> 5 * 60;
        };
    }

    private void spawnParticlesTimed(Player p, Particle particle, int count, int seconds) {
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                Location loc = p.getLocation().clone().add(0, 1.0, 0);
                p.getWorld().spawnParticle(particle, loc, count, 0.5, 0.8, 0.5, 0.02);
                ticks += 5;
                if (ticks >= seconds * 20) cancel();
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    /* -------------------------
       Admin reset helper
       ------------------------- */
    private void resetPlayerAnimal(Player target) {
        if (playerAnimal.containsKey(target.getUniqueId())) {
            playerAnimal.remove(target.getUniqueId());
            saveAnimals();
        }
        // remove potion effects
        for (PotionEffect pe : new ArrayList<>(target.getActivePotionEffects())) {
            target.removePotionEffect(pe.getType());
        }
        target.sendMessage(ChatColor.RED + "Your animal has been reset by an admin. Use /chooseanimal to choose again.");
    }

    /* -------------------------
       Utils
       ------------------------- */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
            }
