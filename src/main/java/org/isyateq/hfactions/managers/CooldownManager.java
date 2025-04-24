package org.isyateq.hfactions.managers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {

    // Мапа для хранения времени окончания кулдауна
    // Ключ: Уникальный идентификатор кулдауна (например, "taser_use_" + playerUUID, "fine_target_" + targetUUID)
    // Значение: Время (в миллисекундах System.currentTimeMillis()), когда кулдаун закончится
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public CooldownManager() {
        // Конструктор пока пуст
    }

    /**
     * Устанавливает кулдаун для указанного идентификатора.
     * @param key Уникальный идентификатор кулдауна (например, "taser_use_PLAYERUUID").
     * @param durationMillis Длительность кулдауна в миллисекундах.
     */
    public void setCooldown(String key, long durationMillis) {
        if (key == null || key.isEmpty() || durationMillis <= 0) {
            return; // Не устанавливаем кулдаун для невалидных данных
        }
        long expireTime = System.currentTimeMillis() + durationMillis;
        cooldowns.put(key, expireTime);
    }

    /**
     * Проверяет, находится ли указанный идентификатор на кулдауне.
     * @param key Уникальный идентификатор кулдауна.
     * @return true, если кулдаун активен, иначе false.
     */
    public boolean isOnCooldown(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        Long expireTime = cooldowns.get(key);
        if (expireTime == null) {
            return false; // Кулдауна нет
        }
        // Проверяем, прошло ли время окончания
        if (System.currentTimeMillis() >= expireTime) {
            // Кулдаун истек, удаляем его из мапы для очистки
            cooldowns.remove(key);
            return false;
        }
        // Кулдаун еще активен
        return true;
    }

    /**
     * Возвращает оставшееся время кулдауна в миллисекундах.
     * @param key Уникальный идентификатор кулдауна.
     * @return Оставшееся время в миллисекундах или 0, если кулдаун не активен.
     */
    public long getRemainingCooldown(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        Long expireTime = cooldowns.get(key);
        if (expireTime == null) {
            return 0; // Кулдауна нет
        }
        long currentTime = System.currentTimeMillis();
        if (currentTime >= expireTime) {
            // Кулдаун истек, удаляем его
            cooldowns.remove(key);
            return 0;
        }
        // Возвращаем разницу
        return expireTime - currentTime;
    }

    /**
     * Удаляет кулдаун для указанного идентификатора.
     * @param key Уникальный идентификатор кулдауна.
     */
    public void removeCooldown(String key) {
        if (key != null) {
            cooldowns.remove(key);
        }
    }

    /**
     * Очищает все кулдауны (например, при перезагрузке).
     * Не рекомендуется делать это часто, так как сбросит все активные кулдауны.
     */
    public void clearAllCooldowns() {
        cooldowns.clear();
    }

    // --- Удобные методы для генерации ключей (примеры) ---

    /**
     * Генерирует ключ кулдауна для использования предмета игроком.
     * @param itemType Тип предмета (например, "taser", "handcuffs").
     * @param playerUUID UUID игрока.
     * @return Ключ кулдауна.
     */
    public static String playerItemUseKey(String itemType, UUID playerUUID) {
        return itemType + "_use_" + playerUUID.toString();
    }

    /**
     * Генерирует ключ кулдауна для действия над целью.
     * @param actionType Тип действия (например, "fine", "cuff").
     * @param targetUUID UUID цели.
     * @return Ключ кулдауна.
     */
    public static String targetActionKey(String actionType, UUID targetUUID) {
        return actionType + "_target_" + targetUUID.toString();
    }

}