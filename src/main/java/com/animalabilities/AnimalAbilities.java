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
 * AnimalAbilities - compact, safe, balanced.
 * Supports: chooseanimal GUI, passives, abilities, cooldowns, save/load, reapply on join/respawn, OP reset.
 */
public class AnimalAbilities extends JavaPlugin implements Listener {

    private final Map<UUID, String> chosen = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<String, Ability> abilities = new HashMap<>();

    private static class Ability {
        final String animal;
        final int cooldownSec;
        final int durationSec;
        Ability(String animal, int cooldownSec, int durationSec) {
            this.animal = animal;
            this.cooldownSec = cooldownSec;
            this.durationSec = durationSec;
        }
    }

    @Override
    public void onEnable() {
        // define abilities
        abilities.put("pounce", new Ability("wolf", 6 * 60, 8));
        abilities.put("focus", new Ability("cat", 7 * 60, 10));
        abilities.put("hover", new Ability("bee", 4 * 60, 6));
        abilities.put("escape", new Ability("fox", 10 * 60, 10));
        abilities.put("harden", new Ability("turtle", 8 * 60, 6));
        abilities.put("gallop", new Ability("horse", 10 * 60, 8));
        abilities.put("soften", new Ability("sheep", 8 * 60, 8));
        abilities.put("sting", new Ability("ant", 5 * 60, 6));

        // load saved choices from config.yml
        loadChoices();

        // register events
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("AnimalAbilities enabled.");
    }

    @Override
    public void onDisable() {
        saveChoices();
        getLogger().info("AnimalAbilities disabled.");
    }

    // -------------------- persistence --------------------

    private void loadChoices() {
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

    private void saveChoices() {
        FileConfiguration cfg = getConfig();
        cfg.set("players", null);
        for (Map.Entry<UUID, String> e : chosen.entrySet()) {
            cfg.set("players." + e.getKey().toString() + ".animal", e.getValue());
        }
        saveConfig();
    }

    // -------------------- commands --------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("chooseanimal")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
            openChooseGui(p);
            return true;
        }

        if (cmd.equals("resetanimal")) {
            if (args.length != 1) { sender.sendMessage(ChatColor.RED + "Usage: /resetanimal <player>"); return true; }
            if (sender instanceof Player sp && !sp.isOp()) { sp.sendMessage(ChatColor.RED + "Only OPs."); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { sender.sendMessage(ChatColor.RED + "Player not found."); return true; }
            chosen.remove(target.getUniqueId());
            saveChoices();
            clearEffects(target);
            target.sendMessage(ChatColor.YELLOW + "Your animal choice was reset by an admin.");
            sender.sendMessage(ChatColor.GREEN + "Reset " + target.getName());
            return true;
        }

        // ability commands
        if (abilities.containsKey(cmd)) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
            useAbility(p, cmd);
            return true;
        }

