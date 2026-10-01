package com.example.playerexplode;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ExplodeCommand implements CommandExecutor {

    private final ExplodingPlayers plugin;

    public ExplodeCommand(ExplodingPlayers plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command!");
            return true;
        }

        // Explosion settings from config
        float power = (float) plugin.getConfig().getDouble("explosion-power", 4.0);
        boolean fire = plugin.getConfig().getBoolean("fire", false);
        boolean breakBlocks = plugin.getConfig().getBoolean("break-blocks", false);

        // Create explosion
        player.getWorld().createExplosion(player.getLocation(), power, fire, breakBlocks);

        // Damage player
        double damage = power * 2; // scale damage with explosion power
        double newHealth = Math.max(player.getHealth() - damage, 0);
        player.setHealth(newHealth);

        player.sendMessage("§4Boom! You exploded yourself and took " + damage + " damage!");
        return true;
    }
}
