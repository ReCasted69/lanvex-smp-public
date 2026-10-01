package com.example.playerexplode;

import org.bukkit.plugin.java.JavaPlugin;

public class ExplodingPlayers extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("ExplodingPlayers has been enabled!");
        saveDefaultConfig(); // saves config.yml if it doesn't exist
        this.getCommand("explode").setExecutor(new ExplodeCommand(this));
    }

    @Override
    public void onDisable() {
        getLogger().info("ExplodingPlayers has been disabled!");
    }
}
