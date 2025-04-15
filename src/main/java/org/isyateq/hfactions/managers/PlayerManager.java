package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.PendingInvite;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class PlayerManager {

    private final HFactions plugin;
    private final DatabaseManager databaseManager; // Используем новый менеджер
    private final FactionManager factionManager;
    private final ConfigManager configManager;

    // Данные игроков хранятся в памяти, пока они онлайн
    private final Map<UUID, String> playerFactions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerRanks = new ConcurrentHashMap<>();
    private final Set<UUID> playersInFactionChat = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingInvite> pendingInvites = new ConcurrentHashMap<>();
    private final Map<UUID, String> adminsInFactionMode = new ConcurrentHashMap<>(); // UUID админа -> ID фракции

    public PlayerManager(HFactions plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager(); // Получаем DatabaseManager
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        // НЕ загружаем данные из файла здесь
    }

    // --- УДАЛИТЬ СТАРЫЕ МЕТОДЫ ЗАГРУЗКИ/СОХРАНЕНИЯ ИЗ YML ---
    /*
    public void loadPlayerData() { ... } // УДАЛИТЬ
    public void savePlayerData() { ... } // УДАЛИТЬ
    public void reloadPlayerData() { ... } // УДАЛИТЬ (или переделать на очистку кэша и перезагрузку онлайн)
    */

    // --- Методы для загрузки/сохранения данных при входе/выходе/релоаде ---

    /**
     * Загружает данные для указанного игрока (обычно при входе).
     */
    public void loadPlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("Loading data for player " + player.getName() + " (" + uuid + ")"); // Debug
        databaseManager.loadPlayerDataAsync(uuid, (factionId, rankId) -> {
            plugin.getLogger().info("Data loaded for " + player.getName() + ": faction=" + factionId + ", rank=" + rankId); // Debug
            if (factionId != null && factionManager.getFaction(factionId) != null) {
                playerFactions.put(uuid, factionId);
                if (rankId != null && factionManager.getFaction(factionId).getRank(rankId) != null) {
                    playerRanks.put(uuid, rankId);
                } else {
                    // Если ранг невалиден или null, сбрасываем на ранг 1
                    playerRanks.put(uuid, 1);
                    plugin.getLogger().warning("Player " + player.getName() + " had invalid rank ID " + rankId + " for faction " + factionId + ". Resetting to rank 1.");
                    // Обновляем данные в БД сразу же, чтобы исправить несоответствие
                    databaseManager.savePlayerDataAsync(uuid, factionId, 1);
                }
                // TODO: Применить права ранга через LuckPerms? (Если права не хранятся в LP)
            } else {
                // Если фракции нет или она недействительна, удаляем данные из кэша
                playerFactions.remove(uuid);
                playerRanks.remove(uuid);
                if(factionId != null) { // Если фракция была указана, но не найдена, очищаем в БД
                    databaseManager.savePlayerDataAsync(uuid, null, null);
                    plugin.getLogger().warning("Player " + player.getName() + " was in a non-existent faction " + factionId + ". Data cleared.");
                }
            }
            // Обновляем префикс/таблист, если нужно
            updatePlayerDisplay(player);
        });
    }

    /**
     * Сохраняет данные для указанного игрока (обычно при выходе).
     */
    public void savePlayerData(Player player) {
        savePlayerData(player.getUniqueId()); // Вызываем перегруженный метод
    }

    /**
     * Сохраняет данные для указанного UUID (используется при выходе и в onDisable).
     */
    public void savePlayerData(UUID uuid) {
        String factionId = playerFactions.get(uuid);
        Integer rankId = playerRanks.get(uuid);
        plugin.getLogger().info("Saving data for player " + uuid + ": faction=" + factionId + ", rank=" + rankId); // Debug
        // Сохраняем текущее состояние (даже если factionId/rankId null, это очистит запись в БД)
        databaseManager.savePlayerDataAsync(uuid, factionId, rankId);
    }


    /**
     * Вызывается при /reload или onEnable для загрузки данных тех, кто уже онлайн.
     */
    public void loadDataForOnlinePlayers() {
        plugin.getLogger().info("Loading data for " + Bukkit.getOnlinePlayers().size() + " online players...");
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerData(player);
        }
    }

    /**
     * Вызывается в onDisable для сохранения данных тех, кто еще онлайн.
     */
    public void saveDataForOnlinePlayers() {
        plugin.getLogger().info("Saving data for " + Bukkit.getOnlinePlayers().size() + " online players...");
        // Сохраняем синхронно при выключении, чтобы гарантировать запись до остановки
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            String factionId = playerFactions.get(uuid);
            Integer rankId = playerRanks.get(uuid);
            // Синхронное сохранение (менее предпочтительно, но безопаснее при выключении)
            String sql = "INSERT OR REPLACE INTO player_data (uuid, faction_id, rank_id) VALUES(?, ?, ?);";
            try (Connection conn = databaseManager.getConnection(); // Получаем соединение напрямую
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, factionId); // factionId может быть null
                if (rankId != null) pstmt.setInt(3, rankId); else pstmt.setNull(3, Types.INTEGER);
                pstmt.executeUpdate();
                plugin.getLogger().fine("Saved data synchronously for " + player.getName());

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save player data synchronously for " + uuid, e);
            }

            // Удаляем из кэша после сохранения (не обязательно, т.к. плагин выключается)
            // clearPlayerData(player);
        }
    }


    /**
     * Очищает кэшированные данные игрока (при выходе).
     */
    public void clearPlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("Clearing cached data for player " + player.getName() + " (" + uuid + ")"); // Debug
        playerFactions.remove(uuid);
        playerRanks.remove(uuid);
        playersInFactionChat.remove(uuid);
        pendingInvites.remove(uuid);
        // Если игрок был в админ режиме, выводим его
        if (adminsInFactionMode.containsKey(uuid)) {
            exitAdminMode(player, true); // Выход без сообщения
        }
        // TODO: Сбросить права LuckPerms, если они выдавались при входе?
        // TODO: Обновить префикс/таблист? (Обычно делается плагином чата)
    }


    // --- Методы управления фракцией игрока ---

    public void joinFaction(Player player, Faction faction) {
        UUID uuid = player.getUniqueId();
        if (isInFaction(player)) {
            player.sendMessage(ChatColor.RED + "You are already in a faction.");
            return;
        }
        if (faction == null) {
            player.sendMessage(ChatColor.RED + "Invalid faction specified.");
            return;
        }

        String factionId = faction.getId();
        int initialRankId = 1; // Всегда начинаем с ранга 1

        playerFactions.put(uuid, factionId);
        playerRanks.put(uuid, initialRankId);

        // Сохраняем в БД АСИНХРОННО
        databaseManager.savePlayerDataAsync(uuid, factionId, initialRankId);

        // Отправляем сообщение игроку и во фракцию
        player.sendMessage(ChatColor.GREEN + "You have joined the " + faction.getName() + " faction!");
        broadcastToFaction(factionId, ChatColor.YELLOW + player.getName() + " has joined the faction.");

        // Обновляем отображение
        updatePlayerDisplay(player);
        // TODO: Выдать права первого ранга через LuckPerms?
    }

    public void leaveFaction(Player player) {
        UUID uuid = player.getUniqueId();
        String factionId = getPlayerFactionId(player);

        if (factionId == null) {
            player.sendMessage(ChatColor.RED + "You are not in a faction.");
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        String factionName = faction != null ? faction.getName() : factionId;

        // Сохраняем null в БД АСИНХРОННО
        databaseManager.savePlayerDataAsync(uuid, null, null);

        // Очищаем кэш
        playerFactions.remove(uuid);
        playerRanks.remove(uuid);
        playersInFactionChat.remove(uuid); // Выходим из фракц. чата

        // Сообщения
        player.sendMessage(ChatColor.GREEN + "You have left the " + factionName + " faction.");
        if (faction != null) {
            broadcastToFaction(factionId, ChatColor.YELLOW + player.getName() + " has left the faction.");
        }

        // Обновляем отображение
        updatePlayerDisplay(player);
        // TODO: Забрать фракционные права LuckPerms?
    }

    public void kickPlayer(Player kicker, Player target) {
        UUID targetUuid = target.getUniqueId();
        String targetFactionId = getPlayerFactionId(target);

        if (targetFactionId == null) {
            kicker.sendMessage(ChatColor.RED + target.getName() + " is not in any faction.");
            return;
        }
        // Проверка, что кикающий в той же фракции (или админ в режиме)
        String kickerFactionId = getPlayerFactionId(kicker);
        String adminModeFaction = adminsInFactionMode.get(kicker.getUniqueId());
        if (!targetFactionId.equals(kickerFactionId) && !targetFactionId.equals(adminModeFaction)) {
            kicker.sendMessage(ChatColor.RED + "You can only kick members of your own faction.");
            return;
        }
        // Проверка ранга (кикающий должен быть выше или иметь право) - логика в FactionCommand
        // ...

        Faction faction = factionManager.getFaction(targetFactionId);
        String factionName = faction != null ? faction.getName() : targetFactionId;

        // Сохраняем null в БД АСИНХРОННО
        databaseManager.savePlayerDataAsync(targetUuid, null, null);

        // Очищаем кэш для кикнутого
        playerFactions.remove(targetUuid);
        playerRanks.remove(targetUuid);
        playersInFactionChat.remove(targetUuid);

        // Сообщения
        target.sendMessage(ChatColor.RED + "You have been kicked from the " + factionName + " faction by " + kicker.getName() + ".");
        kicker.sendMessage(ChatColor.GREEN + "You have kicked " + target.getName() + " from the faction.");
        if (faction != null) {
            broadcastToFaction(targetFactionId, ChatColor.YELLOW + target.getName() + " was kicked from the faction by " + kicker.getName() + ".");
        }

        // Обновляем отображение кикнутого
        updatePlayerDisplay(target);
        // TODO: Забрать фракционные права LuckPerms у кикнутого?
    }

    public void promotePlayer(Player promoter, Player target) {
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);
        Integer currentRankId = getPlayerRankId(target);

        if (factionId == null || currentRankId == null) {
            promoter.sendMessage(ChatColor.RED + target.getName() + " is not in a faction or has an invalid rank.");
            return;
        }
        // Проверка фракции промоутера
        String promoterFactionId = getPlayerFactionId(promoter);
        String adminModeFaction = adminsInFactionMode.get(promoter.getUniqueId());
        if (!factionId.equals(promoterFactionId) && !factionId.equals(adminModeFaction)) {
            promoter.sendMessage(ChatColor.RED + "You can only promote members of your own faction.");
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return; // Маловероятно, но все же

        // Проверка ранга промоутера (в FactionCommand) и проверка, что не повышаем лидера (ранг 11)
        FactionRank leaderRank = faction.getLeaderRank();
        if (leaderRank != null && currentRankId >= leaderRank.getInternalId()) {
            promoter.sendMessage(ChatColor.RED + "You cannot promote the faction leader or someone at the highest rank.");
            return;
        }

        int nextRankId = currentRankId + 1;
        FactionRank nextRank = faction.getRank(nextRankId);
        if (nextRank == null) {
            promoter.sendMessage(ChatColor.RED + "The next rank (" + nextRankId + ") does not exist in this faction.");
            return;
        }

        // Обновляем ранг в кэше
        playerRanks.put(targetUuid, nextRankId);
        // Сохраняем в БД АСИНХРОННО
        databaseManager.savePlayerDataAsync(targetUuid, factionId, nextRankId);

        // Сообщения
        String rankName = nextRank.getDisplayName();
        target.sendMessage(ChatColor.GREEN + "You have been promoted to " + rankName + " by " + promoter.getName() + "!");
        promoter.sendMessage(ChatColor.GREEN + "You have promoted " + target.getName() + " to " + rankName + ".");
        broadcastToFaction(factionId, ChatColor.YELLOW + target.getName() + " was promoted to " + rankName + " by " + promoter.getName() + ".");

        // Обновляем отображение и права
        updatePlayerDisplay(target);
        // TODO: Обновить права LuckPerms для нового ранга?
    }

    public void demotePlayer(Player demoter, Player target) {
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);
        Integer currentRankId = getPlayerRankId(target);

        if (factionId == null || currentRankId == null) {
            demoter.sendMessage(ChatColor.RED + target.getName() + " is not in a faction or has an invalid rank.");
            return;
        }
        // Проверка фракции понижающего
        String demoterFactionId = getPlayerFactionId(demoter);
        String adminModeFaction = adminsInFactionMode.get(demoter.getUniqueId());
        if (!factionId.equals(demoterFactionId) && !factionId.equals(adminModeFaction)) {
            demoter.sendMessage(ChatColor.RED + "You can only demote members of your own faction.");
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return;

        // Проверка ранга понижающего (в FactionCommand) и проверка, что не понижаем ниже ранга 1
        if (currentRankId <= 1) {
            demoter.sendMessage(ChatColor.RED + "You cannot demote someone who is already at the lowest rank.");
            return;
        }

        int newRankId = currentRankId - 1;
        FactionRank newRank = faction.getRank(newRankId); // Ранг 1 точно должен быть

        // Обновляем ранг в кэше
        playerRanks.put(targetUuid, newRankId);
        // Сохраняем в БД АСИНХРОННО
        databaseManager.savePlayerDataAsync(targetUuid, factionId, newRankId);

        // Сообщения
        String rankName = newRank.getDisplayName();
        target.sendMessage(ChatColor.RED + "You have been demoted to " + rankName + " by " + demoter.getName() + ".");
        demoter.sendMessage(ChatColor.GREEN + "You have demoted " + target.getName() + " to " + rankName + ".");
        broadcastToFaction(factionId, ChatColor.YELLOW + target.getName() + " was demoted to " + rankName + " by " + demoter.getName() + ".");

        // Обновляем отображение и права
        updatePlayerDisplay(target);
        // TODO: Обновить права LuckPerms для нового ранга?
    }

    public void setPlayerRank(Player setter, Player target, int rankId) {
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);
        Integer currentRankId = getPlayerRankId(target); // Текущий ранг для сравнения

        if (factionId == null) {
            setter.sendMessage(ChatColor.RED + target.getName() + " is not in a faction.");
            return;
        }
        // Проверка фракции установщика
        String setterFactionId = getPlayerFactionId(setter);
        String adminModeFaction = adminsInFactionMode.get(setter.getUniqueId());
        if (!factionId.equals(setterFactionId) && !factionId.equals(adminModeFaction)) {
            setter.sendMessage(ChatColor.RED + "You can only set rank for members of your own faction.");
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return;

        FactionRank newRank = faction.getRank(rankId);
        if (newRank == null) {
            setter.sendMessage(ChatColor.RED + "Rank ID " + rankId + " does not exist in this faction.");
            return;
        }

        // Проверка ранга установщика (в FactionCommand) - не должен ставить ранг выше своего (если не админ)
        // ...

        // Обновляем ранг в кэше
        playerRanks.put(targetUuid, rankId);
        // Сохраняем в БД АСИНХРОННО
        databaseManager.savePlayerDataAsync(targetUuid, factionId, rankId);

        // Сообщения
        String rankName = newRank.getDisplayName();
        target.sendMessage(ChatColor.YELLOW + "Your rank has been set to " + rankName + " by " + setter.getName() + ".");
        setter.sendMessage(ChatColor.GREEN + "You have set " + target.getName() + "'s rank to " + rankName + ".");
        broadcastToFaction(factionId, ChatColor.YELLOW + target.getName() + "'s rank was set to " + rankName + " by " + setter.getName() + ".");

        // Обновляем отображение и права
        updatePlayerDisplay(target);
        // TODO: Обновить права LuckPerms для нового ранга?
    }


    // --- Геттеры и проверки ---

    public boolean isInFaction(Player player) {
        return playerFactions.containsKey(player.getUniqueId());
    }

    public String getPlayerFactionId(Player player) {
        return playerFactions.get(player.getUniqueId());
    }

    public String getPlayerFactionId(UUID uuid) {
        return playerFactions.get(uuid);
    }


    public Faction getPlayerFaction(Player player) {
        String factionId = getPlayerFactionId(player);
        return factionId != null ? factionManager.getFaction(factionId) : null;
    }

    public Integer getPlayerRankId(Player player) {
        return playerRanks.get(player.getUniqueId());
    }

    public FactionRank getPlayerRank(Player player) {
        String factionId = getPlayerFactionId(player);
        Integer rankId = getPlayerRankId(player);
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (factionId.equals(getPlayerFactionId(player))) {
                members.add(player);
            }
        }
        return members;
    }

    // --- Фракционный чат ---

    public boolean isInFactionChat(Player player) {
        return playersInFactionChat.contains(player.getUniqueId());
    }

    public void toggleFactionChat(Player player) {
        UUID uuid = player.getUniqueId();
        if (!isInFaction(player)) {
            player.sendMessage(ChatColor.RED + "You must be in a faction to use faction chat.");
            return;
        }
        if (playersInFactionChat.contains(uuid)) {
            playersInFactionChat.remove(uuid);
            player.sendMessage(ChatColor.YELLOW + "Faction chat disabled.");
        } else {
            playersInFactionChat.add(uuid);
            player.sendMessage(ChatColor.GREEN + "Faction chat enabled.");
        }
    }

    public void broadcastToFaction(String factionId, String message) {
        if (factionId == null || message == null) return;
        List<Player> members = getOnlineFactionMembers(factionId);
        plugin.getLogger().fine("Broadcasting to faction " + factionId + " (" + members.size() + " members): " + message);
        for (Player member : members) {
            member.sendMessage(message);
        }
        // Можно также логировать сообщение в консоль или файл
    }

    // --- Приглашения ---

    public void addInvite(Player target, PendingInvite invite) {
        removeInvite(target); // Удаляем старое приглашение, если есть
        pendingInvites.put(target.getUniqueId(), invite);
        // Запускаем таймер удаления приглашения
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingInvite currentInvite = pendingInvites.get(target.getUniqueId());
            // Удаляем только если это ТО ЖЕ САМОЕ приглашение (на случай если его приняли/отклонили и пришло новое)
            if (currentInvite != null && currentInvite.getInviterUUID().equals(invite.getInviterUUID()) && currentInvite.getFactionId().equals(invite.getFactionId())) {
                pendingInvites.remove(target.getUniqueId());
                target.sendMessage(ChatColor.RED + "The faction invite from " + invite.getInviterName() + " has expired.");
                // Закрыть GUI если открыто?
                if(target.getOpenInventory().getTitle().contains("Faction Invite")) { // Простая проверка
                    target.closeInventory();
                }
            }
        }, configManager.getConfig().getLong("faction.invite_expire_seconds", 60) * 20L); // 60 секунд по умолчанию
    }

    public PendingInvite getInvite(Player target) {
        return pendingInvites.get(target.getUniqueId());
    }

    public void removeInvite(Player target) {
        pendingInvites.remove(target.getUniqueId());
    }

    // --- Админский режим ---

    public boolean isAdminInMode(Player admin) {
        return adminsInFactionMode.containsKey(admin.getUniqueId());
    }

    public String getAdminModeFactionId(Player admin) {
        return adminsInFactionMode.get(admin.getUniqueId());
    }

    public void enterAdminMode(Player admin, Faction faction) {
        UUID adminUuid = admin.getUniqueId();
        if (isAdminInMode(admin)) {
            admin.sendMessage(ChatColor.RED + "You are already in admin mode for faction " + adminsInFactionMode.get(adminUuid) + ".");
            return;
        }

        if (!admin.hasPermission("hfactions.admin.adminmode")) {
            admin.sendMessage(ChatColor.RED + "You do not have permission to enter admin mode.");
            return;
        }

        if (faction == null) {
            admin.sendMessage(ChatColor.RED + "Invalid faction specified.");
            return;
        }

        // Выдаем права лидера через LuckPerms
        boolean success = plugin.getLuckPermsIntegration().setAdminMode(admin, true);
        if (!success) {
            admin.sendMessage(ChatColor.RED + "Failed to apply admin permissions via LuckPerms.");
            return;
        }

        adminsInFactionMode.put(adminUuid, faction.getId());
        admin.sendMessage(ChatColor.GREEN + "You have entered admin mode for faction: " + faction.getName());
        admin.sendMessage(ChatColor.YELLOW + "You now have leader permissions for this faction's commands.");
        // TODO: Добавить PAPI хук или обновить префикс/таб
        updatePlayerDisplay(admin); // Обновить отображение
    }

    public void exitAdminMode(Player admin, boolean silent) {
        UUID adminUuid = admin.getUniqueId();
        if (!isAdminInMode(admin)) {
            if (!silent) admin.sendMessage(ChatColor.RED + "You are not in admin mode.");
            return;
        }

        String factionId = adminsInFactionMode.get(adminUuid);

        // Забираем права через LuckPerms
        boolean success = plugin.getLuckPermsIntegration().setAdminMode(admin, false);
        if (!success && !silent) { // Сообщаем об ошибке только если не тихий выход (например, при выходе игрока)
            admin.sendMessage(ChatColor.RED + "Failed to remove admin permissions via LuckPerms.");
            // Не выходим из режима, если не удалось снять права? Или выходим? Решаем... пока выходим
        }

        adminsInFactionMode.remove(adminUuid);
        if (!silent) admin.sendMessage(ChatColor.GREEN + "You have exited admin mode for faction: " + factionId);
        // TODO: Обновить PAPI хук или префикс/таб
        updatePlayerDisplay(admin); // Обновить отображение
    }

    // --- Вспомогательные методы ---

    /**
     * Обновляет отображаемое имя игрока (например, для префикса в табе или чате).
     * Требует интеграции с плагином чата/таба или PlaceholderAPI.
     */
    private void updatePlayerDisplay(Player player) {
        // Это ЗАГЛУШКА. Реальная логика зависит от используемого плагина чата/таба.
        // Пример с PlaceholderAPI (если вы создадите Expansion):
        // PlaceholderAPI.refreshPlaceholders(player);

        // Пример прямого изменения ника (простой, но не всегда желательный):
        /*
        Faction faction = getPlayerFaction(player);
        String prefix = "";
        if (isAdminInMode(player)) {
            prefix = ChatColor.GOLD + "[A:" + getAdminModeFactionId(player) + "] ";
        } else if (faction != null) {
            prefix = ChatColor.translateAlternateColorCodes('&', faction.getPrefix()) + " ";
        }
        // ОСТОРОЖНО: setDisplayName влияет на чат, setPlayerListName - на таб.
        // Это может конфликтовать с другими плагинами.
        // player.setDisplayName(prefix + player.getName() + ChatColor.RESET);
        // player.setPlayerListName(prefix + player.getName()); // Может быть обрезан
        */
        plugin.getLogger().fine("Placeholder: updatePlayerDisplay called for " + player.getName());
    }

}