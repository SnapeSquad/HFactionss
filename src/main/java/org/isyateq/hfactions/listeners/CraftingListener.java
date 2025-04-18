package org.isyateq.hfactions.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority; // Указываем приоритет
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.CraftingManager;
import org.isyateq.hfactions.managers.PlayerManager;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.util.Utils;

import java.util.List;
import java.util.logging.Level;

public class CraftingListener implements Listener {

    private final HFactions plugin;
    private final CraftingManager craftingManager;
    private final PlayerManager playerManager;
    private final ConfigManager configManager;

    public CraftingListener(HFactions plugin) {
        this.plugin = plugin;
        // Получаем менеджеры
        this.craftingManager = plugin.getCraftingManager();
        this.playerManager = plugin.getPlayerManager();
        this.configManager = plugin.getConfigManager();

        // Проверки на null
        if (this.craftingManager == null) plugin.getLogger().severe("CraftingManager is null in CraftingListener! Crafting checks disabled.");
        if (this.playerManager == null) plugin.getLogger().severe("PlayerManager is null in CraftingListener! Faction checks disabled.");
        if (this.configManager == null) plugin.getLogger().severe("ConfigManager is null in CraftingListener! Messages might not work.");
    }

    @EventHandler(priority = EventPriority.LOW) // Обрабатываем до других плагинов, чтобы точно отменить если нужно
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        // Проверяем доступность менеджеров
        if (craftingManager == null || playerManager == null || configManager == null) {
            return; // Не можем выполнить проверки
        }

        // Получаем игрока и результат крафта
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        CraftingInventory craftingInventory = event.getInventory();
        ItemStack result = craftingInventory.getResult();

        // Если результата нет или это не предмет, то выходим
        if (result == null || result.getType().isAir()) {
            return;
        }

        // Ищем информацию о нашем кастомном рецепте по результату
        CraftingManager.CustomRecipeInfo recipeInfo = craftingManager.getCustomRecipeInfo(result);

        // Если это не наш кастомный рецепт, ничего не делаем
        if (recipeInfo == null) {
            return;
        }

        // --- Проверки для нашего рецепта ---
        boolean allowed = true;
        String denialReasonKey = null; // Ключ для сообщения об отказе

        // 1. Проверка права (если требуется)
        if (recipeInfo.permissionRequired()) {
            if (!player.hasPermission(recipeInfo.requiredPermission())) {
                allowed = false;
                denialReasonKey = "crafting.no_permission";
            }
        }

        // 2. Проверка фракции (если право не дало доступ ИЛИ если фракции указаны)
        // Позволяем крафтить, если игрок ИМЕЕТ нужное право ИЛИ состоит в РАЗРЕШЕННОЙ фракции.
        // Если право требуется, а его нет, то фракция уже не важна.
        // Если право не требуется ИЛИ оно есть, но заданы фракции - проверяем фракцию.
        if (allowed && !recipeInfo.allowedFactions().isEmpty()) {
            String playerFactionId = playerManager.getPlayerFactionId(player);
            boolean factionMatch = false;

            // Проверяем универсальный доступ
            if (recipeInfo.allowedFactions().contains("ALL")) {
                factionMatch = true;
            }
            // Проверяем конкретную фракцию игрока
            else if (playerFactionId != null && recipeInfo.allowedFactions().contains(playerFactionId)) {
                factionMatch = true;
            }
            // Проверяем тип фракции игрока
            else if (playerFactionId != null) {
                Faction playerFaction = plugin.getFactionManager() != null ? plugin.getFactionManager().getFaction(playerFactionId) : null;
                if (playerFaction != null) {
                    String playerType = playerFaction.getType().name();
                    if (recipeInfo.allowedFactions().contains(playerType)) {
                        factionMatch = true;
                    }
                }
            }

            // Если ни одно условие по фракции не выполнено
            if (!factionMatch) {
                allowed = false;
                denialReasonKey = "crafting.wrong_faction";
            }
        }

        // Если крафт запрещен, убираем результат и сообщаем причину
        if (!allowed) {
            craftingInventory.setResult(null); // Убираем результат
            if (denialReasonKey != null) {
                final String message = configManager.getMessage(denialReasonKey, "&cYou cannot craft this item."); // Получаем сообщение
                // Отправляем сообщение с задержкой в 1 тик
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(message);
                        // Можно добавить звук ошибки
                        // player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    }
                }, 1L);
            }
        }
        // Если разрешено, результат остается видимым
    }
}