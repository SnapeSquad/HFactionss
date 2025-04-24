package org.isyateq.hfactions.integrations; // Убедись, что пакет правильный

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.items.ItemBuilder;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions; // Импорт главного класса
import java.util.logging.Level;

/**
 * Обертка для взаимодействия с Oraxen API.
 */
public class OraxenIntegration {

    private final HFactions plugin;
    private boolean enabled = false; // Флаг успешной инициализации

    public OraxenIntegration(HFactions plugin) {
        this.plugin = plugin;
        // Простая проверка доступности основного класса Oraxen API
        // Делаем это в конструкторе, чтобы сразу знать статус
        try {
            Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            this.enabled = true;
            // Не логируем здесь, т.к. лог будет в setupOraxen() в главном классе
            // plugin.getLogger().info("Oraxen API found. Integration wrapper ready.");
        } catch (ClassNotFoundException e) {
            // Логируем ошибку только если Oraxen ДОЛЖЕН быть включен по конфигу
            if (plugin.getConfigManager() != null && plugin.getConfigManager().getConfig().getBoolean("integrations.oraxen.enabled", false)) {
                plugin.getLogger().log(Level.WARNING, "Oraxen API class (io.th0rgal.oraxen.api.OraxenItems) not found, but Oraxen support is enabled in config. Disabling integration.", e);
            } else {
                // Просто тихо отключаем, если в конфиге и так выключено
            }
            this.enabled = false;
        } catch (Exception e) {
            // Ловим другие возможные ошибки при инициализации Oraxen (если его API может их бросать)
            plugin.getLogger().log(Level.SEVERE, "An unexpected error occurred while checking for Oraxen API.", e);
            this.enabled = false;
        }
    }

    /**
     * Проверяет, включена ли интеграция (т.е. найден ли плагин и API).
     * @return true, если интеграция активна.
     */
    public boolean isEnabled() {
        // Дополнительно проверяем конфиг на случай, если его изменили через /hf reload
        boolean configEnabled = plugin.getConfigManager() != null && plugin.getConfigManager().getConfig().getBoolean("integrations.oraxen.enabled", false);
        return this.enabled && configEnabled;
    }

    /**
     * Проверяет, является ли предмет предметом Oraxen.
     * Требует, чтобы интеграция была включена (isEnabled() == true).
     * @param item Предмет для проверки.
     * @return true, если это предмет Oraxen, иначе false.
     */
    public boolean isOraxenItem(ItemStack item) {
        if (!isEnabled() || item == null) { // Используем isEnabled()
            return false;
        }
        try {
            // Используем API Oraxen для проверки
            return OraxenItems.getIdByItem(item) != null;
        } catch (Exception | NoClassDefFoundError e) { // Ловим ошибки, если Oraxen API недоступно во время выполнения
            plugin.getLogger().log(Level.SEVERE, "Error accessing Oraxen API (isOraxenItem). Disabling Oraxen integration runtime.", e);
            this.enabled = false; // Отключаем интеграцию при ошибке
            return false;
        }
    }

    /**
     * Получает ID предмета Oraxen.
     * Требует, чтобы интеграция была включена (isEnabled() == true).
     * @param item Предмет Oraxen.
     * @return Строковый ID предмета или null, если это не предмет Oraxen или произошла ошибка.
     */
    public String getOraxenItemId(ItemStack item) {
        if (!isEnabled() || item == null) { // Используем isEnabled()
            return null;
        }
        try {
            return OraxenItems.getIdByItem(item);
        } catch (Exception | NoClassDefFoundError e) {
            plugin.getLogger().log(Level.SEVERE, "Error accessing Oraxen API (getOraxenItemId). Disabling Oraxen integration runtime.", e);
            this.enabled = false;
            return null;
        }
    }

    /**
     * Создает ItemStack предмета Oraxen по его ID.
     * Требует, чтобы интеграция была включена (isEnabled() == true).
     * @param itemId ID предмета Oraxen.
     * @return ItemStack или null, если предмет не найден или произошла ошибка.
     */
    public ItemStack getOraxenItemById(String itemId) {
        if (!isEnabled() || itemId == null) { // Используем isEnabled()
            return null;
        }
        try {
            ItemBuilder itemBuilder = OraxenItems.getItemById(itemId);
            return itemBuilder != null ? itemBuilder.build() : null;
        } catch (Exception | NoClassDefFoundError e) {
            plugin.getLogger().log(Level.SEVERE, "Error accessing Oraxen API (getOraxenItemById for '" + itemId + "'). Disabling Oraxen integration runtime.", e);
            this.enabled = false;
            return null;
        }
    }

    public ItemStack getItemStack(String resultItemId) { }
}