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
 * AnimalAbilities - Paper 1.21.x plugin
 *
 * Features:
 * - /chooseanimal opens a GUI (10 animals)
 * - Player is locked into a single animal choice (saved to data.yml)
 * - Passive effects applied and reapplied on join/respawn
 * - Abilities (one-word commands) usable only by players with that animal
 * - Per-player per-ability cooldowns (20–25 minutes range)
 * - Visual particle effects and sounds for abilities (5s visuals)
 * - /resetanimal <player> for OPs (or console) to reset another player's animal
 *
 * Drop into src/main/java/com/animalabilities/AnimalAbilities.java
 */
public class AnimalAbilities extends JavaPlugin implements Listener {

    // saved player -> animal
    private final Map<UUID, String> chosen = new HashMap<>();

    // cooldowns: player -> ability -> untilMillis
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    private File dataFile;
    private YamlConfiguration dataYml;

    @Override
    public void onEnable() {
        // prepare data file
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
        Bukkit.getPluginManager().registerEvents(this, this);

        // No dynamic command registration required if plugin.yml defines commands.
        getLogger().info("AnimalAbilities enabled. Loaded " + chosen.size() + " choices.");
    }

    @Override
    public void onDisable() {
        saveChoices();
        getLogger().info("AnimalAbilities disabled, saved choices.");
    }

