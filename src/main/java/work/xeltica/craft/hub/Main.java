package work.xeltica.craft.hub;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;
import work.xeltica.craft.hub.EmptyChunkGenerator;

public class Main extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        playersFile = new File(getDataFolder(), "players.yml");
        getCommand("hub").setExecutor(this);
        logger = getLogger();
        getServer().getPluginManager().registerEvents(this, this);
        var world = getServer().getWorld("hub");
        if (world != null) {
            worldUuid = world.getUID();
        }
        players = YamlConfiguration.loadConfiguration(playersFile);
    }

    @Override
    public void onDisable() {
        worldUuid = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Player が実行してください");
            return true;
        }
        var player = (Player) sender;
        var isAdmin = player.hasPermission("hub.admin") || player.isOp();
        var server = getServer();

        if (args.length == 0) {
            if (worldUuid == null) {
                player.sendMessage("hub が未生成");
                return true;
            }
            server.getScheduler().runTaskLater(this, new Runnable(){
                @Override
                public void run() {
                    var world = server.getWorld(worldUuid);
                    var loc = world.getSpawnLocation();
                    writePlayerConfig(player);
                    player.getInventory().clear();
                    player.setGameMode(GameMode.ADVENTURE);
                    player.teleport(loc, TeleportCause.PLUGIN);
                }
            }, 20 * 5);
            player.sendMessage("5秒後にロビーに移動します...");
            return true;
        }

        if (!isAdmin) {
            player.sendMessage("/hub");
            return true;
        }
        if (args[0].equalsIgnoreCase("create")) {
            var world = new WorldCreator("hub").environment(Environment.NORMAL).generator(new EmptyChunkGenerator())
                    .createWorld();
            updateWorld(world);
            world.getBlockAt(0, 60, 0).setType(Material.BIRCH_LOG);
            player.sendMessage("Generated!");
            return true;
        } else if (args[0].equalsIgnoreCase("help")) {
            player.sendMessage("/hub [create/help/main/delete/unload/update]");
            return true;
        } else if (args[0].equalsIgnoreCase("unload")) {
            if (worldUuid == null) {
                player.sendMessage("hub が未生成");
                return true;
            }
            var world = server.getWorld(worldUuid);
            server.unloadWorld(world, true);
            worldUuid = null;
            return true;
        } else if (args[0].equalsIgnoreCase("update")) {
            if (worldUuid == null) {
                player.sendMessage("hub が未生成");
                return true;
            }
            var world = server.getWorld(worldUuid);
            updateWorld(world);
            return true;
        }
        return false;
    }

    private void writePlayerConfig(Player player) {
        var uid = player.getUniqueId().toString();
        var section = players.createSection(uid);
        var inv = player.getInventory();
        var items = new ItemStack[inv.getSize()];

        for (var i = 0; i < items.length; i++) {
            items[i] = inv.getItem(i);
        }
        
        section.set("location", player.getLocation());
        section.set("items", items);
        section.set("gamemode", player.getGameMode());

        try {
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
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (worldUuid == null)
            return;
        var player = e.getPlayer();
        if (player.getWorld().getUID().equals(worldUuid)) {
            e.setCancelled(true);
            var inv = player.getInventory();
            inv.clear();
            
            var world = getServer().getWorld("world");
            var section = players.getConfigurationSection(player.getUniqueId().toString());
            if (section == null) {
                player.teleport(world.getSpawnLocation(), TeleportCause.PLUGIN);
                player.sendMessage(ChatColor.RED + "最後にいた場所が記録されていないため、初期スポーンにワープします。これはおそらくバグなので、管理者に報告してください。 Code: 1");
                return;
            }
            var itemsResult = section.get("items");
            if (itemsResult == null || !(itemsResult instanceof ItemStack[])) {
                player.sendMessage(ChatColor.RED + "インベントリ復元失敗。これはおそらくバグなので、管理者に報告してください。 Code: 1");
            } else {
                var items = (ItemStack[])itemsResult;
                for (var i = 0; i < items.length; i++) {
                    inv.setItem(i, items[i]);
                }
            }

            var locationResult = section.get("location");
            if (locationResult == null || !(locationResult instanceof Location)) {
                player.teleport(world.getSpawnLocation(), TeleportCause.PLUGIN);
                player.sendMessage(ChatColor.RED + "最後にいた場所が記録されていないため、初期スポーンにワープします。これはおそらくバグなので、管理者に報告してください。 Code: 2");
                return;
            }
            var gamemode = (GameMode)section.get("gamemode", GameMode.SURVIVAL);

            player.setGameMode(gamemode);
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

    private Logger logger;
    private File playersFile;
    private YamlConfiguration players;
    private UUID worldUuid;
}