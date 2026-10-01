package me.yourname.privaterealms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class PrivateRealms extends JavaPlugin implements Listener {

    private RealmManager manager;
    private ScoreboardManagerPR sbManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.manager = new RealmManager(this);
        this.sbManager = new ScoreboardManagerPR(this, manager);

        // register events & command handling
        Bukkit.getPluginManager().registerEvents(manager, this);
        Bukkit.getPluginManager().registerEvents(sbManager, this);

        getLogger().info("PrivateRealms enabled.");

        // register periodic scoreboard updates
        new BukkitRunnable() {
            @Override
            public void run() {
                sbManager.updateAll();
            }
        }.runTaskTimer(this, 20L, Math.max(20L, getConfig().getLong("scoreboard.update-interval-ticks", 100L)));

        // save data every X seconds to avoid data loss
        long autosaveTicks = Math.max(100L, getConfig().getLong("autosave-interval-ticks", 1200L));
        new BukkitRunnable() {
            @Override
            public void run() {
                manager.saveData();
            }
        }.runTaskTimer(this, autosaveTicks, autosaveTicks);
    }

    @Override
    public void onDisable() {
        getLogger().info("Saving data...");
        manager.saveData();
        getLogger().info("PrivateRealms disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // We use RealmManager to handle commands for clarity
        if (command.getName().equalsIgnoreCase("realm")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players may use this command.");
                return true;
            }
            Player p = (Player) sender;
            manager.handleCommand(p, args);
            return true;
        }

        if (command.getName().equalsIgnoreCase("hub")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players.");
                return true;
            }
            Player p = (Player) sender;
            String hub = getConfig().getString("hub-world", "world");
            World w = Bukkit.getWorld(hub);
            if (w == null) {
                p.sendMessage(ChatColor.RED + "Hub world not found: " + hub);
            } else {
                p.teleport(w.getSpawnLocation());
                p.sendMessage(ChatColor.GREEN + "Teleported to hub.");
            }
            return true;
        }
        return false;
    }

    public RealmManager getManager() {
        return manager;
    }
}