    // ---------- Persistence ----------
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
    }

    private void saveChoices() {
        if (dataYml == null) dataYml = new YamlConfiguration();
        dataYml.set("players", null); // clear old
        for (Map.Entry<UUID, String> e : chosen.entrySet()) {
            dataYml.set("players." + e.getKey().toString() + ".animal", e.getValue());
        }
        try {
            dataYml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Failed to save data.yml: " + ex.getMessage());
        }
    }

    // ---------- Commands & GUI ----------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("chooseanimal")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Only players can run this."); return true; }
            openChooseGui((Player) sender);
            return true;
        }

        if (cmd.equals("resetanimal")) {
            // allow console or OPs to reset another player's animal
            if (args.length != 1) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /resetanimal <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found or offline.");
                return true;
            }
            if (sender instanceof Player) {
                Player p = (Player) sender;
                if (!p.isOp()) {
                    p.sendMessage(ChatColor.RED + "Only operators can use this command.");
                    return true;
                }
            }
            resetPlayer(target);
            sender.sendMessage(ChatColor.GREEN + "Reset " + target.getName() + "'s animal.");
            return true;
        }

        // ability commands (one-word): pounce, focus, hover, escape, harden, gallop, soften, sting, glide, discovery
        List<String> abilities = Arrays.asList("pounce","focus","hover","escape","harden","gallop","soften","sting","glide","discovery");
        if (abilities.contains(cmd)) {
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

        // 2 rows (18) to fit 10 animals, some spacing
        Inventory inv = Bukkit.createInventory(null, 18, ChatColor.DARK_GREEN + "Choose Your Animal");

        inv.setItem(0, makeItem(Material.BONE, "Wolf", "Strong and brave.", "Strength I, Speed I"));
        inv.setItem(1, makeItem(Material.STRING, "Cat", "Agile and alert.", "Jump Boost II, Night Vision"));
        inv.setItem(2, makeItem(Material.HONEYCOMB, "Bee", "Airborne and light.", "Levitation, Speed III"));
        inv.setItem(3, makeItem(Material.SWEET_BERRIES, "Fox", "Sneaky and swift.", "Speed II, Night Invisibility"));
        inv.setItem(4, makeItem(safeMaterial("SCUTE"), "Turtle", "Slow but hardy.", "Water Breathing, Resistance I"));
        inv.setItem(5, makeItem(Material.SADDLE, "Horse", "Fast and strong.", "Speed I, Jump Boost I"));
        inv.setItem(6, makeItem(Material.WHITE_WOOL, "Sheep", "Soft protector.", "Resistance I, Jump Boost I"));
        inv.setItem(7, makeItem(safeMaterial("SOUL_SOIL"), "Ant", "Tiny but mighty.", "Haste, Strength I"));
        inv.setItem(9, makeItem(Material.FEATHER, "Owl", "Silent nocturnal flyer.", "Night Vision, Slow Falling, Jump II"));
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

    private Material safeMaterial(String name) {
        if (name == null) return Material.BARRIER;
        Material m = Material.matchMaterial(name);
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

        Set<String> valid = new HashSet<>(Arrays.asList(
                "wolf","cat","bee","fox","turtle","horse","sheep","ant","owl","coppergolem"
        ));
        // handle alt display names (CopperGolem -> coppergolem)
        disp = disp.replace(" ", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (!valid.contains(disp)) { p.sendMessage(ChatColor.RED + "Invalid selection."); p.closeInventory(); return; }

        // lock choice (cannot choose again)
        if (chosen.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset it.");
            p.closeInventory();
            return;
        }

        chosen.put(p.getUniqueId(), disp);
        saveChoices();
        applyPassiveToPlayer(p, disp);
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + ChatColor.AQUA + capitalize(disp) + ChatColor.GREEN + "!");
        try { p.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, p.getLocation().add(0,1,0), 20); } catch (Throwable ignored) {}
        try { p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f); } catch (Throwable ignored) {}
        p.closeInventory();
    }

    // ---------- Reapply on join/respawn ----------
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

    // ---------- Passive application ----------
    private void applyPassiveToPlayer(Player p, String animal) {
        // remove effects applied by plugin (safe approach: remove all then reapply)
        for (PotionEffect pe : p.getActivePotionEffects()) {
            try { p.removePotionEffect(pe.getType()); } catch (Throwable ignored) {}
        }

        int permTicks = Integer.MAX_VALUE - 1000;

        switch (animal) {
            case "wolf":
                addPotionSafe(p, "INCREASE_DAMAGE", permTicks, 0); // Strength I (amp 0)
                addPotionSafe(p, "SPEED", permTicks, 0); // Speed I
                break;
            case "cat":
                addPotionSafe(p, "JUMP_BOOST", permTicks, 1); // Jump II (amp 1)
                addPotionSafe(p, "NIGHT_VISION", permTicks, 0);
                break;
            case "bee":
                addPotionSafe(p, "LEVITATION", permTicks, 0); // user requested levitation passive for bee
                addPotionSafe(p, "SPEED", permTicks, 2); // Speed III (amp 2)
                break;
            case "fox":
                addPotionSafe(p, "SPEED", permTicks, 1); // Speed II
                scheduleFoxInvisibility(p);
                break;
            case "turtle":
                addPotionSafe(p, "WATER_BREATHING", permTicks, 0);
                addPotionSafe(p, "DAMAGE_RESISTANCE", permTicks, 0); // Resistance I
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
                addPotionSafe(p, "FAST_DIGGING", permTicks, 0); // Haste
                addPotionSafe(p, "INCREASE_DAMAGE", permTicks, 0); // Strength I
                break;
            case "owl":
                addPotionSafe(p, "NIGHT_VISION", permTicks, 0);
                addPotionSafe(p, "SLOW_FALLING", permTicks, 0);
                addPotionSafe(p, "JUMP_BOOST", permTicks, 1); // Jump II (amp 1)
                addPotionSafe(p, "SPEED", permTicks, 0); // speed 1
                break;
            case "coppergolem":
                addPotionSafe(p, "SPEED", permTicks, 0);
                addPotionSafe(p, "FAST_DIGGING", permTicks, 0); // Haste
                break;
            default:
                break;
        }
    }

    private void scheduleFoxInvisibility(Player p) {
        // check world time once and apply invis if night; players will get invis when their passive reapplies
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
        }.runTaskLater(this, 5L);
    }

    // ---------- Abilities ----------
    private void useAbility(Player p, String cmd) {
        String animal = chosen.get(p.getUniqueId());
        if (animal == null) {
            p.sendMessage(ChatColor.RED + "You have not chosen an animal yet. Use /chooseanimal.");
            return;
        }
        if (!canUseAbilityFor(animal, cmd)) {
            p.sendMessage(ChatColor.RED + "Only " + capitalize(animal) + " can use this ability.");
            return;
        }

        Map<String, Long> pc = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        long until = pc.getOrDefault(cmd, 0L);
        if (now < until) {
            long left = (until - now) / 1000;
            p.sendMessage(ChatColor.YELLOW + "Ability on cooldown: " + left + "s");
            return;
        }

        // default durations and cooldowns (seconds)
        int durationSec = 8;
        int cooldownSec = 20 * 60; // default 20 minutes

        switch (cmd) {
            // Wolf: Pounce -> Jump boost 1 + Strength 2 (strength amplifier 1 -> level 2)
            case "pounce":
                durationSec = 10;
                cooldownSec = 22 * 60;
                addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 0); // Jump I
                addPotionSafe(p, "INCREASE_DAMAGE", durationSec * 20, 1); // Strength II
                spawnParticlesTimed(p, Particle.SMOKE_LARGE, 40, 5);
                playSafeSound(p, Sound.ENTITY_WOLF_GROWL);
                p.sendMessage(ChatColor.GREEN + "Pounce activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // Cat: Focus -> Haste + Speed 2
            case "focus":
                durationSec = 10;
                cooldownSec = 23 * 60;
                addPotionSafe(p, "FAST_DIGGING", durationSec * 20, 1); // Haste II
                addPotionSafe(p, "SPEED", durationSec * 20, 1); // Speed II
                spawnParticlesTimed(p, Particle.EXPLOSION_NORMAL, 45, 5);
                playSafeSound(p, Sound.ENTITY_CAT_PURR);
                p.sendMessage(ChatColor.GREEN + "Focus activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // Bee: Hover -> Levitation + Resistance 2; user requested levitation + speed 3 passive but ability is levitation+resistance
            case "hover":
                durationSec = 8;
                cooldownSec = 24 * 60;
                addPotionSafe(p, "LEVITATION", durationSec * 20, 0);
                addPotionSafe(p, "DAMAGE_RESISTANCE", durationSec * 20, 1); // Resistance II (amp 1)
                spawnParticlesTimed(p, Particle.HONEY, 40, 5);
                playSafeSound(p, Sound.ENTITY_BEE_LOOP);
                p.sendMessage(ChatColor.GREEN + "Hover activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // Fox: Escape -> Speed 3 + Haste
            case "escape":
                durationSec = 10;
                cooldownSec = 25 * 60;
                addPotionSafe(p, "SPEED", durationSec * 20, 2); // Speed III (amp 2)
                addPotionSafe(p, "FAST_DIGGING", durationSec * 20, 1);
                spawnParticlesTimed(p, Particle.FLAME, 45, 5);
                playSafeSound(p, Sound.ENTITY_FOX_SCREECH);
                p.sendMessage(ChatColor.GREEN + "Escape activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // Turtle: Harden -> Resistance 3 + Slowness 2
            case "harden":
                durationSec = 9;
                cooldownSec = 22 * 60;
                addPotionSafe(p, "DAMAGE_RESISTANCE", durationSec * 20, 2); // Resistance III (amp2)
                addPotionSafe(p, "SLOWNESS", durationSec * 20, 1); // Slowness II
                spawnParticlesTimed(p, Particle.WATER_SPLASH, 40, 5);
                playSafeSound(p, Sound.ITEM_SHIELD_BLOCK);
                p.sendMessage(ChatColor.GREEN + "Harden activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // Horse: Gallop -> Speed 2, Jump Boost 2
            case "gallop":
                durationSec = 9;
                cooldownSec = 21 * 60;
                addPotionSafe(p, "SPEED", durationSec * 20, 1); // Speed II
                addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 1); // Jump II
                spawnParticlesTimed(p, Particle.CLOUD, 40, 5);
                playSafeSound(p, Sound.ENTITY_HORSE_GALLOP);
                p.sendMessage(ChatColor.GREEN + "Gallop activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // Sheep: Soften -> Jump Boost 2, Regeneration 1
            case "soften":
                durationSec = 8;
                cooldownSec = 20 * 60;
                addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 1); // Jump II
                addPotionSafe(p, "REGENERATION", durationSec * 20, 0); // Regen I
                spawnParticlesTimed(p, Particle.VILLAGER_HAPPY, 40, 5);
                playSafeSound(p, Sound.ENTITY_SHEEP_AMBIENT);
                p.sendMessage(ChatColor.GREEN + "Soften activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // Ant: Sting -> Strength 2, Speed 1
            case "sting":
                durationSec = 7;
                cooldownSec = 21 * 60;
                addPotionSafe(p, "INCREASE_DAMAGE", durationSec * 20, 1);
                addPotionSafe(p, "SPEED", durationSec * 20, 0);
                spawnParticlesTimed(p, Particle.SOUL, 40, 5);
                playSafeSound(p, Sound.ENTITY_BEE_STING);
                p.sendMessage(ChatColor.GREEN + "Sting activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // Owl: Glide -> Speed 2, Jump Boost 2
            case "glide":
                durationSec = 9;
                cooldownSec = 24 * 60;
                addPotionSafe(p, "SPEED", durationSec * 20, 1); // Speed II
                addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 1); // Jump II
                spawnParticlesTimed(p, Particle.SPELL_WITCH, 40, 5);
                playSafeSound(p, Sound.ENTITY_PHANTOM_FLAP);
                p.sendMessage(ChatColor.GREEN + "Glide activated! Cooldown: " + cooldownSec/60 + "m");
                break;

            // CopperGolem: Discovery -> Haste 2, Resistance 2
            case "discovery":
                durationSec = 9;
                cooldownSec = 25 * 60;
                addPotio
