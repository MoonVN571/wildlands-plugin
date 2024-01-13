package me.moon.Wildlands;

import com.github.Anon8281.universalScheduler.UniversalScheduler;
import com.github.Anon8281.universalScheduler.scheduling.schedulers.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

enum WhitelistStatus {
    Ok,
    NotWhitelisted,
    Error,
}


public class Main extends JavaPlugin implements Listener {
    private FileConfiguration config;
    private static TaskScheduler scheduler;
    private static final ArrayList<String> subCommands = new ArrayList<String>() {{
        add("reload");
    }};

    public static TaskScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getConfig().options().copyDefaults(true);
        saveConfig();

        scheduler = UniversalScheduler.getScheduler(this);
        config = getConfig();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onConnect(final AsyncPlayerPreLoginEvent event) {
        if(!config.getBoolean("whitelist.enabled")) return;

        WhitelistStatus status =  isPlayerWhitelisted(event.getName());
        if (status == WhitelistStatus.Ok) return;


        String strConfig ="whitelist.not-whilisted";
        if(status == WhitelistStatus.Error) strConfig = "whitelist.error";

        String kickMessage = ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(config.getString(strConfig)));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
    }


    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!config.getBoolean("death-position.enabled"))
            return;

        Player deadPlayer = event.getEntity();
        Location deadPlayerLocation = deadPlayer.getLocation();
        String x = String.valueOf(deadPlayerLocation.getBlockX());
        String y = String.valueOf(deadPlayerLocation.getBlockY());
        String z = String.valueOf(deadPlayerLocation.getBlockZ());

        String deathMessagePlayer = ChatColor.translateAlternateColorCodes('&',
                        Objects.requireNonNull(config.getString("death-position.message")))
                .replace("{x}", x)
                .replace("{y}", y).replace("{z}", z)
                .replace("{world}", getWorld(deadPlayer));
        deadPlayer.sendMessage(deathMessagePlayer);

        String deathMessageConsole = deadPlayer.getName() + " died at " + x + " " + y + " " + z + " " + "(" + getWorld(deadPlayer) + ")";
        getLogger().info(deathMessageConsole);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if(!command.getName().equalsIgnoreCase("wildlands")) return null;
        if (args.length == 0) {
            return subCommands;
        } else if (args.length == 1) {
            ArrayList<String> answer = new ArrayList<>();
            for (String subCommand : subCommands) {
                if (args[0].toLowerCase().startsWith(args[0].toLowerCase()))
                    answer.add(subCommand);
            }
            return answer;
        }
        return Collections.emptyList();
    }
    private static final ArrayList<Player> cooldownList = new ArrayList<>();

    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if(command.getName().equalsIgnoreCase(("cuu"))) {
            if (sender instanceof Player) {
                Player player = (Player) sender;

                if (!config.getBoolean("cuu.enabled")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("disabled-msgs")));
                    return true;
                }

                if (cooldownList.contains(player)) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("cuu.wait")));
                    return true;
                }

                getScheduler().runTaskAsynchronously(() -> {
                    double x= player.getLocation().getX();
                    double y = player.getLocation().getY() + 10.00;
                    double z= player.getLocation().getZ();
                    player.teleportAsync(new Location(player.getWorld(),x,y,z));

                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(getConfig().getString("cuu.ok"))));
                });

                cooldownList.add(player);
                getScheduler().runTaskLater(() -> cooldownList.remove(player), 60 * 20);
                return true;
            } else {
                sender.sendMessage("[Wildlands] Ingame only.");
                return true;
            }
        }
        else if (command.getName().equalsIgnoreCase("wildlands")) {
            if(args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
                if(!(sender instanceof Player)) {
                    getLogger().info("List commands: \n/wl reload - Reload config\n-/cuu - Teleport to Y+10");
                } else {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&fList commands:\n&c/wl reload &7- &fReload config\n&c/cuu &7- &fTeleport to Y+10"));
                }
            } else {
                if (!sender.hasPermission("wildlands.reload")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(config.getString("no-perm"))));
                    return true;
                }

                reloadConfig();
                config = getConfig();
                if(!(sender instanceof Player)) {
                    getLogger().info("[Wildlands] Configuration reloaded!");
                } else {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(config.getString("reloaded"))));
                }
            }
            return true;
        }
        return false;
    }

    private String getWorld(Player player) {
        World.Environment playerEnvironment = player.getWorld().getEnvironment();

        if (playerEnvironment == World.Environment.NORMAL) {
            return "Overworld";
        }
        else if (playerEnvironment == World.Environment.NETHER) {
            return "Nether";
        }
        else if (playerEnvironment == World.Environment.THE_END) {
            return "End";
        }
        else {
            return "Unknown";
        }
    }

    private WhitelistStatus isPlayerWhitelisted(String playerName) {
        try {
            String apiUrl = config.getString("whitelist.url");
            String apiToken = config.getString("whitelist.token");

            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("token", apiToken);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                JSONParser parser = new JSONParser();
                JSONObject whitelistJson = (JSONObject) parser.parse(response.toString());

                if (whitelistJson.containsKey("username")) {
                    JSONArray whitelistArray = (JSONArray) whitelistJson.get("username");
                    for (Object o : whitelistArray) {
                        String username = (String) o;
                        if (playerName.equals(username)) {
                            return WhitelistStatus.Ok;
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Unable to connect to whitelist API: " + e.getMessage());
            return WhitelistStatus.Error;
        }
        return WhitelistStatus.NotWhitelisted;
    }
}
