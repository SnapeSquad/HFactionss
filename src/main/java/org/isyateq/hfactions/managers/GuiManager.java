package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey; // Импорт для ключа ранга
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta; // Импорт ItemMeta
import org.bukkit.persistence.PersistentDataType; // Импорт PersistentDataType
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.gui.FactionInviteGUI;
import org.isyateq.hfactions.gui.FactionRanksGUI;
import org.isyateq.hfactions.gui.FactionWarehouseGUI;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.PendingInvite;
import org.isyateq.hfactions.util.Utils;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID; // Импорт UUID
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class GuiManager {

    private final HFactions plugin;
    private final PlayerManager playerManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager; // Оставляем, используется в addInvite

    // Отслеживаем открытые GUI
    private final Map<Inventory, String> openWarehouses = new ConcurrentHashMap<>();
    private final Map<Inventory, String> openRankGuis = new ConcurrentHashMap<>();
    private final Map<Inventory, UUID> openInviteGuis = new ConcurrentHashMap<>();


    public GuiManager(HFactions plugin) {
        this.plugin = plugin;
        // Получаем менеджеры из главного класса
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager(); // Получаем ConfigManager
        // Проверки на null, если менеджеры могут быть не инициализированы
        if (this.playerManager == null || this.factionManager == null || this.configManager == null) {
            plugin.getLogger().severe("One or more managers are null in GuiManager constructor! GUIs might not work.");
            // Можно выбросить исключение, чтобы предотвратить запуск с нерабочим GUI
            // throw new IllegalStateException("Required managers not available for GuiManager");
        }
    }

    // --- Открытие GUI ---

    public void openInviteGUI(Player target, PendingInvite invite) {
        FactionInviteGUI inviteGUI = new FactionInviteGUI(plugin, invite);
        Inventory gui = inviteGUI.getInventory();
        openInviteGuis.put(gui, target.getUniqueId());
        target.openInventory(gui);
    }

    public void openRanksGUI(Player player) {
        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(Utils.color("&cYou are not in a faction."));
            return;
        }
        if (!hasPermission(player, "hfactions.faction.manage_ranks")) {
            player.sendMessage(Utils.color("&cYou do not have permission to manage ranks."));
            return;
        }

        FactionRanksGUI ranksGUI = new FactionRanksGUI(plugin, faction);
        Inventory gui = ranksGUI.getInventory();
        openRankGuis.put(gui, faction.getId());
        player.openInventory(gui);
    }

    public void openWarehouseGUI(Player player, int page) {
        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(Utils.color("&cYou are not in a faction."));
            return;
        }
        if (!hasPermission(player, "hfactions.faction.warehouse.open")) {
            player.sendMessage(Utils.color("&cYou do not have permission to open the faction warehouse."));
            return;
        }

        FactionWarehouseGUI warehouseGUI = new FactionWarehouseGUI(plugin, faction);
        Inventory gui = warehouseGUI.getInventory(page);
        if (gui == null) {
            player.sendMessage(Utils.color("&cError opening warehouse GUI."));
            return;
        }
        openWarehouses.put(gui, faction.getId());
        player.openInventory(gui);
    }

    // --- Обработчики Событий (вызываются из Listener'ов) ---

    /**
     * Обрабатывает закрытие любого инвентаря, проверяя, относится ли он к GUI плагина.
     * Вызывается из InventoryCloseListener.
     */
    public void handleInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        Player player = (Player) event.getPlayer();

        // Обработка закрытия склада
        if (openWarehouses.containsKey(inventory)) {
            handleWarehouseClose(inventory, player);
            // Не делаем return, чтобы обработать другие возможные GUI (хотя одновременно открыто одно)
        }

        // Обработка закрытия GUI рангов
        if (openRankGuis.containsKey(inventory)) {
            openRankGuis.remove(inventory);
            plugin.getLogger().fine("Ranks GUI closed for player " + player.getName());
            // Ничего сохранять не нужно при обычном закрытии рангов
        }

        // Обработка закрытия GUI приглашений
        if (openInviteGuis.containsKey(inventory)) {
            UUID targetUUID = openInviteGuis.remove(inventory);
            plugin.getLogger().fine("Invite GUI closed by player " + player.getName() + " (Target UUID: " + targetUUID + ")");
            // Если закрыл не кнопкой, приглашение остается активным до истечения срока
        }
    }

    /**
     * Внутренний метод для обработки закрытия именно склада.
     */
    private void handleWarehouseClose(Inventory inventory, Player player) {
        String factionId = openWarehouses.remove(inventory); // Удаляем из мапы при обработке
        if (factionId == null) return; // Уже обработано или не наш склад

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) {
            plugin.getLogger().warning("Faction " + factionId + " not found when closing warehouse for " + player.getName());
            return;
        }

        plugin.getLogger().fine("Warehouse closed for faction " + factionId + " by " + player.getName() + ". Saving contents...");

        ItemStack[] guiContents = inventory.getContents();
        int contentSlots = 45;
        ItemStack[] currentWarehouse = faction.getWarehouseContents(); // Получаем текущее состояние (копию)
        ItemStack[] newWarehouseContents = Arrays.copyOf(currentWarehouse, currentWarehouse.length); // Создаем изменяемую копию

        int currentPage = FactionWarehouseGUI.getCurrentPageFromInventory(inventory);
        if (currentPage < 1) {
            plugin.getLogger().warning("Could not determine current page for warehouse of faction " + factionId + " on close. Assuming page 1.");
            currentPage = 1;
        }

        int startIndexInFaction = (currentPage - 1) * contentSlots;

        for (int guiSlot = 0; guiSlot < contentSlots; guiSlot++) {
            int indexInFaction = startIndexInFaction + guiSlot;
            if (indexInFaction < faction.getWarehouseSize() && indexInFaction < newWarehouseContents.length) {
                ItemStack item = guiContents[guiSlot];
                newWarehouseContents[indexInFaction] = (item != null && item.getType() != Material.AIR) ? item.clone() : null;
            } else {
                break;
            }
        }

        // Сравниваем старое и новое содержимое перед сохранением, чтобы не сохранять без изменений
        if (!Arrays.equals(currentWarehouse, newWarehouseContents)) {
            faction.setWarehouseContents(newWarehouseContents); // Обновляем содержимое (это пометит фракцию как измененную)
            factionManager.saveModifiedFactions(); // Сохраняем (можно делать реже)
            plugin.getLogger().fine("Warehouse contents for faction " + factionId + " saved.");
        } else {
            plugin.getLogger().fine("Warehouse contents for faction " + factionId + " were not changed.");
        }
        // Удаляем return; отсюда (предупреждение 6)
    }


    /**
     * Обрабатывает клик в любом инвентаре, проверяя, относится ли он к GUI плагина.
     * Вызывается из GuiClickListener.
     */
    public void handleInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory(); // Верхний инвентарь (GUI)
        Inventory clickedInventory = event.getClickedInventory(); // Инвентарь, по которому кликнули (может быть верхним или нижним)
        Player player = (Player) event.getWhoClicked();

        // Если клик вообще не по инвентарю, игнорируем
        if (clickedInventory == null) {
            return;
        }

        // --- Обработка клика по Складу ---
        if (openWarehouses.containsKey(topInventory)) {
            handleWarehouseClick(event, player, topInventory, clickedInventory);
            return; // Завершаем обработку, если это был склад
        }
        // --- Обработка клика по GUI Рангов ---
        if (openRankGuis.containsKey(topInventory)) {
            // Клик должен быть именно в верхнем инвентаре
            if (topInventory.equals(clickedInventory)) {
                handleRanksClick(event, player, topInventory);
            }
            return; // Завершаем обработку
        }
        // --- Обработка клика по GUI Приглашений ---
        if (openInviteGuis.containsKey(topInventory)) {
            // Клик должен быть именно в верхнем инвентаре
            if (topInventory.equals(clickedInventory)) {
                handleInviteClick(event, player, topInventory);
            }
            return; // Завершаем обработку
        }

        // Если это не наше GUI, но клик был в инвентаре игрока, ничего не делаем (разрешаем)
        // Если клик был в чужом GUI (не нашем), тоже ничего не делаем
    }


    // --- Обработчики кликов для конкретных GUI ---

    private void handleWarehouseClick(InventoryClickEvent event, Player player, Inventory topInventory, Inventory clickedInventory) {
        event.setCancelled(true); // Отменяем по умолчанию для склада

        int slot = event.getRawSlot(); // Слот в контексте всего окна (0-53 для верха, 54+ для низа)
        int guiSize = topInventory.getSize(); // 54
        int contentSlots = guiSize - 9; // 45

        // Клик в верхнем инвентаре
        if (clickedInventory != null && clickedInventory.equals(topInventory)) {
            // Клик по кнопкам навигации (45-53)
            if (slot >= contentSlots && slot < guiSize) {
                ItemStack clickedItem = event.getCurrentItem();
                // Проверяем, что это наша кнопка (по localized name)
                if (clickedItem != null && clickedItem.hasItemMeta()) {
                    ItemMeta meta = clickedItem.getItemMeta();
                    if (meta != null && meta.hasLocalizedName()) {
                        String buttonId = meta.getLocalizedName();
                        int currentPage = FactionWarehouseGUI.getCurrentPageFromInventory(topInventory);
                        Faction faction = factionManager.getFaction(openWarehouses.get(topInventory));
                        if (faction == null) return;
                        int totalPages = FactionWarehouseGUI.getTotalPages(faction);

                        if ("prev_page".equals(buttonId) && currentPage > 1) {
                            openWarehouseGUI(player, currentPage - 1);
                        } else if ("next_page".equals(buttonId) && currentPage < totalPages) {
                            openWarehouseGUI(player, currentPage + 1);
                        }
                    }
                }
                return; // Клик по кнопке или рамке обработан
            }

            // Клик по слоту с содержимым (0-44)
            // Избыточная проверка `slot < contentSlots` удалена (предупреждение 8)
            boolean canDeposit = hasPermission(player, "hfactions.faction.warehouse.deposit");
            boolean canWithdraw = hasPermission(player, "hfactions.faction.warehouse.withdraw");

            ItemStack cursorItem = event.getCursor();
            ItemStack clickedSlotItem = event.getCurrentItem();

            // Игнорируем предупреждение IDE о cursorItem != null (предупреждение 9)
            boolean placingItem = cursorItem != null && cursorItem.getType() != Material.AIR;
            boolean takingItem = clickedSlotItem != null && clickedSlotItem.getType() != Material.AIR;
            boolean swapping = placingItem && takingItem; // Обмен предметами

            if (swapping) { // Пытается положить предмет в непустой слот (обмен)
                if (canDeposit && canWithdraw) {
                    event.setCancelled(false); // Разрешаем обмен
                } else {
                    player.sendMessage(Utils.color("&cYou need both deposit and withdraw permissions to swap items."));
                }
            } else if (placingItem) { // Пытается положить предмет в пустой слот
                if (canDeposit) {
                    event.setCancelled(false); // Разрешаем
                } else {
                    player.sendMessage(Utils.color("&cYou don't have permission to deposit items."));
                }
            } else if (takingItem) { // Пытается взять предмет (курсор пуст)
                if (canWithdraw) {
                    event.setCancelled(false); // Разрешаем
                } else {
                    player.sendMessage(Utils.color("&cYou don't have permission to withdraw items."));
                }
            } else {
                // Клик по пустому слоту пустым курсором
                event.setCancelled(false); // Разрешаем (ничего не произойдет)
            }
        }
        // Клик в нижнем инвентаре
        else if (clickedInventory != null && clickedInventory.equals(event.getView().getBottomInventory())) {
            // Shift-клик из нижнего в верхний
            if (event.isShiftClick()) {
                if (hasPermission(player, "hfactions.faction.warehouse.deposit")) {
                    // Ищем первый пустой слот в видимой части склада
                    ItemStack itemToMove = event.getCurrentItem();
                    if (itemToMove != null && itemToMove.getType() != Material.AIR) {
                        int emptySlot = -1;
                        for (int i = 0; i < contentSlots; i++) {
                            if (topInventory.getItem(i) == null || topInventory.getItem(i).getType() == Material.AIR) {
                                emptySlot = i;
                                break;
                            }
                        }
                        if (emptySlot != -1) {
                            event.setCancelled(false); // Разрешаем стандартное поведение (попытку перемещения)
                        } else {
                            player.sendMessage(Utils.color("&cThe current warehouse page is full."));
                            // Оставляем event.setCancelled(true) по умолчанию
                        }
                    } else {
                        event.setCancelled(false); // Разрешаем клик по пустому слоту
                    }
                } else {
                    player.sendMessage(Utils.color("&cYou don't have permission to deposit items using shift-click."));
                    // Оставляем event.setCancelled(true) по умолчанию
                }
            } else {
                // Обычный клик в нижнем инвентаре - разрешаем
                event.setCancelled(false);
            }
        }
        // Клик вне инвентарей - оставляем отмененным по умолчанию
    }

    private void handleRanksClick(InventoryClickEvent event, Player player, Inventory topInventory) {
        event.setCancelled(true); // Запрещаем брать предметы

        int slot = event.getSlot(); // Получаем слот именно в верхнем инвентаре
        // Проверка клика именно в верхнем инвентаре уже сделана в handleInventoryClick

        ItemStack clickedItem = event.getCurrentItem();
        // Улучшенная проверка предмета
        if (clickedItem == null || clickedItem.getType() == Material.AIR || !clickedItem.hasItemMeta()) return;
        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null) return; // На всякий случай

        // Исправленная проверка PersistentData и получение ключа (Ошибка 1)
        NamespacedKey rankKey = FactionRanksGUI.getRankIdKey(); // Получаем ключ из FactionRanksGUI
        if (!meta.getPersistentDataContainer().has(rankKey, PersistentDataType.INTEGER)) {
            plugin.getLogger().fine("Clicked item in ranks GUI does not have the rank ID key.");
            return; // Не наш предмет ранга
        }

        Integer rankId = meta.getPersistentDataContainer().get(rankKey, PersistentDataType.INTEGER);
        // Исправляем имя метода getRankIdFromItem (Ошибка 2) - теперь получаем напрямую
        // Integer rankId = FactionRanksGUI.getRankIdFromItem(clickedItem); // Старый вариант

        if (rankId == null) {
            plugin.getLogger().warning("Failed to get rank ID from item NBT in ranks GUI.");
            return;
        }

        String factionId = openRankGuis.get(topInventory);
        if (factionId == null) {
            plugin.getLogger().severe("FATAL: openRankGuis map does not contain inventory for ranks click!");
            player.closeInventory();
            return;
        }

        if (!hasPermission(player, "hfactions.faction.manage_ranks")) {
            player.sendMessage(Utils.color("&cYou do not have permission to manage ranks."));
            player.closeInventory();
            return;
        }

        // --- Запрос имени через Conversation API ---
        if (event.isLeftClick()) {
            player.closeInventory();
            // TODO: Реализовать Conversation API для запроса нового имени
            // Пример вызова (нужен ConversationFactory и ConversationAbandonedListener)
            // ConversationFactory factory = new ConversationFactory(plugin);
            // Conversation conversation = factory.withFirstPrompt(new RankRenamePrompt(plugin, factionId, rankId))
            //                                   .withLocalEcho(false)
            //                                   .withTimeout(30) // 30 секунд на ввод
            //                                   .buildConversation(player);
            // conversation.begin();
            player.sendMessage(Utils.color("&e[TEMP] Enter new name for rank " + rankId + " in chat (Conversation API needed)."));
            // Временное решение: просто показываем текущее имя или дефолтное
            FactionRank rank = factionManager.getFaction(factionId).getRank(rankId);
            if (rank != null) {
                String currentName = rank.getDisplayName() != null ? rank.getDisplayName() : rank.getDefaultName();
                player.sendMessage(Utils.color("&7Current name: " + currentName));
            }


        } else if (event.isRightClick()) {
            factionManager.resetRankDisplayName(factionId, rankId);
            player.sendMessage(Utils.color("&aDisplay name for rank " + rankId + " reset to default."));
            openRanksGUI(player); // Переоткрываем для обновления
        }
    }

    private void handleInviteClick(InventoryClickEvent event, Player player, Inventory topInventory) {
        event.setCancelled(true); // Запрещаем брать предметы

        int slot = event.getSlot();
        // Проверка клика в верхнем инвентаре уже сделана

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || !clickedItem.hasItemMeta()) return;
        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null || !meta.hasLocalizedName()) return; // Используем localizedName для идентификации кнопок

        String action = meta.getLocalizedName();
        UUID targetUUID = openInviteGuis.get(topInventory);

        if (targetUUID == null || !targetUUID.equals(player.getUniqueId())) {
            plugin.getLogger().warning("Invite GUI click mismatch: Clicker " + player.getName() + ", Expected target UUID " + targetUUID);
            player.closeInventory();
            openInviteGuis.remove(topInventory); // Убираем невалидное GUI
            return;
        }

        PendingInvite invite = playerManager.getInvite(player);
        if (invite == null) {
            player.sendMessage(Utils.color("&cThis faction invite is no longer valid or has expired."));
            player.closeInventory();
            openInviteGuis.remove(topInventory);
            return;
        }

        // --- Обработка действий ---
        player.closeInventory(); // Закрываем в любом случае
        openInviteGuis.remove(topInventory); // Убираем из мапы
        playerManager.removeInvite(player); // Удаляем само приглашение

        if ("accept_invite".equals(action)) {
            Faction faction = factionManager.getFaction(invite.getFactionId());
            Player inviter = Bukkit.getPlayer(invite.getInviterUUID());

            if (faction == null) {
                player.sendMessage(Utils.color("&cThe faction you tried to join no longer exists."));
                if (inviter != null && inviter.isOnline()) inviter.sendMessage(Utils.color("&c" + player.getName() + " could not join your faction as it no longer exists."));
                return;
            }
            if (playerManager.isInFaction(player)) {
                player.sendMessage(Utils.color("&cYou are already in a faction. Leave it first to accept the invite."));
                if (inviter != null && inviter.isOnline()) inviter.sendMessage(Utils.color("&c" + player.getName() + " could not accept your invite because they are already in a faction."));
                return;
            }

            boolean joined = playerManager.joinFaction(player, faction);
            if (inviter != null && inviter.isOnline()) {
                inviter.sendMessage(Utils.color(joined ? "&a" + player.getName() + " has accepted your invite and joined the faction!" : "&c" + player.getName() + " tried to accept your invite but failed to join."));
            }

        } else if ("decline_invite".equals(action)) {
            player.sendMessage(Utils.color("&eYou have declined the faction invite."));
            Player inviter = Bukkit.getPlayer(invite.getInviterUUID());
            if (inviter != null && inviter.isOnline()) {
                inviter.sendMessage(Utils.color("&c" + player.getName() + " has declined your faction invite."));
            }
        }
    }


    // --- Вспомогательный метод для проверки прав (учитывает ранг) ---
    private boolean hasPermission(Player player, String permission) {
        if (player == null || permission == null || permission.isEmpty()) {
            return false;
        }
        // Сначала проверяем прямое право
        if (player.hasPermission(permission)) {
            return true;
        }
        // Затем проверяем права ранга
        FactionRank rank = playerManager.getPlayerRank(player);
        // Убедимся, что ранк не null и его список прав не null перед проверкой
        return rank != null && rank.getPermissions() != null && rank.getPermissions().contains(permission);
    }

    // Метод для добавления таймаута приглашения (вызывается из команды invite)
    public void addInviteTimeout(Player target, PendingInvite invite) {
        long expireTicks = configManager.getConfig().getLong("faction.invite_expire_seconds", 60) * 20L;
        if (expireTicks <= 0) expireTicks = 60 * 20L; // Минимум 60 секунд

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingInvite currentInvite = playerManager.getInvite(target); // Получаем текущее приглашение
            // Удаляем только если это ТО ЖЕ САМОЕ приглашение (на случай, если его уже приняли/отклонили)
            // Сравнение по содержимому, если equals переопределен в PendingInvite, иначе по ссылке
            boolean isSameInvite = (currentInvite != null && currentInvite.equals(invite));
            // Или если equals не переопределен, сравниваем поля:
            // boolean isSameInvite = (currentInvite != null
            //                      && currentInvite.getInviterUUID().equals(invite.getInviterUUID())
            //                      && currentInvite.getFactionId().equals(invite.getFactionId()));

            if (isSameInvite) {
                playerManager.removeInvite(target); // Удаляем из менеджера
                if (target.isOnline()) {
                    target.sendMessage(Utils.color("&cThe faction invite from " + invite.getInviterName() + " has expired."));
                    // Закрываем GUI, если оно еще открыто для этого приглашения
                    Inventory openInv = target.getOpenInventory().getTopInventory();
                    if (openInv != null && openInviteGuis.containsKey(openInv) && openInviteGuis.get(openInv).equals(target.getUniqueId())) {
                        target.closeInventory();
                        openInviteGuis.remove(openInv); // Убираем из мапы после закрытия
                    }
                }
            }
        }, expireTicks);
    }
}