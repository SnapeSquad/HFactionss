package org.isyateq.hfactions.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.GuiManager;

public class InventoryCloseListener implements Listener {

    private final HFactions plugin;
    private final GuiManager guiManager;

    public InventoryCloseListener(HFactions plugin) {
        this.plugin = plugin;
        // GuiManager должен быть инициализирован до этого листенера
        this.guiManager = plugin.getGuiManager();
        if (this.guiManager == null) {
            plugin.logError("FATAL: GuiManager is null in InventoryCloseListener! Warehouse GUI might not save.");
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (guiManager == null) return; // Не работаем без менеджера
        if (!(event.getPlayer() instanceof Player)) return;
        // Player player = (Player) event.getPlayer(); // Пока не используется
        Inventory closedInventory = event.getInventory();
        InventoryHolder holder = closedInventory.getHolder();

        // Сохраняем склад фракции, если это был он
        if (holder instanceof GuiManager.WarehouseGuiHolder) {
            GuiManager.WarehouseGuiHolder whHolder = (GuiManager.WarehouseGuiHolder) holder;

            // Клонируем содержимое инвентаря СИНХРОННО
            // Важно клонировать, т.к. event.getInventory().getContents() может вернуть ссылку,
            // которая станет невалидной после закрытия события.
            final ItemStack[] itemsToSave = new ItemStack[closedInventory.getSize()];
            for(int i=0; i < closedInventory.getSize(); i++){
                ItemStack item = closedInventory.getItem(i);
                if(item != null){
                    itemsToSave[i] = item.clone();
                }
            }

            // Запускаем сохранение АСИНХРОННО
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                guiManager.saveWarehouseFromGui(itemsToSave, whHolder);
            });
        }
        // Здесь можно добавить обработку закрытия других кастомных инвентарей, если нужно
    }
}