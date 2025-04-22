package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.gui.FactionInviteGUI;
import org.isyateq.hfactions.gui.FactionRanksGUI;
import org.isyateq.hfactions.gui.FactionWarehouseGUI;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.PendingInvite;
import org.isyateq.hfactions.util.Utils;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level; // Импорт логгера

public class GuiManager {

    private final HFactions plugin;
    private final PlayerManager playerManager;
    private final FactionManager factionManager;

    // Используем Map для отслеживания открытых складов: Инвентарь -> ID Фракции
    // Используем WeakHashMap, чтобы инвентари автоматически удалялись сборщиком мусора, если на них нет ссылок
    // private final Map<Inventory, String> openWarehouses = new WeakHashMap<>();
    // UPD: WeakHashMap может быть проблематичен с Inventory, т.к. ссылка может сохраняться Bukkit'ом.
    // Лучше использовать ConcurrentHashMap и удалять вручную в onClose/onClick.
    private final Map<Inventory, String> openWarehouses = new ConcurrentHashMap<>();
    // TODO: Добавить мапы для отслеживания других открытых GUI (ранги, инвайты), если нужно обрабатывать их клики/закрытия специфично

    public GuiManager(HFactions plugin) {
        this.plugin = plugin;
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();
    }

    // --- Открытие GUI ---

    public void openInviteGUI(Player target, PendingInvite invite) {
        Faction inviteFaction = factionManager.getFaction(invite.getFactionId());
        if (inviteFaction == null) {
            plugin.getLogger().warning("Attempted to open invite GUI for non-existent faction: " + invite.getFactionId());
            target.sendMessage(Utils.color("&cError: The faction you were invited to no longer exists."));
            playerManager.removeInvite(target); // Удаляем невалидное приглашение
            return;
        }
        FactionInviteGUI inviteGUI = new FactionInviteGUI(plugin, invite, inviteFaction);
        target.openInventory(inviteGUI.getInventory());
        // TODO: Добавить этот инвентарь в мапу отслеживания, если нужно
    }

    public void openRanksGUI(Player player, Faction faction) {
        if (faction == null) {
            player.sendMessage(Utils.color("&cCould not find faction data."));
            return;
        }
        FactionRanksGUI ranksGUI = new FactionRanksGUI(plugin, faction);
        player.openInventory(ranksGUI.getInventory());
        // TODO: Добавить этот инвентарь в мапу отслеживания, если нужно
    }


    public void openWarehouseGUI(Player player, int page) {
        Faction faction = playerManager.getPlayerFaction(player);
        String adminFactionId = playerManager.getAdminModeFactionId(player);
        if (adminFactionId != null) { // Если админ в режиме, используем его фракцию
            faction = factionManager.getFaction(adminFactionId);
        }

        if (faction == null) {
            player.sendMessage(Utils.color("&cYou are not in a faction (or admin mode faction not found)."));
            return;
        }

        // Проверка прав на открытие (базовое ИЛИ ранг ИЛИ админ)
        if (!player.hasPermission("hfactions.faction.warehouse.open")) {
            FactionRank rank = playerManager.getPlayerRank(player); // Получаем ранг (даже если админ, для логов)
            // Админ в режиме всегда может открыть
            if (adminFactionId == null && (rank == null || !rank.getPermissions().contains("hfactions.faction.warehouse.open"))) {
                player.sendMessage(Utils.color("&cYou do not have permission to open the faction warehouse."));
                return;
            }
        }

        FactionWarehouseGUI warehouseGUI = new FactionWarehouseGUI(plugin, faction);
        Inventory gui = warehouseGUI.getInventory(page);
        openWarehouses.put(gui, faction.getId()); // Сохраняем связь инвентаря с ID фракции
        player.openInventory(gui);
    }

    // --- Обработчики событий ---

