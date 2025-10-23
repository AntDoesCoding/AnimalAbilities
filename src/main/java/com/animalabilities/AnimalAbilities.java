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
 * AnimalAbilities - final version
 */
public class AnimalAbilities extends JavaPlugin implements Listener {

    // player uuid -> chosen animal (wolf,cat,bee,fox,turtle,horse,sheep,ant)
    private final Map<UUID, String> chosen = new HashMap<>();

    // player uuid -> (ability -> expiryMillis)
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    private final Map<String, AbilityMeta> abilityMeta = new HashMap<>();

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
        // load saved choices
        loadChoices();

        // register events and commands via plugin.yml
        getServer().getPluginManager().registerEvents(this, this);

        // init abilities meta (cooldowns in seconds, duration of effects in seconds)
        abilityMeta.put("pounce", new AbilityMeta("wolf", 6 * 60, 8));       // Wolf: Jump I + Strength II
        abilityMeta.put("focus", new AbilityMeta("cat", 7 * 60, 10));       // Cat: Haste + Speed II
        abilityMeta.put("hover", new AbilityMeta("bee", 4 * 60, 6));        // Bee: Levitation + Resistance II
        abilityMeta.put("escape", new AbilityMeta("fox", 10 * 60, 10));     // Fox: Speed III + Haste
        abilityMeta.put("harden", new AbilityMeta("turtle", 8 * 60, 6));    // Turtle: Resistance III + Slowness II
        abilityMeta.put("gallop", new AbilityMeta("horse", 10 * 60, 8));    // Horse: Speed II + Jump II
        abilityMeta.put("soften", new AbilityMeta("sheep", 8 * 60, 8));     // Sheep: Jump II + Regeneration I
        abilityMeta.put("sting", new AbilityMeta("ant", 5 * 60, 6));        // Ant: Strength II + Speed I

