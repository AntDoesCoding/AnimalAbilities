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

public class AnimalAbilities extends JavaPlugin implements Listener {

    private final Map<UUID, String> chosen = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    private File dataFile;
    private YamlConfiguration dataYml;

    @Override
    public void onEnable() {
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
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AnimalAbilities enabled.");
    }

    @Override
    public void onDisable() {
        saveChoices();
        getLogger().info("AnimalAbilities disabled.");
    }

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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("chooseanimal")) {
            if (!(sender instanceof Player)) return true;
            openChooseGui((Player) sender);
            return true;
        }
        if (cmd.equals("resetanimal")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            resetPlayer(p);
            p.sendMessage(ChatColor.YELLOW + "Your animal has been reset.");
            return true;
        }

        if (Arrays.asList("pounce", "focus", "hover", "escape", "harden", "gallop", "soften", "sting", "glide", "discovery").contains(cmd)) {
            if (!(sender instanceof Player)) return true;
            useAbility((Player) sender, cmd);
            return true;
        }
        return false;
    }

    private void openChooseGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 18, ChatColor.DARK_GREEN + "Choose Your Animal");
        inv.setItem(0, makeItem(Material.BONE, "Wolf", "Strong and Brave!", "Strength I, Speed I"));
        inv.setItem(1, makeItem(Material.STRING, "Cat", "Agile and Alert!", "Jump Boost II, Night Vision"));
        inv.setItem(2, makeItem(Material.HONEYCOMB, "Bee", "Graceful in air.", "Slow Falling, Jump Boost II"));
        inv.setItem(3, makeItem(Material.SWEET_BERRIES, "Fox", "Sneaky and swift.", "Speed II, Invisibility at night"));
        inv.setItem(4, makeItem(safeMaterial("SCUTE"), "Turtle", "Slow but tough.", "Water Breathing, Resistance I"));
        inv.setItem(5, makeItem(Material.SADDLE, "Horse", "Fast and powerful.", "Speed I, Jump Boost I"));
        inv.setItem(6, makeItem(Material.WHITE_WOOL, "Sheep", "Soft yet sturdy.", "Resistance I, Jump Boost I"));
        inv.setItem(7, makeItem(safeMaterial("SOUL_SOIL"), "Ant", "Small but mighty.", "Haste, Strength I"));
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
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTitle().equals(ChatColor.DARK_GREEN + "Choose Your Animal")) return;
        e.setCancelled(true);
        if (e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta()) return;
        String disp = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
        Set<String> valid = new HashSet<>(Arrays.asList("wolf", "cat", "bee", "fox", "turtle", "horse", "sheep", "ant", "owl", "coppergolem"));
        if (!valid.contains(disp)) return;
        chosen.put(p.getUniqueId(), disp);
        saveChoices();
        applyPassiveToPlayer(p, disp);
        p.sendMessage(ChatColor.GREEN + "You are now bonded with the " + disp + "!");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p.closeInventory();
    }

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
        for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
        int permTicks = Integer.MAX_VALUE - 1000;
        switch (animal) {
            case "wolf" -> { addPotionSafe(p, "INCREASE_DAMAGE", permTicks, 1); addPotionSafe(p, "SPEED", permTicks, 0); }
            case "cat" -> { addPotionSafe(p, "JUMP_BOOST", permTicks, 1); addPotionSafe(p, "NIGHT_VISION", permTicks, 0); }
            case "bee" -> { addPotionSafe(p, "SLOW_FALLING", permTicks, 0); addPotionSafe(p, "JUMP_BOOST", permTicks, 1); }
            case "fox" -> { addPotionSafe(p, "SPEED", permTicks, 1); }
            case "turtle" -> { addPotionSafe(p, "WATER_BREATHING", permTicks, 0); addPotionSafe(p, "DAMAGE_RESISTANCE", permTicks, 0); }
            case "horse" -> { addPotionSafe(p, "SPEED", permTicks, 0); addPotionSafe(p, "JUMP_BOOST", permTicks, 0); }
            case "sheep" -> { addPotionSafe(p, "DAMAGE_RESISTANCE", permTicks, 0); addPotionSafe(p, "JUMP_BOOST", permTicks, 0); }
            case "ant" -> { addPotionSafe(p, "FAST_DIGGING", permTicks, 0); addPotionSafe(p, "INCREASE_DAMAGE", permTicks, 0); }
            case "owl" -> { addPotionSafe(p, "NIGHT_VISION", permTicks, 0); addPotionSafe(p, "SLOW_FALLING", permTicks, 0); addPotionSafe(p, "JUMP_BOOST", permTicks, 0); }
            case "coppergolem" -> { addPotionSafe(p, "SPEED", permTicks, 0); addPotionSafe(p, "FAST_DIGGING", permTicks, 0); }
        }
    }

    private void useAbility(Player p, String cmd) {
        String animal = chosen.get(p.getUniqueId());
        if (animal == null) return;
        if (!canUseAbilityFor(animal, cmd)) return;
        int durationSec, cooldownSec;
        switch (cmd) {
            case "glide" -> { durationSec = 10; cooldownSec = 30; addPotionSafe(p, "SPEED", durationSec * 20, 1); addPotionSafe(p, "JUMP_BOOST", durationSec * 20, 1); }
            case "discovery" -> { durationSec = 10; cooldownSec = 45; addPotionSafe(p, "FAST_DIGGING", durationSec * 20, 1); addPotionSafe(p, "DAMAGE_RESISTANCE", durationSec * 20, 1); }
            default -> { durationSec = 10; cooldownSec = 30; }
        }
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(cmd, System.currentTimeMillis() + cooldownSec * 1000L);
    }

    private boolean canUseAbilityFor(String animal, String ability) {
        return switch (animal) {
            case "owl" -> ability.equals("glide");
            case "coppergolem" -> ability.equals("discovery");
            default -> false;
        };
    }

    private void addPotionSafe(Player p, String name, int ticks, int amp) {
        PotionEffectType t = resolvePotionType(name);
        if (t != null) p.addPotionEffect(new PotionEffect(t, ticks, amp, true, false));
    }

    private PotionEffectType resolvePotionType(String name) {
        if (name == null) return null;
        String n = name.toUpperCase(Locale.ROOT).replace(' ', '_');
        Map<String, String> syn = Map.ofEntries(
                Map.entry("STRENGTH", "INCREASE_DAMAGE"),
                Map.entry("JUMP", "JUMP_BOOST"),
                Map.entry("SPEED", "SPEED"),
                Map.entry("HASTE", "FAST_DIGGING"),
                Map.entry("RESISTANCE", "DAMAGE_RESISTANCE")
        );
        if (syn.containsKey(n)) n = syn.get(n);
        return PotionEffectType.getByName(n);
    }

    private void resetPlayer(Player p) {
        chosen.remove(p.getUniqueId());
        for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
    }
                                            }
