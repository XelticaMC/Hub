package work.xeltica.craft.hub;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;

public class Main extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        playersFile = new File(getDataFolder(), "players.yml");
        getCommand("hub").setExecutor(this);
        logger = getLogger();
        getServer().getPluginManager().registerEvents(this, this);
        var world = getServer().getWorld("hub");
        if (world == null) {
            logger.info("Loading world...");
            world = new WorldCreator("hub").environment(Environment.NORMAL).generator(new EmptyChunkGenerator())
                    .createWorld();
            updateWorld(world);
        }
        worldUuid = world.getUID();
        logger.info("world name: " + world.getName() + "; world uuid: " + worldUuid + ";");
        players = YamlConfiguration.loadConfiguration(playersFile);
    }

    @Override
    public void onDisable() {
        worldUuid = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var isAdmin = sender.hasPermission("hub.admin") || sender.isOp();
        var server = getServer();

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Player が実行してください");
                return true;
            }
            var player = (Player) sender;
            if (worldUuid == null) {
                player.sendMessage("hub が未生成");
                return true;
            }
            var isWarping = isWarpingMap.get(player.getUniqueId());
            if (isWarping != null && isWarping) {
                player.sendMessage("移動中です！");
                return true;
            }
            if (player.getWorld().getUID().equals(worldUuid)) {
                player.sendMessage("既にロビーです！");
                return true;
            }
            server.getScheduler().runTaskLater(this, new Runnable(){
                @Override
                public void run() {
                    var world = server.getWorld(worldUuid);
                    var loc = world.getSpawnLocation();
                    var savePosition =
                        !player.getWorld().getName().equalsIgnoreCase("nightmare");
                    writePlayerConfig(player, savePosition);
                    players = YamlConfiguration.loadConfiguration(playersFile);
                    player.getInventory().clear();
                    player.setGameMode(GameMode.ADVENTURE);
                    player.teleport(loc, TeleportCause.PLUGIN);
                    isWarpingMap.put(player.getUniqueId(), false);
                }
            }, 20 * 5);
            player.sendMessage("5秒後にロビーに移動します...");
            isWarpingMap.put(player.getUniqueId(), true);
            return true;
        }

        if (!isAdmin) {
            sender.sendMessage("/hub");
            return true;
        }
        if (args[0].equalsIgnoreCase("create")) {
            var world = new WorldCreator("hub").environment(Environment.NORMAL).generator(new EmptyChunkGenerator())
                    .createWorld();
            updateWorld(world);
            world.getBlockAt(0, 60, 0).setType(Material.BIRCH_LOG);
            sender.sendMessage("Generated!");
            return true;
        } else if (args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("/hub [create/help/main/delete/unload/update/reloadplayers/forceall]");
            return true;
        } else if (args[0].equalsIgnoreCase("unload")) {
            if (worldUuid == null) {
                sender.sendMessage("hub が未生成");
                return true;
            }
            var world = server.getWorld(worldUuid);
            server.unloadWorld(world, true);
            worldUuid = null;
            return true;
        } else if (args[0].equalsIgnoreCase("update")) {
            if (worldUuid == null) {
                sender.sendMessage("hub が未生成");
                return true;
            }
            var world = server.getWorld(worldUuid);
            updateWorld(world);
            return true;
        } else if (args[0].equalsIgnoreCase("reloadplayers")) {
            players = YamlConfiguration.loadConfiguration(playersFile);
            return true;
        } else if (args[0].equalsIgnoreCase("forceall")) {
            forceAll = !forceAll;
            sender.sendMessage("forceAll を" + forceAll + "にしました");
            return true;
        } else if (args[0].equalsIgnoreCase("nmbed")) {
            if (args.length != 4) {
                sender.sendMessage("/hub nmbed <x> <y> <z>");
                return true;
            }
            var x = Double.parseDouble(args[1]);
            var y = Double.parseDouble(args[2]);
            var z = Double.parseDouble(args[3]);
            var loc = new Location(server.getWorld(worldUuid), x, y, z);
            getConfig().set("nightmareBedLocation", loc);
            saveConfig();
            sender.sendMessage("悪夢ベッドの位置を設定しました。");
            return true;
        }
        return false;
    }

    private void writePlayerConfig(Player player, boolean savesLocation) {
        var uid = player.getUniqueId().toString();

        var section = players.getConfigurationSection(uid);

        if (section == null) {
            players.createSection(uid);
        }

        var inv = player.getInventory();
        var items = new ItemStack[inv.getSize()];

        for (var i = 0; i < items.length; i++) {
            items[i] = inv.getItem(i);
        }
        
        if (savesLocation) {
            section.set("location", player.getLocation());
        }

        section.set("items", items);

        try {
            logger.info(players.toString());
            players.save(playersFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void updateWorld(World world) {
        world.setSpawnLocation(0, 65, 0);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setDifficulty(Difficulty.PEACEFUL);
    }

    @EventHandler
    public void onPlayerHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (worldUuid == null) return;
        var player = (Player)e.getEntity();
        if (player.getWorld().getUID().equals(worldUuid)) {
            e.setCancelled(true);
            if (e.getCause() == DamageCause.VOID) {
                // 落ちた
                var loc = getServer().getWorld(worldUuid).getSpawnLocation();
                player.teleport(loc, TeleportCause.PLUGIN);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        var player = e.getPlayer();
        if (forceAll) {
            var world = getServer().getWorld("world");
            player.teleport(world.getSpawnLocation());
        }
        if (!player.hasPlayedBefore() && worldUuid != null) {
            var world = getServer().getWorld(worldUuid);
            var loc = world.getSpawnLocation();
            player.getInventory().clear();
            player.setGameMode(GameMode.ADVENTURE);
            player.teleport(loc, TeleportCause.PLUGIN);
        }
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (worldUuid == null)
            return;
        var player = e.getPlayer();
        if (player.getWorld().getUID().equals(worldUuid)) {
            e.setCancelled(true);
            player.setGameMode(GameMode.SURVIVAL);

            var world = getServer().getWorld("world");
            ConfigurationSection section = players.getConfigurationSection(player.getUniqueId().toString());
            if (section == null) {
                // はじめましての場合
                player.teleport(world.getSpawnLocation(), TeleportCause.PLUGIN);
                return;
            }

            restoreInventory(player);

            var locationResult = section.get("location");
            if (locationResult == null || !(locationResult instanceof Location)) {
                player.teleport(world.getSpawnLocation(), TeleportCause.PLUGIN);
                player.sendMessage(ChatColor.RED + "最後にいた場所が記録されていないため、初期スポーンにワープします。これはおそらくバグなので、管理者に報告してください。 Code: 2");
                return;
            }

            var loc = (Location)locationResult;
            player.teleport(loc, TeleportCause.PLUGIN);
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent e) {
        if (worldUuid == null)
            return;
        var player = e.getPlayer();
        if (player.getWorld().getUID().equals(worldUuid)) {
            var clicked = e.getRightClicked();
            if (clicked.getCustomName().equals("職員")) {
                player.performCommand("jobs browse");
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractBlock(PlayerInteractEvent e) {
        var p = e.getPlayer();
        var w = p.getWorld();
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (w.getUID().equals(worldUuid)) {
            // 呪いのベッド
            var bedLoc = e.getClickedBlock().getLocation();
            var loc = getConfig().getLocation("nightmareBedLocation");
            if (loc == null) return;
            if (
                bedLoc.getBlockX() != loc.getBlockX() ||
                bedLoc.getBlockY() != loc.getBlockY() ||
                bedLoc.getBlockZ() != loc.getBlockZ()
            ) return;
            e.setCancelled(true);
            var nightmare = getServer().getWorld("nightmare");
            nightmare.setDifficulty(Difficulty.HARD);
            nightmare.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            nightmare.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            nightmare.setGameRule(GameRule.MOB_GRIEFING, false);
            nightmare.setTime(18000);

            restoreInventory(p);
            p.teleport(nightmare.getSpawnLocation());
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 1, 0.5f);
            p.sendMessage("ここは怖い敵がうじゃうじゃいる" + ChatColor.RED + "ナイトメアワールド" + ChatColor.RESET + "。");
            p.sendMessage("手に入れたアイテムは持ち帰れます。");
            p.sendMessage("帰るときは、" + ChatColor.GREEN + "/hub " + ChatColor.RESET + "コマンドを実行してください。");
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        if (e.getPlayer().getWorld().getName().equalsIgnoreCase("nightmare")) {
            // 悪夢から目覚める
            writePlayerConfig(e.getPlayer(), false);
            players = YamlConfiguration.loadConfiguration(playersFile);
            var lobby = getServer().getWorld(worldUuid).getSpawnLocation();
            e.setRespawnLocation(lobby);
        }
    }

    private void restoreInventory(Player player) {
        ConfigurationSection section = players.getConfigurationSection(player.getUniqueId().toString());
        var inv = player.getInventory();
        inv.clear();
        var itemsResult = section.get("items");
        if (itemsResult == null) {
            player.sendMessage(ChatColor.RED + "インベントリ復元失敗。これはおそらくバグなので、管理者に報告してください。 Code: 1");
        } else {
            var items = (ArrayList<ItemStack>) itemsResult;
            for (var i = 0; i < items.size(); i++) {
                inv.setItem(i, items.get(i));
            }
        }
    }

    private Logger logger;
    private File playersFile;
    private YamlConfiguration players;
    private UUID worldUuid;
    private boolean forceAll;
    private HashMap<UUID, Boolean> isWarpingMap = new HashMap<>();
}