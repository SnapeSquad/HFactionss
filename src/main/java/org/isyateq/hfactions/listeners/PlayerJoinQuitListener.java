package org.isyateq.hfactions.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.PlayerManager;

public class PlayerJoinQuitListener implements Listener {

    private final HFactions plugin;
    private final PlayerManager playerManager;

    public PlayerJoinQuitListener(HFactions plugin) {
        this.plugin = plugin;
        this.playerManager = plugin.getPlayerManager();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Загружаем данные игрока асинхронно из БД
        playerManager.loadPlayerData(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Сохраняем данные игрока асинхронно в БД
        playerManager.savePlayerData(player);
        // Очищаем кэшированные данные игрока из памяти
        playerManager.clearPlayerData(player);
        // Если игрок был в режиме наручников, освобождаем его (или сохраняем состояние?)
        plugin.getCuffManager().handlePlayerQuit(player); // Убедитесь, что этот метод есть
    }
}