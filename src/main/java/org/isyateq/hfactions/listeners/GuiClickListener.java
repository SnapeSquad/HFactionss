package org.isyateq.hfactions.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.GuiManager;

public class GuiClickListener implements Listener {

    private final GuiManager guiManager;

    public GuiClickListener(HFactions plugin) {
        if (plugin.getGuiManager() == null) {
            plugin.getLogger().severe("GuiManager is null! GuiClickListener cannot function.");
            this.guiManager = null;
        } else {
            this.guiManager = plugin.getGuiManager();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (guiManager != null) {
            // Вызываем главный обработчик кликов в GuiManager
            guiManager.handleInventoryClick(event);
        }
        // Не отменяем событие здесь, логика отмены внутри handleInventoryClick
    }
}