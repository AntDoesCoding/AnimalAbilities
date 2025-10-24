package com.animalabilities;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class AnimalAbilities extends JavaPlugin implements Listener {
    private final Map<UUID, String> chosen = new HashMap<>();
    private final Map<UUID, String> pendingConfirm = new HashMap<>();
    private File dataFile; private YamlConfiguration dataYml;
    private static final String CHOOSE_TITLE = ChatColor.DARK_GREEN + "Choose Your Animal";
    private static final String CONFIRM_TITLE = ChatColor.DARK_GREEN + "Confirm Choice";

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) try { getDataFolder().mkdirs(); dataFile.createNewFile(); } catch (Exception ignored) {}
        dataYml = YamlConfiguration.loadConfiguration(dataFile);
        if (dataYml.contains("players"))
            for (String k : dataYml.getConfigurationSection("players").getKeys(false))
                chosen.put(UUID.fromString(k), dataYml.getString("players."+k+".animal",""));
        getLogger().info("Loaded "+chosen.size()+" choices.");
    }

    @Override public void onDisable() {
        dataYml.set("players", null);
        for (var e: chosen.entrySet()) dataYml.set("players."+e.getKey()+".animal", e.getValue());
        try { dataYml.save(dataFile);} catch (IOException ignored) {}
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String cmd = c.getName().toLowerCase();
        if (cmd.equals("chooseanimal")) {
            if (s instanceof Player p) openGui(p); else s.sendMessage("Players only."); return true;
        }
        if (cmd.equals("resetanimal")) {
            if (a.length==0 && s instanceof Player p) { if(!p.isOp()){p.sendMessage("OP only.");return true;}
                reset(p); p.sendMessage("Reset."); return true; }
            if (a.length>0 && a[0].equalsIgnoreCase("@a")) { if(s instanceof Player p&&!p.isOp())return true;
                for(Player pl:Bukkit.getOnlinePlayers()) reset(pl); s.sendMessage("All reset."); return true; }
            Player t=Bukkit.getPlayerExact(a[0]); if(t==null){s.sendMessage("Not found.");return true;}
            if(s instanceof Player p&&!p.isOp()){p.sendMessage("OP only.");return true;}
            reset(t); s.sendMessage("Reset "+t.getName()); return true;
        }
        List<String> ab=List.of("pounce","focus","hover","escape","harden","gallop","soften","sting","glide","discovery");
        if(ab.contains(cmd)&&s instanceof Player p){useAbility(p,cmd);return true;}
        return false;
    }

    private void openGui(Player p){
        if(chosen.containsKey(p.getUniqueId())){p.sendMessage("Already chosen.");return;}
        Inventory inv=Bukkit.createInventory(null,54,CHOOSE_TITLE);
        addAnimal(inv,10,"wolf","WOLF_SPAWN_EGG","Strong and Brave!","PASSIVE: Strength I","ABILITY: Pounce");
        addAnimal(inv,12,"cat","CAT_SPAWN_EGG","Agile and Alert!","PASSIVE: Jump II","ABILITY: Focus");
        addAnimal(inv,14,"bee","BEE_SPAWN_EGG","Airborne!","PASSIVE: Slow Falling","ABILITY: Hover");
        addAnimal(inv,16,"fox","FOX_SPAWN_EGG","Sneaky!","PASSIVE: Speed II","ABILITY: Escape");
        addAnimal(inv,20,"turtle","TURTLE_HELMET","Tough!","PASSIVE: Resistance","ABILITY: Harden");
        addAnimal(inv,22,"horse","SADDLE","Fast!","PASSIVE: Speed I","ABILITY: Gallop");
        addAnimal(inv,24,"sheep","WHITE_WOOL","Soft!","PASSIVE: Jump I","ABILITY: Soften");
        addAnimal(inv,30,"ant","SOUL_SOIL","Tiny!","PASSIVE: Haste","ABILITY: Sting");
        addAnimal(inv,32,"owl","FEATHER","Silent!","PASSIVE: Night Vision","ABILITY: Glide");
        addAnimal(inv,34,"coppergolem","COPPER_INGOT","Curious!","PASSIVE: Haste","ABILITY: Discovery");
        inv.setItem(49,item(Material.GREEN_CONCRETE,"Confirm","Confirm choice"));
        inv.setItem(53,item(Material.RED_CONCRETE,"Cancel","Cancel"));
        p.openInventory(inv);
    }

    private void addAnimal(Inventory inv,int slot,String id,String mat,String sub,String pass,String abil){
        inv.setItem(slot,item(Material.matchMaterial(mat),id.toUpperCase(),sub));
        inv.setItem(slot+9,item(Material.PAPER,pass,abil));
    }

    @EventHandler public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(e.getView().getTitle().equals(CHOOSE_TITLE)){
            e.setCancelled(true);
            ItemStack i=e.getCurrentItem(); if(i==null||!i.hasItemMeta())return;
            String n=ChatColor.stripColor(i.getItemMeta().getDisplayName()).toLowerCase();
            if(n.equals("confirm")){String id=pendingConfirm.remove(p.getUniqueId());
                if(id==null){p.sendMessage("Nothing chosen.");return;}
                chosen.put(p.getUniqueId(),id); p.sendMessage("You are now bonded with the "+id+"!");
                applyPassive(p,id); p.closeInventory(); return;}
            if(n.equals("cancel")){p.closeInventory();return;}
            if(isAnimal(n)){pendingConfirm.put(p.getUniqueId(),n); p.sendMessage("Confirm "+n+"!");return;}
        }
    }

    private boolean isAnimal(String s){return List.of("wolf","cat","bee","fox","turtle","horse","sheep","ant","owl","coppergolem").contains(s);}
    private void reset(Player p){chosen.remove(p.getUniqueId());}
    private void useAbility(Player p,String a){p.sendMessage("Used "+a);}
    private void applyPassive(Player p,String a){p.sendMessage("Passive of "+a+" applied.");}
    private ItemStack item(Material m,String n,String lore){ItemStack i=new ItemStack(m);ItemMeta im=i.getItemMeta();im.setDisplayName(ChatColor.GOLD+n);im.setLore(List.of(ChatColor.WHITE+lore));i.setItemMeta(im);return i;}
                }
