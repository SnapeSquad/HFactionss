package org.isyateq.hfactions.listeners;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action; // Для PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent; // Для PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

// Импорты для Conversation API
import org.bukkit.conversations.*;

// HFactions Imports
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.conversations.FineAmountPrompt; // Импорт Prompt
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.ItemManager;
import org.isyateq.hfactions.managers.PlayerManager;
import org.isyateq.hfactions.managers.ConversationManager; // Нужен ConversationManager
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.util.Utils;

import java.util.logging.Level;

public class ProtocolListener implements Listener {

    private final HFactions plugin;
    private final ItemManager itemManager;
    private final PlayerManager playerManager;
    private final ConfigManager configManager;
    private final ConversationManager conversationManager; // Используем ConversationManager

    public ProtocolListener(HFactions plugin) {
        this.plugin = plugin;
        // Получаем менеджеры
        this.itemManager = plugin.getItemManager();
        this.playerManager = plugin.getPlayerManager();
        this.configManager = plugin.getConfigManager();
        this.conversationManager = plugin.getConversationManager(); // Получаем ConversationManager

        // Проверки
        if (this.itemManager == null) plugin.getLogger().severe("ItemManager is null in ProtocolListener!");
        if (this.playerManager == null) plugin.getLogger().severe("PlayerManager is null in ProtocolListener!");
        if (this.configManager == null) plugin.getLogger().severe("ConfigManager is null in ProtocolListener!");
        if (this.conversationManager == null) plugin.getLogger().severe("ConversationManager is null in ProtocolListener! Fining via protocol item will not work.");
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Проверяем доступность менеджеров
        if (itemManager == null || playerManager == null || configManager == null || conversationManager == null) {
            return;
        }

        // Проверяем, что кликнули правой кнопкой по другому игроку
        if (!(event.getRightClicked() instanceof Player target)) return;
        // Проверяем, что клик был основной рукой
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player officer = event.getPlayer();
        ItemStack itemInHand = officer.getInventory().getItemInMainHand();

        // Проверяем, что в руке протокол
        if (itemManager.isProtocol(itemInHand)) {
            event.setCancelled(true); // Отменяем стандартное взаимодействие сущностей

            // Проверяем, включено ли использование протокола в конфиге
            FileConfiguration config = configManager.getConfig();
            if (config == null || !config.getBoolean("mechanics.fining.use_protocol_item", true)) {
                officer.sendMessage(configManager.getMessage("fine.protocol_disabled", "&cUsing the protocol item for fining is disabled."));
                return;
            }

            // Проверяем права и ранг офицера на выписку штрафа
            boolean canFine = officer.hasPermission("hfactions.pd.fine") || officer.hasPermission("hfactions.admin.*");
            int minRank = config.getInt("mechanics.fining.min_rank", 1);
            Integer officerRankId = null;

            if (!canFine) { // Проверяем ранг только если нет основного права
                FactionRank rank = playerManager.getPlayerRank(officer);
                if (rank != null && rank.getPermissions().contains("hfactions.pd.fine")) {
                    officerRankId = rank.getInternalId();
                    if(officerRankId >= minRank) { // Проверяем минимальный ранг
                        canFine = true;
                    }
                }
            } else { // Если есть основной перм, получаем ранг для возможной доп. проверки
                officerRankId = playerManager.getPlayerRankId(officer);
                if (officerRankId != null && officerRankId < minRank && !officer.hasPermission("hfactions.admin.*")){ // Админ игнорирует мин. ранг
                    canFine = false; // Снимаем право, если ранг ниже минимального
                }
            }


            if (!canFine) {
                officer.sendMessage(configManager.getMessage("fine.no_permission_issue", "&cYou do not have permission or required rank to issue fines."));
                return;
            }

            // Проверяем, не штрафует ли себя
            if (officer.getUniqueId().equals(target.getUniqueId())) {
                officer.sendMessage(configManager.getMessage("fine.cant_fine_self", "&cYou cannot fine yourself."));
                return;
            }

            // Проверяем, не находится ли офицер уже в диалоге
            if (officer.isConversing()) {
                officer.sendMessage(Utils.color(configManager.getMessage("conversation.already_active", "&cYou are already in a conversation. Type '/cancel' first.")));
                return;
            }

            // Начинаем диалог через ConversationManager
            conversationManager.startFineConversation(officer, target); // Нужен такой метод в ConversationManager
            officer.sendMessage(configManager.getMessage("fine.conversation_started", "&eStarted fine process for {target_name}...")
                    .replace("{target_name}", target.getName()));
        }
    }

    // Отмена ПКМ по воздуху/блоку с протоколом в руке
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Проверяем только ПКМ
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Проверяем руку
        if (event.getHand() != EquipmentSlot.HAND) return;
        // Проверяем предмет
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (itemManager != null && itemManager.isProtocol(item)) {
            // Отменяем стандартное действие (например, открытие сундука)
            event.setCancelled(true);
        }
    }

} // Конец класса ProtocolListener