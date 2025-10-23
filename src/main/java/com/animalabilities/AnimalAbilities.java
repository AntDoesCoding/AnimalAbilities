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

public class AnimalAbilities extends JavaPlugin implements Listener {

    // player UUID -> chosen animal id (lowercase)
    private final Map<UUID, String> playerAnimal = new HashMap<>();

    // player UUID -> map(commandName -> expiryMillis)
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    // Config key root for saved data
    private static final String DATA_ROOT = "players";

    // Abilities config (cooldowns seconds, duration seconds)
    private final Map<String, AbilityInfo> abilityInfo = new HashMap<>();

    @Override
    public void onEnable() {
        // default ability configuration (cooldownSeconds, effectDurationSeconds)
        initAbilityInfo();

        saveDefaultConfig(); // ensure config exists
        loadSavedData();

        getServer().getPluginManager().registerEvents(this, this);

        // register simple command handlers
        Objects.requireNonNull(getCommand("chooseanimal")).setExecutor(this::cmdChooseAnimal);
        Objects.requireNonNull(getCommand("resetanimal")).setExecutor(this::cmdResetAnimal);

        // ability commands
        for (String cmd : abilityInfo.keySet()) {
            Objects.requireNonNull(getCommand(cmd)).setExecutor(this::cmdAbility);
        }

        // schedule repeating task to update fox nocturnal invisibility (every 200 ticks)
        new BukkitRunnable() {
            @Override
            public void run() {
                updateFoxInvisibility();
            }
        }.runTaskTimer(this, 20L, 200L);

        getLogger().info("AnimalAbilities enabled.");
    }

    @Override
    public void onDisable() {
        saveAllData();
        getLogger().info("AnimalAbilities disabled.");
    }

    // ---------- Ability info helper ----------
    private static class AbilityInfo {
        final String animal;       // required animal
        final int cooldownSecs;    // cooldown seconds
        final int durationSecs;    // duration of potion effects
        AbilityInfo(String animal, int cooldownSecs, int durationSecs) {
            this.animal = animal;
            this.cooldownSecs = cooldownSecs;
            this.durationSecs = durationSecs;
        }
    }

    private void initAbilityInfo() {
        // commandName -> (animal, cooldownSeconds, durationSeconds)
        abilityInfo.put("pounce", new AbilityInfo("wolf", 6 * 60, 8));
        abilityInfo.put("focus", new AbilityInfo("cat", 7 * 60, 10));
        abilityInfo.put("hover", new AbilityInfo("bee", 4 * 60, 6));
        abilityInfo.put("escape", new AbilityInfo("fox", 10 * 60, 10));
        abilityInfo.put("harden", new AbilityInfo("turtle", 8 * 60, 6));
        abilityInfo.put("gallop", new AbilityInfo("horse", 10 * 60, 8));
        abilityInfo.put("soften", new AbilityInfo("sheep", 8 * 60, 8));
        abilityInfo.put("sting", new AbilityInfo("ant", 5 * 60, 6));
    }

    // ---------- Data load/save ----------
    private void loadSavedData() {
        FileConfiguration cfg = getConfig();
        if (!cfg.contains(DATA_ROOT)) return;
        for (String key : cfg.getConfigurationSection(DATA_ROOT).getKeys(false)) {
            try {
                UUID u = UUID.fromString(key);
                String animal = cfg.getString(DATA_ROOT + "." + key + ".animal", "").toLowerCase(Locale.ROOT);
                if (!animal.isEmpty()) playerAnimal.put(u, animal);
            } catch (Exception ignored) {}
        }
        getLogger().info("Loaded " + playerAnimal.size() + " saved players' animals.");
    }

    private void saveAllData() {
        FileConfiguration cfg = getConfig();
        // clear existing players node and re-put
        cfg.set(DATA_ROOT, null);
        for (Map.Entry<UUID, String> e : playerAnimal.entrySet()) {
            cfg.set(DATA_ROOT + "." + e.getKey().toString() + ".animal", e.getValue());
        }
        saveConfig();
    }

