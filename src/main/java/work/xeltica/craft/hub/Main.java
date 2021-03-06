package work.xeltica.craft.hub;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;

public class Main extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        playersFile = new File(getDataFolder(), "players.yml");
        signsFile = new File(getDataFolder(), "signs.yml");
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
        world.getPlayers().stream().forEach(player -> {
            player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
            player.setFoodLevel(20);
            player.setSaturation(0);
            player.setExhaustion(0);
            player.setLevel(0);
            player.setExp(0);
            player.setFireTicks(0);
        });
        logger.info("world name: " + world.getName() + "; world uuid: " + worldUuid + ";");
        players = YamlConfiguration.loadConfiguration(playersFile);
        loadSignsFile();
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
            var worldName = player.getWorld().getName();
            var isSandbox = worldName.equals("sandbox");
            server.getScheduler().runTaskLater(this, new Runnable(){
                @Override
                public void run() {
                    var world = server.getWorld(worldUuid);
                    var loc = world.getSpawnLocation();
                    var savePosition = !worldName.equalsIgnoreCase("nightmare");
                    // 砂場から行く場合は記録しない & ポーション効果を潰す
                    if (!isSandbox) {
                        writePlayerConfig(player, savePosition);
                        players = YamlConfiguration.loadConfiguration(playersFile);
                        // ポーション効果削除
                    } else {
                        player.getActivePotionEffects().stream().forEach(e -> player.removePotionEffect(e.getType()));
                    }
                    player.getInventory().clear();
                    player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
                    player.setFoodLevel(20);
                    player.setSaturation(0);
                    player.setExhaustion(0);
                    player.setLevel(0);
                    player.setExp(0);
                    player.setFireTicks(0);

                    player.setGameMode(GameMode.ADVENTURE);
                    player.teleport(loc, TeleportCause.PLUGIN);
                    isWarpingMap.put(player.getUniqueId(), false);
                }
            }, isSandbox ? 1 : 20 * 5);
            if (!isSandbox) {
                player.sendMessage("5秒後にロビーに移動します...");
                isWarpingMap.put(player.getUniqueId(), true);
            }
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
        writePlayerConfig(player, savesLocation, true);
    }

    private void writePlayerConfig(Player player, boolean savesLocation, boolean savesParams) {
        var uid = player.getUniqueId().toString();
        var section = players.getConfigurationSection(uid);
        if (section == null) {
            section = players.createSection(uid);
        }

        // 座標を記録
        if (savesLocation) {
            section.set("location", player.getLocation());
        }

        // インベントリを記録
        var inv = player.getInventory();
        var items = new ItemStack[inv.getSize()];
        for (var i = 0; i < items.length; i++) {
            items[i] = inv.getItem(i);
        }
        section.set("items", items);

        // 体力、満腹度、レベル、炎状態を記録
        section.set("health", savesParams ? player.getHealth() : null);
        section.set("foodLevel", savesParams ? player.getFoodLevel() : null);
        section.set("saturaton", savesParams ? player.getSaturation() : null);
        section.set("exhaustion", savesParams ? player.getExhaustion() : null);
        section.set("exp", savesParams ? player.getExp() : null);
        section.set("level", savesParams ? player.getLevel() : null);
        section.set("fire", savesParams ? player.getFireTicks() : null);

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
    public void onSignChange(SignChangeEvent e) {
        var p = e.getPlayer();
        var inHub = p.getWorld().getUID().equals(worldUuid);
        if (inHub) {
            var lines = e.getLines();

            if (lines[0].equals("[Hub]")) {
                var command = lines[1];
                var arg1 = lines[2];
                var arg2 = lines[3];

                if (command.equalsIgnoreCase("teleport")) {
                    e.setLine(0, "[§a§lテレポート§r]");
                    e.setLine(1, "");
                    e.setLine(2, "§b" + arg2);
                    e.setLine(3, "§rクリックorタップ");
                } else if (command.equalsIgnoreCase("return")) {
                    e.setLine(0, "§a元の場所に帰る");
                    e.setLine(1, "");
                    e.setLine(2, "");
                    e.setLine(3, "§rクリックorタップ");
                } else {
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 1, 0.5f);
                    p.sendMessage("設置に失敗しました。存在しないコマンドです。");
                    return;
                }
                signData.add(new SignData(e.getBlock().getLocation(), command, arg1, arg2));
                try {
                    saveSignsFile();
                    p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, SoundCategory.BLOCKS, 1, 1);
                    p.sendMessage("設置に成功しました。");
                } catch (IOException e1) {
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 1, 0.5f);
                    p.sendMessage("内部エラー発生。");
                    e1.printStackTrace();
                }
            }
        }
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent e) {
        var p = e.getPlayer();
        if (p.getWorld().getName().equals("sandbox")) {
            var advancement = e.getAdvancement();

            for (var criteria : advancement.getCriteria()) {
                p.getAdvancementProgress(advancement).revokeCriteria(criteria);
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        var p = e.getPlayer();
        if (p.getWorld().getName().equals("sandbox")) {
            var block = e.getBlock().getType();
            // エンダーチェストはダメ
            if (block == Material.ENDER_CHEST) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        var p = e.getPlayer();
        var inHub = p.getWorld().getUID().equals(worldUuid);
        if (inHub) {
            var sign = getSignDataOf(e.getBlock().getLocation());
            if (sign != null) {
                signData.remove(sign);
                try {
                    saveSignsFile();
                    p.sendMessage("看板を撤去しました。");
                } catch (IOException e1) {
                    p.sendMessage("§b看板の撤去に失敗しました。");
                    e1.printStackTrace();
                }
                
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
            returnToWorld(player);
        }
    }

    @EventHandler
    public void onPlayerHunger(FoodLevelChangeEvent e) {
        if (worldUuid == null)
            return;
        var player = e.getEntity();
        if (player.getWorld().getUID().equals(worldUuid)) {
            e.setCancelled(true);
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
            processNightmareBed(e, p);
            processSigns(e, p);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        if (e.getPlayer().getWorld().getName().equalsIgnoreCase("nightmare")) {
            // 悪夢から目覚める
            writePlayerConfig(e.getPlayer(), false, false);
            players = YamlConfiguration.loadConfiguration(playersFile);
            var lobby = getServer().getWorld(worldUuid).getSpawnLocation();
            e.setRespawnLocation(lobby);
        }
    }


    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        var player = e.getPlayer();
        var from = e.getFrom().getWorld();
        var to = e.getTo().getWorld();
        if (from.getName().equals(to.getName())) return;
        var fromName = getWorldDisplayName(from);
        var toName = getWorldDisplayName(to);

        var toPlayers = to.getPlayers();
        var allPlayersExceptInDestination = getServer()
            .getOnlinePlayers()
            .stream()
            // tpとマッチするUUIDがひとつも無いpのみを抽出
            .filter(p -> toPlayers.stream().allMatch(tp -> !tp.getUniqueId().equals(p.getUniqueId())))
            .collect(Collectors.toList());

        // fromにいる人宛に「toに行く旨」を伝える
        if (toName != null) {
            for (Player p : allPlayersExceptInDestination) {
                if (p.getUniqueId().equals(player.getUniqueId())) continue;
                p.sendMessage(String.format("§a%s§bが§e%s§bに行きました", player.getDisplayName(), toName));
            }
        }

        // toにいる人宛に「fromから来た旨」を伝える
        if (fromName != null) {
            for (Player p : toPlayers) {
                if (p.getUniqueId().equals(player.getUniqueId()))
                    continue;
                p.sendMessage(String.format("§a%s§bが§e%s§bから来ました", player.getDisplayName(), fromName));
            }
        }
    }

    private SignData getSignDataOf(Location loc) {
        return signData.stream().filter(s -> LocationComparator.equals(loc, s.getLocation())).findFirst().orElse(null);
    }

    private String getWorldDisplayName(World w) {
        if (w.getName().equals("world")) return "メインワールド";
        else if (w.getName().equals("world_nether")) return "ネザー";
        else if (w.getName().equals("world_the_end")) return "ジ・エンド";
        else if (w.getName().equals("hub")) return "ロビー";
        else if (w.getName().equals("sandbox")) return "サンドボックス";
        else if (w.getName().equals("nightmare")) return "ナイトメア";
        else if (w.getName().equals("art")) return "アートワールド";
        else if (w.getName().startsWith("travel_")) return null;
        else return "なぞのばしょ";
    }
    
    private void processSigns(PlayerInteractEvent e, Player p) {
        var signLoc = e.getClickedBlock().getLocation();
        // var loc = getConfig().getLocation("sandboxSignLocation");
        var signData = getSignDataOf(signLoc);
        if (signData != null) {
            e.setCancelled(true);
            var cmd = signData.getCommand();
            if (cmd.equalsIgnoreCase("teleport")) {
                var worldName = signData.getArg1();
                teleportToOtherWorld(p, worldName);
            } else if (cmd.equalsIgnoreCase("return")) {
                returnToWorld(p);
            }
        }
    }

    private void teleportToOtherWorld(Player p, String worldName) {
        var world = getServer().getWorld(worldName);
        if (world == null) {
            p.sendMessage("§bテレポートに失敗しました。ワールドが存在しないようです。");
        } else {
            p.teleport(world.getSpawnLocation());
            if (worldName.equals("sandbox")) {
                // サンドボックス限定
                p.setGameMode(GameMode.CREATIVE);
                p.sendMessage("ここは、" + ChatColor.AQUA + "クリエイティブモード" + ChatColor.RESET + "で好きなだけ遊べる" + ChatColor.RED + "サンドボックスワールド" + ChatColor.RESET + "。");
                p.sendMessage("元の世界の道具や経験値はお預かりしているので、好きなだけあそんでね！");
                p.sendMessage(ChatColor.GRAY + "(あ、でも他の人の建築物を壊したりしないでね)");
                p.sendMessage("帰るときは、" + ChatColor.GREEN + "/hub " + ChatColor.RESET + "コマンドを実行してください。");
            } else if (worldName.equals("art")) {
                // アート・ワールド限定
                p.setGameMode(GameMode.CREATIVE);
                p.sendMessage("ここは、" + ChatColor.AQUA + "地上絵" + ChatColor.RESET + "に特化した" + ChatColor.RED + "アートワールド" + ChatColor.RESET + "。");
                p.sendMessage("元の世界の道具や経験値はお預かりしているので、安心して地上絵を作成・観覧できます！");
                p.sendMessage(ChatColor.GRAY + "(他の人の作った地上絵を壊さないようお願いします。)");
                p.sendMessage("帰るときは、" + ChatColor.GREEN + "/hub " + ChatColor.RESET + "コマンドを実行してください。");
            } else if (worldName.equals("nightmare")) {
                // ナイトメア限定
                world.setDifficulty(Difficulty.HARD);
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                world.setGameRule(GameRule.MOB_GRIEFING, false);
                world.setTime(18000);
                world.setStorm(true);
                world.setWeatherDuration(20000);
                world.setThundering(true);
                world.setThunderDuration(20000);

                restoreInventory(p);
                restoreParams(p);
                p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 1, 0.5f);
                p.sendMessage("ここは怖い敵がうじゃうじゃいる" + ChatColor.RED + "ナイトメアワールド" + ChatColor.RESET + "。");
                p.sendMessage("手に入れたアイテムは持ち帰れます。");
                p.sendMessage("帰るときは、" + ChatColor.GREEN + "/hub " + ChatColor.RESET + "コマンドを実行してください。");
            }
        }
    }

    private void processNightmareBed(PlayerInteractEvent e, Player p) {
        var bedLoc = e.getClickedBlock().getLocation();
        var loc = getConfig().getLocation("nightmareBedLocation");
        if (loc != null && bedLoc.getBlockX() == loc.getBlockX() && bedLoc.getBlockY() == loc.getBlockY() && bedLoc.getBlockZ() == loc.getBlockZ()) {
            e.setCancelled(true);
            teleportToOtherWorld(p, "nightmare");
        }
    }

    private void restoreInventory(Player player) {
        ConfigurationSection section = players.getConfigurationSection(player.getUniqueId().toString());
        if (section == null) return;

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

    private void restoreParams(Player player) {
        ConfigurationSection section = players.getConfigurationSection(player.getUniqueId().toString());
        if (section == null) return;

        player.setHealth(section.getDouble("health", player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
        // TODO: 満腹度上限の決め打ちをしなくて済むならそうしたい
        player.setFoodLevel(section.getInt("foodLevel", 20));
        player.setSaturation((float)section.getDouble("saturaton", 0));
        player.setExhaustion((float)section.getDouble("exhaustion", 0));
        player.setExp((float)section.getDouble("exp", 0));
        player.setLevel(section.getInt("level", 0));
        player.setFireTicks(section.getInt("fire", 0));
    }

    private void loadSignsFile() {
        signs = YamlConfiguration.loadConfiguration(signsFile);
        signData = (List<SignData>)signs.getList("signs", new ArrayList<SignData>());
    }

    private void saveSignsFile() throws IOException {
        signs = YamlConfiguration.loadConfiguration(signsFile);
        signs.set("signs", signData);
        signs.save(signsFile);
        loadSignsFile();
    }

    private void returnToWorld(Player player) {
        player.setGameMode(GameMode.SURVIVAL);

        var world = getServer().getWorld("world");
        ConfigurationSection section = players.getConfigurationSection(player.getUniqueId().toString());
        if (section == null) {
            // はじめましての場合
            player.teleport(world.getSpawnLocation(), TeleportCause.PLUGIN);
            return;
        }

        restoreInventory(player);
        restoreParams(player);

        var locationResult = section.get("location");
        if (locationResult == null || !(locationResult instanceof Location)) {
            player.teleport(world.getSpawnLocation(), TeleportCause.PLUGIN);
            player.sendMessage(ChatColor.RED + "最後にいた場所が記録されていないため、初期スポーンにワープします。これはおそらくバグなので、管理者に報告してください。 Code: 2");
            return;
        }

        var loc = (Location) locationResult;
        player.teleport(loc, TeleportCause.PLUGIN);
    }

    private Logger logger;
    private File playersFile;
    private YamlConfiguration players;
    private File signsFile;
    private YamlConfiguration signs;
    private UUID worldUuid;
    private boolean forceAll;
    private HashMap<UUID, Boolean> isWarpingMap = new HashMap<>();
    private List<SignData> signData;
}