    // Обработчик закрытия инвентаря (вызывается из InventoryCloseListener)
    public void handleWarehouseClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        // Проверяем, был ли это наш инвентарь склада И он еще есть в мапе
        if (openWarehouses.containsKey(inventory)) {
            String factionId = openWarehouses.remove(inventory); // Получаем ID и удаляем из мапы
            Faction faction = factionManager.getFaction(factionId);
            Player player = (Player) event.getPlayer();

            // Проверка, что фракция еще существует
            if (faction == null) {
                plugin.getLogger().warning("Faction " + factionId + " not found when closing warehouse for " + player.getName() + ". Contents not saved.");
                return;
            }

            plugin.getLogger().fine("Warehouse closed for faction " + factionId + " by " + player.getName() + ". Saving contents...");

            // Получаем актуальное содержимое инвентаря
            ItemStack[] finalContents = new ItemStack[faction.getWarehouseSize()];
            int guiSize = inventory.getSize();
            int contentSlots = guiSize - 9;
            int currentPage = FactionWarehouseGUI.getCurrentPageFromInventory(inventory);
            if (currentPage < 1) {
                plugin.getLogger().warning("Could not determine current page for warehouse inventory of faction " + factionId + ". Assuming page 1.");
                currentPage = 1;
            }

            int startIndexInFaction = (currentPage - 1) * contentSlots;

            // Копируем ВСЕ текущие предметы фракции, чтобы не потерять другие страницы
            ItemStack[] currentFactionContents = faction.getWarehouseContents().clone(); // КЛОНИРУЕМ массив!
            System.arraycopy(currentFactionContents, 0, finalContents, 0, Math.min(currentFactionContents.length, finalContents.length));

            // Теперь перезаписываем видимую страницу из закрытого GUI
            boolean changed = false;
            for (int guiSlot = 0; guiSlot < contentSlots; guiSlot++) {
                int indexInFaction = startIndexInFaction + guiSlot;
                if (indexInFaction < faction.getWarehouseSize()) {
                    ItemStack newItem = inventory.getItem(guiSlot);
                    ItemStack oldItem = finalContents[indexInFaction]; // Предмет, который был там до открытия/изменения
                    // Сравниваем предметы (null-safe)
                    if (!Objects.equals(newItem, oldItem)) {
                        finalContents[indexInFaction] = newItem; // Записываем предмет (или null)
                        changed = true; // Отмечаем, что было изменение
                    }
                } else {
                    break;
                }
            }

            // Обновляем и сохраняем только если были изменения
            if (changed) {
                plugin.getLogger().fine("Warehouse contents changed for faction " + factionId + ". Updating and marking for save.");
                faction.setWarehouseContents(finalContents);
                factionManager.markFactionAsModified(factionId);
                // Сохранение произойдет автоматически по таймеру FactionManager
                // factionManager.saveModifiedFactions(); // Не сохраняем здесь вручную
                // player.sendMessage(Utils.color("&aFaction warehouse contents saved.")); // Можно убрать, т.к. автосохранение
            } else {
                plugin.getLogger().fine("Warehouse contents were not changed for faction " + factionId + ".");
            }
        }
    }

    // Обработчик клика (вызывается из GuiClickListener)
    public void handleWarehouseClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();

        // Проверяем, является ли верхний инвентарь отслеживаемым складом
        if (openWarehouses.containsKey(topInventory)) {
            event.setCancelled(true); // Отменяем ВСЕ клики по умолчанию внутри склада

            Player player = (Player) event.getWhoClicked();
            Inventory clickedInventory = event.getClickedInventory(); // Инвентарь, по которому кликнули
            int slot = event.getRawSlot(); // Слот относительно всего окна (верх+низ)
            int topSize = topInventory.getSize(); // 54
            int contentSlots = topSize - 9; // 45

            String factionId = openWarehouses.get(topInventory); // Получаем ID фракции
            String adminFactionId = playerManager.getAdminModeFactionId(player); // Проверяем админ режим

            // --- Обработка кликов по кнопкам навигации (в верхнем инвентаре) ---
            if (slot >= contentSlots && slot < topSize) {
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem != null && clickedItem.hasItemMeta() && clickedItem.getItemMeta().hasLocalizedName()) {
                    String buttonId = clickedItem.getItemMeta().getLocalizedName();
                    int currentPage = FactionWarehouseGUI.getCurrentPageFromInventory(topInventory);
                    Faction currentFaction = factionManager.getFaction(factionId); // Нужен объект фракции для totalPages
                    if(currentFaction != null) {
                        int totalPages = FactionWarehouseGUI.getTotalPages(currentFaction);
                        if ("prev_page".equals(buttonId) && currentPage > 1) {
                            openWarehouseGUI(player, currentPage - 1);
                        } else if ("next_page".equals(buttonId) && currentPage < totalPages) {
                            openWarehouseGUI(player, currentPage + 1);
                        }
                    }
                }
                return; // Клик по кнопке обработан
            }

            // Проверка прав на депозит/снятие (нужна для всех действий ниже)
            boolean canDeposit = player.hasPermission("hfactions.faction.warehouse.deposit");
            boolean canWithdraw = player.hasPermission("hfactions.faction.warehouse.withdraw");
            FactionRank rank = playerManager.getPlayerRank(player);
            boolean isAdmin = adminFactionId != null; // Админ в режиме?

            if (!isAdmin) { // Если не админ, проверяем права ранга
                if (rank != null) {
                    if (!canDeposit && rank.getPermissions().contains("hfactions.faction.warehouse.deposit")) canDeposit = true;
                    if (!canWithdraw && rank.getPermissions().contains("hfactions.faction.warehouse.withdraw")) canWithdraw = true;
                }
            } else { // Админ в режиме может все
                canDeposit = true;
                canWithdraw = true;
            }


            // --- Разрешаем клики внутри инвентаря игрока ---
            if (clickedInventory != null && clickedInventory.equals(event.getView().getBottomInventory())) {
                // Shift-клик из низа в верх (депозит)
                if (event.isShiftClick()) {
                    if (canDeposit) {
                        event.setCancelled(false); // Разрешаем стандартное перемещение
                    } else {
                        player.sendMessage(Utils.color("&cYou don't have permission to deposit items."));
                        // event.setCancelled(true); // Уже true
                    }
                } else {
                    // Обычный клик в нижнем инвентаре - всегда разрешен
                    event.setCancelled(false);
                }
                return; // Клик в нижнем инвентаре обработан
            }


            // --- Обработка кликов в самом складе (верхний инвентарь) ---
            if (clickedInventory != null && clickedInventory.equals(topInventory) && slot < contentSlots) {
                ItemStack cursorItem = event.getCursor(); // Предмет на курсоре
                ItemStack clickedSlotItem = event.getCurrentItem(); // Предмет в слоте

                boolean placingItem = cursorItem != null && cursorItem.getType() != Material.AIR;
                boolean takingItem = (clickedSlotItem != null && clickedSlotItem.getType() != Material.AIR) && !placingItem;

                if (placingItem) { // Игрок хочет ПОЛОЖИТЬ предмет с курсора
                    if (canDeposit) {
                        event.setCancelled(false); // Разрешаем
                    } else {
                        player.sendMessage(Utils.color("&cYou don't have permission to deposit items."));
                        // event.setCancelled(true); // Уже true
                    }
                } else if (takingItem) { // Игрок хочет ВЗЯТЬ предмет из слота
                    if (canWithdraw) {
                        event.setCancelled(false); // Разрешаем
                    } else {
                        player.sendMessage(Utils.color("&cYou don't have permission to withdraw items."));
                        // event.setCancelled(true); // Уже true
                    }
                } else {
                    // Клик по пустому слоту с пустым курсором - разрешаем (ничего не делает)
                    event.setCancelled(false);
                }
                return; // Клик в верхнем инвентаре обработан
            }

            // Клик вне зоны (например, за пределами GUI) - остается отмененным
        }
        // TODO: Добавить обработку кликов для других GUI (если они используют event.setCancelled(true))
         /* else if (event.getView().getTitle().contains("Faction Invite")) {
              handleInviteClick(event);
         } else if (event.getView().getTitle().contains("Manage Ranks")) {
              handleRanksClick(event);
         } */
    }

    // TODO: Реализовать handleInviteClick(event) и handleRanksClick(event)
    // Они должны проверять event.getCurrentItem(), получать нужные данные
    // (например, из LocalizedName кнопки или слота ранга) и вызывать
    // соответствующие методы PlayerManager или FactionManager. Не забывать event.setCancelled(true).

} // Конец класса GuiManager