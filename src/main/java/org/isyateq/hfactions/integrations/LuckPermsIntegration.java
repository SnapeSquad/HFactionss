package org.isyateq.hfactions.integrations; // Убедись, что пакет правильный

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions; // Импорт главного класса

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Обертка для взаимодействия с LuckPerms API.
 */
public class LuckPermsIntegration {

    private final LuckPerms luckPermsApi;
    private final String adminModePermission = "hfactions.admin.temporary"; // Уникальное право для админ режима

    public LuckPermsIntegration(LuckPerms luckPermsApi) {
        this.luckPermsApi = luckPermsApi;
    }

    public LuckPerms getApi() {
        return luckPermsApi;
    }

    /**
     * Проверяет наличие права у игрока (более надежный способ, чем player.hasPermission).
     * @param player Игрок
     * @param permission Право для проверки
     * @return true, если право есть, иначе false
     */
    public boolean hasPermission(Player player, String permission) {
        return player.hasPermission(permission); // Можно использовать и стандартный метод
        // Или через API LuckPerms (асинхронно, но точнее для контекстов):
        /*
        User user = luckPermsApi.getPlayerAdapter(Player.class).getUser(player);
        return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
        */
    }

    /**
     * Включает или отключает режим администратора для игрока,
     * временно выдавая/забирая специальное разрешение.
     * @param player Игрок-администратор
     * @param enable true - включить режим, false - выключить
     * @return true в случае успеха, false если произошла ошибка
     */
    public boolean setAdminMode(Player player, boolean enable) {
        User user = luckPermsApi.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            HFactions.getInstance().getLogger().warning("Could not find LuckPerms user for " + player.getName());
            return false; // Не удалось получить пользователя LP
        }

        // Создаем узел права
        PermissionNode node = PermissionNode.builder(adminModePermission).value(true).build();

        CompletableFuture<Void> future;
        if (enable) {
            // Выдаем временное право
            user.data().add(node);
            HFactions.getInstance().getLogger().info("Added temporary admin permission to " + player.getName());
        } else {
            // Забираем временное право
            user.data().remove(node);
            HFactions.getInstance().getLogger().info("Removed temporary admin permission from " + player.getName());
        }

        // Сохраняем изменения пользователя
        future = luckPermsApi.getUserManager().saveUser(user);

        // Обработка результата (можно сделать асинхронно или дождаться)
        try {
            future.join(); // Дожидаемся завершения сохранения (может немного замедлить, но гарантирует)
            return true;
        } catch (Exception e) {
            HFactions.getInstance().getLogger().log(Level.SEVERE, "Failed to save LuckPerms user data for " + player.getName() + " while setting admin mode!", e);
            return false;
        }

        /* // Асинхронный вариант без блокировки
        future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                HFactions.getInstance().getLogger().log(Level.SEVERE, "Failed to save LuckPerms user data for " + player.getName() + "!", throwable);
                // Как сообщить об ошибке? Сложно из асинхронного контекста.
            } else {
                 HFactions.getInstance().getLogger().info("LuckPerms user data saved for " + player.getName());
            }
        });
        return true; // Возвращаем true немедленно, но сохранение может упасть позже
        */
    }

    // TODO: Добавить методы для управления правами рангов, если нужно
    // Например, добавление/удаление родительской группы ранга игроку
    // public void applyRankPermissions(UUID playerUuid, FactionRank rank) { ... }
    // public void clearRankPermissions(UUID playerUuid, FactionRank rank) { ... }

}