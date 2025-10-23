package com.animalabilities;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
 * Final patched plugin class - balanced braces, full features:
 * - /chooseanimal GUI (8 animals)
 * - Passive effects per animal
 * - Ability commands (per animal only)
 * - Cooldowns, particle visuals (5s), sounds
 * - Save choices to config, reapply on join/respawn
 * - /resetanimal <player> (OP or console)
 */
public class AnimalAbilities extends JavaPlugin implements Listener {

    // map player -> animal (lowercase)
    private final Map<UUID, String> chosen = new HashMap<>();

    // map player -> (ability -> expiryMillis)
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    // ability metadata: command -> (animal, cooldownSeconds, durationSeconds)
    private final Map<String, AbilityMeta> abilities = new HashMap<>();

    private static class AbilityMeta {
        final String animal;
        final int cooldownSec;
        final int durationSec;
        AbilityMeta(String animal, int cooldownSec, int durationSec) {
            this.animal = animal;
            this.cooldownSec = cooldownSec;
            this.durationSec = durationSec;
        }
    }

    @Override
    public void onEnable() {
        // init abilities
        initAbilities();

        // load saved data from config
        loadSavedChoices();

        // register events
        getServer().getPluginManager().registerEvents(this, this);

        // ensure commands exist in plugin.yml (they must)
        // We'll handle command execution in onCommand.

        getLogger().info("AnimalAbilities enabled.");
    }

    @Override
    public void onDisable() {
        saveAllChoices();
        getLogger().info("AnimalAbilities disabled.");
    }

    private void initAbilities() {
        // (command, animal, cooldown seconds, duration seconds)
        abilities.put("pounce", new AbilityMeta("wolf", 6 * 60, 8));      // Jump I, Strength II
        abilities.put("focus", new AbilityMeta("cat", 7 * 60, 10));      // Haste, Speed II
        abilities.put("hover", new AbilityMeta("bee", 4 * 60, 6));       // Levitation, Resistance II
        abilities.put("escape", new AbilityMeta("fox", 10 * 60, 10));    // Speed III, Haste
        abilities.put("harden", new AbilityMeta("turtle", 8 * 60, 6));   // Resistance III, Slowness II
        abilities.put("gallop", new AbilityMeta("horse", 10 * 60, 8));   // Speed II, Jump II
        abilities.put("soften", new AbilityMeta("sheep", 8 * 60, 8));    // Jump II, Regen I
        abilities.put("sting", new AbilityMeta("ant", 5 * 60, 6));       // Strength II, Speed I
    }

    // ------------------ persistence ------------------
    private void loadSavedChoices() {
        FileConfiguration cfg = getConfig();
        if (!cfg.contains("players")) return;
        for (String key : cfg.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID u = UUID.fromString(key);
                String a = cfg.getString("players." + key + ".animal", "");
                if (!a.isEmpty()) chosen.put(u, a.toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {}
        }
        getLogger().info("Loaded " + chosen.size() + " player choices.");
    }

    private void saveAllChoices() {
        FileConfiguration cfg = getConfig();
        cfg.set("players", null);
        for (Map.Entry<UUID, String> e : chosen.entrySet()) {
            cfg.set("players." + e.getKey().toString() + ".animal", e.getValue());
        }
        saveConfig();
    }

    // ------------------ GUI / chooser ------------------
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        // resetanimal handled here as it's admin-only
        if (cmdName.equals("resetanimal")) {
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /resetanimal <player>");
                return true;
            }
            // allow console or OP
            if (sender instanceof Player p && !p.isOp()) {
                sender.sendMessage(ChatColor.RED + "Only operators may run this command.");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            chosen.remove(target.getUniqueId());
            saveAllChoices();
            // clear effects we applied
            clearPluginEffects(target);
            target.sendMessage(ChatColor.YELLOW + "Your animal choice was reset by an admin.");
            sender.sendMessage(ChatColor.GREEN + "Reset " + target.getName());
            return true;
        }

        // chooseanimal opens GUI
        if (cmdName.equals("chooseanimal")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("This command is for players only.");
                return true;
            }
            openChooseGui(p);
            return true;
        }