    // ---------- Commands ----------
    private boolean cmdChooseAnimal(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command is only usable by players.");
            return true;
        }
        Player p = (Player) sender;
        openChooserGui(p);
        return true;
    }

    private boolean cmdResetAnimal(CommandSender sender, Command command, String label, String[] args) {
        // /resetanimal <player>
        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /resetanimal <player>");
            return true;
        }
        if (!(sender instanceof Player) || ((Player) sender).isOp() || !(sender instanceof Player)) {
            // allow console to run too (console is permitted)
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found or not online.");
            return true;
        }
        if (playerAnimal.remove(target.getUniqueId()) != null) {
            saveAllData();
            // clear potion effects the plugin applied to be safe
            clearAllPluginEffects(target);
            target.sendMessage(ChatColor.RED + "Your animal has been reset by an admin.");
            sender.sendMessage(ChatColor.GREEN + "Reset " + target.getName() + "'s animal.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Player had no chosen animal.");
        }
        return true;
    }

    private boolean cmdAbility(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        AbilityInfo info = abilityInfo.get(cmdName);
        if (info == null) return true; // safety
        String chosen = playerAnimal.get(p.getUniqueId());
        if (chosen == null) {
            p.sendMessage(ChatColor.RED + "You haven't chosen an animal yet. Use /chooseanimal");
            return true;
        }
        if (!chosen.equalsIgnoreCase(info.animal)) {
            p.sendMessage(ChatColor.RED + "Only " + capitalize(info.animal) + "s can use this ability.");
            return true;
        }

        // cooldown check
        long now = System.currentTimeMillis();
        Map<String, Long> userMap = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long expiry = userMap.getOrDefault(cmdName, 0L);
        if (now < expiry) {
            long leftSec = (expiry - now) / 1000;
            p.sendMessage(ChatColor.RED + cmdName + " is on cooldown. " + ChatColor.YELLOW + leftSec + "s");
            return true;
        }

        // Apply ability
        runAbility(cmdName, p, info.durationSecs);

        // Set cooldown
        userMap.put(cmdName, now + info.cooldownSecs * 1000L);
        p.sendMessage(ChatColor.GREEN + "Ability " + ChatColor.AQUA + cmdName + ChatColor.GREEN + " activated! Cooldown: " + info.cooldownSecs + "s");
        return true;
    }

    // ---------- GUI / chooser ----------
    private void openChooserGui(Player p) {
        if (playerAnimal.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Contact an OP to reset.");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Choose Your Animal");

        // slot -> animal mapping
        inv.setItem(0, createGuiItem(Material.BONE, ChatColor.GRAY + "Wolf", List.of(
                ChatColor.WHITE + "Strong and Brave!",
                ChatColor.GRAY + "Passive: Strength I, Speed I",
                ChatColor.GRAY + "Ability: /pounce (Jump I + Strength II)")));
        inv.setItem(1, createGuiItem(Material.CAT_SPAWN_EGG, ChatColor.YELLOW + "Cat", List.of(
                ChatColor.WHITE + "Stealthy and alert.",
                ChatColor.GRAY + "Passive: Jump Boost II, Night Vision",
                ChatColor.GRAY + "Ability: /focus (Haste + Speed II)")));
        inv.setItem(2, createGuiItem(Material.HONEYCOMB, ChatColor.GOLD + "Bee", List.of(
                ChatColor.WHITE + "Hoverer of skies.",
                ChatColor.GRAY + "Passive: Slow Falling, Jump Boost II",
                ChatColor.GRAY + "Ability: /hover (Levitation + Resistance II)")));
        inv.setItem(3, createGuiItem(Material.SWEET_BERRIES, ChatColor.RED + "Fox", List.of(
                ChatColor.WHITE + "Quick and cunning.",
                ChatColor.GRAY + "Passive: Speed II, Invisibility at night",
                ChatColor.GRAY + "Ability: /escape (Speed III + Haste)")));
        inv.setItem(4, createGuiItem(Material.TURTLE_HELMET, ChatColor.AQUA + "Turtle", List.of(
                ChatColor.WHITE + "Sturdy swimmer.",
                ChatColor.GRAY + "Passive: Water Breathing, Resistance I",
                ChatColor.GRAY + "Ability: /harden (Resistance III + Slowness II)")));
        inv.setItem(5, createGuiItem(Material.SADDLE, ChatColor.DARK_GRAY + "Horse", List.of(
                ChatColor.WHITE + "Swift mount.",
                ChatColor.GRAY + "Passive: Speed I, Jump Boost I",
                ChatColor.GRAY + "Ability: /gallop (Speed II + Jump Boost II)")));
        inv.setItem(6, createGuiItem(Material.WHITE_WOOL, ChatColor.WHITE + "Sheep", List.of(
                ChatColor.WHITE + "Soft but tough.",
                ChatColor.GRAY + "Passive: Resistance I, Jump Boost I",
                ChatColor.GRAY + "Ability: /soften (Jump Boost II + Regeneration I)")));
        inv.setItem(7, createGuiItem(Material.ANVIL, ChatColor.DARK_PURPLE + "Ant", List.of(
                ChatColor.WHITE + "Tiny but mighty.",
                ChatColor.GRAY + "Passive: Haste, Strength I",
                ChatColor.GRAY + "Ability: /sting (Strength II + Speed I)")));

        p.openInventory(inv);
    }

    private ItemStack createGuiItem(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            m.setLore(lore);
            is.setItemMeta(m);
        }
        return is;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView().getTitle() == null) return;
        if (!e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Choose Your Animal")) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            p.closeInventory();
            return;
        }
        String display = ChatColor.stripColor(clicked.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);

        // ensure mapping matches our allowed animals
        Set<String> allowed = Set.of("wolf","cat","bee","fox","turtle","horse","sheep","ant");
        if (!allowed.contains(display)) {
            p.sendMessage(ChatColor.RED + "Invalid selection.");
            p.closeInventory();
            return;
        }
        // lock selection
        playerAnimal.put(p.getUniqueId(), display);
        saveAllData();
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + ChatColor.AQUA + capitalize(display) + ChatColor.GREEN + "!");
        p.closeInventory();
        // apply passives right away
        applyPassiveToPlayer(p, display);
        // visual effect: particle + sound
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0,1,0), 25, 0.5, 0.5, 0.5, 0.01);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    // ---------- Passive effects (apply & reapply on join/respawn) ----------
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String animal = playerAnimal.get(p.getUniqueId());
        if (animal != null) applyPassiveToPlayer(p, animal);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        // small delay to ensure player is fully spawned
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String animal = playerAnimal.get(p.getUniqueId());
            if (animal != null) applyPassiveToPlayer(p, animal);
        }, 20L);
    }

    private void applyPassiveToPlayer(Player p, String animal) {
        // remove all potion effects we may have applied (safe approach)
        clearAllPluginEffects(p);

        int permTicks = Integer.MAX_VALUE - 1000; // effectively permanent

        switch (animal.toLowerCase(Locale.ROOT)) {
            case "wolf":
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, permTicks, 0, true, false)); // Strength I
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, permTicks, 0, true, false));
                break;
            case "cat":
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, permTicks, 1, true, false)); // Jump Boost II (amp 1)
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, permTicks, 0, true, false));
                break;
            case "bee":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, permTicks, 0, true, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, permTicks, 1, true, false));
                break;
            case "fox":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, permTicks, 1, true, false));
                // invisibility only at night; updateFoxInvisibility handles toggling
                break;
            case "turtle":
                p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, permTicks, 0, true, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, permTicks, 0, true, false)); // Resistance I
                break;
            case "horse":
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, permTicks, 0, true, false)); // Speed I
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, permTicks, 0, true, false));
                break;
            case "sheep":
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, permTicks, 0, true, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, permTicks, 0, true, false));
                break;
            case "ant":
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, permTicks, 0, true, false)); // haste
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, permTicks, 0, true, false)); // strength I
                break;
        }
    }

    // Toggle invisibility for foxes depending on time (day/night)
    private void updateFoxInvisibility() {
        for (UUID u : new ArrayList<>(playerAnimal.keySet())) {
            if (!"fox".equals(playerAnimal.get(u))) continue;
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) continue;
            long time = p.getWorld().getTime(); // 0-24000
            boolean night = (time >= 13000 || time <= 2300);
            boolean has = p.hasPotionEffect(PotionEffectType.INVISIBILITY);
            if (night && !has) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE - 1000, 0, true, false));
            } else if (!night && has) {
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
        }
    }

    // Remove plugin-applied effects by type (safer to clear all; optional: check list)
    private void clearAllPluginEffects(Player p) {
        for (PotionEffect pe : p.getActivePotionEffects()) {
            // remove all effects (we assume plugin is allowed)
            p.removePotionEffect(pe.getType());
        }
    }

    // ---------- Ability execution and visuals ----------
    private void runAbility(String cmd, Player p, int durationSecs) {
        Location loc = p.getLocation().clone();

        switch (cmd) {
            case "pounce": // Wolf: Jump I + Strength II
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSecs * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, durationSecs * 20, 1));
                spawnTimedParticles(p, Particle.SMOKE_LARGE, 5); // smoke around player
                p.playSound(loc, Sound.ENTITY_WOLF_AMBIENT, 1f, 1.2f);
                break;

            case "focus": // Cat: Haste + Speed II
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, durationSecs * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSecs * 20, 1));
                spawnTimedParticles(p, Particle.EXPLOSION_NORMAL, 5); // exp-ish circle
                p.playSound(loc, Sound.ENTITY_CAT_PURR, 1f, 1.0f);
                break;

            case "hover": // Bee: Levitation + Resistance II
                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, durationSecs * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, durationSecs * 20, 1));
                spawnTimedParticles(p, Particle.CLOUD, 5);
                p.playSound(loc, Sound.ENTITY_BEE_LOOP, 1f, 1.0f);
                break;

            case "escape": // Fox: Speed III + Haste
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSecs * 20, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, durationSecs * 20, 0));
                spawnTimedParticles(p, Particle.FLAME, 5); // flame circle
                p.playSound(loc, Sound.ENTITY_FOX_ESCAPE, 1f, 1.0f);
                break;

            case "harden": // Turtle: Resistance III + Slowness II
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, durationSecs * 20, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, durationSecs * 20, 1));
                spawnTimedParticles(p, Particle.WATER_SPLASH, 5);
                p.playSound(loc, Sound.ITEM_SHIELD_BLOCK, 1f, 1.0f);
                break;

            case "gallop": // Horse: Speed II + Jump Boost II
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSecs * 20, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSecs * 20, 1));
                spawnTimedParticles(p, Particle.SMOKE_LARGE, 5);
                p.playSound(loc, Sound.ENTITY_HORSE_GALLOP, 1f, 1.0f);
                break;

            case "soften": // Sheep: Jump Boost II + Regeneration I
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSecs * 20, 1));
             
