package org.isyateq.hfactions.managers;

// Bukkit/Paper Imports
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer; // Для LuckPerms
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable; // Для таймера приглашений

// HFactions Imports
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.PendingInvite;
import org.isyateq.hfactions.integrations.LuckPermsIntegration; // Для админ режима

// Java Util Imports
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

// НЕ ДОЛЖНО БЫТЬ: public class FactionManager { ... } здесь

public class PlayerManager {

    private final HFactions plugin;
    private final DatabaseManager databaseManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager;
    private final LuckPermsIntegration luckPermsIntegration; // Добавляем для админ режима

    // Данные игроков в памяти
    private final Map<UUID, String> playerFactions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerRanks = new ConcurrentHashMap<>();
    private final Set<UUID> playersInFactionChat = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingInvite> pendingInvites = new ConcurrentHashMap<>();
    private final Map<UUID, String> adminsInFactionMode = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> inviteTimers = new ConcurrentHashMap<>(); // Для отмены таймеров

    public PlayerManager(HFactions plugin) {
        this.plugin = plugin;
        // Получаем менеджеры из главного класса
        this.databaseManager = plugin.getDatabaseManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        this.luckPermsIntegration = plugin.getLuckPermsIntegration(); // Получаем LP

        // Проверяем, что менеджеры не null
        if (this.databaseManager == null) plugin.getLogger().severe("DatabaseManager is null in PlayerManager!");
        if (this.factionManager == null) plugin.getLogger().severe("FactionManager is null in PlayerManager!");
        if (this.configManager == null) plugin.getLogger().severe("ConfigManager is null in PlayerManager!");
        if (this.luckPermsIntegration == null) plugin.getLogger().warning("LuckPermsIntegration is null in PlayerManager! Admin mode may not work."); // LP может отсутствовать
    }
    public void clearFactionDataFor(String factionId) {
        plugin.getLogger().info("Clearing player data for deleted faction: " + factionId);
        List<UUID> playersToClear = new ArrayList<>();
        // Собираем UUID игроков этой фракции из кэша
        for (Map.Entry<UUID, String> entry : playerFactions.entrySet()) {
            if (factionId.equalsIgnoreCase(entry.getValue())) {
                playersToClear.add(entry.getKey());
            }
        }

        if (playersToClear.isEmpty()) {
            plugin.getLogger().info("No online players found for faction " + factionId + " to clear cache.");
            // Все равно нужно очистить в БД для оффлайн игроков!
        } else {
            plugin.getLogger().info("Clearing cache for " + playersToClear.size() + " online players of faction " + factionId);
        }

        for (UUID uuid : playersToClear) {
            // Очищаем кэш
            playerFactions.remove(uuid);
            playerRanks.remove(uuid);
            playersInFactionChat.remove(uuid);

            // Обновляем отображение онлайн игрока
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            if (onlinePlayer != null) {
                onlinePlayer.sendMessage(ChatColor.RED + "The faction you were in has been disbanded!");
                updatePlayerDisplay(onlinePlayer);
            }
        }

        // Очищаем данные в БД для ВСЕХ игроков этой фракции (включая оффлайн)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "UPDATE player_data SET faction_id = NULL, rank_id = NULL WHERE faction_id = ?;";
            int updatedRows = 0;
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, factionId);
                updatedRows = pstmt.executeUpdate();
                plugin.getLogger().info("Cleared faction data in DB for " + updatedRows + " players of faction " + factionId);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not clear player faction data in DB for faction " + factionId, e);
            }
        });
    }

    // --- Методы загрузки/сохранения данных ---

    public void loadPlayerData(Player player) {
        if (player == null || databaseManager == null) return;
        UUID uuid = player.getUniqueId();
        plugin.getLogger().fine("Loading data for player " + player.getName() + " (" + uuid + ")");
        databaseManager.loadPlayerDataAsync(uuid, (factionId, rankId) -> {
            plugin.getLogger().fine("Data loaded for " + player.getName() + ": faction=" + factionId + ", rank=" + rankId);
            // Проверяем игрока снова, вдруг он вышел пока грузились данные
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                plugin.getLogger().fine("Player " + uuid + " logged out before data could be applied.");
                return; // Не применяем данные, если игрок уже оффлайн
            }

            // Применяем данные
            applyLoadedData(onlinePlayer, factionId, rankId);
            // Обновляем отображение (PAPI/Таб)
            updatePlayerDisplay(onlinePlayer);
        });
    }

    // Применяет загруженные данные к игроку (вызывается из callback'а)
    private void applyLoadedData(Player player, String factionId, Integer rankId) {
        UUID uuid = player.getUniqueId();
        // Проверяем FactionManager перед использованием
        if (factionManager == null) {
            plugin.getLogger().severe("FactionManager is null! Cannot apply loaded data for " + player.getName());
            return;
        }

        if (factionId != null) {
            Faction faction = factionManager.getFaction(factionId);
            if (faction != null) {
                playerFactions.put(uuid, factionId);
                FactionRank rank = (rankId != null) ? faction.getRank(rankId) : null;
                if (rank != null) {
                    playerRanks.put(uuid, rankId);
                } else {
                    // Сбрасываем на ранг 1, если ранг невалиден
                    int defaultRankId = 1;
                    playerRanks.put(uuid, defaultRankId);
                    plugin.getLogger().warning("Player " + player.getName() + " had invalid rank ID " + rankId + " for faction " + factionId + ". Resetting to rank " + defaultRankId + ".");
                    if (databaseManager != null) {
                        databaseManager.savePlayerDataAsync(uuid, factionId, defaultRankId); // Исправляем в БД
                    }
                }
                // Здесь можно применить права ранга через LuckPerms, если это необходимо
                // applyRankPermissions(player, getPlayerRank(player));
            } else {
                // Фракция из БД не найдена, очищаем данные
                plugin.getLogger().warning("Player " + player.getName() + " was in a non-existent faction " + factionId + ". Clearing data.");
                clearLocalPlayerData(uuid); // Очищаем кэш
                if (databaseManager != null) {
                    databaseManager.savePlayerDataAsync(uuid, null, null); // Очищаем в БД
                }
            }
        } else {
            // Игрок не состоит во фракции
            clearLocalPlayerData(uuid); // Очищаем кэш на всякий случай
        }
    }

    // Сохраняет данные ОДНОГО игрока (асинхронно)
    public void savePlayerData(Player player) {
        if (player == null || databaseManager == null) return;
        savePlayerData(player.getUniqueId());
    }

    // Сохраняет данные по UUID (асинхронно)
    public void savePlayerData(UUID uuid) {
        if (uuid == null || databaseManager == null) return;
        String factionId = playerFactions.get(uuid);
        Integer rankId = playerRanks.get(uuid);
        plugin.getLogger().fine("Saving data for player " + uuid + ": faction=" + factionId + ", rank=" + rankId);
        databaseManager.savePlayerDataAsync(uuid, factionId, rankId);
    }


    // Загрузка для онлайн игроков при старте/релоаде
    public void loadDataForOnlinePlayers() {
        plugin.getLogger().info("Loading data for " + Bukkit.getOnlinePlayers().size() + " online players...");
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerData(player);
        }
    }

    // Сохранение для онлайн игроков при выключении (СИНХРОННО)
    public void saveDataForOnlinePlayers() {
        plugin.getLogger().info("Saving data synchronously for " + Bukkit.getOnlinePlayers().size() + " online players...");
        if (databaseManager == null) {
            plugin.getLogger().severe("Cannot save player data on disable: DatabaseManager is null!");
            return;
        }
        // Прямое синхронное сохранение в БД
        String sql = "INSERT OR REPLACE INTO player_data (uuid, faction_id, rank_id) VALUES(?, ?, ?);";
        int savedCount = 0;
        try (Connection conn = databaseManager.getConnection(); // Получаем соединение
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                String factionId = playerFactions.get(uuid);
                Integer rankId = playerRanks.get(uuid);

                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, factionId); // Может быть null
                if (rankId != null) pstmt.setInt(3, rankId); else pstmt.setNull(3, Types.INTEGER);

                pstmt.addBatch(); // Добавляем в пакет
                savedCount++;
            }
            pstmt.executeBatch(); // Выполняем пакет запросов
            plugin.getLogger().info("Synchronously saved data for " + savedCount + " players.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save player data synchronously on disable!", e);
        } catch (Exception e) { // Ловим другие ошибки
            plugin.getLogger().log(Level.SEVERE, "Unexpected error during synchronous player data save!", e);
        }
    }

    // Очистка кэша игрока при выходе
    public void clearPlayerData(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        plugin.getLogger().fine("Clearing cached data for player " + player.getName() + " (" + uuid + ")");
        clearLocalPlayerData(uuid); // Выносим очистку кэша в отдельный метод

        // Отменяем таймер приглашения, если он был
        cancelInviteTimer(uuid);

        // Если игрок был в админ режиме, выводим его (тихо)
        if (adminsInFactionMode.containsKey(uuid)) {
            exitAdminMode(player, true);
        }
        // Сбросить права LuckPerms? Обычно не требуется, т.к. они выдаются на время сессии или хранятся в LP
        // clearRankPermissions(player);
    }

    // Вспомогательный метод для очистки локальных кэшей
    private void clearLocalPlayerData(UUID uuid) {
        playerFactions.remove(uuid);
        playerRanks.remove(uuid);
        playersInFactionChat.remove(uuid);
        pendingInvites.remove(uuid);
        // adminsInFactionMode очищается в exitAdminMode
    }

    /**
     * Очищает данные о фракции для всех игроков, принадлежащих к указанной фракции.
     * Вызывается FactionManager при удалении фракции.
     */


    // --- Методы управления фракцией игрока ---

    public void joinFaction(Player player, Faction faction) {
        if (player == null || faction == null || factionManager == null || databaseManager == null || configManager == null) return;
        UUID uuid = player.getUniqueId();
        if (isInFaction(player)) {
            player.sendMessage(configManager.getMessage("faction.already_in", "&cYou are already in a faction."));
            return;
        }

        String factionId = faction.getId(); // ID уже lowercase
        int initialRankId = 1; // Начинаем с ранга 1

        playerFactions.put(uuid, factionId);
        playerRanks.put(uuid, initialRankId);
        databaseManager.savePlayerDataAsync(uuid, factionId, initialRankId);

        String joinMsgSelf = configManager.getMessage("faction.joined_self", "&aYou have joined the {faction_name} faction!");
        String joinMsgFaction = configManager.getMessage("faction.member_joined", "&e{player_name} has joined the faction.");

        player.sendMessage(joinMsgSelf.replace("{faction_name}", faction.getName()));
        broadcastToFaction(factionId, joinMsgFaction.replace("{player_name}", player.getName()));

        updatePlayerDisplay(player);
        // Применить права первого ранга?
        // applyRankPermissions(player, faction.getRank(initialRankId));
    }

    public void leaveFaction(Player player) {
        if (player == null || factionManager == null || databaseManager == null || configManager == null) return;
        UUID uuid = player.getUniqueId();
        String factionId = getPlayerFactionId(player);

        if (factionId == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        String factionName = faction != null ? faction.getName() : factionId;

        // Сохраняем null в БД
        databaseManager.savePlayerDataAsync(uuid, null, null);
        // Очищаем кэш
        clearLocalPlayerData(uuid);

        String leaveMsgSelf = configManager.getMessage("faction.left_self", "&aYou have left the {faction_name} faction.");
        String leaveMsgFaction = configManager.getMessage("faction.member_left", "&e{player_name} has left the faction.");

        player.sendMessage(leaveMsgSelf.replace("{faction_name}", factionName));
        if (faction != null) { // Отправляем сообщение фракции только если она еще существует
            broadcastToFaction(factionId, leaveMsgFaction.replace("{player_name}", player.getName()));
        }

        updatePlayerDisplay(player);
        // Забрать фракционные права?
        // clearRankPermissions(player);
    }

    public void kickPlayer(Player kicker, Player target) {
        if (kicker == null || target == null || kicker == target || factionManager == null || databaseManager == null || configManager == null) return;
        UUID targetUuid = target.getUniqueId();
        String targetFactionId = getPlayerFactionId(target);

        if (targetFactionId == null) {
            kicker.sendMessage(configManager.getMessage("player.not_in_faction", "&c{player_name} is not in any faction.").replace("{player_name}", target.getName()));
            return;
        }
        // Проверка, что кикающий в той же фракции или админ в режиме
        String kickerFactionId = getPlayerFactionId(kicker);
        String adminModeFaction = adminsInFactionMode.get(kicker.getUniqueId());
        boolean canKick = targetFactionId.equals(kickerFactionId) || targetFactionId.equals(adminModeFaction);

        if (!canKick) {
            kicker.sendMessage(configManager.getMessage("faction.cannot_kick_other_faction", "&cYou can only kick members of your own faction (or while in admin mode)."));
            return;
        }
        // Проверка ранга кикающего и цели находится в FactionCommand

        Faction faction = factionManager.getFaction(targetFactionId);
        String factionName = faction != null ? faction.getName() : targetFactionId;

        // Сохраняем null в БД
        databaseManager.savePlayerDataAsync(targetUuid, null, null);
        // Очищаем кэш
        clearLocalPlayerData(targetUuid);

        String kickMsgTarget = configManager.getMessage("faction.kicked_target", "&cYou have been kicked from {faction_name} by {kicker_name}.");
        String kickMsgKicker = configManager.getMessage("faction.kicked_kicker", "&aYou have kicked {target_name} from the faction.");
        String kickMsgFaction = configManager.getMessage("faction.member_kicked", "&e{target_name} was kicked from the faction by {kicker_name}.");

        target.sendMessage(kickMsgTarget.replace("{faction_name}", factionName).replace("{kicker_name}", kicker.getName()));
        kicker.sendMessage(kickMsgKicker.replace("{target_name}", target.getName()));
        if (faction != null) {
            broadcastToFaction(targetFactionId, kickMsgFaction.replace("{target_name}", target.getName()).replace("{kicker_name}", kicker.getName()));
        }

        updatePlayerDisplay(target);
        // clearRankPermissions(target);
    }

    public void promotePlayer(Player promoter, Player target) {
        if (promoter == null || target == null || promoter == target || factionManager == null || databaseManager == null || configManager == null) return;
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);
        Integer currentRankId = getPlayerRankId(target);

        if (factionId == null || currentRankId == null) {
            promoter.sendMessage(configManager.getMessage("player.not_in_faction_or_invalid_rank", "&c{player_name} is not in a faction or has an invalid rank.").replace("{player_name}", target.getName()));
            return;
        }
        // Проверка фракции промоутера
        String promoterFactionId = getPlayerFactionId(promoter);
        String adminModeFaction = adminsInFactionMode.get(promoter.getUniqueId());
        boolean canPromote = factionId.equals(promoterFactionId) || factionId.equals(adminModeFaction);

        if (!canPromote) {
            promoter.sendMessage(configManager.getMessage("faction.cannot_promote_other_faction", "&cYou can only promote members of your own faction (or while in admin mode)."));
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return; // Маловероятно

        // Проверка ранга промоутера и ранга цели (в FactionCommand)
        FactionRank leaderRank = faction.getLeaderRank(); // Ранг 11
        if (leaderRank != null && currentRankId >= leaderRank.getInternalId()) {
            promoter.sendMessage(configManager.getMessage("faction.cannot_promote_max_rank", "&cYou cannot promote someone at the highest rank."));
            return;
        }

        int nextRankId = currentRankId + 1;
        FactionRank nextRank = faction.getRank(nextRankId);
        if (nextRank == null) {
            String msg = configManager.getMessage("faction.rank_not_exist", "&cRank ID {rank_id} does not exist in this faction.");
            promoter.sendMessage(msg.replace("{rank_id}", String.valueOf(nextRankId)));
            return;
        }

        // Обновляем ранг в кэше и БД
        playerRanks.put(targetUuid, nextRankId);
        databaseManager.savePlayerDataAsync(targetUuid, factionId, nextRankId);

        // Сообщения
        String rankName = nextRank.getDisplayName();
        String promoteMsgTarget = configManager.getMessage("faction.promoted_target", "&aYou have been promoted to {rank_name} by {promoter_name}!");
        String promoteMsgPromoter = configManager.getMessage("faction.promoted_promoter", "&aYou promoted {target_name} to {rank_name}.");
        String promoteMsgFaction = configManager.getMessage("faction.member_promoted", "&e{target_name} was promoted to {rank_name} by {promoter_name}.");

        target.sendMessage(promoteMsgTarget.replace("{rank_name}", rankName).replace("{promoter_name}", promoter.getName()));
        promoter.sendMessage(promoteMsgPromoter.replace("{target_name}", target.getName()).replace("{rank_name}", rankName));
        broadcastToFaction(factionId, promoteMsgFaction.replace("{target_name}", target.getName()).replace("{rank_name}", rankName).replace("{promoter_name}", promoter.getName()));

        updatePlayerDisplay(target);
        // applyRankPermissions(target, nextRank);
    }

    public void demotePlayer(Player demoter, Player target) {
        if (demoter == null || target == null || demoter == target || factionManager == null || databaseManager == null || configManager == null) return;
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);
        Integer currentRankId = getPlayerRankId(target);

        if (factionId == null || currentRankId == null) {
            demoter.sendMessage(configManager.getMessage("player.not_in_faction_or_invalid_rank", "&c{player_name} is not in a faction or has an invalid rank.").replace("{player_name}", target.getName()));
            return;
        }
        // Проверка фракции понижающего
        String demoterFactionId = getPlayerFactionId(demoter);
        String adminModeFaction = adminsInFactionMode.get(demoter.getUniqueId());
        boolean canDemote = factionId.equals(demoterFactionId) || factionId.equals(adminModeFaction);

        if (!canDemote) {
            demoter.sendMessage(configManager.getMessage("faction.cannot_demote_other_faction", "&cYou can only demote members of your own faction (or while in admin mode)."));
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return;

        // Проверка ранга понижающего (в FactionCommand) и проверка, что не понижаем ниже ранга 1
        if (currentRankId <= 1) {
            demoter.sendMessage(configManager.getMessage("faction.cannot_demote_min_rank", "&cYou cannot demote someone at the lowest rank."));
            return;
        }

        int newRankId = currentRankId - 1;
        FactionRank newRank = faction.getRank(newRankId);
        if (newRank == null) { // Ранг 1 должен существовать, но проверим
            String msg = configManager.getMessage("faction.rank_not_exist", "&cRank ID {rank_id} does not exist in this faction.");
            demoter.sendMessage(msg.replace("{rank_id}", String.valueOf(newRankId)));
            return;
        }

        // Обновляем ранг в кэше и БД
        playerRanks.put(targetUuid, newRankId);
        databaseManager.savePlayerDataAsync(targetUuid, factionId, newRankId);

        // Сообщения
        String rankName = newRank.getDisplayName();
        String demoteMsgTarget = configManager.getMessage("faction.demoted_target", "&cYou have been demoted to {rank_name} by {demoter_name}.");
        String demoteMsgDemoter = configManager.getMessage("faction.demoted_demoter", "&aYou demoted {target_name} to {rank_name}.");
        String demoteMsgFaction = configManager.getMessage("faction.member_demoted", "&e{target_name} was demoted to {rank_name} by {demoter_name}.");

        target.sendMessage(demoteMsgTarget.replace("{rank_name}", rankName).replace("{demoter_name}", demoter.getName()));
        demoter.sendMessage(demoteMsgDemoter.replace("{target_name}", target.getName()).replace("{rank_name}", rankName));
        broadcastToFaction(factionId, demoteMsgFaction.replace("{target_name}", target.getName()).replace("{rank_name}", rankName).replace("{demoter_name}", demoter.getName()));

        updatePlayerDisplay(target);
        // applyRankPermissions(target, newRank);
    }

    public void setPlayerRank(Player setter, Player target, int rankId) {
        if (setter == null || target == null || setter == target || factionManager == null || databaseManager == null || configManager == null) return;
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);

        if (factionId == null) {
            setter.sendMessage(configManager.getMessage("player.not_in_faction", "&c{player_name} is not in any faction.").replace("{player_name}", target.getName()));
            return;
        }
        // Проверка фракции установщика
        String setterFactionId = getPlayerFactionId(setter);
        String adminModeFaction = adminsInFactionMode.get(setter.getUniqueId());
        boolean canSetRank = factionId.equals(setterFactionId) || factionId.equals(adminModeFaction);

        if (!canSetRank) {
            setter.sendMessage(configManager.getMessage("faction.cannot_setrank_other_faction", "&cYou can only set rank for members of your own faction (or while in admin mode)."));
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return;

        FactionRank newRank = faction.getRank(rankId);
        if (newRank == null) {
            String msg = configManager.getMessage("faction.rank_not_exist", "&cRank ID {rank_id} does not exist in this faction.");
            setter.sendMessage(msg.replace("{rank_id}", String.valueOf(rankId)));
            return;
        }

        // Проверка ранга установщика (в FactionCommand)

        // Обновляем ранг в кэше и БД
        playerRanks.put(targetUuid, rankId);
        databaseManager.savePlayerDataAsync(targetUuid, factionId, rankId);

        // Сообщения
        String rankName = newRank.getDisplayName();
        String setrankMsgTarget = configManager.getMessage("faction.setrank_target", "&eYour rank has been set to {rank_name} by {setter_name}.");
        String setrankMsgSetter = configManager.getMessage("faction.setrank_setter", "&aYou set {target_name}'s rank to {rank_name}.");
        String setrankMsgFaction = configManager.getMessage("faction.member_setrank", "&e{target_name}'s rank was set to {rank_name} by {setter_name}.");

        target.sendMessage(setrankMsgTarget.replace("{rank_name}", rankName).replace("{setter_name}", setter.getName()));
        setter.sendMessage(setrankMsgSetter.replace("{target_name}", target.getName()).replace("{rank_name}", rankName));
        broadcastToFaction(factionId, setrankMsgFaction.replace("{target_name}", target.getName()).replace("{rank_name}", rankName).replace("{setter_name}", setter.getName()));

        updatePlayerDisplay(target);
        // applyRankPermissions(target, newRank);
    }


    // --- Геттеры и проверки ---

    public boolean isInFaction(Player player) {
        return player != null && playerFactions.containsKey(player.getUniqueId());
    }
    public boolean isInFaction(UUID uuid) {
        return uuid != null && playerFactions.containsKey(uuid);
    }

    public String getPlayerFactionId(Player player) {
        return player != null ? playerFactions.get(player.getUniqueId()) : null;
    }
    public String getPlayerFactionId(UUID uuid) {
        return uuid != null ? playerFactions.get(uuid) : null;
    }

    public Faction getPlayerFaction(Player player) {
        if (player == null || factionManager == null) return null;
        String factionId = getPlayerFactionId(player);
        return factionId != null ? factionManager.getFaction(factionId) : null;
    }
    public Faction getPlayerFaction(UUID uuid) {
        if (uuid == null || factionManager == null) return null;
        String factionId = getPlayerFactionId(uuid);
        return factionId != null ? factionManager.getFaction(factionId) : null;
    }


    public Integer getPlayerRankId(Player player) {
        return player != null ? playerRanks.get(player.getUniqueId()) : null;
    }
    public Integer getPlayerRankId(UUID uuid) {
        return uuid != null ? playerRanks.get(uuid) : null;
    }

    public FactionRank getPlayerRank(Player player) {
        if (player == null) return null;
        return getPlayerRank(player.getUniqueId()); // Используем UUID версию
    }
    public FactionRank getPlayerRank(UUID uuid) {
        if (uuid == null || factionManager == null) return null;
        String factionId = getPlayerFactionId(uuid);
        Integer rankId = getPlayerRankId(uuid);
        if (factionId != null && rankId != null) {
            Faction faction = factionManager.getFaction(factionId);
            if (faction != null) {
                return faction.getRank(rankId);
            }
        }
        return null;
    }


    public List<Player> getOnlineFactionMembers(String factionId) {
        List<Player> members = new ArrayList<>();
        if (factionId == null) return members;
        String lowerCaseFactionId = factionId.toLowerCase();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Сравниваем ID в нижнем регистре
            if (lowerCaseFactionId.equals(getPlayerFactionId(player))) {
                members.add(player);
            }
        }
        return members;
    }

    // --- Фракционный чат ---

    public boolean isInFactionChat(Player player) {
        return player != null && playersInFactionChat.contains(player.getUniqueId());
    }

    public void toggleFactionChat(Player player) {
        if (player == null || configManager == null) return;
        UUID uuid = player.getUniqueId();
        if (!isInFaction(player)) {
            player.sendMessage(configManager.getMessage("faction.chat.must_be_in_faction", "&cYou must be in a faction to use faction chat."));
            return;
        }
        if (playersInFactionChat.contains(uuid)) {
            playersInFactionChat.remove(uuid);
            player.sendMessage(configManager.getMessage("faction.chat.disabled", "&eFaction chat disabled."));
        } else {
            playersInFactionChat.add(uuid);
            player.sendMessage(configManager.getMessage("faction.chat.enabled", "&aFaction chat enabled."));
        }
    }

    public void broadcastToFaction(String factionId, String message) {
        if (factionId == null || message == null) return;
        List<Player> members = getOnlineFactionMembers(factionId); // Уже использует lowercase ID
        plugin.getLogger().fine("Broadcasting to faction " + factionId + " (" + members.size() + " members): " + message);
        for (Player member : members) {
            member.sendMessage(message); // Сообщение уже должно быть отформатировано
        }
    }

    // --- Приглашения ---

    public void addInvite(Player target, PendingInvite invite) {
        if (target == null || invite == null || configManager == null) return;
        UUID targetUuid = target.getUniqueId();
        removeInvite(target); // Удаляем старое приглашение и таймер

        pendingInvites.put(targetUuid, invite);

        long expireSeconds = configManager.getConfig().getLong("faction.invite_expire_seconds", 60);
        if (expireSeconds <= 0) expireSeconds = 60; // Защита от 0 или отрицательного значения

        // Запускаем и сохраняем таймер удаления приглашения
        BukkitRunnable timer = new BukkitRunnable() {
            @Override
            public void run() {
                PendingInvite currentInvite = pendingInvites.get(targetUuid);
                // Удаляем только если это ТО ЖЕ САМОЕ приглашение
                if (currentInvite != null && currentInvite.getInviterUUID().equals(invite.getInviterUUID()) && currentInvite.getFactionId().equals(invite.getFactionId())) {
                    pendingInvites.remove(targetUuid);
                    inviteTimers.remove(targetUuid); // Удаляем таймер из мапы
                    if (target.isOnline()) { // Сообщаем только если игрок онлайн
                        target.sendMessage(configManager.getMessage("faction.invite.expired", "&cThe faction invite from {inviter} has expired.").replace("{inviter}", invite.getInviterName()));
                        // Закрываем GUI, если оно открыто
                        if(target.getOpenInventory().getTitle().contains("Faction Invite")) {
                            target.closeInventory();
                        }
                    }
                } else {
                    // Это уже другое приглашение или его нет, просто удаляем этот таймер из мапы
                    inviteTimers.remove(targetUuid);
                }
            }
        };
        inviteTimers.put(targetUuid, timer);
        timer.runTaskLater(plugin, expireSeconds * 20L);
    }

    public PendingInvite getInvite(Player target) {
        return target != null ? pendingInvites.get(target.getUniqueId()) : null;
    }

    public void removeInvite(Player target) {
        if (target == null) return;
        UUID uuid = target.getUniqueId();
        pendingInvites.remove(uuid);
        cancelInviteTimer(uuid); // Отменяем таймер
    }

    // Отменяет и удаляет таймер приглашения для игрока
    private void cancelInviteTimer(UUID uuid) {
        BukkitRunnable timer = inviteTimers.remove(uuid);
        if (timer != null && !timer.isCancelled()) {
            timer.cancel();
        }
    }

    // --- Админский режим ---

    public boolean isAdminInMode(Player admin) {
        return admin != null && adminsInFactionMode.containsKey(admin.getUniqueId());
    }

    public String getAdminModeFactionId(Player admin) {
        return admin != null ? adminsInFactionMode.get(admin.getUniqueId()) : null;
    }

    public void enterAdminMode(Player admin, Faction faction) {
        if (admin == null || faction == null || configManager == null) return;
        UUID adminUuid = admin.getUniqueId();
        if (isAdminInMode(admin)) {
            String msg = configManager.getMessage("admin.mode.already_in", "&cYou are already in admin mode for faction {faction_id}.");
            admin.sendMessage(msg.replace("{faction_id}", adminsInFactionMode.get(adminUuid)));
            return;
        }

        if (!admin.hasPermission("hfactions.admin.adminmode")) {
            admin.sendMessage(configManager.getMessage("command.no_permission", "&cYou do not have permission to use this command."));
            return;
        }

        // Проверяем LuckPerms перед входом
        if (luckPermsIntegration == null) {
            admin.sendMessage(configManager.getMessage("admin.mode.luckperms_error", "&cError: LuckPerms integration is not available. Cannot enter admin mode."));
            return;
        }
        boolean success = luckPermsIntegration.setAdminMode(admin, true);
        if (!success) {
            admin.sendMessage(configManager.getMessage("admin.mode.permission_error", "&cFailed to apply admin permissions via LuckPerms."));
            return;
        }

        adminsInFactionMode.put(adminUuid, faction.getId());
        String msgEnter = configManager.getMessage("admin.mode.entered", "&aEntered admin mode for faction: {faction_name}.");
        String msgInfo = configManager.getMessage("admin.mode.info", "&eYou now have leader permissions for this faction's commands.");
        admin.sendMessage(msgEnter.replace("{faction_name}", faction.getName()));
        admin.sendMessage(msgInfo);
        updatePlayerDisplay(admin); // Обновить отображение
    }

    public void exitAdminMode(Player admin, boolean silent) {
        if (admin == null || configManager == null) return;
        UUID adminUuid = admin.getUniqueId();
        if (!isAdminInMode(admin)) {
            if (!silent) admin.sendMessage(configManager.getMessage("admin.mode.not_in", "&cYou are not in admin mode."));
            return;
        }

        String factionId = adminsInFactionMode.remove(adminUuid); // Удаляем из кэша сразу

        // Проверяем LuckPerms перед выходом
        if (luckPermsIntegration != null) {
            boolean success = luckPermsIntegration.setAdminMode(admin, false);
            if (!success && !silent) {
                admin.sendMessage(configManager.getMessage("admin.mode.permission_error_exit", "&cFailed to remove admin permissions via LuckPerms, but exiting mode anyway."));
            }
        } else if (!silent) {
            admin.sendMessage(configManager.getMessage("admin.mode.luckperms_error_exit", "&cWarning: LuckPerms integration not available. Could not ensure permissions were removed."));
        }

        if (!silent) {
            String msgExit = configManager.getMessage("admin.mode.exited", "&aExited admin mode for faction: {faction_id}.");
            admin.sendMessage(msgExit.replace("{faction_id}", factionId));
        }
        updatePlayerDisplay(admin); // Обновить отображение
    }

    // --- Вспомогательные методы ---

    /**
     * Обновляет отображаемое имя игрока (требует PAPI или плагин чата/таба).
     */
    private void updatePlayerDisplay(Player player) {
        if (player == null || !player.isOnline()) return;
        // Эта функция - точка интеграции с PlaceholderAPI или другими плагинами
        // Если PAPI установлен, можно обновить его плейсхолдеры:
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            // Запускаем с небольшой задержкой, чтобы данные точно применились
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) { // Проверяем еще раз
                        // PlaceholderAPI.setPlaceholders(player, "%hfactions_...%"); // Неправильно
                        // Для обновления плейсхолдеров сторонних плагинов обычно ничего делать не нужно,
                        // они сами их запросят, когда понадобится (например, при отправке сообщения в чат).
                        // Главное, чтобы ваш Expansion был зарегистрирован и возвращал актуальные данные.
                        plugin.getLogger().fine("PAPI placeholders should refresh for " + player.getName());
                    }
                }
            }.runTaskLater(plugin, 1L); // Задержка в 1 тик
        }

        // Прямое изменение ника лучше не использовать из-за конфликтов
        plugin.getLogger().fine("updatePlayerDisplay called for " + player.getName());
    }

    // --- Применение/очистка прав ранга (если не используется система групп LP) ---
     /*
     private void applyRankPermissions(Player player, FactionRank rank) {
         if (player == null || rank == null || luckPermsIntegration == null) return;
         User user = luckPermsIntegration.getApi().getPlayerAdapter(Player.class).getUser(player);
         List<String> perms = rank.getPermissions();
         if (perms != null && !perms.isEmpty()) {
             plugin.getLogger().fine("Applying " + perms.size() + " permissions for rank " + rank.getDisplayName() + " to " + player.getName());
             perms.forEach(perm -> {
                 Node node = PermissionNode.builder(perm).value(true).build();
                 user.data().add(node);
             });
             luckPermsIntegration.getApi().getUserManager().saveUser(user);
         }
     }

     private void clearRankPermissions(Player player) { // Или принимать старый ранг?
          if (player == null || luckPermsIntegration == null) return;
          User user = luckPermsIntegration.getApi().getPlayerAdapter(Player.class).getUser(player);
          boolean changed = false;
          // Удаляем все права, начинающиеся с hfactions., КРОМЕ базовых и админских? Сложно.
          // Проще использовать группы LP для рангов.
          // Если все же удалять напрямую:
          // Collection<Node> nodes = user.getNodes();
          // for (Node node : nodes) {
          //     if (node instanceof PermissionNode && node.getKey().startsWith("hfactions.faction.")) { // Пример
          //         user.data().remove(node);
          //         changed = true;
          //     }
          // }
          // if (changed) {
          //     luckPermsIntegration.getApi().getUserManager().saveUser(user);
          // }
     }
     */

} // Конец класса PlayerManager