        return false;
    }

    // -------------------- GUI --------------------

    private void openChooseGui(Player p) {
        if (chosen.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset.");
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Choose Your Animal");

        inv.setItem(0, makeItem("BONE", "Wolf", "Strong and Brave!", "Strength I, Speed I"));
        inv.setItem(1, makeItem("STRING", "Cat", "Agile and Alert!", "Jump Boost II, Night Vision"));
        inv.setItem(2, makeItem("HONEYCOMB", "Bee", "Graceful in the air!", "Slow Falling, Jump Boost II"));
        inv.setItem(3, makeItem("SWEET_BERRIES", "Fox", "Sneaky and Swift!", "Speed II, Invisibility at night"));
        inv.setItem(4, makeItem("SCUTE", "Turtle", "Calm and tough!", "Water Breathing, Resistance I"));
        inv.setItem(5, makeItem("SADDLE", "Horse", "Fast and powerful!", "Speed I, Jump Boost I"));
        inv.setItem(6, makeItem("WHITE_WOOL", "Sheep", "Soft yet resilient!", "Resistance I, Jump Boost I"));
        inv.setItem(7, makeItem("ANVIL", "Ant", "Tiny but mighty!", "Haste, Strength I"));

        p.openInventory(inv);
    }

    private ItemStack makeItem(String matName, String title, String desc, String effects) {
        Material m = safeMaterial(matName);
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + title);
            meta.setLore(Arrays.asList(ChatColor.WHITE + desc, ChatColor.GREEN + effects));
            it.setItemMeta(meta);
        }
        return it;
    }

    private Material safeMaterial(String name) {
        Material m = Material.matchMaterial(name);
        if (m != null) return m;
        try { return Material.valueOf(name); } catch (Exception ignored) {}
        return Material.BARRIER;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView().getTitle() == null) return;
        if (!e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Choose Your Animal")) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getCurrentItem() == null || e.getCurrentItem().getItemMeta() == null) { p.closeInventory(); return; }
        String sel = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
        Set<String> allowed = Set.of("wolf","cat","bee","fox","turtle","horse","sheep","ant");
        if (!allowed.contains(sel)) { p.sendMessage(ChatColor.RED + "Invalid selection."); p.closeInventory(); return; }
        chosen.put(p.getUniqueId(), sel);
        saveChoices();
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + ChatColor.AQUA + capitalize(sel) + ChatColor.GREEN + "!");
        applyPassive(p);
        spawnParticlesTimed(p, guessParticle("VILLAGER_HAPPY"), 20, 5);
        playSafeSound(p, Sound.ENTITY_PLAYER_LEVELUP);
        p.closeInventory();
    }

    // -------------------- passives --------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (chosen.containsKey(p.getUniqueId())) applyPassive(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> { if (chosen.containsKey(p.getUniqueId())) applyPassive(p); }, 20L);
    }

    private void applyPassive(Player p) {
        clearEffects(p);
        String a = chosen.get(p.getUniqueId());
        if (a == null) return;
        int perm = Integer.MAX_VALUE - 1000;

        switch (a) {
            case "wolf":
                addPotionByNames(p, new String[]{"INCREASE_DAMAGE","STRENGTH"}, perm, 0);
                addPotionByNames(p, new String[]{"SPEED"}, perm, 0);
                break;
            case "cat":
                addPotionByNames(p, new String[]{"JUMP_BOOST","JUMP"}, perm, 1);
                addPotionByNames(p, new String[]{"NIGHT_VISION"}, perm, 0);
                break;
            case "bee":
                addPotionByNames(p, new String[]{"SLOW_FALLING"}, perm, 0);
                addPotionByNames(p, new String[]{"JUMP_BOOST","JUMP"}, perm, 1);
                break;
            case "fox":
                addPotionByNames(p, new String[]{"SPEED"}, perm, 1);
                scheduleFoxInvis(p);
                break;
            case "turtle":
                addPotionByNames(p, new String[]{"WATER_BREATHING"}, perm, 0);
                addPotionByNames(p, new String[]{"DAMAGE_RESISTANCE","RESISTANCE"}, perm, 0);
                break;
            case "horse":
                addPotionByNames(p, new String[]{"SPEED"}, perm, 0);
                addPotionByNames(p, new String[]{"JUMP_BOOST","JUMP"}, perm, 0);
                break;
            case "sheep":
                addPotionByNames(p, new String[]{"DAMAGE_RESISTANCE","RESISTANCE"}, perm, 0);
                addPotionByNames(p, new String[]{"JUMP_BOOST","JUMP"}, perm, 0);
                break;
            case "ant":
                addPotionByNames(p, new String[]{"FAST_DIGGING","HASTE"}, perm, 0);
                addPotionByNames(p, new String[]{"INCREASE_DAMAGE","STRENGTH"}, perm, 0);
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
                if (night) addPotionByNames(p, new String[]{"INVISIBILITY"}, Integer.MAX_VALUE - 1000, 0);
                else p.removePotionEffect(PotionEffectType.INVISIBILITY);
                cancel();
            }
        }.runTaskLater(this, 10L);
    }

    private void clearEffects(Player p) {
        for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
    }

    // -------------------- abilities --------------------

    private void useAbility(Player p, String cmd) {
        Ability a = abilities.get(cmd);
        if (a == null) return;
        String playerChoice = chosen.get(p.getUniqueId());
        if (playerChoice == null) { p.sendMessage(ChatColor.RED + "You have not chosen an animal. Use /chooseanimal"); return; }
        if (!playerChoice.equals(a.animal)) { p.sendMessage(ChatColor.RED + "Only " + capitalize(a.animal) + "s can use that."); return; }

        Map<String, Long> userCd = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        long until = userCd.getOrDefault(cmd, 0L);
        if (now < until) { p.sendMessage(ChatColor.YELLOW + "Ability on cooldown: " + ((until - now) / 1000) + "s"); return; }

        // Apply ability effects and visuals
        applyAbilityEffects(cmd, p, a.durationSec);

        // set cooldown
        userCd.put(cmd, now + a.cooldownSec * 1000L);
    }

    private void applyAbilityEffects(String cmd, Player p, int durationSec) {
        switch (cmd) {
            case "pounce":
                addPotionByNames(p, new String[]{"JUMP_BOOST","JUMP"}, durationSec * 20, 0);
                addPotionByNames(p, new String[]{"INCREASE_DAMAGE","STRENGTH"}, durationSec * 20, 1);
                spawnParticlesTimed(p, guessParticle("SMOKE_LARGE"), 40, 5);
                playSafeSound(p, Sound.ENTITY_WOLF_GROWL);
                p.sendMessage(ChatColor.GREEN + "Pounce activated!");
                break;
            case "focus":
                addPotionByNames(p, new String[]{"FAST_DIGGING","HASTE"}, durationSec * 20, 0);
                addPotionByNames(p, new String[]{"SPEED"}, durationSec * 20, 1);
                spawnParticlesTimed(p, guessParticle("EXPLOSION_NORMAL"), 40, 5);
                playSafeSound(p, Sound.ENTITY_CAT_PURR);
                p.sendMessage(ChatColor.GREEN + "Focus activated!");
                break;
            case "hover":
                addPotionByNames(p, new String[]{"LEVITATION"}, durationSec * 20, 0);
                addPotionByNames(p, new String[]{"DAMAGE_RESISTANCE","RESISTANCE"}, durationSec * 20, 1);
                spawnParticlesTimed(p, guessParticle("CLOUD"), 40, 5);
                playSafeSound(p, Sound.ENTITY_BEE_LOOP);
                p.sendMessage(ChatColor.GREEN + "Hover activated!");
                break;
            case "escape":
                addPotionByNames(p, new String[]{"SPEED"}, durationSec * 20, 2);
                addPotionByNames(p, new String[]{"FAST_DIGGING","HASTE"}, durationSec * 20, 0);
                spawnParticlesTimed(p, guessParticle("FLAME"), 40, 5);
                playSafeSound(p, Sound.ENTITY_FOX_SCREECH);
                p.sendMessage(ChatColor.GREEN + "Escape activated!");
                break;
            case "harden":
                addPotionByNames(p, new String[]{"DAMAGE_RESISTANCE","RESISTANCE"}, durationSec * 20, 2);
                addPotionByNames(p, new String[]{"SLOW"}, durationSec * 20, 1);
                spawnParticlesTimed(p, guessParticle("DRIP_WATER"), 40, 5);
                playSafeSound(p, Sound.ITEM_SHIELD_BLOCK);
                p.sendMessage(ChatColor.GREEN + "Harden activated!");
                break;
            case "gallop":
                addPotionByNames(p, new String[]{"SPEED"}, durationSec * 20, 1);
                addPotionByNames(p, new String[]{"JUMP_BOOST","JUMP"}, durationSec * 20, 1);
                spawnParticlesTimed(p, guessParticle("SMOKE_LARGE"), 40, 5);
                playSafeSound(p, Sound.ENTITY_HORSE_GALLOP);
                p.sendMessage(ChatColor.GREEN + "Gallop activated!");
                break;
            case "soften":
                addPotionByNames(p, new String[]{"JUMP_BOOST","JUMP"}, durationSec * 20, 1);
                addPotionByNames(p, new String[]{"REGENERATION"}, durationSec * 20, 0);
                spawnParticlesTimed(p, guessParticle("VILLAGER_HAPPY"), 40, 5);
                playSafeSound(p, Sound.ENTITY_SHEEP_AMBIENT);
                p.sendMessage(ChatColor.GREEN + "Soften activated!");
                break;
            case "sting":
                addPotionByNames(p, new String[]{"INCREASE_DAMAGE","STRENGTH"}, durationSec * 20, 1);
                addPotionByNames(p, new String[]{"SPEED"}, durationSec * 20, 0);
                spawnParticlesTimed(p, guessParticle("SOUL"), 40, 5);
                playSafeSound(p, Sound.ENTITY_BEE_STING);
                p.sendMessage(ChatColor.GREEN + "Sting activated!");
                break;
            default:
                p.sendMessage(ChatColor.RED + "Unknown ability.");
                break;
        }
    }

    // -------------------- safe helpers --------------------

    private void addPotionByNames(Player p, String[] names, int durationTicks, int amp) {
        PotionEffectType t = null;
        for (String n : names) {
            if (n == null) continue;
            try { t = PotionEffectType.getByName(n.toUpperCase(Locale.ROOT)); } catch (Throwable ignored) {}
            if (t != null) break;
            try { t = PotionEffectType.valueOf(n.toUpperCase(Locale.ROOT)); } catch (Throwable ignored) {}
            if (t != null) break;
        }
        if (t != null) p.addPotionEffect(new PotionEffect(t, durationTicks, amp, true, false));
    }

    private Particle guessParticle(String name) {
        if (name == null) return Particle.CLOUD;
        try { return Particle.valueOf(name.toUpperCase(Locale.ROOT)); } catch (Throwable ignored) {}
        try { return Particle.valueOf(name.replace(' ', '_').toUpperCase(Locale.ROOT)); } catch (Throwable ignored) {}
        return Particle.CLOUD;
    }

    private void spawnParticlesTimed(Player p, Particle particle, int count, int seconds) {
        Particle part = particle != null ? particle : Particle.CLOUD;
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                Location loc = p.getLocation().clone().add(0, 1.0, 0);
                p.getWorld().spawnParticle(part, loc, count, 0.6, 0.8, 0.6, 0.02);
                ticks += 5;
                if (ticks >= seconds * 20) cancel();
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private void playSafeSound(Player p, Sound s) {
        try { p.playSound(p.getLocation(), s, SoundCategory.PLAYERS, 1f, 1f); } catch (Throwable ignored) {}
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

} // end class
