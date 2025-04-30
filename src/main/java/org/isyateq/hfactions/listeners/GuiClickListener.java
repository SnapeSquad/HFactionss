package org.isyateq.hfactions.listeners;

// Bukkit API
import org.bukkit.ChatColor; // Используем для сообщения об ошибке
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory; // Для проверки инвентаря

// Локальные классы
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.GuiManager; // Зависимость

// Утилиты Java
import java.util.Objects; // Для проверки на null
import java.util.logging.Level;

/**
 * Слушатель кликов в инвентарях.
 * Делегирует обработку кликов в GUI плагина HFactions менеджеру GuiManager.
 */
public final class GuiClickListener implements Listener { // Делаем класс final

    private final HFactions plugin;
    private final GuiManager guiManager;

    /**
     * Конструктор GuiClickListener.
     * @param plugin Экземпляр главного класса плагина.
     * @throws IllegalStateException если GuiManager не был инициализирован в главном классе.
     */
    public GuiClickListener(HFactions plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin instance cannot be null");
        // Получаем GuiManager, он должен быть уже инициализирован
        this.guiManager = plugin.getGuiManager();
        if (this.guiManager == null) {
            String errorMsg = "GuiManager is null! GuiClickListener cannot function. Check plugin initialization order.";
            plugin.getLogger().severe(errorMsg);
            // Бросаем исключение, чтобы остановить загрузку плагина, если GUI критичны
            throw new IllegalStateException(errorMsg);
        }
    }

    /**
     * Обрабатывает событие клика в любом инвентаре.
     * Если клик произошел в GUI, управляемом HFactions, передает событие в GuiManager.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false) // ignoreCancelled=false, GuiManager решает
    public void onInventoryClick(InventoryClickEvent event) {
        // Проверяем, что кликнул игрок и есть верхний инвентарь
        if (!(event.getWhoClicked() instanceof Player player)) return; // Используем pattern variable
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory == null) return;

        // --- Передаем обработку в GuiManager ---
        // Он проверит, относится ли topInventory к его ведению
        try {
            // Вызываем ПУБЛИЧНЫЙ метод GuiManager
            guiManager.handleInventoryClick(event);
        } catch (Exception e) {
            // Ловим любые ошибки из GuiManager для предотвращения спама в консоли
            plugin.getLogger().log(Level.SEVERE, "Error handling GUI click for player " + player.getName() + " in inventory: " + event.getView().getTitle(), e);
            // Сообщаем игроку о проблеме
            player.sendMessage(ChatColor.RED + "An internal error occurred while processing your action. Please report this.");
            event.setCancelled(true); // Отменяем действие, если произошла ошибка
        }
    }

} // Конец класса GuiClickListener