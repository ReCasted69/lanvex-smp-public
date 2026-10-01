package me.yourname.privaterealms;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class RealmManager implements Listener {

    private final JavaPlugin plugin;
    private final File dataFile;
    private FileConfiguration data;
    private final File worldsRoot;
    private final Map<UUID, RealmInfo> realms = new HashMap<>();

    public RealmManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        this.worldsRoot = new File(plugin.getServer().getWorldContainer(), plugin.getConfig().getString("realms-folder", "private_realms"));
        if (!worldsRoot.exists()) worldsRoot.mkdirs();
        loadData();
        // register worlds that exist in data
        registerSavedRealms();
    }

    // ----------- Data structures --------------
    public static class RealmInfo {
        public UUID owner;
        public String worldName; // folder name
        public Set<String> invites = new HashSet<>();
        public Set<String> bans = new HashSet<>();
        public Set<String> requests = new HashSet<>(); // pending join requests (player names)
        public RealmInfo(UUID owner, String worldName) {
            this.owner = owner;
            this.worldName = worldName;
        }
        public int onlineCount() {
            World w = Bukkit.getWorld(worldName);
            return (w == null) ? 0 : w.getPlayers().size();
        }
    }

    // ------------- Persistence -----------------
    public void loadData() {
        if (!dataFile.exists()) {
            this.data = YamlConfiguration.loadConfiguration(dataFile);
            saveData();
            return;
        }
        this.data = YamlConfiguration.loadConfiguration(dataFile);
        // load realms map from yml
        if (data.isConfigurationSection("realms")) {
            for (String key : data.getConfigurationSection("realms").getKeys(false)) {
                String path = "realms." + key;
                UUID owner = UUID.fromString(key);
                String worldName = data.getString(path + ".worldName");
                RealmInfo r = new RealmInfo(owner, worldName);
                r.invites.addAll(data.getStringList(path + ".invites"));
                r.bans.addAll(data.getStringList(path + ".bans"));
                r.requests.addAll(data.getStringList(path + ".requests"));
                realms.put(owner, r);
            }
        }
    }

    public void saveData() {
        try {
            if (data == null) data = YamlConfiguration.loadConfiguration(dataFile);
            data.set("realms", null);
            for (Map.Entry<UUID, RealmInfo> e : realms.entrySet()) {
                String base = "realms." + e.getKey().toString();
                RealmInfo r = e.getValue();
                data.set(base + ".worldName", r.worldName);
                data.set(base + ".invites", new ArrayList<>(r.invites));
                data.set(base + ".bans", new ArrayList<>(r.bans));
                data.set(base + ".requests", new ArrayList<>(r.requests));
            }
            data.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save data.yml: " + ex.getMessage());
        }
    }

    // ------------- helper ---------------------
    private String realmFolderName(UUID owner) {
        return "PrivateRealm_" + owner.toString().replace("-", "");
    }

    private File realmFolder(UUID owner) {
        return new File(worldsRoot, realmFolderName(owner));
    }

    private boolean worldExistsOnDisk(File folder) {
        return folder.exists() && folder.isDirectory();
    }

    private void registerSavedRealms() {
        // Ensure world's loaded? We don't auto-load all worlds on start. We'll register existence.
        for (RealmInfo r : realms.values()) {
            // if world is on disk but not loaded, leave it unloaded until someone joins
            // if world is loaded, ok
        }
    }

    // ------------- Events for unload -----------
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        maybeUnloadWorld(e.getPlayer().getWorld());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        maybeUnloadWorld(e.getFrom());
        maybeUnloadWorld(e.getPlayer().getWorld());
    }

    private void maybeUnloadWorld(World w) {
        if (w == null) return;
        // don't unload hub or non-realm worlds
        String hub = plugin.getConfig().getString("hub-world", "world");
        if (w.getName().equals(hub)) return;
        for (RealmInfo r : realms.values()) {
            if (r.worldName.equals(w.getName())) {
                // check player count
                if (w.getPlayers().isEmpty() && plugin.getConfig().getBoolean("auto-unload-when-empty", true)) {
                    Bukkit.getLogger().info("Auto-unloading realm world " + w.getName() + " (empty).");
                    Bukkit.unloadWorld(w, true);
                }
                return;
            }
        }
    }

    // -------------- Core API -------------------
    public boolean hasRealm(UUID owner) {
        return realms.containsKey(owner) && worldExistsOnDisk(realmFolder(owner));
    }

    public boolean createRealm(Player owner) {
        UUID uid = owner.getUniqueId();
        if (realms.containsKey(uid)) return false;
        // create folder name
        File dest = realmFolder(uid);
        if (dest.exists()) return false;
        // find template
        String template = plugin.getConfig().getString("template-world", "template");
        File templateFolder = new File(plugin.getServer().getWorldContainer(), template);
        if (!templateFolder.exists()) {
            owner.sendMessage(ChatColor.RED + "Template world not found: " + template + ". Please create a world named 'template' or change config.");
            return false;
        }
        try {
            Utils.copyFolder(templateFolder.toPath(), dest.toPath());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed copying template: " + e.getMessage());
            owner.sendMessage(ChatColor.RED + "Failed to create realm: " + e.getMessage());
            return false;
        }
        // create world instance
        WorldCreator wc = new WorldCreator(dest.getName());
        wc.environment(World.Environment.NORMAL);
        wc.generateStructures(true);
        World w = Bukkit.createWorld(wc);
        RealmInfo info = new RealmInfo(uid, w.getName());
        realms.put(uid, info);
        saveData();
        owner.teleport(w.getSpawnLocation());
        owner.sendMessage(ChatColor.GREEN + "Realm created and you have been teleported to it.");
        return true;
    }

    public boolean deleteRealm(Player owner) {
        UUID uid = owner.getUniqueId();
        RealmInfo r = realms.get(uid);
        if (r == null) {
            owner.sendMessage(ChatColor.RED + "You don't have a realm.");
            return false;
        }
        World w = Bukkit.getWorld(r.worldName);
        if (w != null) {
            // kick everyone to hub
            String hub = plugin.getConfig().getString("hub-world", "world");
            World hubWorld = Bukkit.getWorld(hub);
            for (Player p : new ArrayList<>(w.getPlayers())) {
                if (hubWorld != null) p.teleport(hubWorld.getSpawnLocation());
            }
            Bukkit.unloadWorld(w, true);
        }
        // delete files
        try {
            Utils.deleteFolder(new File(worldsRoot, r.worldName).toPath());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to delete realm files: " + e.getMessage());
            owner.sendMessage(ChatColor.RED + "Failed to delete realm files: " + e.getMessage());
            return false;
        }
        realms.remove(uid);
        saveData();
        owner.sendMessage(ChatColor.GREEN + "Your realm was deleted.");
        return true;
    }

    public boolean joinRealm(Player player, String ownerName, boolean adminBypass) {
        // find owner by name
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(ownerName);
        UUID ownerUUID = null;
        if (off != null && off.hasPlayedBefore()) ownerUUID = off.getUniqueId();
        else {
            // try by online player
            Player on = Bukkit.getPlayerExact(ownerName);
            if (on != null) ownerUUID = on.getUniqueId();
        }

        if (ownerUUID == null) {
            player.sendMessage(ChatColor.RED + "Player not found: " + ownerName);
            return false;
        }

        RealmInfo r = realms.get(ownerUUID);
        if (r == null) {
            player.sendMessage(ChatColor.RED + "That player does not have a realm.");
            return false;
        }

        if (r.bans.contains(player.getName())) {
            player.sendMessage(ChatColor.RED + "You are banned from that realm.");
            return false;
        }

        // admin bypass: ops can always join
        if (!adminBypass && !player.isOp()) {
            // owner can always join own realm
            if (!ownerUUID.equals(player.getUniqueId())) {
                // check invites or accepted requests
                if (!r.invites.contains(player.getName()) && !r.requests.contains(player.getName())) {
                    player.sendMessage(ChatColor.RED + "You are not invited and you have no accepted request. Use /realm request " + ownerName + " to ask.");
                    return false;
                }
            }
        }

        // check max players
        int max = plugin.getConfig().getInt("max-players-per-realm", 4);
        World w = Bukkit.getWorld(r.worldName);
        if (w == null) {
            // load world from disk
            File f = new File(worldsRoot, r.worldName);
            if (!f.exists()) {
                player.sendMessage(ChatColor.RED + "Realm world files missing.");
                return false;
            }
            WorldCreator wc = new WorldCreator(f.getName());
            w = Bukkit.createWorld(wc);
            if (w == null) {
                player.sendMessage(ChatColor.RED + "Failed to load realm world.");
                return false;
            }
        }
        if (w.getPlayers().size() >= max && !player.isOp()) {
            player.sendMessage(ChatColor.RED + "That realm is full (" + w.getPlayers().size() + "/" + max + ").");
            return false;
        }

        player.teleport(w.getSpawnLocation());
        player.sendMessage(ChatColor.GREEN + "Teleported to " + ownerName + "'s realm.");
        // If join was via accepted request, remove it (single-use)
        r.requests.remove(player.getName());
        saveData();
        return true;
    }

    public boolean invite(Player owner, String targetName) {
        UUID uid = owner.getUniqueId();
        RealmInfo r = realms.get(uid);
        if (r == null) {
            owner.sendMessage(ChatColor.RED + "You must create your realm first.");
            return false;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            owner.sendMessage(ChatColor.RED + "Player not found or offline: " + targetName);
            return false;
        }
        if (r.bans.contains(target.getName())) {
            owner.sendMessage(ChatColor.RED + "That player is banned from your realm.");
            return false;
        }
        r.invites.add(target.getName());
        saveData();
        owner.sendMessage(ChatColor.GREEN + "Invited " + target.getName() + " to your realm.");
        target.sendMessage(ChatColor.GOLD + owner.getName() + " invited you to their realm. Use " + ChatColor.YELLOW + "/realm join " + owner.getName());
        return true;
    }

    public boolean requestJoin(Player requester, String ownerName) {
        Player owner = Bukkit.getPlayerExact(ownerName);
        UUID ownerUuid = null;
        if (owner != null) ownerUuid = owner.getUniqueId();
        else {
            OfflinePlayer off = Bukkit.getOfflinePlayer(ownerName);
            if (off != null && off.hasPlayedBefore()) ownerUuid = off.getUniqueId();
        }
        if (ownerUuid == null) {
            requester.sendMessage(ChatColor.RED + "Player not found: " + ownerName);
            return false;
        }
        RealmInfo r = realms.get(ownerUuid);
        if (r == null) {
            requester.sendMessage(ChatColor.RED + "That player does not have a realm.");
            return false;
        }
        if (r.bans.contains(requester.getName())) {
            requester.sendMessage(ChatColor.RED + "You are banned from that realm.");
            return false;
        }
        r.requests.add(requester.getName());
        saveData();
        // notify owner (if online) with clickable message (simple text)
        if (owner != null) {
            owner.sendMessage(ChatColor.AQUA + requester.getName() + " has requested to join your realm. Use " + ChatColor.YELLOW + "/realm accept " + requester.getName() + ChatColor.AQUA + " or " + ChatColor.YELLOW + "/realm deny " + requester.getName());
        }
        requester.sendMessage(ChatColor.GREEN + "Request sent to " + ownerName + ". Wait for them to accept.");
        return true;
    }

    public boolean acceptRequest(Player owner, String requesterName) {
        UUID uid = owner.getUniqueId();
        RealmInfo r = realms.get(uid);
        if (r == null) {
            owner.sendMessage(ChatColor.RED + "You don't have a realm.");
            return false;
        }
        if (!r.requests.contains(requesterName)) {
            owner.sendMessage(ChatColor.RED + "No join request from " + requesterName);
            return false;
        }
        // move request to invites (or leave as accepted — we'll allow immediate join)
        r.invites.add(requesterName);
        r.requests.remove(requesterName);
        saveData();
        owner.sendMessage(ChatColor.GREEN + "Accepted join request from " + requesterName);
        Player p = Bukkit.getPlayerExact(requesterName);
        if (p != null) p.sendMessage(ChatColor.GREEN + owner.getName() + " accepted your request. Use " + ChatColor.YELLOW + "/realm join " + owner.getName());
        return true;
    }

    public boolean denyRequest(Player owner, String requesterName) {
        UUID uid = owner.getUniqueId();
        RealmInfo r = realms.get(uid);
        if (r == null) {
            owner.sendMessage(ChatColor.RED + "You don't have a realm.");
            return false;
        }
        if (!r.requests.contains(requesterName)) {
            owner.sendMessage(ChatColor.RED + "No join request from " + requesterName);
            return false;
        }
        r.requests.remove(requesterName);
        saveData();
        owner.sendMessage(ChatColor.GREEN + "Denied join request from " + requesterName);
        Player p = Bukkit.getPlayerExact(requesterName);
        if (p != null) p.sendMessage(ChatColor.RED + owner.getName() + " denied your request to join their realm.");
        return true;
    }

    public boolean kickFromRealm(Player owner, String targetName) {
        UUID uid = owner.getUniqueId();
        RealmInfo r = realms.get(uid);
        if (r == null) {
            owner.sendMessage(ChatColor.RED + "You don't have a realm.");
            return false;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            owner.sendMessage(ChatColor.RED + "Player offline or not found.");
            return false;
        }
        World w = Bukkit.getWorld(r.worldName);
        if (w == null || !target.getWorld().equals(w)) {
            owner.sendMessage(ChatColor.RED + "That player is not in your realm.");
            return false;
        }
        // teleport to hub
        String hub = plugin.getConfig().getString("hub-world", "world");
        World hubWorld = Bukkit.getWorld(hub);
        if (hubWorld != null) target.teleport(hubWorld.getSpawnLocation());
        owner.sendMessage(ChatColor.GREEN + "Kicked " + targetName + " from your realm.");
        target.sendMessage(ChatColor.RED + "You were kicked from " + owner.getName() + "'s realm.");
        return true;
    }

    public boolean banFromRealm(Player owner, String targetName) {
        UUID uid = owner.getUniqueId();
        RealmInfo r = realms.get(uid);
        if (r == null) {
            owner.sendMessage(ChatColor.RED + "You don't have a realm.");
            return false;
        }
        // remove if currently inside
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null) {
            World w = Bukkit.getWorld(r.worldName);
            if (w != null && target.getWorld().equals(w)) {
                String hub = plugin.getConfig().getString("hub-world", "world");
                World hubWorld = Bukkit.getWorld(hub);
                if (hubWorld != null) target.teleport(hubWorld.getSpawnLocation());
            }
        }
        r.bans.add(targetName);
        r.invites.remove(targetName);
        r.requests.remove(targetName);
        saveData();
        owner.sendMessage(ChatColor.GREEN + "Banned " + targetName + " from your realm.");
        Player on = Bukkit.getPlayerExact(targetName);
        if (on != null) on.sendMessage(ChatColor.RED + "You were banned from " + owner.getName() + "'s realm.");
        return true;
    }

    public boolean unbanFromRealm(Player owner, String targetName) {
        UUID uid = owner.getUniqueId();
        RealmInfo r = realms.get(uid);
        if (r == null) {
            owner.sendMessage(ChatColor.RED + "You don't have a realm.");
            return false;
        }
        if (r.bans.contains(targetName)) {
            r.bans.remove(targetName);
            saveData();
            owner.sendMessage(ChatColor.GREEN + "Unbanned " + targetName + ".");
            return true;
        } else {
            owner.sendMessage(ChatColor.RED + "Player not banned.");
            return false;
        }
    }

    // ----------------- Command dispatcher ------------------
    public void handleCommand(Player p, String[] args) {
        if (args.length == 0) {
            p.sendMessage(ChatColor.YELLOW + "Usage: /realm create | delete | invite <player> | request <player> | accept <player> | deny <player> | join [player] | kick <player> | ban <player> | unban <player> | list");
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> createRealmCommand(p);
            case "delete" -> deleteRealmCommand(p);
            case "invite" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /realm invite <player>"); return; }
                invite(p, args[1]);
            }
            case "request" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /realm request <player>"); return; }
                requestJoin(p, args[1]);
            }
            case "accept" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /realm accept <player>"); return; }
                acceptRequest(p, args[1]);
            }
            case "deny" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /realm deny <player>"); return; }
                denyRequest(p, args[1]);
            }
            case "join" -> {
                if (args.length == 1) { joinSelf(p); }
                else if (args.length >= 2) {
                    // check for "admin" flag: if last arg == "admin" and player is op, allow bypass
                    boolean adminBypass = p.isOp() || (args.length >=3 && args[2].equalsIgnoreCase("admin") && p.isOp());
                    joinRealm(p, args[1], adminBypass);
                }
            }
            case "kick" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /realm kick <player>"); return; }
                kickFromRealm(p, args[1]);
            }
            case "ban" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /realm ban <player>"); return; }
                banFromRealm(p, args[1]);
            }
            case "unban" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /realm unban <player>"); return; }
                unbanFromRealm(p, args[1]);
            }
            case "list" -> {
                listInvites(p);
            }
            case "info" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /realm info <player>"); return; }
                infoFor(p, args[1]);
            }
            default -> p.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }
    }

    // specific handlers
    private void createRealmCommand(Player p) {
        if (hasRealm(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You already have a realm.");
            return;
        }
        createRealm(p);
    }

    private void deleteRealmCommand(Player p) {
        deleteRealm(p);
    }

    private void joinSelf(Player p) {
        // join own realm
        UUID uid = p.getUniqueId();
        RealmInfo r = realms.get(uid);
        if (r == null) {
            p.sendMessage(ChatColor.RED + "You don't have a realm. Use /realm create.");
            return;
        }
        joinRealm(p, p.getName(), p.isOp());
    }

    private void listInvites(Player p) {
        RealmInfo r = realms.get(p.getUniqueId());
        if (r == null) {
            p.sendMessage(ChatColor.YELLOW + "You haven't created a realm yet.");
            return;
        }
        p.sendMessage(ChatColor.AQUA + "Invites: " + String.join(", ", r.invites.isEmpty() ? Collections.singleton("none") : r.invites));
        p.sendMessage(ChatColor.AQUA + "Pending requests: " + String.join(", ", r.requests.isEmpty() ? Collections.singleton("none") : r.requests));
        p.sendMessage(ChatColor.AQUA + "Bans: " + String.join(", ", r.bans.isEmpty() ? Collections.singleton("none") : r.bans));
    }

    private void infoFor(Player p, String playerName) {
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(playerName);
        UUID ownerUuid = null;
        if (off != null && off.hasPlayedBefore()) ownerUuid = off.getUniqueId();
        else {
            Player on = Bukkit.getPlayerExact(playerName);
            if (on != null) ownerUuid = on.getUniqueId();
        }
        if (ownerUuid == null) {
            p.sendMessage(ChatColor.RED + "Player not found.");
            return;
        }
        RealmInfo r = realms.get(ownerUuid);
        if (r == null) {
            p.sendMessage(ChatColor.YELLOW + "That player has no realm.");
            return;
        }
        p.sendMessage(ChatColor.GREEN + "Realm owner: " + playerName + " | world: " + r.worldName + " | online: " + r.onlineCount());
    }

    // ---------------- getters for scoreboard --------------
    public List<RealmInfo> listActiveRealms() {
        // return realms where world exists on disk (we consider them active)
        return realms.values().stream().collect(Collectors.toList());
    }

    public RealmInfo getRealm(UUID owner) {
        return realms.get(owner);
    }
}
