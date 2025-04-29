package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.PendingInvite;
import org.isyateq.hfactions.util.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerManager {

    private final HFactions plugin;
    private final DatabaseManager databaseManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager;

    // Кэш данных игроков онлайн
    private final Map<UUID, String> playerFactions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerRanks = new ConcurrentHashMap<>();
    private final Set<UUID> playersInFactionChat = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingInvite> pendingInvites = new ConcurrentHashMap<>();
    private final Map<UUID, String> adminsInFactionMode = new ConcurrentHashMap<>();

    public PlayerManager(HFactions plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
    }

    // --- Методы для загрузки/сохранения данных ---

    public void loadPlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().fine("Loading data for player " + player.getName() + " (" + uuid + ")");
        databaseManager.loadPlayerDataAsync(uuid, (factionId, rankId) -> {
            plugin.getLogger().fine("Data received for " + player.getName() + ": faction=" + factionId + ", rank=" + rankId);

            // Проверяем, существует ли еще фракция
            Faction currentFaction = null;
            if (factionId != null) {
                currentFaction = factionManager.getFaction(factionId);
                if (currentFaction == null) {
                    plugin.getLogger().warning("Player " + player.getName() + " was in faction " + factionId + ", but it no longer exists. Clearing data.");
                    // Очищаем невалидные данные в БД асинхронно
                    databaseManager.savePlayerDataAsync(uuid, null, null);
                    factionId = null; // Сбрасываем для кэша
                    rankId = null;
                }
            }

            // Обновляем кэш
            if (factionId != null && currentFaction != null) { // Убедимся, что фракция существует
                playerFactions.put(uuid, factionId);
                // Проверяем валидность ранга
                if (rankId != null && currentFaction.getRank(rankId) != null) {
                    playerRanks.put(uuid, rankId);
                } else {
                    // Если ранг невалиден или null, ставим ранг 1
                    int defaultRank = 1;
                    playerRanks.put(uuid, defaultRank);
                    plugin.getLogger().warning("Player " + player.getName() + " had invalid rank ID " + rankId + " for faction " + factionId + ". Resetting to rank " + defaultRank + ".");
                    // Обновляем ранг в БД
                    databaseManager.savePlayerDataAsync(uuid, factionId, defaultRank);
                }
            } else {
                // Игрок не состоит во фракции
                playerFactions.remove(uuid);
                playerRanks.remove(uuid);
            }
            // Обновляем отображение (префикс и т.д.)
            updatePlayerDisplay(player);
            // TODO: Применить права LuckPerms?
        });
    }

    public void savePlayerData(Player player) {
        savePlayerData(player.getUniqueId());
    }

    public void savePlayerData(UUID uuid) {
        String factionId = playerFactions.get(uuid);
        Integer rankId = playerRanks.get(uuid);
        plugin.getLogger().fine("Queueing save data for player " + uuid + ": faction=" + factionId + ", rank=" + rankId);
        databaseManager.savePlayerDataAsync(uuid, factionId, rankId);
    }

    public void loadDataForOnlinePlayers() {
        plugin.getLogger().info("Loading data for " + Bukkit.getOnlinePlayers().size() + " online players...");
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerData(player);
        }
    }

    public void saveDataForOnlinePlayers() {
        plugin.getLogger().info("Saving data for " + Bukkit.getOnlinePlayers().size() + " online players...");
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            String factionId = playerFactions.get(uuid);
            Integer rankId = playerRanks.get(uuid);
            // Используем СИНХРОННОЕ сохранение при выключении
            databaseManager.savePlayerDataSync(uuid, factionId, rankId);
        }
        plugin.getLogger().info("Finished saving data for online players.");
    }

    public void clearPlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().fine("Clearing cached data for player " + player.getName() + " (" + uuid + ")");
        playerFactions.remove(uuid);
        playerRanks.remove(uuid);
        playersInFactionChat.remove(uuid);
        pendingInvites.remove(uuid);
        if (adminsInFactionMode.containsKey(uuid)) {
            exitAdminMode(player, true); // Тихий выход
        }
        // TODO: Сбросить права LuckPerms?
        // Обновляем отображение, чтобы убрать префикс
        updatePlayerDisplay(player);
    }

    /**
     * Проверяет данные всех онлайн игроков после перезагрузки конфигов фракций.
     * Если фракция или ранг игрока больше не действительны, сбрасывает их.
     */
    public void validatePlayerDataForAllOnline() {
        plugin.getLogger().info("Validating faction data for online players after reload...");
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            String factionId = playerFactions.get(uuid);
            if (factionId == null) continue; // Игрок не во фракции

            Faction faction = factionManager.getFaction(factionId);
            if (faction == null) {
                // Фракция удалена
                plugin.getLogger().warning("Player " + player.getName() + "'s faction " + factionId + " no longer exists after reload. Clearing data.");
                databaseManager.savePlayerDataAsync(uuid, null, null); // Очищаем в БД
                clearPlayerData(player); // Очищаем кэш и обновляем дисплей
                player.sendMessage(ChatColor.RED + "Your faction no longer exists and you have been removed from it.");
            } else {
                // Фракция существует, проверяем ранг
                Integer rankId = playerRanks.get(uuid);
                if (rankId == null || faction.getRank(rankId) == null) {
                    plugin.getLogger().warning("Player " + player.getName() + "'s rank " + rankId + " in faction " + factionId + " is no longer valid after reload. Resetting to rank 1.");
                    int defaultRank = 1;
                    playerRanks.put(uuid, defaultRank); // Обновляем кэш
                    databaseManager.savePlayerDataAsync(uuid, factionId, defaultRank); // Обновляем БД
                    updatePlayerDisplay(player); // Обновляем дисплей
                    player.sendMessage(ChatColor.YELLOW + "Your rank in the faction was reset due to configuration changes.");
                }
            }
        }
    }


    // --- Методы управления фракцией игрока ---

    public boolean joinFaction(Player player, Faction faction) {
        UUID uuid = player.getUniqueId();
        if (isInFaction(player)) {
            player.sendMessage(Utils.color("&cYou are already in a faction."));
            return false;
        }
        if (faction == null) {
            player.sendMessage(Utils.color("&cInvalid faction specified."));
            return false;
        }

        String factionId = faction.getId();
        int initialRankId = 1; // Всегда начинаем с ранга 1

        FactionRank rank = faction.getRank(initialRankId);
        if (rank == null) {
            plugin.getLogger().severe("FATAL: Rank 1 does not exist in faction " + factionId + "! Cannot join.");
            player.sendMessage(Utils.color("&cError joining faction: Default rank not found. Please contact an administrator."));
            return false;
        }

        playerFactions.put(uuid, factionId);
        playerRanks.put(uuid, initialRankId);
        databaseManager.savePlayerDataAsync(uuid, factionId, initialRankId);

        player.sendMessage(Utils.color("&aYou have joined the " + faction.getName() + "&a faction!"));
        broadcastToFaction(factionId, Utils.color("&e" + player.getName() + " has joined the faction."));

        updatePlayerDisplay(player);
        // TODO: Выдать права LuckPerms?
        return true;
    }

    public boolean leaveFaction(Player player) {
        UUID uuid = player.getUniqueId();
        String factionId = getPlayerFactionId(player);

        if (factionId == null) {
            player.sendMessage(Utils.color("&cYou are not in a faction."));
            return false;
        }

        Faction faction = factionManager.getFaction(factionId);
        String factionName = faction != null ? faction.getName() : factionId;

        databaseManager.savePlayerDataAsync(uuid, null, null); // Очищаем в БД

        String playerName = player.getName(); // Сохраняем имя перед очисткой кэша

        // Очищаем кэш ПОСЛЕ отправки сообщений и обновления дисплея
        player.sendMessage(Utils.color("&aYou have left the " + factionName + "&a faction."));
        if (faction != null) {
            broadcastToFaction(factionId, Utils.color("&e" + playerName + " has left the faction."));
        }
        // Очищаем кэш
        clearPlayerData(player); // Это также обновит дисплей

        // TODO: Забрать права LuckPerms?
        return true;
    }

    public boolean kickPlayer(Player kicker, Player target) {
        UUID targetUuid = target.getUniqueId();
        String targetFactionId = getPlayerFactionId(target);

        if (targetFactionId == null) {
            kicker.sendMessage(Utils.color("&c" + target.getName() + " is not in any faction."));
            return false;
        }
        // Проверка фракции кикера (или админ режима)
        if (!canManagePlayer(kicker, target)) {
            kicker.sendMessage(Utils.color("&cYou cannot kick members of this faction."));
            return false;
        }
        // Проверка ранга кикера относительно цели (логика в FactionCommand)

        Faction faction = factionManager.getFaction(targetFactionId);
        String factionName = faction != null ? faction.getName() : targetFactionId;
        String targetName = target.getName(); // Сохраняем имя
        String kickerName = kicker.getName();

        databaseManager.savePlayerDataAsync(targetUuid, null, null); // Очищаем в БД

        target.sendMessage(Utils.color("&cYou have been kicked from the " + factionName + "&c faction by " + kickerName + "."));
        kicker.sendMessage(Utils.color("&aYou have kicked " + targetName + " from the faction."));
        if (faction != null) {
            broadcastToFaction(targetFactionId, Utils.color("&e" + targetName + " was kicked from the faction by " + kickerName + "."));
        }

        clearPlayerData(target); // Очищаем кэш и обновляем дисплей цели

        // TODO: Забрать права LuckPerms у цели?
        return true;
    }

    public boolean promotePlayer(Player promoter, Player target) {
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);
        Integer currentRankId = getPlayerRankId(target);

        if (factionId == null || currentRankId == null) {
            promoter.sendMessage(Utils.color("&c" + target.getName() + " is not in a faction or has an invalid rank."));
            return false;
        }
        if (!canManagePlayer(promoter, target)) {
            promoter.sendMessage(Utils.color("&cYou cannot promote members of this faction."));
            return false;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return false;

        // Проверка ранга промоутера и проверка, что не повышаем лидера (в FactionCommand)
        FactionRank leaderRank = faction.getLeaderRank();
        if (leaderRank != null && currentRankId >= leaderRank.getInternalId()) {
            promoter.sendMessage(Utils.color("&cYou cannot promote the faction leader or someone at the highest rank."));
            return false;
        }

        int nextRankId = currentRankId + 1;
        FactionRank nextRank = faction.getRank(nextRankId);
        if (nextRank == null) {
            promoter.sendMessage(Utils.color("&cThe next rank (" + nextRankId + ") does not exist in this faction."));
            return false;
        }

        playerRanks.put(targetUuid, nextRankId);
        databaseManager.savePlayerDataAsync(targetUuid, factionId, nextRankId);

        String rankName = nextRank.getDisplayName() != null ? nextRank.getDisplayName() : nextRank.getDefaultName();
        target.sendMessage(Utils.color("&aYou have been promoted to " + rankName + "&a by " + promoter.getName() + "!"));
        promoter.sendMessage(Utils.color("&aYou have promoted " + target.getName() + " to " + rankName + "."));
        broadcastToFaction(factionId, Utils.color("&e" + target.getName() + " was promoted to " + rankName + " by " + promoter.getName() + "."));

        updatePlayerDisplay(target);
        // TODO: Обновить права LuckPerms?
        return true;
    }

    public boolean demotePlayer(Player demoter, Player target) {
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);
        Integer currentRankId = getPlayerRankId(target);

        if (factionId == null || currentRankId == null) {
            demoter.sendMessage(Utils.color("&c" + target.getName() + " is not in a faction or has an invalid rank."));
            return false;
        }
        if (!canManagePlayer(demoter, target)) {
            demoter.sendMessage(Utils.color("&cYou cannot demote members of this faction."));
            return false;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return false;

        // Проверка ранга понижающего (в FactionCommand)
        if (currentRankId <= 1) {
            demoter.sendMessage(Utils.color("&cYou cannot demote someone who is already at the lowest rank."));
            return false;
        }
        // Проверка, чтобы не понизить себя (если не админ)
        if (demoter.getUniqueId().equals(target.getUniqueId()) && !isAdminInMode(demoter)) {
            demoter.sendMessage(Utils.color("&cYou cannot demote yourself."));
            return false;
        }

        int newRankId = currentRankId - 1;
        FactionRank newRank = faction.getRank(newRankId);
        if (newRank == null) { // Маловероятно, т.к. ранг 1 должен быть
            plugin.getLogger().severe("Error demoting: Rank " + newRankId + " not found in faction " + factionId);
            demoter.sendMessage(Utils.color("&cInternal error during demotion. Please contact an admin."));
            return false;
        }


        playerRanks.put(targetUuid, newRankId);
        databaseManager.savePlayerDataAsync(targetUuid, factionId, newRankId);

        String rankName = newRank.getDisplayName() != null ? newRank.getDisplayName() : newRank.getDefaultName();
        target.sendMessage(Utils.color("&cYou have been demoted to " + rankName + "&c by " + demoter.getName() + "."));
        demoter.sendMessage(Utils.color("&aYou have demoted " + target.getName() + " to " + rankName + "."));
        broadcastToFaction(factionId, Utils.color("&e" + target.getName() + " was demoted to " + rankName + " by " + demoter.getName() + "."));

        updatePlayerDisplay(target);
        // TODO: Обновить права LuckPerms?
        return true;
    }

    public boolean setPlayerRank(Player setter, Player target, int rankId) {
        UUID targetUuid = target.getUniqueId();
        String factionId = getPlayerFactionId(target);

        if (factionId == null) {
            setter.sendMessage(Utils.color("&c" + target.getName() + " is not in a faction."));
            return false;
        }
        if (!canManagePlayer(setter, target)) {
            setter.sendMessage(Utils.color("&cYou cannot set rank for members of this faction."));
            return false;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) return false;

        FactionRank newRank = faction.getRank(rankId);
        if (newRank == null) {
            setter.sendMessage(Utils.color("&cRank ID " + rankId + " does not exist in this faction."));
            return false;
        }

        // Проверка ранга установщика (в FactionCommand)

        // Проверка, чтобы не понизить себя (если не админ)
        if (setter.getUniqueId().equals(target.getUniqueId()) && !isAdminInMode(setter)) {
            Integer setterRankId = getPlayerRankId(setter);
            if (setterRankId != null && rankId < setterRankId) {
                setter.sendMessage(Utils.color("&cYou cannot set your own rank lower than your current rank."));
                return false;
            }
        }


        playerRanks.put(targetUuid, rankId);
        databaseManager.savePlayerDataAsync(targetUuid, factionId, rankId);

        String rankName = newRank.getDisplayName() != null ? newRank.getDisplayName() : newRank.getDefaultName();
        target.sendMessage(Utils.color("&eYour rank has been set to " + rankName + "&e by " + setter.getName() + "."));
        setter.sendMessage(Utils.color("&aYou have set " + target.getName() + "'s rank to " + rankName + "."));
        broadcastToFaction(factionId, Utils.color("&e" + target.getName() + "'s rank was set to " + rankName + " by " + setter.getName() + "."));

        updatePlayerDisplay(target);
        // TODO: Обновить права LuckPerms?
        return true;
    }

    /**
     * Проверяет, может ли управляющий игрок (manager) управлять целевым игроком (target).
     * Учитывает принадлежность к одной фракции и админский режим.
     */
    private boolean canManagePlayer(Player manager, Player target) {
        String targetFactionId = getPlayerFactionId(target);
        if (targetFactionId == null) return false; // Нельзя управлять тем, кто не во фракции

        String managerFactionId = getPlayerFactionId(manager);
        String adminModeFaction = adminsInFactionMode.get(manager.getUniqueId());

        return targetFactionId.equals(managerFactionId) || targetFactionId.equals(adminModeFaction);
    }


    // --- Геттеры и проверки ---

    public boolean isInFaction(Player player) {
        return playerFactions.containsKey(player.getUniqueId());
    }
    public boolean isInFaction(UUID uuid) {
        return playerFactions.containsKey(uuid);
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
    public Faction getPlayerFaction(UUID uuid) {
        String factionId = getPlayerFactionId(uuid);
        return factionId != null ? factionManager.getFaction(factionId) : null;
    }

    public Integer getPlayerRankId(Player player) {
        return playerRanks.get(player.getUniqueId());
    }
    public Integer getPlayerRankId(UUID uuid) {
        return playerRanks.get(uuid);
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
    public FactionRank getPlayerRank(UUID uuid) {
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
        String factionIdLower = factionId.toLowerCase();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (factionIdLower.equals(getPlayerFactionId(player))) {
                members.add(player);
            }
        }
        return members;
    }

    public List<UUID> getAllFactionMemberUUIDs(String factionId) {
        // TODO: Этот метод потребует запроса к БД, чтобы получить ВСЕХ членов, включая оффлайн.
        // Пока возвращает только онлайн.
        List<UUID> members = new ArrayList<>();
        if (factionId == null) return members;
        String factionIdLower = factionId.toLowerCase();
        for (UUID uuid : playerFactions.keySet()) { // Итерируем по кэшу онлайн игроков
            if (factionIdLower.equals(playerFactions.get(uuid))) {
                members.add(uuid);
            }
        }
        return members;
        // Для получения оффлайн нужен будет отдельный метод в DatabaseManager
        // List<UUID> allMembers = databaseManager.getFactionMemberUUIDs(factionId);
        // return allMembers;
    }


    // --- Фракционный чат ---

    public boolean isInFactionChat(Player player) {
        return playersInFactionChat.contains(player.getUniqueId());
    }

    public void toggleFactionChat(Player player) {
        UUID uuid = player.getUniqueId();
        if (!isInFaction(player)) {
            player.sendMessage(Utils.color("&cYou must be in a faction to use faction chat."));
            return;
        }
        if (playersInFactionChat.contains(uuid)) {
            playersInFactionChat.remove(uuid);
            player.sendMessage(Utils.color("&eFaction chat disabled."));
        } else {
            playersInFactionChat.add(uuid);
            player.sendMessage(Utils.color("&aFaction chat enabled."));
        }
    }

    public void broadcastToFaction(String factionId, String message) {
        if (factionId == null || message == null) return;
        List<Player> members = getOnlineFactionMembers(factionId);
        plugin.getLogger().fine("Broadcasting to faction " + factionId + " (" + members.size() + " members): " + message);
        message = Utils.color(message); // Применяем цвета один раз
        for (Player member : members) {
            member.sendMessage(message);
        }
    }

    // --- Приглашения ---

    public void addInvite(Player target, PendingInvite invite) {
        removeInvite(target); // Удаляем старое
        pendingInvites.put(target.getUniqueId(), invite);

        long expireTicks = configManager.getConfig().getLong("faction.invite_expire_seconds", 60) * 20L;
        if (expireTicks <= 0) expireTicks = 60 * 20L; // Минимум 60 секунд

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingInvite currentInvite = pendingInvites.get(target.getUniqueId());
            // Удаляем только если это то же самое приглашение
            if (currentInvite != null && currentInvite.equals(invite)) { // Используем equals, если он переопределен в PendingInvite
                pendingInvites.remove(target.getUniqueId());
                if (target.isOnline()) { // Сообщаем только если игрок онлайн
                    target.sendMessage(Utils.color("&cThe faction invite from " + invite.getInviterName() + " has expired."));
                    // Закрыть GUI, если открыто
                    if (target.getOpenInventory().getTitle().contains("Faction Invite")) {
                        target.closeInventory();
                    }
                }
            }
        }, expireTicks);
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

    public boolean enterAdminMode(Player admin, Faction faction) {
        UUID adminUuid = admin.getUniqueId();
        if (isAdminInMode(admin)) {
            admin.sendMessage(Utils.color("&cYou are already in admin mode for faction " + adminsInFactionMode.get(adminUuid) + "."));
            return false;
        }

        if (!admin.hasPermission("hfactions.admin.adminmode")) {
            admin.sendMessage(Utils.color("&cYou do not have permission to enter admin mode."));
            return false;
        }

        if (faction == null) {
            admin.sendMessage(Utils.color("&cInvalid faction specified."));
            return false;
        }

        // Выдаем права через LuckPerms
        boolean success = plugin.getLuckPermsIntegration().setAdminMode(admin, true);
        if (!success) {
            admin.sendMessage(Utils.color("&cFailed to apply admin permissions via LuckPerms. Aborting."));
            return false;
        }

        adminsInFactionMode.put(adminUuid, faction.getId());
        admin.sendMessage(Utils.color("&aYou have entered admin mode for faction: " + faction.getName()));
        admin.sendMessage(Utils.color("&eYou now have leader permissions for this faction's commands."));
        updatePlayerDisplay(admin); // Обновить отображение
        return true;
    }

    public boolean exitAdminMode(Player admin, boolean silent) {
        UUID adminUuid = admin.getUniqueId();
        if (!isAdminInMode(admin)) {
            if (!silent) admin.sendMessage(Utils.color("&cYou are not in admin mode."));
            return false;
        }

        String factionId = adminsInFactionMode.get(adminUuid);

        // Забираем права через LuckPerms
        boolean success = plugin.getLuckPermsIntegration().setAdminMode(admin, false);
        if (!success && !silent) {
            admin.sendMessage(Utils.color("&cWarning: Failed to remove admin permissions via LuckPerms."));
            // Продолжаем выход из режима в любом случае
        }

        adminsInFactionMode.remove(adminUuid);
        if (!silent) admin.sendMessage(Utils.color("&aYou have exited admin mode for faction: " + factionId));
        updatePlayerDisplay(admin); // Обновить отображение
        return true;
    }

    // --- Вспомогательные методы ---

    private void updatePlayerDisplay(Player player) {
        // ЗАГЛУШКА - Реализация зависит от вашего плагина чата/таба и PlaceholderAPI
        plugin.getLogger().fine("Placeholder: updatePlayerDisplay called for " + player.getName());
        // Пример: если вы используете PlaceholderAPI и создали свои плейсхолдеры:
        // if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
        //    PlaceholderAPI.setPlaceholders(player, "%" + plugin.getName().toLowerCase() + "_faction_prefix%");
        //    // и другие плейсхолдеры
        // }
    }


    /**
     * Очищает данные о фракции для всех игроков (онлайн и оффлайн), принадлежащих к указанной фракции.
     * Вызывается при удалении фракции. Использует СИНХРОННЫЕ операции с БД.
     * @param factionId ID удаляемой фракции.
     */
    public void clearFactionDataFor(String factionId) {
        if (factionId == null) return;
        String factionIdLower = factionId.toLowerCase();
        plugin.getLogger().info("Clearing player data for deleted faction: " + factionIdLower);

        // 1. Очистка кэша онлайн игроков
        List<UUID> onlinePlayersToClear = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : playerFactions.entrySet()) {
            if (factionIdLower.equalsIgnoreCase(entry.getValue())) {
                onlinePlayersToClear.add(entry.getKey());
            }
        }

        plugin.getLogger().info("Clearing cache for " + onlinePlayersToClear.size() + " online players of faction " + factionIdLower);
        for (UUID uuid : onlinePlayersToClear) {
            Player onlinePlayer = Bukkit.getPlayer(uuid); // Получаем игрока
            playerFactions.remove(uuid);
            playerRanks.remove(uuid);
            playersInFactionChat.remove(uuid);
            if (onlinePlayer != null) { // Проверяем, что игрок все еще онлайн
                onlinePlayer.sendMessage(ChatColor.RED + "The faction you were in has been disbanded!");
                updatePlayerDisplay(onlinePlayer); // Обновляем дисплей
            }
        }

        // 2. Очистка данных в БД (СИНХРОННО, так как вызывается из админ команды)
        databaseManager.clearFactionDataSync(factionIdLower);
    }

    public void saveDataForOnlinePlayersSynchronously() {
    }
}