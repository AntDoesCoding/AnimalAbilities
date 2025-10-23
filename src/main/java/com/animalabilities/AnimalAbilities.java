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
 * Patched AnimalAbilities - runtime-safe enum lookups to avoid missing-constant compile errors.
 */
public class AnimalAbilities extends JavaPlugin implements Listener {

    // player uuid -> chosen animal
    private final Map<UUID, String> chosen = new HashMap<>();

    // player uuid -> ability -> expiryMillis
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
        // init meta
        abilityMeta.put("pounce", new AbilityMeta("wolf", 6 * 60, 8));
        abilityMeta.put("focus", new AbilityMeta("cat", 7 * 60, 10));
        abilityMeta.put("hover", new AbilityMeta("bee", 4 * 60, 6));
        abilityMeta.put("escape", new AbilityMeta("fox", 10 * 60, 10));
        abilityMeta.put("harden", new AbilityMeta("turtle", 8 * 60, 6));
        abilityMeta.put("gallop", new AbilityMeta("horse", 10 * 60, 8));
        abilityMeta.put("soften", new AbilityMeta("sheep", 8 * 60, 8));
        abilityMeta.put("sting", new AbilityMeta("ant", 5 * 60, 6));

        // load saved choices
        loadChoices();

        // register
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("AnimalAbilities enabled.");
    }

    @Override
    public void onDisable() {
        saveChoices();
        getLogger().info("AnimalAbilities disabled.");
    }

    // ---------------- persistence ----------------

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
        getLogger().info("Loaded " + chosen.size() + " saved choices.");
    }

    private void saveChoices() {
        FileConfiguration cfg = getConfig();
        cfg.set("players", null);
        for (Map.Entry<UUID, String> e : chosen.entrySet()) {
            cfg.set("players." + e.getKey().toString() + ".animal", e.getValue());
        }
        saveConfig();
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("chooseanimal")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only players can use this command.");
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
            // console allowed; in-game require OP
            if (sender instanceof Player sp && !sp.isOp()) {
                sender.sendMessage(ChatColor.RED + "Only OPs may run this.");
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

        // ability commands (defined in plugin.yml)
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

    // ---------------- GUI ----------------

    private void openChooseGui(Player p) {
        if (chosen.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset.");
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Choose Your Animal");

        inv.setItem(0, createGuiItem("BONE", "Wolf", "Strong and Brave!", "Strength I, Speed I"));
        inv.setItem(1, createGuiItem("STRING", "Cat", "Agile and Alert!", "Jump Boost II, Night Vision"));
        inv.setItem(2, createGuiItem("HONEYCOMB", "Bee", "Graceful in the air!", "Slow Falling, Jump Boost II"));
        inv.setItem(3, createGuiItem("SWEET_BERRIES", "Fox", "Sneaky and Swift!", "Speed II, Invisibility at night"));
        inv.setItem(4, createGuiItem("SCUTE", "Turtle", "Calm and tough!", "Water Breathing, Resistance I"));
        inv.setItem(5, createGuiItem("SADDLE", "Horse", "Fast and powerful!", "Speed I, Jump Boost I"));
        inv.setItem(6, createGuiItem("WHITE_WOOL", "Sheep", "Soft yet resilient!", "Resistance I, Jump Boost I"));
        inv.setItem(7, createGuiItem("ANVIL", "Ant", "Tiny but mighty!", "Haste, Strength I"));

        p.openInventory(inv);
    }

    private ItemStack createGuiItem(String materialName, String displayName, String desc, String effects) {
        Material mat = matchMaterialFallback(materialName);
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + displayName);
            meta.setLore(Arrays.asList(ChatColor.WHITE + desc, ChatColor.GREEN + effects));
            it.setItemMeta(meta);
        }
        return it;
    }

    private Material matchMaterialFallback(String name) {
        Material m = Material.matchMaterial(name);
        if (m != null) return m;
        try {
            return Material.valueOf(name);
        } catch (Exception ex) {
            // ultimate fallback
            return Material.BARRIER;
        }
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
            p.sendMessage(ChatColor.RED + "Invalid selection.");
            p.closeInventory();
            return;
        }
        chosen.put(p.getUniqueId(), display);
        saveChoices();
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + ChatColor.AQUA + capitalize(display) + ChatColor.GREEN + "!");
        applyPassive(p);
        // spawn a safe particle (try to use VILLAGER_HAPPY; fallback handled)
        spawnParticleOnce(p, "VILLAGER_HAPPY", 20);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1f, 1f);
        p.closeInventory();
    }

    // ---------------- passives & reapply ----------------

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
        clearAllEffects(p);
        String a = chosen.get(p.getUniqueId());
        if (a == null) return;
        int perm = Integer.MAX_VALUE - 1000;

        switch (a) {
            case "wolf":
                addPotionSafe(p, new String[]{"INCREASE_DAMAGE","STRENGTH"}, perm, 0);
                addPotionSafe(p, new String[]{"SPEED"}, perm, 0);
                break;
            case "cat":
                addPotionSafe(p, new String[]{"JUMP_BOOST","JUMP"}, perm, 1);
                addPotionSafe(p, new String[]{"NIGHT_VISION","NIGHT_VISION"}, perm, 0);
                break;
            case "bee":
                addPotionSafe(p, new String[]{"SLOW_FALLING","SLOW_FALLING"}, perm, 0);
                addPotionSafe(p, new String[]{"JUMP_BOOST","JUMP"}, perm, 1);
                break;
            case "fox":
                addPotionSafe(p, new String[]{"SPEED"}, perm, 1);
                scheduleFoxVisibility(p);
                break;
            case "turtle":
                addPotionSafe(p, new String[]{"WATER_BREATHING"}, perm, 0);
                addPotionSafe(p, new String[]{"DAMAGE_RESISTANCE","RESISTANCE"}, perm, 0);
                break;
            case "horse":
                addPotionSafe(p, new String[]{"SPEED"}, perm, 0);
                addPotionSafe(p, new String[]{"JUMP_BOOST","JUMP"}, perm, 0);
                break;
            case "sheep":
                addPotionSafe(p, new String[]{"DAMAGE_RESISTANCE","RESISTANCE"}, perm, 0);
                addPotionSafe(p, new String[]{"JUMP_BOOST","JUMP"}, perm, 0);
                break;
            case "ant":
                addPotionSafe(p, new String[]{"FAST_DIGGING","HASTE"}, perm, 0);
                addPotionSafe(p, new String[]{"INCREASE_DAMAGE","STRENGTH"}, perm, 0);
                break;
        }
    }

    private void scheduleFoxVisibility(Player p) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                long time = p.getWorld().getTime();
                boolean night = time >= 13000 || time <= 2300;
                if (night) addPotionSafe(p, new String[]{"INVISIBILITY"}, Integer.MAX_VALUE - 1000, 0);
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

    // ---------------- ability handling ----------------

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
        long expiry = userCd.getOrDefault(abilityCmd, 0L);
        if (now < expiry) {
            long left = (expiry - now) / 1000;
            p.sendMessage(ChatColor.YELLOW + "Ability on cooldown: " + left + "s");
            return;
        }

        // apply ability - each ability uses safe lookup helpers for potion/particle
        runAbilitySafe(abilityCmd, p, meta.durationSec);

        // set cooldown
        userCd.put(abilityCmd, now + meta.cooldownSec * 1000L);
    }

    private void runAbilitySafe(String cmd, Player p, int durationSeconds) {
        Location loc = p.getLocation().clone();

        switch (cmd) {
            case "pounce":
                addPotionSafe(p, new String[]{"JUMP_BOOST","JUMP"}, durationSeconds * 20, 0);
                addPotionSafe(p, new String[]{"INCREASE_DAMAGE","STRENGTH"}, durationSeconds * 20, 1);
                spawnParticlesForSecondsSafe(p, new String[]{"SMOKE_LARGE","SMOKE"}, 5);
                playSafeSound(p, Sound.ENTITY_WOLF_GROWL);
                p.sendMessage(ChatColor.GREEN + "Pounce activated!");
                break;

            case "focus":
                addPotionSafe(p, new String[]{"FAST_DIGGING","HASTE"}, durationSeconds * 20, 0);
                addPotionSafe(p, new String[]{"SPEED"}, durationSeconds * 20, 1);
                spawnParticlesForSecondsSafe(p, new String[]{"EXPLOSION_NORMAL","CLOUD"}, 5);
                playSafeSound(p, Sound.ENTITY_CAT_PURR);
                p.sendMessage(ChatColor.GREEN + "Focus activated!");
                break;

            case "hover":
                addPotionSafe(p, new String[]{"LEVITATION"}, durationSeconds * 20, 0);
                addPotionSafe(p, new String[]{"DAMAGE_RESISTANCE","RESISTANCE"}, durationSeconds * 20, 1);
                spawnParticlesForSecondsSafe(p, new String[]{"CLOUD"}, 5);
                playSafeSound(p, Sound.ENTITY_BEE_LOOP);
                p.sendMessage(ChatColor.GREEN + "Hover activated!");
                break;

            case "escape":
                addPotionSafe(p, new String[]{"SPEED"}, durationSeconds * 20, 2);
                addPotionSafe(p, new String[]{"FAST_DIGGING","HASTE"}, durationSeconds * 20, 0);
                spawnParticlesForSecondsSafe(p, new String[]{"FLAME"}, 5);
                playSafeSound(p, Sound.ENTITY_FOX_SCREECH);
                p.sendMessage(ChatColor.GREEN + "Escape activated!");
                break;

            case "harden":
                addPotionSafe(p, new String[]{"DAMAGE_RESISTANCE","RESISTANCE"}, durationSeconds * 20, 2);
                addPotionSafe(p, new String[]{"SLOW"}, durationSeconds * 20, 1);
                spawnParticlesForSecondsSafe(p, new String[]{"WATER_SPLASH"}, 5);
                playSafeSound(p, Sound.ITEM_SHIELD_BLOCK);
                p.sendMessage(ChatColor.GREEN + "Harden activated!");
                break;

            case "gallop":
                addPotionSafe(p, new String[]{"SPEED"}, durationSeconds * 20, 1);
                addPotionSafe(p, new String[]{"JUMP_BOOST","JUMP"}, durationSeconds * 20, 1);
                spawnParticlesForSecondsSafe(p, new String[]{"SMOKE_LARGE","SMOKE"}, 5);
                playSafeSound(p, Sound.ENTITY_HORSE_GALLOP);
                p.sendMessage(ChatColor.GREEN + "Gallop activated!");
                break;

            case "soften":
                addPotionSafe(p, new String[]{"JUMP_BOOST","JUMP"}, durationSeconds * 20, 1);
                addPotionSafe(p, new String[]{"REGENERATION"}, durationSeconds * 20, 0);
                spawnParticlesForSecondsSafe(p, new String[]{"VILLAGER_HAPPY","HEART"}, 5);
                playSafeSound(p, Sound.ENTITY_SHEEP_AMBIENT);
                p.sendMessage(ChatColor.GREEN + "Soften activated!");
                break;

            case "sting":
                addPotionSafe(p, new String[]{"INCREASE_DAMAGE","STRENGTH"}, durationSeconds * 20, 1);
                addPotionSafe(p, new String[]{"SPEED"}, durationSeconds * 20, 0);
                spawnParticlesForSecondsSafe(p, new String[]{"SOUL","SMOKE"}, 5);
                playSafeSound(p, Sound.ENTITY_BEE_STING);
                p.sendMessage(ChatColor.GREEN + "Sting activated!");
                break;

            default:
                p.sendMessage(ChatColor.RED + "Unknown ability.");
                break;
        }
    }

    // ---------------- safe helpers ----------------

    // Try multiple names for potion effect type and add if found
    private void addPotionSafe(Player p, String[] names, int durationTicks, int amp) {
        PotionEffectType type = lookupPotion(names);
        if (type != null) p.addPotionEffect(new PotionEffect(type, durationTicks, amp, true, false));
    }

    // Try multiple names for potion effect type and add if found (duration seconds style)
    private void addPotionSafe(Player p, String[] names, int durationSeconds, int amp) {
        addPotionSafe(p, names, durationSeconds, amp);
    }

    private PotionEffectType lookupPotion(String[] names) {
        for (String n : names) {
            if (n == null) continue;
            try {
                PotionEffectType t = PotionEffectType.getByName(n.toUpperCase(Locale.ROOT));
                if (t != null) return t;
            } catch (Throwable ignored) {}
        }
        // some servers might register lowercase aliases; attempt direct valueOf
        for (String n : names) {
            try {
                return PotionEffectType.valueOf(n.toUpperCase(Locale.ROOT));
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // Spawn a particle once by name (safe)
    private void spawnParticleOnce(Player p, String particleName, int count) {
        Particle particle = lookupParticle(particleName);
        Location loc = p.getLocation().clone().add(0, 1.0, 0);
        if (particle != null) p.getWorld().spawnParticle(particle, loc, count, 0.4, 0.6, 0.4, 0.02);
    }

    // Spawn repeated particles safely for seconds
    private void spawnParticlesForSecondsSafe(Player p, String[] candidates, int seconds) {
        Particle particle = null;
        for (String s : candidates) {
            particle = lookupParticle(s);
            if (particle != null) break;
        }
        if (particle == null) particle = Particle.CLOUD; // fallback

        final Particle finalParticle = particle;
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                Location c = p.getLocation().clone().add(0, 1.0, 0);
                p.getWorld().spawnParticle(finalParticle, c, 40, 0.6, 0.6, 0.6, 0.02);
                ticks += 5;
                if (ticks >= seconds * 20) cancel();
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private Particle lookupParticle(String name) {
        if (name == null) return null;
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {}
        // try some common fallbacks
        try {
            return Particle.valueOf(name.replaceAll(" ", "_").toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {}
        return
