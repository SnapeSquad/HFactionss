package org.isyateq.hfactions.tasks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.CuffManager; // Нужен для проверки состояния
import org.isyateq.hfactions.util.Utils; // Для сообщений

import java.util.UUID;

/**
 * Задача, отвечающая за "поводок" для игрока в наручниках.
 * Периодически проверяет дистанцию и телепортирует связанного игрока к ведущему, если нужно.
 */
public class CuffLeashTask extends BukkitRunnable {

    private final HFactions plugin;
    private final CuffManager cuffManager;
    private final ConfigManager configManager;
    private final UUID cuffedPlayerUUID;
    private final UUID leadingPlayerUUID;
    private final double maxDistanceSquared; // Храним квадрат дистанции для производительности
    private final boolean teleportEnabled;
    private final String teleportMessage;

    public CuffLeashTask(HFactions plugin, UUID cuffedPlayerUUID, UUID leadingPlayerUUID) {
        this.plugin = plugin;
        this.cuffManager = plugin.getCuffManager();
        this.configManager = plugin.getConfigManager();
        this.cuffedPlayerUUID = cuffedPlayerUUID;
        this.leadingPlayerUUID = leadingPlayerUUID;

        // Загружаем настройки из конфига
        this.teleportEnabled = configManager.getConfig().getBoolean("mechanics.handcuffs.leash.teleport_enabled", true);
        double distance = configManager.getConfig().getDouble("mechanics.handcuffs.leash.max_distance", 10.0);
        this.maxDistanceSquared = distance * distance; // Считаем квадрат один раз
        this.teleportMessage = Utils.color(configManager.getConfig().getString("mechanics.handcuffs.leash.teleport_message", "&cYou were pulled back by the handcuffs."));
    }

    @Override
    public void run() {
        // Получаем игроков по UUID
        Player cuffedPlayer = Bukkit.getPlayer(cuffedPlayerUUID);
        Player leadingPlayer = Bukkit.getPlayer(leadingPlayerUUID);

        // --- Проверки на выход из задачи ---
        // 1. Один из игроков вышел из сети
        if (cuffedPlayer == null || !cuffedPlayer.isOnline() || leadingPlayer == null || !leadingPlayer.isOnline()) {
            plugin.getLogger().fine("Leash task cancelled: Player offline (Cuffed: " + cuffedPlayerUUID + ", Leader: " + leadingPlayerUUID + ")");
            cuffManager.stopLeashTask(cuffedPlayerUUID); // Сообщаем менеджеру об остановке
            this.cancel(); // Отменяем саму задачу BukkitRunnable
            return;
        }

        // 2. Игрок больше не в наручниках (проверка через CuffManager)
        if (!cuffManager.isCuffed(cuffedPlayer) || !leadingPlayerUUID.equals(cuffManager.getWhoCuffed(cuffedPlayer))) {
            plugin.getLogger().fine("Leash task cancelled: Player no longer cuffed or leader changed (Cuffed: " + cuffedPlayerUUID + ")");
            // Не нужно вызывать stopLeashTask здесь, т.к. он уже был вызван при снятии наручников
            this.cancel();
            return;
        }

        // 3. Игроки в разных мирах (телепортация между мирами обычно не нужна)
        if (!cuffedPlayer.getWorld().equals(leadingPlayer.getWorld())) {
            // Просто пропускаем проверку дистанции в этом тике
            return;
        }

        // 4. Механика телепортации отключена в конфиге
        if (!teleportEnabled) {
            // Задача продолжает работать для проверок выше, но не телепортирует
            return;
        }

        // --- Основная логика телепортации ---
        Location cuffedLoc = cuffedPlayer.getLocation();
        Location leaderLoc = leadingPlayer.getLocation();

        // Используем distanceSquared для производительности (избегаем вычисления корня)
        if (cuffedLoc.distanceSquared(leaderLoc) > maxDistanceSquared) {
            // Телепортируем связанного игрока к ведущему
            // Немного смещаем позицию, чтобы не застрять в блоках
            Location tpLoc = leaderLoc.clone().add(leaderLoc.getDirection().normalize().multiply(0.5)); // Чуть впереди лидера
            // Или просто ставим рядом: leaderLoc.clone().add(1, 0, 0);
            // Или просто в ту же точку: leaderLoc;

            // Убедимся, что место безопасно (опционально, но рекомендуется)
            if (!isSafeLocation(tpLoc)) {
                // Если позиция лидера небезопасна, ищем ближайшую безопасную или телепортируем на его место
                tpLoc = findSafeLocationNear(leaderLoc);
                if (tpLoc == null) tpLoc = leaderLoc; // В крайнем случае телепортируем как есть
            }

            cuffedPlayer.teleport(tpLoc);

            // Отправляем сообщение связанному игроку
            if (teleportMessage != null && !teleportMessage.isEmpty()) {
                cuffedPlayer.sendMessage(teleportMessage);
            }
            plugin.getLogger().fine("Leashed player " + cuffedPlayer.getName() + " back to " + leadingPlayer.getName());
        }
    }

    // --- Вспомогательные методы для безопасной телепортации (простые примеры) ---

    private boolean isSafeLocation(Location location) {
        if (location == null) return false;
        // Проверяем блок под ногами и два блока над головой
        Location ground = location.clone().subtract(0, 1, 0);
        Location head = location.clone().add(0, 1, 0);
        Location feet = location.clone();

        // Должен стоять на твердом блоке (не воздух, не жидкость и т.д.)
        // Голова и ноги должны быть в безопасных блоках (воздух, трава и т.д.)
        return ground.getBlock().getType().isSolid() && !ground.getBlock().isLiquid()
                && isPassable(feet.getBlock().getType())
                && isPassable(head.getBlock().getType());
    }

    private boolean isPassable(org.bukkit.Material material) {
        // Простой список безопасных блоков для нахождения в них
        return !material.isSolid() || material.name().contains("SIGN") || material.name().contains("PLATE") || material.name().contains("BANNER"); // Добавьте другие по необходимости
    }


    private Location findSafeLocationNear(Location center) {
        // Простая проверка вверх на 1-2 блока
        for (int yOffset = 1; yOffset <= 2; yOffset++) {
            Location check = center.clone().add(0, yOffset, 0);
            if (isSafeLocation(check)) {
                return check;
            }
        }
        // Можно добавить проверку по горизонтали, если нужно
        return null; // Не нашли безопасного места рядом
    }
}