        // Ability commands (one-per-command mapping)
        if (abilities.containsKey(cmdName)) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only players may use abilities.");
                return true;
            }
            handleAbilityCommand(p, cmdName);
            return true;
        }

        return false;
    }

    private void openChooseGui(Player p) {
        if (chosen.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset.");
            return;
        }
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Choose Your Animal");

        gui.setItem(0, createItem(Material.BONE, "Wolf", "Strong and Brave!", "Strength I, Speed I"));
        gui.setItem(1, createItem(Material.STRING, "Cat", "Agile and Alert!", "Jump Boost II, Night Vision"));
        gui.setItem(2, createItem(Material.HONEYCOMB, "Bee", "Light and airborne!", "Slow Falling, Jump Boost II"));
        gui.setItem(3, createItem(Material.SWEET_BERRIES, "Fox", "Quick and sly!", "Speed II, Invisibility at night"));
        gui.setItem(4, createItem(Material.SCUTE, "Turtle", "Durable swimmer!", "Water Breathing, Resistance I"));
        gui.setItem(5, createItem(Material.SADDLE, "Horse", "Swift runner!", "Speed I, Jump Boost I"));
        gui.setItem(6, createItem(Material.WHITE_WOOL, "Sheep", "Soft but tough!", "Resistance I, Jump Boost I"));
        gui.setItem(7, createItem(Material.ANVIL, "Ant", "Tiny powerhouse!", "Haste, Strength I"));

        p.openInventory(gui);
    }

    private ItemStack createItem(Material mat, String name, String line1, String line2) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + name);
        meta.setLore(Arrays.asList(ChatColor.WHITE + line1, ChatColor.GRAY + line2));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView().getTitle() == null) return;
        if (!e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Choose Your Animal")) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getCurrentItem() == null || e.getCurrentItem().getItemMeta() == null) {
            p.closeInventory();
            return;
        }
        String display = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
        Set<String> allowed = Set.of("wolf","cat","bee","fox","turtle","horse","sheep","ant");
        if (!allowed.contains(display)) {
            p.sendMessage(ChatColor.RED + "Invalid choice.");
            p.closeInventory();
            return;
        }
        // lock and save
        chosen.put(p.getUniqueId(), display);
        saveAllChoices();
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + ChatColor.AQUA + capitalize(display) + ChatColor.GREEN + "!");
        applyPassiveEffects(p);
        // small visual & sound
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0,1,0), 20, 0.5,0.5,0.5, 0.02);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1f, 1f);
        p.closeInventory();
    }

    // ------------------ passives ------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (chosen.containsKey(p.getUniqueId())) applyPassiveEffects(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (chosen.containsKey(p.getUniqueId())) applyPassiveEffects(p);
        }, 20L);
    }

    private void applyPassiveEffects(Player p) {
        // remove all plugin-applied effects (safe)
        clearPluginEffects(p);

        String animal = chosen.get(p.getUniqueId());
        if (animal == null) return;
        int permDuration = Integer.MAX_VALUE - 1000;

        switch (animal) {
            case "wolf":
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, permDuration, 0)); // Strength I
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, permDuration, 0));
                break;
            case "cat":
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, permDuration, 1)); // Jump II
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, permDuration, 0));
                break;
            case "bee":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, permDuration, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, permDuration, 1));
                break;
            case "fox":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, permDuration, 1)); // Speed II
                // invisibility at night; schedule check task
                scheduleFoxNightCheck(p);
                break;
            case "turtle":
                p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, permDuration, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, permDuration, 0));
                break;
            case "horse":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, permDuration, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, permDuration, 0));
                break;
            case "sheep":
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, permDuration, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, permDuration, 0));
                break;
            case "ant":
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, permDuration, 0)); // Haste I
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, permDuration, 0)); // Strength I
                break;
        }
    }

    private void scheduleFoxNightCheck(Player p) {
        // add invisibility if it's night in player's world, remove if day; run once after short delay
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            long time = p.getWorld().getTime();
            boolean night = (time >= 13000 || time <= 2300);
            if (night) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE - 1000, 0));
            } else {
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
        }, 10L);
    }

    private void clearPluginEffects(Player p) {
        // remove all effects (safe approach). Optionally be more selective.
        for (PotionEffect pe : p.getActivePotionEffects()) {
            p.removePotionEffect(pe.getType());
        }
    }

    // ------------------ abilities ------------------
    private void handleAbilityCommand(Player p, String cmdName) {
        AbilityMeta meta = abilities.get(cmdName);
        if (meta == null) return;
        String playerAnimal = chosen.get(p.getUniqueId());
        if (playerAnimal == null) {
            p.sendMessage(ChatColor.RED + "You have not chosen an animal yet. Use /chooseanimal");
            return;
        }
        if (!playerAnimal.equals(meta.animal)) {
            p.sendMessage(ChatColor.RED + "Only " + capitalize(meta.animal) + "s can use this ability.");
            return;
        }
        // cooldown map
        Map<String, Long> userCd = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        long expiry = userCd.getOrDefault(cmdName, 0L);
        if (now < expiry) {
            long left = (expiry - now) / 1000;
            p.sendMessage(ChatColor.YELLOW + "Ability on cooldown: " + left + "s");
            return;
        }

        // run ability effects & visuals
        runAbility(cmdName, p, meta.durationSec);

        // set cooldown
        userCd.put(cmdName, now + meta.cooldownSec * 1000L);
        p.sendMessage(ChatColor.GREEN + "Activated " + ChatColor.AQUA + cmdName + ChatColor.GREEN + ". Cooldown: " + meta.cooldownSec + "s");
    }

    private void runAbility(String cmd, Player p, int durationSeconds) {
        // each ability: apply potion effects and spawn 5-second visuals & play sound
        Location loc = p.getLocation().clone();
        switch (cmd) {
            case "pounce":
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSeconds * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, durationSeconds * 20, 1));
                spawnParticlesForSeconds(p, Particle.SMOKE_LARGE, 5);
                p.playSound(loc, Sound.ENTITY_WOLF_GROWL, SoundCategory.PLAYERS, 1f, 1.0f);
                break;

            case "focus":
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, durationSeconds * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, 1));
                spawnParticlesForSeconds(p, Particle.EXPLOSION_NORMAL, 5);
                p.playSound(loc, Sound.ENTITY_CAT_PURR, SoundCategory.PLAYERS, 1f, 1.0f);
                break;

            case "hover":
                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, durationSeconds * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, durationSeconds * 20, 1));
                spawnParticlesForSeconds(p, Particle.CLOUD, 5);
                p.playSound(loc, Sound.ENTITY_BEE_LOOP, SoundCategory.PLAYERS, 1f, 1.0f);
                break;

            case "escape":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, durationSeconds * 20, 0));
                spawnParticlesForSeconds(p, Particle.FLAME, 5);
                p.playSound(loc, Sound.ENTITY_FOX_SCREECH, SoundCategory.PLAYERS, 1f, 1.0f);
                break;

            case "harden":
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, durationSeconds * 20, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, durationSeconds * 20, 1));
                spawnParticlesForSeconds(p, Particle.WATER_SPLASH, 5);
                p.playSound(loc, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 1.0f);
                break;

            case "gallop":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSeconds * 20, 1));
                spawnParticlesForSeconds(p, Particle.SMOKE_LARGE, 5);
                p.playSound(loc, Sound.ENTITY_HORSE_GALLOP, SoundCategory.PLAYERS, 1f, 1.0f);
                break;

            case "soften":
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSeconds * 20, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, durationSeconds * 20, 0));
                spawnParticlesForSeconds(p, Particle.VILLAGER_HAPPY, 5);
                p.playSound(loc, Sound.ENTITY_SHEEP_AMBIENT, SoundCategory.PLAYERS, 1f, 1.0f);
                break;

            case "sting":
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, durationSeconds * 20, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, 0));
                spawnParticlesForSeconds(p, Particle.SOUL, 5);
                p.playSound(loc, Sound.ENTITY_BEE_STING, SoundCategory.PLAYERS, 1f, 1.0f);
                break;

            default:
                p.sendMessage(ChatColor.RED + "Unknown ability.");
                break;
        }
    }

    /**
     * Spawn a repeating particle burst around player for 'seconds' seconds.
     */
    private void spawnParticlesForSeconds(Player p, Particle particle, int seconds) {
        final int totalTicks = seconds * 20;
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                Location c = p.getLocation().clone().add(0, 1, 0);
                p.getWorld().spawnParticle(particle, c, 40, 0.6, 0.8, 0.6, 0.02);
                ticks += 5;
                if (ticks >= totalTicks) cancel();
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    // ------------------ Utils ------------------
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

} // END CLASS
