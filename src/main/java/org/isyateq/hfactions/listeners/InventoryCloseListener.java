package org.isyateq.hfactions.listeners;

// Bukkit API
import org.bukkit.entity.Player; // Для проверки типа сущности
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory; // Для получения инвентаря

// Локальные классы
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.GuiManager; // Зависимость

// Утилиты Java
import java.util.Objects; // Для проверки на null
import java.util.logging.Level;

/**
 * Слушатель закрытия инвентарей.
 * Делегирует обработку закрытия GUI плагина HFactions (например, сохранение склада) менеджеру GuiManager.
 */
public final class InventoryCloseListener implements Listener { // Делаем класс final

    private final HFactions plugin;
    private final GuiManager guiManager;

    /**
     * Конструктор InventoryCloseListener.
     * @param plugin Экземпляр главного класса плагина.
     * @throws IllegalStateException если GuiManager не был инициализирован.
     */
    public InventoryCloseListener(HFactions plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin instance cannot be null");
        // Получаем GuiManager
        this.guiManager = plugin.getGuiManager();
        if (this.guiManager == null) {
            String errorMsg = "GuiManager is null! InventoryCloseListener cannot function. Check plugin initialization order.";
            plugin.getLogger().severe(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
    }

    /**
     * Обрабатывает событие закрытия инвентаря.
     * Если это был один из GUI HFactions, передает событие в GuiManager.
     */
    @EventHandler(priority = EventPriority.NORMAL) // Не нужен высокий приоритет
    public void onInventoryClose(InventoryCloseEvent event) {
        // Получаем инвентарь и игрока
        Inventory inventory = event.getInventory();
        // Проверяем, что игрок существует и это именно игрок
        if (inventory == null || !(event.getPlayer() instanceof Player player)) { // Используем pattern variable
            return; // Не инвентарь или не игрок
        }

        // --- Передаем обработку в GuiManager ---
        // Он проверит, отслеживался ли этот инвентарь, и выполнит действия (например, сохранение склада)
        try {
            // Вызываем ПУБЛИЧНЫЙ метод GuiManager
            guiManager.handleInventoryClose(event);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error handling GUI close for player " + player.getName() + " in inventory: " + event.getView().getTitle(), e);
            // Сообщение игроку здесь обычно не требуется
        }
    }

} // Конец класса InventoryCloseListener