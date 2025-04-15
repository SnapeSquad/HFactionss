package org.isyateq.hfactions.managers; // Или org.isyateq.hfactions.database;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

public class DatabaseManager {

    private final HFactions plugin;
    private Connection connection;
    private final String dbPath;

    public DatabaseManager(HFactions plugin) {
        this.plugin = plugin;
        // Создаем папку data, если ее нет
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.dbPath = "jdbc:sqlite:" + dataFolder.getAbsolutePath() + File.separator + "playerdata.db";
        initializeDatabase();
    }

    // --- Управление соединением ---

    private Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                // Загружаем драйвер (на всякий случай, хотя обычно не требуется для новых JDBC)
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection(dbPath);
                plugin.getLogger().info("SQLite connection established.");
            } catch (ClassNotFoundException e) {
                plugin.getLogger().log(Level.SEVERE, "SQLite JDBC Driver not found!", e);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not connect to SQLite database!", e);
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
        }
    }

    // --- Инициализация таблицы ---

    private void initializeDatabase() {
        String sql = "CREATE TABLE IF NOT EXISTS player_data (" +
                " uuid TEXT PRIMARY KEY NOT NULL," + // UUID игрока как строка
                " faction_id TEXT," +                // ID фракции (может быть NULL)
                " rank_id INTEGER" +                  // ID ранга (может быть NULL)
                ");";

        try (Connection conn = getConnection(); // Используем try-with-resources
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            plugin.getLogger().info("Player data table initialized successfully.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize player data table!", e);
        }
    }

    // --- Методы для работы с данными игрока ---

    /**
     * Загружает данные игрока из БД. Вызывается асинхронно.
     * @param playerUuid UUID игрока
     * @param callback Действие, выполняемое после загрузки (в основном потоке)
     */
    public void loadPlayerDataAsync(UUID playerUuid, PlayerDataCallback callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "SELECT faction_id, rank_id FROM player_data WHERE uuid = ?;";
            String factionId = null;
            Integer rankId = null;

            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, playerUuid.toString());
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    factionId = rs.getString("faction_id");
                    // rank_id может быть NULL в БД, если игрок не во фракции, но был там раньше
                    int tempRankId = rs.getInt("rank_id");
                    if (!rs.wasNull()) {
                        rankId = tempRankId;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not load player data for " + playerUuid, e);
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

    /**
     * Сохраняет (или обновляет) данные игрока в БД. Вызывается асинхронно.
     * @param playerUuid UUID игрока
     * @param factionId ID фракции (может быть null)
     * @param rankId ID ранга (может быть null)
     */
    public void savePlayerDataAsync(UUID playerUuid, String factionId, Integer rankId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Используем INSERT OR REPLACE (UPSERT) для простоты
            String sql = "INSERT OR REPLACE INTO player_data (uuid, faction_id, rank_id) VALUES(?, ?, ?);";

            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, playerUuid.toString());

                if (factionId != null) {
                    pstmt.setString(2, factionId);
                } else {
                    pstmt.setNull(2, Types.VARCHAR); // Устанавливаем NULL для faction_id
                }

                if (rankId != null) {
                    pstmt.setInt(3, rankId);
                } else {
                    pstmt.setNull(3, Types.INTEGER); // Устанавливаем NULL для rank_id
                }

                pstmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save player data for " + playerUuid, e);
            }
        });
    }

    /**
     * Удаляет данные игрока из БД (например, если плагин удаляется). Не обязательно.
     * @param playerUuid UUID игрока
     */
    public void deletePlayerDataAsync(UUID playerUuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "DELETE FROM player_data WHERE uuid = ?;";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerUuid.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not delete player data for " + playerUuid, e);
            }
        });
    }


    // Интерфейс для callback'а после загрузки данных
    @FunctionalInterface
    public interface PlayerDataCallback {
        void onQueryDone(String factionId, Integer rankId);
    }
}