        getLogger().info("AnimalAbilities enabled.");
    }

    @Override
    public void onDisable() {
        saveChoices();
        getLogger().info("AnimalAbilities disabled.");
    }

    // ------------------ persistence ------------------

    private void loadChoices() {
        FileConfiguration cfg = getConfig();
        if (!cfg.contains("players")) return;
        for (String key : cfg.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID u = UUID.fromString(key);
                String animal = cfg.getString("players." + key + ".animal", "");
                if (!animal.isEmpty()) chosen.put(u, animal.toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {}
        }
        getLogger().info("Loaded " + chosen.size() + " player choices.");
    }

    private void saveChoices() {
        FileConfiguration cfg = getConfig();
        cfg.set("players", null);
        for (Map.Entry<UUID, String> e : chosen.entrySet()) {
            cfg.set("players." + e.getKey().toString() + ".animal", e.getValue());
        }
        saveConfig();
    }

    // ------------------ commands ------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("chooseanimal")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only players can choose animals.");
                return true;
            }
            openChooseGui(p);
            return true;
        }

        if (cmd.equals("resetanimal")) {
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /resetanimal <player>");
                return true;
            }
            if (sender instanceof Player sp && !sp.isOp()) {
                sender.sendMessage(ChatColor.RED + "Only OPs can run this command.");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            chosen.remove(target.getUniqueId());
            saveChoices();
            clearAllEffects(target);
            target.sendMessage(ChatColor.YELLOW + "Your animal choice was reset by an admin.");
            sender.sendMessage(ChatColor.GREEN + "Reset " + target.getName());
            return true;
        }

        // ability commands (use plugin.yml to define them)
        if (abilityMeta.containsKey(cmd)) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only players can use abilities.");
                return true;
            }
            handleAbility(p, cmd);
            return true;
        }

        return false;
    }

    // ------------------ GUI ------------------

    private void openChooseGui(Player p) {
        if (chosen.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset.");
            return;
        }
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Choose Your Animal");

        gui.setItem(0, createItem(Material.BONE, "Wolf", "Strong and Brave!", "Strength I, Speed I"));
        gui.setItem(1, createItem(Material.STRING, "Cat", "Agile and Alert!", "Jump Boost II, Night Vision"));
        gui.setItem(2, createItem(Material.HONEYCOMB, "Bee", "Graceful in the air!", "Slow Falling, Jump Boost II"));
        gui.setItem(3, createItem(Material.SWEET_BERRIES, "Fox", "Sneaky and Swift!", "Speed II, Invisibility at night"));
        gui.setItem(4, createItem(Material.SCUTE, "Turtle", "Calm and tough!", "Water Breathing, Resistance I"));
        gui.setItem(5, createItem(Material.SADDLE, "Horse", "Fast and powerful!", "Speed I, Jump Boost I"));
        gui.setItem(6, createItem(Material.WHITE_WOOL, "Sheep", "Soft yet resilient!", "Resistance I, Jump Boost I"));
        gui.setItem(7, createItem(Material.ANVIL, "Ant", "Tiny but mighty!", "Haste, Strength I"));

        p.openInventory(gui);
    }

    private ItemStack createItem(Material mat, String name, String desc, String effects) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        meta.setLore(Arrays.asList(ChatColor.WHITE + desc, ChatColor.GREEN + effects));
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
        String name = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
        Set<String> allowed = Set.of("wolf","cat","bee","fox","turtle","horse","sheep","ant");
        if (!allowed.contains(name)) {
            p.sendMessage(ChatColor.RED + "Invalid selection.");
            p.closeInventory();
            return;
        }
        chosen.put(p.getUniqueId(), name);
        saveChoices();
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + ChatColor.AQUA + capitalize(name) + ChatColor.GREEN + "!");
        applyPassive(p);
        p.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, p.getLocation().add(0,1,0), 20, 0.4,0.6,0.4, 0.02);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1f, 1f);
        p.closeInventory();
    }

    // ------------------ passives & reapply ------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (chosen.containsKey(p.getUniqueId())) applyPassive(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (chosen.containsKey(p.getUniqueId())) applyPassive(p);
        }, 20L);
    }

    private void applyPassive(Player p) {
        // clear current potion effects to avoid stacking
        clearAllEffects(p);

        String animal = chosen.get(p.getUniqueId());
        if (animal == null) return;

        int perm = Integer.MAX_VALUE - 1000;

        switch (animal) {
            case "wolf":
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, perm, 0)); // Strength I
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, perm, 0));
                break;
            case "cat":
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, perm, 1)); // Jump II
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, perm, 0));
                break;
            case "bee":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, perm, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, perm, 1));
                break;
            case "fox":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, perm, 1)); // Speed II
                // invis at night only - simple check now
                scheduleFoxInvisibilityCheck(p);
                break;
            case "turtle":
                p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, perm, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, perm, 0));
                break;
            case "horse":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, perm, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, perm, 0));
                break;
            case "sheep":
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, perm, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, perm, 0));
                break;
            case "ant":
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, perm, 0)); // Haste I
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, perm, 0)); // Strength I
                break;
        }
    }

    private void scheduleFoxInvisibilityCheck(Player p) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                long time = p.getWorld().getTime();
                boolean night = time >= 13000 || time <= 2300;
                if (night) p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE - 1000, 0));
                else p.removePotionEffect(PotionEffectType.INVISIBILITY);
                cancel();
            }
        }.runTaskLater(this, 10L);
    }

    private void clearAllEffects(Player p) {
        for (PotionEffect pe : p.getActivePotionEffects()) {
            p.removePotionEffect(pe.getType());
        }
    }

    // ------------------ ability handling ------------------

    private void handleAbility(Player p, String abilityCmd) {
        AbilityMeta meta = abilityMeta.get(abilityCmd);
        if (meta == null) return;

        String playerChoice = chosen.get(p.getUniqueId());
        if (playerChoice == null) {
            p.sendMessage(ChatColor.RED + "You have not chosen an animal yet. Use /chooseanimal");
            return;
        }
        if (!playerChoice.equals(meta.animal)) {
            p.sendMessage(ChatColor.RED + "Only " + capitalize(meta.animal) + "s can use this ability.");
            return;
        }

        Map<String, Long> userCd = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        long until = userCd.getOrDefault(abilityCmd, 0L);
        if (now < until) {
            long left = (until - now) / 1000;
            p.sendMessage(ChatColor.YELLOW + "Ability on cooldown: " + left + "s");
            return;
        }

        // Apply ability effects, particles, sound
        runAbility(abilityCmd, p, meta.durationSec);

        // set cooldown
        userCd.put(abilityCmd, now + meta.cooldownSec * 1000L);
    }

    private void runAbility(String abilityCmd, Player p, int durationSeconds) {
        Location loc = p.getLocation().clone();
        switch (abilityCmd) {
            case "pounce": // Wolf: Jump I, Strength II
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSeconds * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, durationSeconds * 20, 1));
                spawnParticlesTimed(p, Particle.SMOKE_LARGE, 5);
                p.playSound(loc, Sound.ENTITY_WOLF_GROWL, SoundCategory.PLAYERS, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Pounce activated!");
                break;

            case "focus": // Cat: Haste, Speed II
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, durationSeconds * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, 1));
                spawnParticlesTimed(p, Particle.EXPLOSION_NORMAL, 5);
                p.playSound(loc, Sound.ENTITY_CAT_PURR, SoundCategory.PLAYERS, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Focus activated!");
                break;

            case "hover": // Bee: Levitation, Resistance II
                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, durationSeconds * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, durationSeconds * 20, 1));
                spawnParticlesTimed(p, Particle.CLOUD, 5);
                p.playSound(loc, Sound.ENTITY_BEE_LOOP, SoundCategory.PLAYERS, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Hover activated!");
                break;

            case "escape": // Fox: Speed III, Haste
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, durationSeconds * 20, 0));
                spawnParticlesTimed(p, Particle.FLAME, 5);
                p.playSound(loc, Sound.ENTITY_FOX_SCREECH, SoundCategory.PLAYERS, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Escape activated!");
                break;

            case "harden": // Turtle: Resistance III, Slowness II
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, durationSeconds * 20, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, durationSeconds * 20, 1));
                spawnParticlesTimed(p, Particle.WATER_SPLASH, 5);
                p.playSound(loc, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Harden activated!");
                break;

            case "gallop": // Horse: Speed II, Jump II
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSeconds * 20, 1));
                spawnParticlesTimed(p, Particle.SMOKE_LARGE, 5);
                p.playSound(loc, Sound.ENTITY_HORSE_GALLOP, SoundCategory.PLAYERS, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Gallop activated!");
                break;

            case "soften": // Sheep: Jump II, Regeneration I
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSeconds * 20, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, durationSeconds * 20, 0));
                spawnParticlesTimed(p, Particle.VILLAGER_HAPPY, 5);
                p.playSound(loc, Sound.ENTITY_SHEEP_AMBIENT, SoundCategory.PLAYERS, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Soften activated!");
                break;

            case "sting": // Ant: Strength II, Speed I
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, durationSeconds * 20, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, 0));
                spawnParticlesTimed(p, Particle.SOUL, 5);
                p.playSound(loc, Sound.ENTITY_BEE_STING, SoundCategory.PLAYERS, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "Sting activated!");
                break;

            default:
                p.sendMessage(ChatColor.RED + "Unknown ability.");
                break;
        }
    }

    /**
     * Spawns repeated particle bursts around the player for 'seconds' seconds.
     */
    private void spawnParticlesTimed(Player p, Particle particle, int seconds) {
        new BukkitRunnable() {
            int ticks = 0;
            final int interval = 5; // run every 5 ticks
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                Location c = p.getLocation().clone().add(0, 1.0, 0);
                p.getWorld().spawnParticle(particle, c, 40, 0.6, 0.6, 0.6, 0.02);
                ticks += interval;
                if (ticks >= seconds * 20) cancel();
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    // ------------------ utilities ------------------

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
                    }
