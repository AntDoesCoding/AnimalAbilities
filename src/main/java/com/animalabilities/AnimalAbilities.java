// File: src/main/java/com/animalabilities/AnimalAbilities.java
package com.animalabilities;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.inventory.ItemFlag;
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
 * AnimalAbilities - Paper 1.21.x plugin main class
 *
 * Features implemented (as requested):
 * - /chooseanimal -> double chest GUI with icons + paper descriptions + confirm/cancel flow
 * - /resetanimal <player|@a> -> OP-only for resetting others, console allowed, reset self allowed
 * - Passive effects applied and re-applied on join/respawn
 * - Player locked into a single animal once chosen
 * - Abilities (one-word command names), cooldowns, durations, visuals, sounds
 * - All potion resolution uses PotionEffectType.getByName(...) via resolvePotionType for compatibility
 * - All GUI confirmations have sound feedback
 * - Messages sent to players about states and cooldowns
 */
public class AnimalAbilities extends JavaPlugin implements Listener {

    private final Map<UUID, String> chosen = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    private File dataFile;
    private YamlConfiguration dataYml;

    // inventory titles / constants
    private static final String CHOOSE_TITLE = ChatColor.DARK_GREEN + "Choose Your Animal";
    private static final String CONFIRM_TITLE = ChatColor.DARK_GREEN + "Confirm Your Choice";

    @Override
    public void onEnable() {
        // data file
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
        getLogger().info("AnimalAbilities enabled. Loaded " + chosen.size() + " saved choices.");
    }

    @Override
    public void onDisable() {
        saveChoices();
        getLogger().info("AnimalAbilities disabled.");
    }

    // ---------------- Persistence ----------------
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

