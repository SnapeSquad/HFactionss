package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.isyateq.hfactions.HFactions;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

public class DatabaseManager {

    private final HFactions plugin;
    private Connection connection;
    private final String dbPath;

    public DatabaseManager(HFactions plugin) {
        this.plugin = plugin;
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }
        this.dbPath = "jdbc:sqlite:" + dataFolder.getAbsolutePath() + File.separator + "playerdata.db";
        // Инициализация происходит при первом получении соединения
    }

    // --- Управление соединением ---

    // Получаем соединение, создаем БД и таблицу если нужно
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                // Загружаем драйвер (на всякий случай)
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection(dbPath);
                plugin.getLogger().info("SQLite connection established.");
                // Инициализируем таблицу после успешного соединения
                initializeDatabaseTable();
            } catch (ClassNotFoundException e) {
                plugin.getLogger().log(Level.SEVERE, "SQLite JDBC Driver not found!", e);
                throw new SQLException("SQLite JDBC Driver not found!", e); // Пробрасываем исключение
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not connect to SQLite database!", e);
                throw e; // Пробрасываем исключение
            }
        }
        return connection;
    }

    public void closeConnection() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    plugin.getLogger().info("SQLite connection closed.");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Error closing SQLite connection!", e);
            }
            connection = null; // Сбрасываем ссылку
        }
    }

    // --- Инициализация таблицы ---

    private void initializeDatabaseTable() {
        // Используем getConnection(), который уже содержит логику подключения
        // Обертка в try-with-resources для Statement
        String sql = "CREATE TABLE IF NOT EXISTS player_data (" +
                " uuid TEXT PRIMARY KEY NOT NULL," +
                " faction_id TEXT," +
                " rank_id INTEGER" +
                ");";

        try (Connection conn = getConnection(); // Получаем соединение (создаст, если нужно)
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            plugin.getLogger().info("Player data table initialized successfully.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize player data table!", e);
            // Consider disabling the plugin or parts of it if the DB is essential and fails here
        }
    }

    // --- Методы для работы с данными игрока ---

    public void loadPlayerDataAsync(UUID playerUuid, PlayerDataCallback callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "SELECT faction_id, rank_id FROM player_data WHERE uuid = ?;";
            String factionId = null;
            Integer rankId = null;

            try (Connection conn = getConnection(); // Получаем соединение
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, playerUuid.toString());
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    factionId = rs.getString("faction_id");
                    int tempRankId = rs.getInt("rank_id");
                    if (!rs.wasNull()) {
                        rankId = tempRankId;
                    }
                }
                // Закрываем ResultSet явно (хотя try-with-resources для PreparedStatement обычно этого достаточно)
                rs.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not load player data for " + playerUuid, e);
                // Важно обработать ошибку - возможно, вызвать callback с null значениями
                Bukkit.getScheduler().runTask(plugin, () -> callback.onQueryDone(null, null));
                return; // Прерываем выполнение асинхронной задачи
            }

            // Финальные значения для лямбды
            final String finalFactionId = factionId;
            final Integer finalRankId = rankId;

            // Возвращаемся в основной поток для обновления данных в PlayerManager
            Bukkit.getScheduler().runTask(plugin, () -> {
                callback.onQueryDone(finalFactionId, finalRankId);
            });
        });
    }

    public void savePlayerDataAsync(UUID playerUuid, String factionId, Integer rankId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT OR REPLACE INTO player_data (uuid, faction_id, rank_id) VALUES(?, ?, ?);";

            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, playerUuid.toString());

                if (factionId != null) {
                    pstmt.setString(2, factionId);
                } else {
                    pstmt.setNull(2, Types.VARCHAR);
                }

                if (rankId != null) {
                    pstmt.setInt(3, rankId);
                } else {
                    pstmt.setNull(3, Types.INTEGER);
                }

                pstmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save player data for " + playerUuid, e);
            }
        });
    }

    // Синхронное сохранение для использования в onDisable
    public void savePlayerDataSync(UUID playerUuid, String factionId, Integer rankId) {
        String sql = "INSERT OR REPLACE INTO player_data (uuid, faction_id, rank_id) VALUES(?, ?, ?);";
        try (Connection conn = getConnection(); // Получаем соединение напрямую
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, playerUuid.toString());
            pstmt.setString(2, factionId); // factionId может быть null
            if (rankId != null) pstmt.setInt(3, rankId); else pstmt.setNull(3, Types.INTEGER);
            pstmt.executeUpdate();
            plugin.getLogger().fine("Saved data synchronously for " + playerUuid);

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save player data synchronously for " + playerUuid, e);
        }
    }

    // Синхронная очистка данных фракции в БД для использования в onDisable или при удалении фракции
    public void clearFactionDataSync(String factionId) {
        String sql = "UPDATE player_data SET faction_id = NULL, rank_id = NULL WHERE faction_id = ?;";
        int updatedRows = 0;
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, factionId.toLowerCase()); // Сравниваем с lowercase ID
            updatedRows = pstmt.executeUpdate();
            plugin.getLogger().info("Cleared faction data synchronously in DB for " + updatedRows + " players of faction " + factionId);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not clear player faction data synchronously in DB for faction " + factionId, e);
        }
    }


    // Интерфейс для callback'а после загрузки данных
    @FunctionalInterface
    public interface PlayerDataCallback {
        void onQueryDone(String factionId, Integer rankId);
    }
}