package org.isyateq.hfactions.listeners;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.Recipe;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.CraftingManager;

import java.util.logging.Level;

public class CraftingListener implements Listener {

    private final HFactions plugin;
    private final CraftingManager craftingManager;

    public CraftingListener(HFactions plugin) {
        this.plugin = plugin;
        this.craftingManager = plugin.getCraftingManager();

        // Проверка инициализации CraftingManager
        if (this.craftingManager == null) {
            plugin.getLogger().severe("CraftingManager is null! CraftingListener will not function.");
        }
    }

    @EventHandler
    public void onPrepareCrafting(PrepareItemCraftEvent event) {
        // Если менеджер не был инициализирован, ничего не делаем
        if (craftingManager == null) {
            return;
        }

        Recipe recipe = event.getRecipe();
        HumanEntity human = event.getView().getPlayer();

        // Проверяем, что рецепт есть и крафтит игрок
        if (recipe == null || !(human instanceof Player player)) {
            return;
        }

        // Вызываем метод проверки из CraftingManager
        try {
            if (!craftingManager.canCraft(player, recipe)) {
                // Если не может крафтить, убираем результат
                event.getInventory().setResult(null);

                // Опционально: отправить сообщение игроку один раз
                // Нужно добавить кулдаун, чтобы не спамить
                // sendCraftDenyMessage(player);
            }
            // Если canCraft вернул true, результат остается, крафт будет возможен
        } catch (Exception e) {
            // Ловим непредвиденные ошибки при проверке крафта
            plugin.getLogger().log(Level.SEVERE, "Error checking craft permission for player " + player.getName(), e);
            event.getInventory().setResult(null); // На всякий случай запрещаем крафт при ошибке
        }
    }
}