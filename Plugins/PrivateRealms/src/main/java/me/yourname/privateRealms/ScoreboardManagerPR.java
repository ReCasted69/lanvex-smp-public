package me.yourname.privaterealms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.List;

public class ScoreboardManagerPR {
    private final PrivateRealms plugin;
    private final RealmManager realmManager;

    public ScoreboardManagerPR(PrivateRealms plugin, RealmManager realmManager) {
        this.plugin = plugin;
        this.realmManager = realmManager;
    }

    public void updateAll() {
        // update scoreboard for all online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateFor(p);
        }
    }

    public void updateFor(Player p) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        Scoreboard board = manager.getNewScoreboard();
        Objective obj = board.registerNewObjective("privaterealms", "dummy", ChatColor.GOLD + "" + ChatColor.BOLD + "Private Realms");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<RealmManager.RealmInfo> list = realmManager.listActiveRealms();
        int score = list.size() + 5;
        if (list.isEmpty()) {
            obj.getScore(ChatColor.GRAY + "No active realms").setScore(score--);
        } else {
            for (RealmManager.RealmInfo r : list) {
                String ownerName = Bukkit.getOfflinePlayer(r.owner).getName();
                if (ownerName == null) ownerName = r.owner.toString().substring(0, 8);
                String line = ChatColor.GREEN + ownerName + ChatColor.WHITE + " (" + r.onlineCount() + "/" + plugin.getConfig().getInt("max-players-per-realm", 4) + ")";
                // Scoreboard lines must be unique; append spaces if necessary
                String key = makeUnique(line, score);
                obj.getScore(key).setScore(score--);
            }
        }
        p.setScoreboard(board);
    }

    private String makeUnique(String text, int index) {
        // append color resets/spaces to make each line unique when necessary
        return text + ChatColor.RESET + " " + index;
    }
}
