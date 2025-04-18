package org.isyateq.hfactions.listeners;

import org.bukkit.OfflinePlayer; // Используем OfflinePlayer для quit
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.CuffManager; // Добавляем импорт
import org.isyateq.hfactions.managers.PlayerManager;

public class PlayerJoinQuitListener implements Listener {

    private final HFactions plugin;
    // Получаем менеджеры сразу для удобства
    private final PlayerManager playerManager;
    private final CuffManager cuffManager;

    public PlayerJoinQuitListener(HFactions plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin instance cannot be null!");
        }
        this.plugin = plugin;
        this.playerManager = plugin.getPlayerManager();
        this.cuffManager = plugin.getCuffManager(); // Получаем CuffManager

        if (this.playerManager == null) plugin.getLogger().severe("PlayerManager is null in PlayerJoinQuitListener!");
        if (this.cuffManager == null) plugin.getLogger().severe("CuffManager is null in PlayerJoinQuitListener!");

    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (playerManager != null) {
            // Загружаем данные игрока асинхронно из БД
            playerManager.loadPlayerData(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player offlinePlayer = event.getPlayer(); // Используем OfflinePlayer
        if (playerManager != null) {
            // Сохраняем данные игрока асинхронно в БД
            playerManager.savePlayerData(offlinePlayer); // Передаем OfflinePlayer
            // Очищаем кэшированные данные игрока из памяти
            playerManager.clearPlayerData(offlinePlayer); // Передаем OfflinePlayer
        }
        // Если игрок был в режиме наручников, обрабатываем выход
        if (cuffManager != null) {
            // Передаем Player, т.к. он еще доступен в этом событии
            cuffManager.handlePlayerQuit(event.getPlayer());
        }
    }
}