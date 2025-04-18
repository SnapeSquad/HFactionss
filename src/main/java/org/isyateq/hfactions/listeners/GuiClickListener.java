package org.isyateq.hfactions.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.milkbowl.vault.economy.Economy; // Импорт Economy
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.*; // Импортируем все менеджеры
import org.isyateq.hfactions.util.Utils;

public class GuiClickListener implements Listener {

    private final HFactions plugin;
    private final PlayerManager playerManager;
    private final GuiManager guiManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager;
    private final Economy economy; // Добавили экономику

    public GuiClickListener(HFactions plugin) {
        this.plugin = plugin;
        this.playerManager = plugin.getPlayerManager();
        this.guiManager = plugin.getGuiManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        this.economy = HFactions.getEconomy(); // Получаем экономику
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory();
        Inventory topInventory = event.getView().getTopInventory();
        guiManager.handleWarehouseClick(event, topInventory);
        if (topInventory == null) return; // Добавлена проверка на null
        InventoryHolder holder = topInventory.getHolder();
        if (holder == null) return; // Игнорируем стандартные инвентари без холдера

        // --- GUI Приглашения ---
        if (holder instanceof GuiManager.InviteGuiHolder) {
            event.setCancelled(true); ItemStack clickedItem = event.getCurrentItem(); if (clickedInventory != topInventory || clickedItem == null || clickedItem.getType() == Material.AIR) return; PendingInvite invite = ((GuiManager.InviteGuiHolder) holder).getInvite(); if (invite == null) { player.closeInventory(); return; } ItemMeta meta = clickedItem.getItemMeta(); if (meta == null) return; PersistentDataContainer data = meta.getPersistentDataContainer();
            if (data.has(GuiManager.INVITE_ACTION_KEY, PersistentDataType.STRING)) {
                String action = data.get(GuiManager.INVITE_ACTION_KEY, PersistentDataType.STRING); player.closeInventory();
                PendingInvite currentInvite = playerManager.getPendingInvite(player.getUniqueId()); if (currentInvite == null || currentInvite.getTimestamp() != invite.getTimestamp()) { Utils.msg(player, configManager.getLangMessage("invite-expired-gui", "&cПриглашение больше недействительно.")); return; }
                if (GuiManager.INVITE_ACTION_ACCEPT.equals(action)) { if (playerManager.acceptInvite(player.getUniqueId())) { Faction joinedFaction = playerManager.getPlayerFaction(player.getUniqueId()); if (joinedFaction != null) { Utils.msg(player, configManager.getLangMessage("invite-accept-success-target", Map.of("highlight", configManager.getHighlightColor(), "faction_name", joinedFaction.getName()))); Player inviterPlayer = Bukkit.getPlayer(invite.getInviterUuid()); if (inviterPlayer != null) { Utils.msg(inviterPlayer, configManager.getLangMessage("invite-accept-success-inviter", Map.of("player", player.getName()))); } } } else { Utils.msg(player, configManager.getLangMessage("invite-accept-fail")); } }
                else if (GuiManager.INVITE_ACTION_DECLINE.equals(action)) { playerManager.removeInvite(player.getUniqueId()); Utils.msg(player, configManager.getLangMessage("invite-decline-success-target", Map.of("highlight", configManager.getHighlightColor(), "faction_id", invite.getFactionId()))); Player inviterPlayer = Bukkit.getPlayer(invite.getInviterUuid()); if (inviterPlayer != null) { Utils.msg(inviterPlayer, configManager.getLangMessage("invite-decline-success-inviter", Map.of("player", player.getName()))); } }
            }
            // --- GUI Управления Рангами (Редактирование) ---
        } else if (holder instanceof GuiManager.RankEditGuiHolder) {
            event.setCancelled(true); ItemStack clickedItem = event.getCurrentItem(); if (clickedInventory != topInventory || clickedItem == null || clickedItem.getType() == Material.AIR) return; ItemMeta meta = clickedItem.getItemMeta(); if (meta == null || !meta.getPersistentDataContainer().has(GuiManager.RANK_EDIT_ACTION_KEY, PersistentDataType.INTEGER)) return;
            int rankId = meta.getPersistentDataContainer().getOrDefault(GuiManager.RANK_EDIT_ACTION_KEY, PersistentDataType.INTEGER, -1); String factionId = ((GuiManager.RankEditGuiHolder) holder).getFactionId(); if (rankId == -1 || factionId == null) return;
            ClickType clickType = event.getClick(); Faction faction = factionManager.getFaction(factionId); if (faction == null) { Utils.msg(player, configManager.getLangMessage("internal-error")); player.closeInventory(); return; }
            if (clickType == ClickType.LEFT) { guiManager.startRankNameEdit(player, factionId, rankId); }
            else if (clickType == ClickType.RIGHT) { guiManager.resetRankName(player, faction, rankId); guiManager.openRankManagementGui(player, faction); } // Переоткрываем для обновления
            // --- GUI Склада Фракции ---
        } else if (holder instanceof GuiManager.WarehouseGuiHolder) {
            WarehouseGuiHolder whHolder = (GuiManager.WarehouseGuiHolder) holder; ItemStack clickedItem = event.getCurrentItem(); ItemStack cursorItem = event.getCursor(); int clickedSlot = event.getSlot(); int rawSlot = event.getRawSlot(); int topInvSize = topInventory.getSize(); int itemsPerPage = topInvSize - 9;
            if (clickedInventory == topInventory && clickedSlot >= itemsPerPage) { // Клик по навигации
                event.setCancelled(true); if (clickedItem != null && clickedItem.hasItemMeta()) { ItemMeta meta = clickedItem.getItemMeta(); PersistentDataContainer data = meta.getPersistentDataContainer(); if (data.has(GuiManager.WAREHOUSE_ACTION_KEY, PersistentDataType.STRING)) { int targetPage = data.getOrDefault(GuiManager.WAREHOUSE_PAGE_KEY, PersistentDataType.INTEGER, 1); String currentSort = data.getOrDefault(GuiManager.WAREHOUSE_SORT_KEY, PersistentDataType.STRING, "rank"); Faction faction = factionManager.getFaction(whHolder.getFactionId()); if (faction != null) { guiManager.openWarehouseGui(player, faction, targetPage); } return; } } return;
            }
            boolean canDeposit = player.hasPermission(configManager.getWarehouseDepositPermission()) || player.hasPermission("hfactions.admin.*"); boolean canWithdraw = player.hasPermission(configManager.getWarehouseWithdrawPermission()) || player.hasPermission("hfactions.admin.*");
            ClickType clickType = event.getClick(); boolean isTopInvClick = rawSlot < topInvSize; boolean isShiftClick = clickType.isShiftClick(); boolean tryingToWithdraw = false; boolean tryingToDeposit = false;
            if (isTopInvClick) { if (clickedItem != null && clickedItem.getType() != Material.AIR) { tryingToWithdraw = true; } if (cursorItem != null && cursorItem.getType() != Material.AIR) { tryingToDeposit = true; } if (isShiftClick && tryingToWithdraw) { tryingToDeposit = false; } }
            else if (clickedInventory == event.getView().getBottomInventory()) { if (isShiftClick && clickedItem != null && clickedItem.getType() != Material.AIR) { tryingToDeposit = true; } }
            if (tryingToWithdraw && !canWithdraw) { Utils.msg(player, configManager.getLangMessage("warehouse-withdraw-no-perms", "&cНет прав на взятие предметов.")); event.setCancelled(true); }
            if (tryingToDeposit && !canDeposit) { Utils.msg(player, configManager.getLangMessage("warehouse-deposit-no-perms", "&cНет прав на размещение предметов.")); event.setCancelled(true); }
            // --- Основное GUI Фракции (/fg) ---
        } else if (holder instanceof GuiManager.FactionMainGuiHolder) {
            event.setCancelled(true); ItemStack clickedItem = event.getCurrentItem(); if (clickedInventory != topInventory || clickedItem == null || clickedItem.getType() == Material.AIR) return; ItemMeta meta = clickedItem.getItemMeta(); if (meta == null) return; PersistentDataContainer data = meta.getPersistentDataContainer();
            if (data.has(GuiManager.FACTION_GUI_ACTION_KEY, PersistentDataType.STRING)) {
                String action = data.get(GuiManager.FACTION_GUI_ACTION_KEY, PersistentDataType.STRING); String factionId = ((GuiManager.FactionMainGuiHolder) holder).getFactionId(); Faction faction = factionManager.getFaction(factionId); if (faction == null) { player.closeInventory(); return; }
                switch (action) {
                    case GuiManager.FACTION_GUI_ACTION_MEMBERS: guiManager.openMemberListGui(player, faction, 1, "rank"); break;
                    case GuiManager.FACTION_GUI_ACTION_RANKS: guiManager.openRankViewGui(player, faction); break;
                    case GuiManager.FACTION_GUI_ACTION_WAREHOUSE: if (configManager.isWarehouseEnabled() && player.hasPermission(configManager.getWarehouseOpenPermission())) guiManager.openWarehouseGui(player, faction, 1); else { Utils.msg(player, configManager.getLangMessage("warehouse-no-access")); player.closeInventory(); } break;
                    case GuiManager.FACTION_GUI_ACTION_BALANCE: if (economy != null) guiManager.openBalanceLogGui(player, faction, 1); else { Utils.msg(player, configManager.getLangMessage("economy-disabled")); player.closeInventory(); } break;
                    case GuiManager.FACTION_GUI_ACTION_TERRITORY: player.closeInventory(); if (configManager.isDynmapEnabled()) { String mapUrl = configManager.getConfig().getString("dynmap.web_url", ""); if (mapUrl.isEmpty()) { Utils.msg(player, configManager.getLangMessage("territory-map-url-not-set", "&cURL карты Dynmap не настроен.")); } else { Component linkMessage = Component.text().append(Component.text(ChatColor.translateAlternateColorCodes('&', configManager.getPrefix()))).append(Component.text(ChatColor.translateAlternateColorCodes('&', configManager.getLangMessage("territory-link-message", Map.of("map_url", mapUrl, "faction_name", faction.getName()), "&b[Карта] &eНажмите здесь, чтобы открыть Dynmap!"))).clickEvent(ClickEvent.openUrl(mapUrl)).hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Перейти на Dynmap: " + mapUrl)))).build(); player.sendMessage(linkMessage); } } else { Utils.msg(player, configManager.getLangMessage("territory-dynmap-disabled")); } break;
                    case GuiManager.FACTION_GUI_ACTION_CONTRACTS: player.closeInventory(); Utils.msg(player, configManager.getLangMessage("contracts-wip", "&dСистема контрактов в разработке!")); break;
                }
            }
            // --- GUI Просмотра Рангов ---
        } else if (holder instanceof GuiManager.RankViewGuiHolder) {
            event.setCancelled(true); ItemStack clickedItem = event.getCurrentItem(); if (clickedInventory != topInventory || clickedItem == null || clickedItem.getType() == Material.AIR) return; ItemMeta meta = clickedItem.getItemMeta(); if (meta == null) return; PersistentDataContainer data = meta.getPersistentDataContainer();
            if (data.has(GuiManager.FACTION_GUI_ACTION_KEY, PersistentDataType.STRING)) { String action = data.get(GuiManager.FACTION_GUI_ACTION_KEY, PersistentDataType.STRING); if (GuiManager.FACTION_GUI_ACTION_MANAGE_RANKS.equals(action)) { String factionId = ((GuiManager.RankViewGuiHolder) holder).getFactionId(); Faction faction = factionManager.getFaction(factionId); if (faction != null) { guiManager.openRankManagementGui(player, faction); } else { player.closeInventory(); } } }
            // --- GUI Баланса и Лога ---
        } else if (holder instanceof GuiManager.BalanceLogGuiHolder) {
            event.setCancelled(true); ItemStack clickedItem = event.getCurrentItem(); if (clickedInventory != topInventory || clickedItem == null || clickedItem.getType() == Material.AIR) return; ItemMeta meta = clickedItem.getItemMeta(); if (meta == null) return; PersistentDataContainer data = meta.getPersistentDataContainer();
            if (data.has(GuiManager.BALANCE_LOG_ACTION_KEY, PersistentDataType.STRING)) { String action = data.get(GuiManager.BALANCE_LOG_ACTION_KEY, PersistentDataType.STRING); String factionId = ((GuiManager.BalanceLogGuiHolder) holder).getFactionId(); Faction faction = factionManager.getFaction(factionId); if (faction == null) { player.closeInventory(); return; }
                if (GuiManager.BALANCE_LOG_ACTION_PREV.equals(action) || GuiManager.BALANCE_LOG_ACTION_NEXT.equals(action)) { int targetPage = data.getOrDefault(GuiManager.BALANCE_LOG_PAGE_KEY, PersistentDataType.INTEGER, 1); guiManager.openBalanceLogGui(player, faction, targetPage); }
                else if (GuiManager.BALANCE_LOG_ACTION_DEPOSIT.equals(action)) { player.closeInventory(); Utils.msg(player, configManager.getLangMessage("fine-prompt-deposit", Map.of("highlight", configManager.getHighlightColor()), "&bВведите: &e/hf deposit <сумма>")); }
                else if (GuiManager.BALANCE_LOG_ACTION_WITHDRAW.equals(action)) { player.closeInventory(); Utils.msg(player, configManager.getLangMessage("fine-prompt-withdraw", Map.of("highlight", configManager.getHighlightColor()), "&bВведите: &e/hf withdraw <сумма>")); }
            }
            // --- GUI Списка Участников ---
        } else if (holder instanceof GuiManager.MemberListGuiHolder) {
            event.setCancelled(true); ItemStack clickedItem = event.getCurrentItem(); if (clickedInventory != topInventory || clickedItem == null || clickedItem.getType() == Material.AIR) return; ItemMeta meta = clickedItem.getItemMeta(); if (meta == null) return; PersistentDataContainer data = meta.getPersistentDataContainer();
            if (data.has(GuiManager.MEMBER_LIST_ACTION_KEY, PersistentDataType.STRING)) {
                String action = data.get(GuiManager.MEMBER_LIST_ACTION_KEY, PersistentDataType.STRING); String factionId = ((GuiManager.MemberListGuiHolder) holder).getFactionId(); Faction faction = factionManager.getFaction(factionId); if (faction == null) { player.closeInventory(); return; }
                if (GuiManager.MEMBER_LIST_ACTION_PREV.equals(action) || GuiManager.MEMBER_LIST_ACTION_NEXT.equals(action)) { int targetPage = data.getOrDefault(GuiManager.MEMBER_LIST_PAGE_KEY, PersistentDataType.INTEGER, 1); String currentSort = data.getOrDefault(GuiManager.MEMBER_LIST_SORT_KEY, PersistentDataType.STRING, "rank"); guiManager.openMemberListGui(player, faction, targetPage, currentSort); }
                else if (GuiManager.MEMBER_LIST_ACTION_SORT_RANK.equals(action)) { guiManager.openMemberListGui(player, faction, 1, "rank"); }
                else if (GuiManager.MEMBER_LIST_ACTION_SORT_NAME.equals(action)) { guiManager.openMemberListGui(player, faction, 1, "name"); }
                else if (GuiManager.MEMBER_LIST_ACTION_SORT_ONLINE.equals(action)) { guiManager.openMemberListGui(player, faction, 1, "online"); }
            }
        }
    }
}