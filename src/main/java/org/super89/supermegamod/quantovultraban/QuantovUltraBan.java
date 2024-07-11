package org.super89.supermegamod.quantovultraban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class QuantovUltraBan extends JavaPlugin implements Listener {
    private String discordWebhookUrl;
    private String databaseUsername;
    private String databasePassword;

    public Connection connection;
    Gson gson = new Gson();

    @Override
    public void onEnable() {
        // Загрузка конфигурации из config.yml
        loadConfig();

        // Подключение к базе данных MySQL
        try {
            Class.forName("com.mysql.jdbc.Driver");
            connection = DriverManager.getConnection("jdbc:mysql://localhost:" + "3306" + "/" + "qrbansbd", databaseUsername, databasePassword);
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
            getLogger().severe("Не удалось подключиться к базе данных!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Создание таблиц, если они не существуют
        createTables();

        getServer().getPluginManager().registerEvents(this, this);

        // Регистрация команд
        getCommand("qrban").setExecutor(new SuperBanCommand());
        getCommand("qrunban").setExecutor(new SuperUnbanCommand());
        new BukkitRunnable() {
            @Override
            public void run() {
                checkBans();
            }
        }.runTaskTimer(this, 0, 60);
    }

    @Override
    public void onDisable() {
        // Закрытие соединения с базой данных
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Загрузка конфигурации из config.yml
    private void loadConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        }
        try (FileInputStream fis = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(fis);

            databaseUsername = (String) config.get("database-username");
            databasePassword = (String) config.get("database-password");

        } catch (Exception e) {
            e.printStackTrace();
            getLogger().severe("Ошибка при загрузке конфигурации!");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    // Создание таблиц, если они не существуют
    private void createTables() {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS banned_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(255), ip VARCHAR(255))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS PlayersIP (uuid VARCHAR(36) PRIMARY KEY, ip VARCHAR(255), username VARCHAR(255))");
        } catch (SQLException e) {
            e.printStackTrace();
            getLogger().severe("Не удалось создать таблицу!");
        }
    }

    // Проверка, забанен ли игрок
    public boolean isBanned(String uuid, String ip) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM banned_players WHERE uuid = ? OR ip = ?")) {
            statement.setString(1, uuid);
            statement.setString(2, ip);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Получение IP-адреса по имени или UUID
    public String getIpByNameOrUuid(String nameOrUuid) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ip FROM PlayersIP WHERE uuid = ? OR username = ?")) {
            statement.setString(1, nameOrUuid);
            statement.setString(2, nameOrUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString("ip");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null; // Если IP не найден
    }

    private void sendDiscordMessage(String message) throws Exception {
        URL url = new URL("https://discord.com/api/webhooks/1240672044388061236/_gh2g6FT929l3YCDNQyQLjSgNJ7S6YVpCl3K8_IFr65EUoK3Oo50h-I_v2s07CtBumeM");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        Map<String, String> data = new HashMap<>();
        data.put("content", message);

        String json = gson.toJson(data);

        OutputStream os = connection.getOutputStream();
        os.write(json.getBytes());
        os.flush();

        // Обработка ответа от Discord (при необходимости)
        if (connection.getResponseCode() != 204) {
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
            in.close();
        }
        connection.disconnect();
    }

    private class SuperBanCommand implements CommandExecutor {
        String uuid;
        String name;

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender.hasPermission("qrban")) {
                if (args.length != 1) {
                    sender.sendMessage("Использование: /qrban <игрок>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                try {
                    Player player = Bukkit.getPlayer(args[0]);
                    player.kickPlayer("Вы были забанены!");
                } catch (NullPointerException e) {
                    sender.sendMessage("Игрок хоть и оффлайн , но был забанен)");
                }

                String uuid = target.getUniqueId().toString();
                String name = target.getName();
                String ip = getIpByNameOrUuid(name);

                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO banned_players (uuid, name, ip) VALUES (?, ?, ?)")) {
                    statement.setString(1, uuid);
                    statement.setString(2, name);
                    statement.setString(3, ip);
                    statement.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                    sender.sendMessage("Ошибка при бане игрока!");
                    return true;
                }


                // Кик игрока со всех серверов
                try {
                    sendDiscordMessage("Игрок " + target.getName() + " был забанен! " + getServer().getIp() + " " + databaseUsername);
                } catch (Exception e) {
                    e.printStackTrace();
                    getLogger().severe("Не удалось отправить сообщение в Discord!");
                }
                return true;
            }
            return true;
        }
    }

    // Проверка при входе игрока
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        String ip = event.getPlayer().getAddress().getAddress().getHostAddress();
        String name = event.getPlayer().getName();

        // Проверка на существование записи в PlayersIP
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM PlayersIP WHERE uuid = ?")) {
            statement.setString(1, uuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    // Запись уже существует, проверяем IP
                    try (PreparedStatement updateStatement = connection.prepareStatement("UPDATE PlayersIP SET ip = ?, username = ? WHERE uuid = ?")) {
                        updateStatement.setString(1, ip);
                        updateStatement.setString(2, name);
                        updateStatement.setString(3, uuid);
                        updateStatement.executeUpdate();
                    } catch (SQLException e) {
                        e.printStackTrace();
                        getLogger().severe("Не удалось обновить IP-адрес и имя игрока!");
                    }
                } else {
                    // Запись отсутствует, добавляем новую
                    try (PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO PlayersIP (uuid, ip, username) VALUES (?, ?, ?)")) {
                        insertStatement.setString(1, uuid);
                        insertStatement.setString(2, ip);
                        insertStatement.setString(3, name);
                        insertStatement.executeUpdate();
                    } catch (SQLException e) {
                        e.printStackTrace();
                        getLogger().severe("Не удалось сохранить IP-адрес и имя игрока!");
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            getLogger().severe("Не удалось проверить существование записи!");
        }

        if (isBanned(uuid, ip)) {
            event.getPlayer().kickPlayer("Вы забанены!");
        }
    }

    private void checkBans() {
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, ip FROM banned_players")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String bannedUuid = resultSet.getString("uuid");
                    String bannedIp = resultSet.getString("ip");
                    Player player = Bukkit.getPlayer(UUID.fromString(bannedUuid));
                    if (player != null) {
                        player.kickPlayer("Вы забанены!");
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private class SuperUnbanCommand implements CommandExecutor {

        @Override
        public boolean onCommand(CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
            if (sender.hasPermission("qrunban")) {
                if (args.length != 1) {
                    sender.sendMessage("Использование: /qrunban <игрок>");
                    return true;
                }

                String targetName = args[0];

                // Получение IP по имени или UUID из базы данных
                String ip = getIpByNameOrUuid(targetName);
                if (ip != null) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM banned_players WHERE uuid = ? OR ip = ?")) {
                        statement.setString(1, Bukkit.getOfflinePlayer(targetName).getUniqueId().toString());
                        statement.setString(2, ip);
                        int affectedRows = statement.executeUpdate();

                        if (affectedRows > 0) {
                            sender.sendMessage("Игрок " + targetName + " разбанен!");
                        } else {
                            sender.sendMessage("Игрок " + targetName + " не найден в списке забаненных!");
                        }

                    } catch (SQLException e) {
                        e.printStackTrace();
                        sender.sendMessage("Ошибка при разбане игрока!");
                    }
                    try {
                        sendDiscordMessage("Игрок " + targetName + " был разбанен! " + getServer().getIp());
                    } catch (Exception e) {
                        e.printStackTrace();
                        getLogger().severe("Не удалось отправить сообщение в Discord!");
                    }
                    return true;
                } else {
                    sender.sendMessage("Игрок " + targetName + " не найден в списке забаненных!");
                    return true;
                }
            }
            return true;
        }
    }
}