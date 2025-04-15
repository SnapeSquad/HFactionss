package org.isyateq.hfactions.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.CraftingManager;

public class CraftingListener implements Listener {

    private final CraftingManager craftingManager;

    public CraftingListener(HFactions plugin) {
        this.craftingManager = plugin.getCraftingManager();
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        ItemStack result = inventory.getResult();

        if (result == null || result.getType() == Material.AIR || event.getRecipe() == null) {
            return;
        }

        if (!(event.getView().getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getView().getPlayer();

        // Проверяем, наш ли это кастомный рецепт и может ли игрок его скрафтить
        if (!craftingManager.canCraft(player, event.getRecipe())) {
            // Если не может, блокируем крафт, устанавливая результат в null
            inventory.setResult(null);
            // Сообщение об ошибке отправляется внутри canCraft
        }
        // Если может, ничего не делаем, позволяя стандартному механизму работать
    }
}