    // ---------------- Commands ----------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("chooseanimal")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can run this command.");
                return true;
            }
            Player p = (Player) sender;
            openChooseGui(p);
            return true;
        }

        if (cmd.equals("resetanimal")) {
            // /resetanimal <player|@a>  or no args -> reset self (if player)
            if (args.length == 0) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /resetanimal <player|@a>");
                    return true;
                }
                Player p = (Player) sender;
                if (!p.isOp()) {
                    p.sendMessage(ChatColor.RED + "Only OPs can reset others. Use /resetanimal <player> or ask an OP.");
                    return true;
                }
                resetPlayer(p);
                sender.sendMessage(ChatColor.GREEN + "Your animal reset.");
                return true;
            } else {
                // args provided
                String targetArg = args[0];
                if (targetArg.equalsIgnoreCase("@a")) {
                    // OP-only or console
                    if (sender instanceof Player) {
                        Player p = (Player) sender;
                        if (!p.isOp()) { p.sendMessage(ChatColor.RED + "Only OPs may reset all players."); return true; }
                    }
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        resetPlayer(online);
                    }
                    sender.sendMessage(ChatColor.GREEN + "All players' animals have been reset.");
                    return true;
                } else {
                    Player target = Bukkit.getPlayerExact(targetArg);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Player not found or offline.");
                        return true;
                    }
                    // only OPs & console can reset others
                    if (sender instanceof Player) {
                        Player p = (Player) sender;
                        if (!p.isOp()) {
                            p.sendMessage(ChatColor.RED + "Only OPs can reset other players.");
                            return true;
                        }
                    }
                    resetPlayer(target);
                    sender.sendMessage(ChatColor.GREEN + "Reset " + target.getName() + "'s animal.");
                    return true;
                }
            }
        }

        // ability commands (one-word)
        List<String> abilities = Arrays.asList(
                "pounce","focus","hover","escape","harden","gallop","soften","sting","glide","discovery"
        );
        if (abilities.contains(cmd)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players may use abilities.");
                return true;
            }
            useAbility((Player) sender, cmd);
            return true;
        }

        return false;
    }

    // ---------------- GUI: choose -> confirm flow ----------------
    private void openChooseGui(Player p) {
        if (chosen.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset it.");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 54, CHOOSE_TITLE);

        // We'll place pairs: icon (slot i) and paper (i+9) so icons align side-by-side visually.
        // Row design and chosen slots: place animals across the top two rows for clarity.
        // 0..8 top row, 9..17 second row papers etc. We'll place icons starting at 10 etc so they look grouped.
        // Simpler: place icons spread across first row; place description paper directly below each icon.

        // icon slot indices and paper slot indices
        int[] iconSlots = {10, 12, 14, 16, 19, 21, 23, 25, 30, 32}; // space for each animal icon
        int[] paperSlots = {19, 22, 25, 28, 31, 34, 37, 40, 41, 44}; // under/near icons (approx)

        // Animals and their icon materials + descriptions (kept as requested)
        List<AnimalDescriptor> animals = Arrays.asList(
                new AnimalDescriptor("wolf", safeMaterial("WOLF_SPAWN_EGG"), "Wolf!", "Strong and Brave!", "PASSIVE: Strength I, Speed I", "ABILITY: Pounce", "Effect: Jump Boost I, Strength II"),
                new AnimalDescriptor("cat", safeMaterial("CAT_SPAWN_EGG"), "Cat!", "Agile and Alert!", "PASSIVE: Jump Boost II, Night Vision", "ABILITY: Focus", "Effect: Haste, Speed II"),
                new AnimalDescriptor("bee", safeMaterial("BEE_SPAWN_EGG"), "Bee!", "Airborne and tiny!", "PASSIVE: Slow Falling, Jump Boost II", "ABILITY: Hover", "Effect: Levitation, Resistance II (on use); Passive: Levitation + Speed III (update)"),
                new AnimalDescriptor("fox", safeMaterial("FOX_SPAWN_EGG"), "Fox!", "Sneaky and Swift!", "PASSIVE: Speed II, Invisibility at night", "ABILITY: Escape", "Effect: Speed III, Haste"),
                new AnimalDescriptor("turtle", safeMaterial("TURTLE_HELMET"), "Turtle!", "Slow but tough!", "PASSIVE: Water Breathing, Resistance I", "ABILITY: Harden", "Effect: Resistance III, Slowness II"),
                new AnimalDescriptor("horse", safeMaterial("SADDLE"), "Horse!", "Fast and reliable!", "PASSIVE: Speed I, Jump Boost I", "ABILITY: Gallop", "Effect: Speed II, Jump Boost II"),
                new AnimalDescriptor("sheep", safeMaterial("WHITE_WOOL"), "Sheep!", "Soft yet sturdy!", "PASSIVE: Resistance I, Jump Boost I", "ABILITY: Soften", "Effect: Jump Boost II, Regeneration I"),
                new AnimalDescriptor("ant", safeMaterial("SOUL_SOIL"), "Ant!", "Tiny powerhouse!", "PASSIVE: Haste, Strength I", "ABILITY: Sting", "Effect: Strength II, Speed I"),
                new AnimalDescriptor("owl", safeMaterial("FEATHER"), "Owl!", "Silent nocturnal flyer!", "PASSIVE: Night Vision, Slow Falling, Jump Boost I", "ABILITY: Glide", "Effect: Speed II, Jump Boost II"),
                new AnimalDescriptor("coppergolem", safeMaterial("COPPER_INGOT"), "CopperGolem!", "Curious construct!", "PASSIVE: Speed, Haste", "ABILITY: Discovery", "Effect: Haste II, Resistance II")
        );

        // Fill with icons and papers
        for (int i = 0; i < animals.size(); i++) {
            AnimalDescriptor ad = animals.get(i);
            int iconSlot = (i < iconSlots.length) ? iconSlots[i] : (10 + i);
            int paperSlot = (i < paperSlots.length) ? paperSlots[i] : (28 + i);

            inv.setItem(iconSlot, makeItem(ad.iconMaterial, ad.title, ad.subtitle, ""));
            inv.setItem(paperSlot, makeItem(Material.PAPER, ad.title + " Info", ad.passive, ad.ability + " - " + ad.abilityEffect));
        }

        // confirm & cancel area (bottom row)
        inv.setItem(49, makeItem(Material.GREEN_CONCRETE, "Confirm", "Confirm your selected animal", "Click to confirm"));
        inv.setItem(53, makeItem(Material.RED_CONCRETE, "Cancel", "Cancel selection", "Close menu"));

        p.openInventory(inv);
        // sound when opening
        playSafeSound(p, Sound.UI_BUTTON_CLICK);
    }

    // descriptor helper
    private static class AnimalDescriptor {
        final String id;
        final Material iconMaterial;
        final String title;
        final String subtitle;
        final String passive;
        final String ability;
        final String abilityEffect;

        AnimalDescriptor(String id, Material iconMaterial, String title, String subtitle, String passive, String ability, String abilityEffect) {
            this.id = id;
            this.iconMaterial = iconMaterial;
            this.title = title;
            this.subtitle = subtitle;
            this.passive = passive;
            this.ability = ability;
            this.abilityEffect = abilityEffect;
        }
    }

    private ItemStack makeItem(Material mat, String name, String lore1, String lore2) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name);
            List<String> lore = new ArrayList<>();
            if (lore1 != null && !lore1.isEmpty()) lore.add(ChatColor.WHITE + lore1);
            if (lore2 != null && !lore2.isEmpty()) lore.add(ChatColor.GREEN + lore2);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }

    private Material safeMaterial(String s) {
        if (s == null) return Material.BARRIER;
        Material m = Material.matchMaterial(s);
        return m != null ? m : Material.BARRIER;
    }

    // ---------------- Inventory interactions ----------------
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView() == null || e.getView().getTitle() == null) return;

        String title = e.getView().getTitle();
        if (!title.equals(CHOOSE_TITLE) && !title.equals(CONFIRM_TITLE)) return;

        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();

        ItemStack current = e.getCurrentItem();
        if (current == null || !current.hasItemMeta()) {
            // clicking empty slot -> ignore
            return;
        }

        String display = ChatColor.stripColor(current.getItemMeta().getDisplayName());
        String key = display.replace(" ", "").replace("!", "").toLowerCase(Locale.ROOT);

        if (title.equals(CHOOSE_TITLE)) {
            // Confirm / Cancel buttons
            if (display.equalsIgnoreCase("Confirm")) {
                // find selected candidate paper or icon in the inventory
                // We'll detect the first paper in view that contains "Info" or a named icon that is not confirm/cancel
                String chosenId = null;
                for (ItemStack it : e.getView().getTopInventory().getContents()) {
                    if (it == null || !it.hasItemMeta()) continue;
                    String d = ChatColor.stripColor(it.getItemMeta().getDisplayName());
                    if (d.endsWith(" Info")) {
                        // extract id from title (e.g., "Wolf! Info")
                        String id = d.replace(" Info", "").replace("!", "").trim().toLowerCase(Locale.ROOT);
                        // map to our canonical ids
                        id = id.replace("coppergolem", "coppergolem");
                        if (isValidAnimalId(id)) { chosenId = id; break; }
                    }
                }
                // If we couldn't auto-detect, attempt to read clicked item (maybe they clicked an icon)
                if (chosenId == null) {
                    if (isValidAnimalId(key)) chosenId = key;
                }
                if (chosenId == null) {
                    p.sendMessage(ChatColor.RED + "No animal selected to confirm.");
                    playSafeSound(p, Sound.BLOCK_ANVIL_LAND);
                    p.closeInventory();
                    return;
                }
                // if player already chosen before confirming
                if (chosen.containsKey(p.getUniqueId())) {
                    p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset it.");
                    playSafeSound(p, Sound.ENTITY_VILLAGER_NO);
                    p.closeInventory();
                    return;
                }
                // open a final confirmation GUI with green/red
                Inventory conf = Bukkit.createInventory(null, 9, CONFIRM_TITLE);
                conf.setItem(3, makeItem(safeMaterial("PAPER"), capitalize(chosenId), "Confirm your choice", "Click green to accept"));
                conf.setItem(4, makeItem(Material.GREEN_CONCRETE, "Confirm", "Click to accept", ""));
                conf.setItem(5, makeItem(Material.RED_CONCRETE, "Cancel", "Click to cancel", ""));
                // store chosen id in persistent metadata? Simpler: use player's persistent data? Here, we temporarily store in cooldowns map under special key
                // Instead of complex storage, we'll set player's held metadata by putting into cooldowns map under UUID with key "__pending__" -> value = time + chosenId hashed
                // Simpler approach: store in chosen map only after pressing confirm. So we attach chosenId to player's scoreboard? To keep simple: store in a temporary field map.
                pendingConfirm.put(p.getUniqueId(), chosenId);
                p.openInventory(conf);
                playSafeSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                return;
            } else if (display.equalsIgnoreCase("Cancel")) {
                playSafeSound(p, Sound.ENTITY_ITEM_BREAK);
                p.closeInventory();
                return;
            }

            // clicking an animal icon or info paper -> select that animal visually by opening a small confirm dialog
            String candidate = key;
            if (candidate.endsWith("info")) candidate = candidate.replace(" info", "").trim();
            // normalize certain names
            candidate = candidate.replace(" ", "").replace("-", "").toLowerCase(Locale.ROOT);
            if (!isValidAnimalId(candidate)) {
                // could be "Wolf!" or similar; try strip punctuation
                candidate = candidate.replace("!", "");
            }
            if (!isValidAnimalId(candidate)) {
                // not a selectable item
                return;
            }

            // open small confirm GUI for this specific animal
            Inventory conf = Bukkit.createInventory(null, 9, CONFIRM_TITLE);
            conf.setItem(1, makeItem(getAnimalIconFor(candidate), capitalize(candidate), "Confirm your choice of " + capitalize(candidate), ""));
            conf.setItem(3, makeItem(Material.PAPER, "PASSIVE", getPassiveFor(candidate), ""));
            conf.setItem(5, makeItem(Material.PAPER, "ABILITY", getAbilityFor(candidate) + " - " + getAbilityEffectFor(candidate), ""));
            conf.setItem(7, makeItem(Material.GREEN_CONCRETE, "Confirm", "Click to accept", ""));
            conf.setItem(8, makeItem(Material.RED_CONCRETE, "Cancel", "Click to cancel", ""));
            pendingConfirm.put(p.getUniqueId(), candidate);
            p.openInventory(conf);
            playSafeSound(p, Sound.UI_BUTTON_CLICK);
            return;
        }

        // CONFIRM_TITLE handling (final confirm)
        if (title.equals(CONFIRM_TITLE)) {
            if (display.equalsIgnoreCase("Confirm")) {
                String pending = pendingConfirm.remove(p.getUniqueId());
                if (pending == null) {
                    p.sendMessage(ChatColor.RED + "Nothing pending to confirm.");
                    playSafeSound(p, Sound.BLOCK_ANVIL_LAND);
                    p.closeInventory();
                    return;
                }
                // lock choice
                if (chosen.containsKey(p.getUniqueId())) {
                    p.sendMessage(ChatColor.RED + "You already chose an animal. Ask an OP to reset it.");
                    playSafeSound(p, Sound.ENTITY_VILLAGER_NO);
                    p.closeInventory();
                    return;
                }
                chosen.put(p.getUniqueId(), pending);
                saveChoices();
                applyPassiveToPlayer(p, pending);
                p.s
