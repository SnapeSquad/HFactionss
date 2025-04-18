package org.isyateq.hfactions.managers;

// Bukkit Imports
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

// HFactions Imports
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.gui.FactionInviteGUI;
import org.isyateq.hfactions.gui.FactionRanksGUI;
import org.isyateq.hfactions.gui.FactionWarehouseGUI;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.PendingInvite;
import org.isyateq.hfactions.util.Utils; // Для Utils.color

// Java Imports
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GuiManager {

    private final HFactions plugin;
    private final PlayerManager playerManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager;
    private final ConversationManager conversationManager; // Добавляем ConversationManager

    // Отслеживание открытых GUI
    private final Map<Inventory, String> openWarehouses = new ConcurrentHashMap<>(); // Инвентарь -> ID Фракции
    private final Map<Inventory, UUID> openInviteGuis = new ConcurrentHashMap<>(); // Инвентарь -> UUID Цели
    private final Map<Inventory, String> openRankGuis = new ConcurrentHashMap<>(); // Инвентарь -> ID Фракции


    public GuiManager(HFactions plugin) {
        this.plugin = plugin;
        // Получаем зависимости
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        this.conversationManager = plugin.getConversationManager(); // Получаем ConversationManager

        // Проверки на null
        if (this.playerManager == null) plugin.getLogger().severe("PlayerManager is null in GuiManager!");
        if (this.factionManager == null) plugin.getLogger().severe("FactionManager is null in GuiManager!");
        if (this.configManager == null) plugin.getLogger().severe("ConfigManager is null in GuiManager!");
        if (this.conversationManager == null) plugin.getLogger().severe("ConversationManager is null in GuiManager!"); // Проверка нового менеджера
    }

    // --- Открытие GUI ---

    public void openInviteGUI(Player target, PendingInvite invite) {
        if (target == null || invite == null) {
            plugin.getLogger().warning("Attempted to open invite GUI with null target or invite.");
            return;
        }
        // Проверяем, онлайн ли игрок перед открытием
        if (!target.isOnline()){
            plugin.getLogger().fine("Target player " + target.getName() + " is offline, cannot open invite GUI.");
            // Можно удалить приглашение здесь, если игрок вышел
            // playerManager.removeInvite(target);
            return;
        }

        FactionInviteGUI inviteGUI = new FactionInviteGUI(plugin);
        Inventory gui = inviteGUI.getInventory(invite);
        if (gui != null) {
            openInviteGuis.put(gui, target.getUniqueId());
            // Используем Bukkit.getScheduler().runTask для гарантии выполнения в основном потоке
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (target.isOnline()){ // Еще раз проверка на онлайн
                    target.openInventory(gui);
                }
            });
        } else {
            plugin.getLogger().warning("Failed to create invite GUI for " + target.getName());
        }
    }

    public void openRanksGUI(Player player) {
        if (player == null || playerManager == null || factionManager == null) return;
        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            if (configManager != null) player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            else player.sendMessage(Utils.color("&cYou are not in a faction."));
            return;
        }

        // Проверяем права на управление рангами ПЕРЕД открытием GUI
        if (!player.hasPermission("hfactions.faction.manage_ranks")) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.manage_ranks")) {
                player.sendMessage(configManager != null ? configManager.getMessage("ranks.no_permission_open", "&cYou do not have permission to manage ranks.")
                        : Utils.color("&cYou do not have permission to manage ranks."));
                return;
            }
        }


        FactionRanksGUI ranksGUI = new FactionRanksGUI(plugin);
        Inventory gui = ranksGUI.getInventory(faction);
        if (gui != null) {
            openRankGuis.put(gui, faction.getId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()){
                    player.openInventory(gui);
                }
            });
        } else {
            plugin.getLogger().warning("Failed to create ranks GUI for " + player.getName());
        }
    }


    public void openWarehouseGUI(Player player, int page) {
        if (player == null || playerManager == null || factionManager == null || configManager == null) return;

        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        // Проверка прав на открытие
        boolean canOpen = player.hasPermission("hfactions.faction.warehouse.open") || player.hasPermission("hfactions.admin.*");
        if (!canOpen) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank != null && rank.getPermissions().contains("hfactions.faction.warehouse.open")) {
                canOpen = true;
            }
        }

        if (!canOpen) {
            player.sendMessage(configManager.getMessage("warehouse.no_permission_open", "&cYou do not have permission to open the faction warehouse."));
            return;
        }

        FactionWarehouseGUI warehouseGUI = new FactionWarehouseGUI(plugin, faction);
        Inventory gui = warehouseGUI.getInventory(page);
        if (gui != null) {
            openWarehouses.put(gui, faction.getId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()){
                    player.openInventory(gui);
                }
            });
        } else {
            plugin.getLogger().warning("Failed to create warehouse GUI for " + player.getName());
        }

    }

    // --- Обработка событий GUI ---

    /**
     * Обрабатывает клик в любом из GUI плагина. Вызывается из GuiClickListener.
     */
    public void handleGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Inventory topInventory = event.getView().getTopInventory(); // Верхний инвентарь (GUI)

        // Проверяем, существует ли инвентарь вообще
        if (topInventory == null) return;

        // Определяем, какое GUI открыто, используя наши карты
        if (openWarehouses.containsKey(topInventory)) {
            handleWarehouseClick(event, topInventory);
        } else if (openInviteGuis.containsKey(topInventory)) {
            handleInviteClick(event, player, topInventory);
        } else if (openRankGuis.containsKey(topInventory)) {
            handleRanksClick(event, player, topInventory);
        }
    }

    /**
     * Обрабатывает клик в GUI склада.
     */
    public void handleWarehouseClick(InventoryClickEvent event, Inventory topInventory) {
        if (factionManager == null || configManager == null || playerManager == null) {
            plugin.getLogger().severe("Cannot handle warehouse click: Managers null.");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true); // Отменяем по умолчанию

        int slot = event.getRawSlot();
        int guiSize = topInventory.getSize();
        int contentSlots = guiSize - 9;

        String factionId = openWarehouses.get(topInventory);
        if (factionId == null) {
            plugin.getLogger().warning("Warehouse Faction ID not found for inventory during click for " + player.getName());
            player.closeInventory();
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) {
            player.closeInventory();
            player.sendMessage(configManager.getMessage("faction.disbanded", "&cThe faction warehouse is no longer available."));
            return;
        }

        // --- Клик по кнопкам навигации ---
        if (slot >= contentSlots && slot < guiSize) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && clickedItem.hasItemMeta() && clickedItem.getItemMeta().hasLocalizedName()) {
                String buttonId = clickedItem.getItemMeta().getLocalizedName();
                int currentPage = FactionWarehouseGUI.getCurrentPageFromInventory(topInventory);
                int totalPages = FactionWarehouseGUI.getTotalPages(faction);

                if ("prev_page".equals(buttonId) && currentPage > 1) {
                    openWarehouseGUI(player, currentPage - 1);
                } else if ("next_page".equals(buttonId) && currentPage < totalPages) {
                    openWarehouseGUI(player, currentPage + 1);
                }
            }
            return;
        }

        // --- Клик в слоте склада ---
        if (slot >= 0 && slot < contentSlots) {
            boolean canDeposit = player.hasPermission("hfactions.faction.warehouse.deposit") || player.hasPermission("hfactions.admin.*");
            boolean canWithdraw = player.hasPermission("hfactions.faction.warehouse.withdraw") || player.hasPermission("hfactions.admin.*");

            if (!canDeposit || !canWithdraw) {
                FactionRank rank = playerManager.getPlayerRank(player);
                if (rank != null) {
                    List<String> perms = rank.getPermissions();
                    if (!canDeposit && perms.contains("hfactions.faction.warehouse.deposit")) canDeposit = true;
                    if (!canWithdraw && perms.contains("hfactions.faction.warehouse.withdraw")) canWithdraw = true;
                }
            }

            ItemStack cursorItem = event.getCursor();
            ItemStack clickedSlotItem = event.getCurrentItem();

            // Пытаемся положить предмет
            if (cursorItem != null && !cursorItem.getType().isAir()) {
                if (canDeposit) {
                    event.setCancelled(false);
                } else {
                    player.sendMessage(configManager.getMessage("warehouse.no_permission_deposit", "&cNo permission to deposit items."));
                }
            }
            // Пытаемся взять предмет
            else if (clickedSlotItem != null && !clickedSlotItem.getType().isAir()) {
                if (canWithdraw) {
                    event.setCancelled(false);
                } else {
                    player.sendMessage(configManager.getMessage("warehouse.no_permission_withdraw", "&cNo permission to withdraw items."));
                }
            }
            // Клик по пустому слоту с пустым курсором
            else {
                event.setCancelled(false);
            }
            return;
        }

        // --- Клик в инвентаре игрока ---
        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory != null && clickedInventory.equals(event.getView().getBottomInventory())) {
            // Shift-клик
            if (event.isShiftClick()) {
                boolean canDeposit = player.hasPermission("hfactions.faction.warehouse.deposit") || player.hasPermission("hfactions.admin.*");
                if (!canDeposit) {
                    FactionRank rank = playerManager.getPlayerRank(player);
                    if (rank != null && rank.getPermissions().contains("hfactions.faction.warehouse.deposit")) canDeposit = true;
                }

                if (canDeposit) {
                    ItemStack clickedPlayerItem = event.getCurrentItem(); // Предмет, по которому кликнули в инвентаре игрока
                    if (clickedPlayerItem != null && !clickedPlayerItem.getType().isAir()) {
                        // Стандартная логика Shift-клика попытается переместить предмет. Разрешаем.
                        event.setCancelled(false);
                    } else {
                        // Кликнули по пустому слоту или shift-клик без предмета - отменяем
                        event.setCancelled(true);
                    }
                } else {
                    player.sendMessage(configManager.getMessage("warehouse.no_permission_deposit", "&cNo permission to deposit items."));
                    event.setCancelled(true); // Отменяем, т.к. прав нет
                }
            }
            // Обычный клик
            else {
                event.setCancelled(false); // Разрешаем обычные клики в инвентаре игрока
            }
            return;
        }
    }


    /**
     * Обрабатывает клик в GUI приглашения.
     */
    private void handleInviteClick(InventoryClickEvent event, Player player, Inventory topInventory) {
        event.setCancelled(true);
        if (playerManager == null || configManager == null || factionManager == null) return;

        UUID targetUUID = openInviteGuis.get(topInventory);
        if (targetUUID == null || !targetUUID.equals(player.getUniqueId())) {
            return;
        }

        PendingInvite invite = playerManager.getInvite(player);
        if (invite == null) {
            player.closeInventory();
            player.sendMessage(configManager.getMessage("faction.invite_invalid", "&cThis faction invite is no longer valid."));
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || !clickedItem.hasItemMeta() || !clickedItem.getItemMeta().hasLocalizedName()) {
            return;
        }

        String action = clickedItem.getItemMeta().getLocalizedName();

        if ("accept_invite".equals(action)) {
            player.closeInventory(); // Закрываем ДО выполнения действий
            Faction factionToJoin = factionManager.getFaction(invite.getFactionId());
            if (factionToJoin != null) {
                playerManager.joinFaction(player, factionToJoin);
            } else {
                player.sendMessage(configManager.getMessage("faction.invite_faction_deleted", "&cThe faction you were invited to no longer exists."));
            }
            playerManager.removeInvite(player); // Удаляем приглашение после обработки
        } else if ("decline_invite".equals(action)) {
            player.closeInventory();
            playerManager.removeInvite(player);
            player.sendMessage(configManager.getMessage("faction.invite_declined", "&eYou declined the faction invite from {inviter_name}.")
                    .replace("{inviter_name}", invite.getInviterName()));
            Player inviter = Bukkit.getPlayer(invite.getInviterUUID());
            if (inviter != null && inviter.isOnline()) {
                inviter.sendMessage(configManager.getMessage("faction.invite_was_declined", "&e{target_name} declined your faction invite.")
                        .replace("{target_name}", player.getName()));
            }
        }
    }

    /**
     * Обрабатывает клик в GUI управления рангами.
     */
    private void handleRanksClick(InventoryClickEvent event, Player player, Inventory topInventory) {
        event.setCancelled(true);
        if (playerManager == null || factionManager == null || configManager == null || conversationManager == null) return;

        String factionId = openRankGuis.get(topInventory);
        Faction faction = playerManager.getPlayerFaction(player);
        if (factionId == null || faction == null || !factionId.equals(faction.getId())) {
            player.closeInventory();
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR || !clickedItem.hasItemMeta()) {
            return;
        }

        int rankId = -1;
        String localizedName = clickedItem.getItemMeta().getLocalizedName();
        if (localizedName != null) {
            try {
                rankId = Integer.parseInt(localizedName); // Получаем ID ранга из LocalizedName
            } catch (NumberFormatException e) { /* ignore */ }
        }

        if (rankId == -1) return;

        FactionRank clickedRank = faction.getRank(rankId);
        if (clickedRank == null) return;

        // Проверяем права на управление рангами ОДИН РАЗ
        boolean canManageRanks = player.hasPermission("hfactions.faction.manage_ranks");
        if (!canManageRanks) {
            FactionRank playerRank = playerManager.getPlayerRank(player);
            if (playerRank != null && playerRank.getPermissions().contains("hfactions.faction.manage_ranks")) {
                canManageRanks = true;
            }
        }
        if (!canManageRanks) {
            player.sendMessage(configManager.getMessage("ranks.no_permission_manage", "&cYou do not have permission to manage ranks."));
            return;
        }


        // --- Обработка ЛКМ (Изменить имя) ---
        if (event.isLeftClick()) {
            player.closeInventory(); // Закрываем GUI
            // Используем ConversationManager
            conversationManager.startRankRenameConversation(player, factionId, rankId);
            player.sendMessage(configManager.getMessage("ranks.enter_new_name", "&aEnter the new display name for rank {rank_id} ({rank_name}) in chat, or type 'cancel'.")
                    .replace("{rank_id}", String.valueOf(rankId))
                    .replace("{rank_name}", clickedRank.getDisplayName()));
        }
        // --- Обработка ПКМ (Сбросить имя) ---
        else if (event.isRightClick()) {
            if (clickedRank.getDisplayName() != null && !clickedRank.getDisplayName().equals(clickedRank.getDefaultName())) {
                factionManager.resetRankDisplayName(factionId, rankId); // Сбрасываем имя
                player.sendMessage(configManager.getMessage("ranks.name_reset", "&aDisplay name for rank {rank_id} reset to default.")
                        .replace("{rank_id}", String.valueOf(rankId)));
                // Переоткрываем GUI для обновления
                openRanksGUI(player); // Открываем заново
            } else {
                player.sendMessage(configManager.getMessage("ranks.name_already_default", "&eRank name is already set to default."));
            }
        }
    }


    /**
     * Обрабатывает закрытие инвентаря склада. Вызывается из InventoryCloseListener.
     */
    public void handleWarehouseClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return; // Убедимся, что это игрок
        Player player = (Player) event.getPlayer();
        Inventory inventory = event.getInventory();

        // Обработка закрытия СКЛАДА
        if (openWarehouses.containsKey(inventory)) {
            String factionId = openWarehouses.remove(inventory);
            if (factionManager == null || factionId == null || configManager == null) return;

            Faction faction = factionManager.getFaction(factionId);
            if (faction == null) {
                plugin.getLogger().warning("Faction " + factionId + " not found when closing warehouse for " + player.getName());
                return;
            }

            plugin.getLogger().fine("Warehouse closed for faction " + factionId + " by " + player.getName() + ". Saving contents...");

            ItemStack[] guiContentsPage = new ItemStack[45];
            for (int i = 0; i < 45; i++) {
                guiContentsPage[i] = inventory.getItem(i);
            }

            int closedPage = FactionWarehouseGUI.getCurrentPageFromInventory(inventory);
            if (closedPage <= 0) {
                plugin.getLogger().severe("Could not determine page number for closed warehouse of faction " + factionId + ". Contents NOT saved!");
                player.sendMessage(Utils.color("&cError saving warehouse contents (invalid page). Contact admin.")); // Сообщаем игроку
                return;
            }

            ItemStack[] currentFactionContents = faction.getWarehouseContents();
            ItemStack[] finalContents = (currentFactionContents != null && currentFactionContents.length == faction.getWarehouseSize())
                    ? currentFactionContents : new ItemStack[faction.getWarehouseSize()];

            int startIndexInFaction = (closedPage - 1) * 45;
            for (int i = 0; i < 45; i++) {
                int indexInFaction = startIndexInFaction + i;
                if (indexInFaction < finalContents.length) {
                    finalContents[indexInFaction] = guiContentsPage[i];
                } else {
                    break;
                }
            }

            faction.setWarehouseContents(finalContents);
            factionManager.markFactionAsModified(factionId); // Помечаем для автосохранения

            player.sendMessage(configManager.getMessage("warehouse.saved", "&aFaction warehouse contents saved."));
        }
        // Обработка закрытия других GUI
        else if (openInviteGuis.containsKey(inventory)) {
            openInviteGuis.remove(inventory);
        } else if (openRankGuis.containsKey(inventory)) {
            openRankGuis.remove(inventory);
            // Отменяем беседу переименования, если игрок закрыл GUI рангов
            if (conversationManager != null) {
                conversationManager.cancelRenameConversation(player.getUniqueId());
            }
        }
    }

} // Конец класса GuiManager