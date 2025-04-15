package org.isyateq.hfactions.managers;

import net.milkbowl.vault.economy.Economy; // Добавлен импорт Economy
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer; // Добавлен импорт OfflinePlayer
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta; // Добавлен импорт SkullMeta
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.FactionType; // Добавлен импорт FactionType
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.util.Utils;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.text.DecimalFormat; // Добавлен импорт DecimalFormat
import java.util.*;
import java.util.stream.Collectors;

public class GuiManager {

    private final HFactions plugin;
    private final PlayerManager playerManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager;
    private final Economy economy; // Добавлено поле Economy
    private final DecimalFormat moneyFormat = new DecimalFormat("#,##0.00"); // Добавлен форматтер

    // --- Ключи NBT ---
    public static final NamespacedKey INVITE_ACTION_KEY = new NamespacedKey(HFactions.getInstance(), "invite_action");
    public static final String INVITE_ACTION_ACCEPT = "accept";
    public static final String INVITE_ACTION_DECLINE = "decline";
    public static final NamespacedKey RANK_EDIT_ACTION_KEY = new NamespacedKey(HFactions.getInstance(), "rank_edit_action");
    public static final NamespacedKey WAREHOUSE_PAGE_KEY = new NamespacedKey(HFactions.getInstance(), "warehouse_page");
    public static final NamespacedKey WAREHOUSE_ACTION_KEY = new NamespacedKey(HFactions.getInstance(), "warehouse_action");
    public static final String WAREHOUSE_ACTION_PREV = "prev_page";
    public static final String WAREHOUSE_ACTION_NEXT = "next_page";
    public static final NamespacedKey FACTION_GUI_ACTION_KEY = new NamespacedKey(HFactions.getInstance(), "faction_gui_action");
    public static final String FACTION_GUI_ACTION_MEMBERS = "view_members";
    public static final String FACTION_GUI_ACTION_RANKS = "view_ranks";
    public static final String FACTION_GUI_ACTION_WAREHOUSE = "open_warehouse";
    public static final String FACTION_GUI_ACTION_BALANCE = "view_balance";
    public static final String FACTION_GUI_ACTION_TERRITORY = "view_territory";
    public static final String FACTION_GUI_ACTION_CONTRACTS = "view_contracts";
    public static final String FACTION_GUI_ACTION_MANAGE_RANKS = "manage_ranks";
    public static final String FACTION_GUI_ACTION_DEPOSIT = "deposit";
    public static final String FACTION_GUI_ACTION_WITHDRAW = "withdraw";
    public static final NamespacedKey MEMBER_LIST_ACTION_KEY = new NamespacedKey(HFactions.getInstance(), "member_list_action");
    public static final String MEMBER_LIST_ACTION_PREV = "prev_page";
    public static final String MEMBER_LIST_ACTION_NEXT = "next_page";
    public static final String MEMBER_LIST_ACTION_SORT_RANK = "sort_rank";
    public static final String MEMBER_LIST_ACTION_SORT_NAME = "sort_name";
    public static final String MEMBER_LIST_ACTION_SORT_ONLINE = "sort_online";
    public static final NamespacedKey MEMBER_LIST_PAGE_KEY = new NamespacedKey(HFactions.getInstance(), "member_list_page");
    public static final NamespacedKey MEMBER_LIST_SORT_KEY = new NamespacedKey(HFactions.getInstance(), "member_list_sort");
    public static final NamespacedKey BALANCE_LOG_ACTION_KEY = new NamespacedKey(HFactions.getInstance(), "balance_log_action");
    public static final String BALANCE_LOG_ACTION_PREV = "prev_page";
    public static final String BALANCE_LOG_ACTION_NEXT = "next_page";
    public static final String BALANCE_LOG_ACTION_DEPOSIT = FACTION_GUI_ACTION_DEPOSIT;
    public static final String BALANCE_LOG_ACTION_WITHDRAW = FACTION_GUI_ACTION_WITHDRAW;
    public static final NamespacedKey BALANCE_LOG_PAGE_KEY = new NamespacedKey(HFactions.getInstance(), "balance_log_page");

    // Отслеживание редактирования рангов
    private final Map<UUID, Integer> playersEditingRank = new HashMap<>();
    private final Map<UUID, String> playerEditingFaction = new HashMap<>();

    public GuiManager(HFactions plugin) {
        this.plugin = plugin;
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        this.economy = HFactions.getEconomy(); // Инициализируем экономику
    }

    // --- Холдеры для GUI ---
    public static class InviteGuiHolder implements InventoryHolder { private final PendingInvite invite; public InviteGuiHolder(PendingInvite invite) { this.invite = invite; } public PendingInvite getInvite() { return invite; } @Override public @NotNull Inventory getInventory() { return null; } }
    public static class RankEditGuiHolder implements InventoryHolder { private final String factionId; public RankEditGuiHolder(String factionId) { this.factionId = factionId; } public String getFactionId() { return factionId; } @Override public @NotNull Inventory getInventory() { return null; } }
    public static class WarehouseGuiHolder implements InventoryHolder { private final String factionId; private final int currentPage; private final int maxPages; public WarehouseGuiHolder(String factionId, int currentPage, int maxPages) { this.factionId = factionId; this.currentPage = currentPage; this.maxPages = maxPages; } public String getFactionId() { return factionId; } public int getCurrentPage() { return currentPage; } public int getMaxPages() { return maxPages; } @Override public @NotNull Inventory getInventory() { return null; } }
    public static class FactionMainGuiHolder implements InventoryHolder { private final String factionId; public FactionMainGuiHolder(String factionId) { this.factionId = factionId; } public String getFactionId() { return factionId; } @Override public @NotNull Inventory getInventory() { return null; } }
    public static class BalanceLogGuiHolder implements InventoryHolder { private final String factionId; private final int currentPage; private final int maxPages; public BalanceLogGuiHolder(String factionId, int currentPage, int maxPages) { this.factionId = factionId; this.currentPage = currentPage; this.maxPages = maxPages; } public String getFactionId() { return factionId; } public int getCurrentPage() { return currentPage; } public int getMaxPages() { return maxPages; } @Override public @NotNull Inventory getInventory() { return null; } }
    public static class MemberListGuiHolder implements InventoryHolder { private final String factionId; private final int currentPage; private final int maxPages; private final String currentSort; public MemberListGuiHolder(String factionId, int currentPage, int maxPages, String currentSort) { this.factionId = factionId; this.currentPage = currentPage; this.maxPages = maxPages; this.currentSort = currentSort; } public String getFactionId() { return factionId; } public int getCurrentPage() { return currentPage; } public int getMaxPages() { return maxPages; } public String getCurrentSort() { return currentSort; } @Override public @NotNull Inventory getInventory() { return null; } }
    // Вспомогательная структура для данных участника
    private record MemberData(UUID uuid, String name, boolean isOnline, int rankId, String rankName) {}
    /** Холдер для GUI Просмотра Рангов */
    public static class RankViewGuiHolder implements InventoryHolder { private final String factionId; public RankViewGuiHolder(String factionId) { this.factionId = factionId; } public String getFactionId() { return factionId; } @Override public @NotNull Inventory getInventory() { return null; } }


    // --- GUI Приглашения ---
    public void openInviteGui(Player targetPlayer, String inviterName, String factionId) {
        PendingInvite invite = playerManager.getPendingInvite(targetPlayer.getUniqueId());
        if (invite == null || !invite.getFactionId().equalsIgnoreCase(factionId)) { Utils.msg(targetPlayer, configManager.getLangMessage("invite-expired-gui", "&cПриглашение больше недействительно.")); return; }
        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) { Utils.msg(targetPlayer, configManager.getLangMessage("internal-error")); plugin.logError("Cannot open invite GUI: Faction " + factionId + " not found!"); return; }

        InviteGuiHolder holder = new InviteGuiHolder(invite);
        Inventory gui = Bukkit.createInventory(holder, 27, ChatColor.translateAlternateColorCodes('&', configManager.getLangMessage("invite-gui-title", "&1Приглашение во фракцию")));

        long timeoutSeconds = configManager.getInviteTimeoutSeconds();
        ItemStack infoItem = createGuiItem(Material.PAPER, configManager.getLangMessage("invite-gui-info-name"), Arrays.asList(configManager.getLangMessage("invite-gui-info-lore", String.valueOf(Arrays.asList("&7Игрок: &f{inviter}", "&7Фракция: {prefix_colored}{faction_name}", "", "&8Действительно {timeout} сек."))).replace("{inviter}", inviterName).replace("{prefix_colored}", faction.getFormattedPrefix()).replace("{faction_name}", faction.getName()).replace("{timeout}", String.valueOf(timeoutSeconds)).split("\n")));
        gui.setItem(13, infoItem);

        ItemStack acceptButton = createGuiItem(Material.LIME_WOOL, configManager.getLangMessage("invite-gui-accept-name"), Arrays.asList(configManager.getLangMessage("invite-gui-accept-lore", "&7Нажмите, чтобы вступить.").split("\n")));
        addItemNbt(acceptButton, INVITE_ACTION_KEY, PersistentDataType.STRING, INVITE_ACTION_ACCEPT);
        gui.setItem(11, acceptButton);

        ItemStack declineButton = createGuiItem(Material.RED_WOOL, configManager.getLangMessage("invite-gui-decline-name"), Arrays.asList(configManager.getLangMessage("invite-gui-decline-lore", "&7Нажмите, чтобы отклонить.").split("\n")));
        addItemNbt(declineButton, INVITE_ACTION_KEY, PersistentDataType.STRING, INVITE_ACTION_DECLINE);
        gui.setItem(15, declineButton);

        targetPlayer.openInventory(gui);
    }

    // --- GUI Управления Рангами (Редактирование) ---
    public void openRankManagementGui(Player player, Faction faction) {
        if (!player.hasPermission("hfactions.faction.manage_ranks") && !player.hasPermission("hfactions.admin.*")) { Utils.msg(player, configManager.getLangMessage("ranks-manage-no-perms")); return; }
        RankEditGuiHolder holder = new RankEditGuiHolder(faction.getId());
        Inventory gui = Bukkit.createInventory(holder, 27, ChatColor.translateAlternateColorCodes('&', configManager.getLangMessage("ranks-manage-gui-title", "&1Управление Рангами: &9{faction_name}").replace("{faction_name}", faction.getName())));
        ItemStack titleItem = createGuiItem(Material.BOOK, configManager.getLangMessage("ranks-manage-gui-header-name"), Arrays.asList(configManager.getLangMessage("ranks-manage-gui-header-lore", "&7ЛКМ - изменить имя\n&7ПКМ - сбросить имя").split("\n")));
        gui.setItem(4, titleItem);
        int slotIndex = 10;
        Map<Integer, FactionRank> sortedRanks = faction.getRanks().entrySet().stream().sorted(Map.Entry.comparingByKey()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
        for (Map.Entry<Integer, FactionRank> entry : sortedRanks.entrySet()) {
            if (slotIndex == 13) slotIndex++; if (slotIndex > 16 && slotIndex < 19) slotIndex = 19; if (slotIndex >= 22) break; // Layout
            int rankId = entry.getKey(); FactionRank rank = entry.getValue(); String currentName = rank.getDisplayName(); String defaultName = rank.getDefaultName(); boolean isCustom = !currentName.equals(defaultName);
            ItemStack rankItem = createGuiItem(Material.NAME_TAG, configManager.getLangMessage("ranks-manage-gui-rank-name", String.valueOf(Map.of("id", String.valueOf(rankId), "name", currentName))), Arrays.asList(configManager.getLangMessage("ranks-manage-gui-rank-lore", "&7ЛКМ - Изменить\n&7ПКМ - Сбросить к '{default_name}'\n\n{status}").replace("{default_name}", defaultName).replace("{status}", isCustom ? configManager.getLangMessage("ranks-manage-gui-rank-status-custom") : configManager.getLangMessage("ranks-manage-gui-rank-status-default")).split("\n")));
            addItemNbt(rankItem, RANK_EDIT_ACTION_KEY, PersistentDataType.INTEGER, rankId); gui.setItem(slotIndex++, rankItem);
        }
        player.openInventory(gui);
    }

    // --- GUI Просмотра Рангов ---
    public void openRankViewGui(Player player, Faction faction) {
        if (faction == null) return;
        RankViewGuiHolder holder = new RankViewGuiHolder(faction.getId());
        Inventory gui = Bukkit.createInventory(holder, 36, ChatColor.translateAlternateColorCodes('&', "&1Ранги: &9" + faction.getName())); // TODO: lang title

        int slotIndex = 0; // Начинаем с 0 слота
        Map<Integer, FactionRank> sortedRanks = faction.getRanks().entrySet().stream().sorted(Map.Entry.comparingByKey()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
        for (Map.Entry<Integer, FactionRank> entry : sortedRanks.entrySet()) {
            if (slotIndex >= 27) break; // Показываем макс 3 ряда рангов
            int rankId = entry.getKey(); FactionRank rank = entry.getValue(); Material rankMaterial = Material.EXPERIENCE_BOTTLE; String rankName = rank.getDisplayName(); double salary = rank.getSalary(); List<String> permissions = rank.getPermissions();
            List<String> lore = new ArrayList<>(); lore.add(ChatColor.GRAY + "ID: " + ChatColor.WHITE + rankId);
            if (economy != null) lore.add(ChatColor.GRAY + "Зарплата: " + ChatColor.YELLOW + moneyFormat.format(salary) + " " + economy.currencyNameShort()); else lore.add(ChatColor.GRAY + "Зарплата: " + ChatColor.DARK_GRAY + "(Эко выкл.)");
            if (permissions != null && !permissions.isEmpty()) { lore.add(ChatColor.GRAY + "Доп. права:"); for (String perm : permissions) { String shortPerm = perm.startsWith("hfactions.") ? perm.substring(10) : perm; lore.add(ChatColor.DARK_AQUA + "- " + shortPerm); } } else lore.add(ChatColor.GRAY + "Доп. права: " + ChatColor.DARK_GRAY + "Нет");
            ItemStack rankItem = createGuiItem(rankMaterial, "&b" + rankName, lore); gui.setItem(slotIndex++, rankItem);
        }

        boolean canManageRanks = player.hasPermission("hfactions.faction.manage_ranks") || player.hasPermission("hfactions.admin.*");
        if (canManageRanks) { ItemStack manageBtn = createGuiItem(Material.ANVIL, "&6Управлять Рангами", Arrays.asList("&7Нажмите, чтобы изменить", "&7названия рангов.")); addItemNbt(manageBtn, FACTION_GUI_ACTION_KEY, PersistentDataType.STRING, FACTION_GUI_ACTION_MANAGE_RANKS); gui.setItem(31, manageBtn); } // Центр нижнего ряда

        fillEmptySlots(gui, Material.GRAY_STAINED_GLASS_PANE);
        player.openInventory(gui);
    }


    // --- GUI Склада Фракции ---
    public void openWarehouseGui(Player player, Faction faction, int page) {
        if (!configManager.isWarehouseEnabled()) { Utils.msg(player, configManager.getLangMessage("warehouse-disabled")); return; }
        if (!player.hasPermission(configManager.getWarehouseOpenPermission()) && !player.hasPermission("hfactions.admin.*")) { Utils.msg(player, configManager.getLangMessage("warehouse-no-access")); return; }
        int warehouseSize = faction.getWarehouseSize(); int guiSize = 54; int itemsPerPage = guiSize - 9; int maxPages = (warehouseSize <= 0) ? 1 : (int) Math.ceil((double) warehouseSize / itemsPerPage); page = Math.max(1, Math.min(page, maxPages));
        WarehouseGuiHolder holder = new WarehouseGuiHolder(faction.getId(), page, maxPages);
        String title = ChatColor.translateAlternateColorCodes('&', configManager.getWarehouseTitleFormat().replace("{faction_name}", faction.getName()).replace("{page}", String.valueOf(page)).replace("{max_pages}", String.valueOf(maxPages)));
        Inventory gui = Bukkit.createInventory(holder, guiSize, title);
        ItemStack[] allContents = faction.getWarehouseContents(); int startIndex = (page - 1) * itemsPerPage;
        for (int guiSlot = 0; guiSlot < itemsPerPage; guiSlot++) { int warehouseIndex = startIndex + guiSlot; if (warehouseIndex < warehouseSize) gui.setItem(guiSlot, allContents[warehouseIndex]); else break; }
        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " ", null); for (int i = itemsPerPage; i < guiSize; i++) gui.setItem(i, filler);
        if (page > 1) { ItemStack btn = createGuiItem(Material.ARROW, configManager.getLangMessage("warehouse-nav-prev", "&e<< Предыдущая (Стр. {page})").replace("{page}", String.valueOf(page-1))); addItemNbt(btn, WAREHOUSE_ACTION_KEY, PersistentDataType.STRING, WAREHOUSE_ACTION_PREV); addItemNbt(btn, WAREHOUSE_PAGE_KEY, PersistentDataType.INTEGER, page - 1); gui.setItem(itemsPerPage, btn); } else { gui.setItem(itemsPerPage, createGuiItem(Material.BARRIER, configManager.getLangMessage("warehouse-nav-first"), null)); }
        if (page < maxPages) { ItemStack btn = createGuiItem(Material.ARROW, configManager.getLangMessage("warehouse-nav-next", "&eСледующая (Стр. {page}) >>").replace("{page}", String.valueOf(page+1))); addItemNbt(btn, WAREHOUSE_ACTION_KEY, PersistentDataType.STRING, WAREHOUSE_ACTION_NEXT); addItemNbt(btn, WAREHOUSE_PAGE_KEY, PersistentDataType.INTEGER, page + 1); gui.setItem(itemsPerPage + 8, btn); } else { gui.setItem(itemsPerPage + 8, createGuiItem(Material.BARRIER, configManager.getLangMessage("warehouse-nav-last"), null)); }
        gui.setItem(itemsPerPage + 4, createGuiItem(Material.PAPER, configManager.getLangMessage("warehouse-page-info", Map.of("page", String.valueOf(page), "max_pages", String.valueOf(maxPages)))));
        player.openInventory(gui);
    }
    
    // --- GUI Баланса и Лога ---
    public void openBalanceLogGui(Player player, Faction faction, int page) {
        if (economy == null) { Utils.msg(player, configManager.getLangMessage("economy-disabled")); return; }
        List<Faction.TransactionData> logEntries = faction.getTransactionLog();
        int logGuiSize = 54; int itemsPerPage = logGuiSize - 9; int totalLogs = logEntries.size(); int maxPages = (totalLogs <= 0) ? 1 : (int) Math.ceil((double) totalLogs / itemsPerPage); page = Math.max(1, Math.min(page, maxPages));
        BalanceLogGuiHolder holder = new BalanceLogGuiHolder(faction.getId(), page, maxPages);
        String title = ChatColor.translateAlternateColorCodes('&', "&1Финансы: &9" + faction.getName() + " &7(Лог: " + page + "/" + maxPages + ")"); // TODO: lang
        Inventory gui = Bukkit.createInventory(holder, logGuiSize, title);
        int startIndex = (page - 1) * itemsPerPage;
        for (int i = 0; i < itemsPerPage; i++) { int logIndex = startIndex + i; if (logIndex >= totalLogs) break; Faction.TransactionData logData = logEntries.get(logIndex); ItemStack logItem = createGuiItem(Material.PAPER, logData.formatForGui(), null); gui.setItem(i, logItem); }
        ItemStack filler = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", null); for (int i = itemsPerPage; i < logGuiSize; i++) gui.setItem(i, filler);
        if (page > 1) { ItemStack btn = createGuiItem(Material.ARROW, "&e<< Лог Назад (Стр. " + (page - 1) + ")"); addItemNbt(btn, BALANCE_LOG_ACTION_KEY, PersistentDataType.STRING, BALANCE_LOG_ACTION_PREV); addItemNbt(btn, BALANCE_LOG_PAGE_KEY, PersistentDataType.INTEGER, page - 1); gui.setItem(itemsPerPage, btn); } else { gui.setItem(itemsPerPage, createGuiItem(Material.BARRIER, "&cПервая страница лога", null)); }
        if (page < maxPages) { ItemStack btn = createGuiItem(Material.ARROW, "&eЛог Вперед (Стр. " + (page + 1) + ") >>"); addItemNbt(btn, BALANCE_LOG_ACTION_KEY, PersistentDataType.STRING, BALANCE_LOG_ACTION_NEXT); addItemNbt(btn, BALANCE_LOG_PAGE_KEY, PersistentDataType.INTEGER, page + 1); gui.setItem(itemsPerPage + 8, btn); } else { gui.setItem(itemsPerPage + 8, createGuiItem(Material.BARRIER, "&cПоследняя страница лога", null)); }
        String balanceStr = moneyFormat.format(faction.getBalance()); String currency = economy.currencyNamePlural(); ItemStack balanceInfo = createGuiItem(Material.GOLD_BLOCK, "&eТекущий Баланс", List.of("&a" + balanceStr + " " + currency)); gui.setItem(itemsPerPage + 3, balanceInfo);
        if (player.hasPermission("hfactions.faction.deposit") || player.hasPermission("hfactions.admin.*")) { ItemStack btn = createGuiItem(Material.GREEN_WOOL, "&aВнести Средства", List.of("&7Нажмите для ввода команды")); addItemNbt(btn, BALANCE_LOG_ACTION_KEY, PersistentDataType.STRING, BALANCE_LOG_ACTION_DEPOSIT); gui.setItem(itemsPerPage + 5, btn); } else { gui.setItem(itemsPerPage + 5, createGuiItem(Material.BARRIER, "&cВнести Средства", List.of("&cНет прав"))); }
        boolean canWithdraw = player.hasPermission("hfactions.faction.withdraw") || player.hasPermission("hfactions.faction.manage_balance") || player.hasPermission("hfactions.admin.*"); if (canWithdraw) { ItemStack btn = createGuiItem(Material.RED_WOOL, "&cСнять Средства", List.of("&7Нажмите для ввода команды")); addItemNbt(btn, BALANCE_LOG_ACTION_KEY, PersistentDataType.STRING, BALANCE_LOG_ACTION_WITHDRAW); gui.setItem(itemsPerPage + 6, btn); } else { gui.setItem(itemsPerPage + 6, createGuiItem(Material.BARRIER, "&cСнять Средства", List.of("&cНет прав"))); }
        player.openInventory(gui);
    }

    // --- Основное GUI Фракции (/fg) ---
    public void openFactionMainGui(Player player, Faction faction) {
        if (faction == null) { Utils.msg(player, configManager.getLangMessage("must-be-in-faction")); return; }
        FactionMainGuiHolder holder = new FactionMainGuiHolder(faction.getId());
        Inventory gui = Bukkit.createInventory(holder, 27, ChatColor.translateAlternateColorCodes('&', "&1Меню Фракции: &9" + faction.getName())); // TODO: lang title
        // Слот 10: Участники
        ItemStack membersBtn = createGuiItem(Material.PLAYER_HEAD, "&bСписок Участников", Arrays.asList("&7Просмотреть состав", "&7и их статус."));
        addItemNbt(membersBtn, FACTION_GUI_ACTION_KEY, PersistentDataType.STRING, FACTION_GUI_ACTION_MEMBERS); gui.setItem(10, membersBtn);
        // Слот 11: Ранги
        ItemStack ranksBtn = createGuiItem(Material.EXPERIENCE_BOTTLE, "&eРанги", Arrays.asList("&7Просмотреть иерархию", "&7зарплаты и права."));
        addItemNbt(ranksBtn, FACTION_GUI_ACTION_KEY, PersistentDataType.STRING, FACTION_GUI_ACTION_RANKS); gui.setItem(11, ranksBtn);
        // Слот 12: Склад
        gui.setItem(12, configManager.isWarehouseEnabled() ? addItemNbt(createGuiItem(Material.CHEST, "&6Склад Фракции", List.of("&7Открыть склад.")), FACTION_GUI_ACTION_KEY, PersistentDataType.STRING, FACTION_GUI_ACTION_WAREHOUSE) : createGuiItem(Material.BARRIER, "&cСклад", List.of("&cОтключено")));
        // Слот 13: Баланс
        gui.setItem(13, economy != null ? addItemNbt(createGuiItem(Material.GOLD_INGOT, "&aБаланс Фракции", List.of("&7Баланс: &e" + moneyFormat.format(faction.getBalance()), "&7Нажмите для управления.")), FACTION_GUI_ACTION_KEY, PersistentDataType.STRING, FACTION_GUI_ACTION_BALANCE) : createGuiItem(Material.BARRIER, "&cБаланс", List.of("&cЭко откл.")));
        // Слот 14: Территория
        boolean canViewTerritory = configManager.isDynmapEnabled(); // Убрали проверку на криминал
        if (canViewTerritory) { ItemStack btn = createGuiItem(Material.FILLED_MAP, "&cТерритория", Arrays.asList("&7Открыть карту", "&7территорий на Dynmap.")); addItemNbt(btn, FACTION_GUI_ACTION_KEY, PersistentDataType.STRING, FACTION_GUI_ACTION_TERRITORY); gui.setItem(14, btn); }
        else { gui.setItem(14, createGuiItem(Material.MAP, "&cТерритория", List.of("&cDynmap отключен"))); }
        // Слот 15: Контракты (Заглушка)
        ItemStack contractsBtn = createGuiItem(Material.WRITABLE_BOOK, "&dКонтракты", Arrays.asList("&7Система контрактов", "&7в разработке!")); gui.setItem(15, contractsBtn);
        player.openInventory(gui);
    }

    // --- Сохранение склада ---
    public void saveWarehouseFromGui(ItemStack[] guiContents, WarehouseGuiHolder holder) {
        Faction faction = factionManager.getFaction(holder.getFactionId()); if (faction == null) return;
        ItemStack[] allContents = faction.getWarehouseContents(); int itemsPerPage = 54 - 9; int page = holder.getCurrentPage(); int startIndex = (page - 1) * itemsPerPage; boolean changed = false;
        for (int guiSlot = 0; guiSlot < itemsPerPage; guiSlot++) { int warehouseIndex = startIndex + guiSlot; if (warehouseIndex < faction.getWarehouseSize()) { ItemStack guiItem = (guiSlot < guiContents.length) ? guiContents[guiSlot] : null; if (!Objects.equals(guiItem, allContents[warehouseIndex])) { allContents[warehouseIndex] = guiItem; changed = true; } } }
        if (changed) { faction.setWarehouseContents(allContents); factionManager.saveFactionsConfig(); }
    }

    // --- Логика Редактирования Имени Ранга ---
    public boolean isPlayerEditingRank(UUID playerUuid) { return playersEditingRank.containsKey(playerUuid); }
    public void startRankNameEdit(Player player, String factionId, int rankId) { playersEditingRank.put(player.getUniqueId(), rankId); playerEditingFaction.put(player.getUniqueId(), factionId); player.closeInventory(); Utils.msg(player, configManager.getLangMessage("ranks-edit-prompt", Map.of("id", String.valueOf(rankId)))); Utils.msg(player, configManager.getLangMessage("ranks-edit-cancel-prompt")); }
    public void resetRankName(Player player, Faction faction, int rankId) { if (faction == null) return; FactionRank rank = faction.getRank(rankId); if (rank == null) return; String defaultName = rank.getDefaultName(); if (rank.getDisplayName().equals(defaultName)) { Utils.msg(player, configManager.getLangMessage("ranks-reset-already-default")); return; } rank.setDisplayName(defaultName); factionManager.saveFactionsConfig(); Utils.msg(player, configManager.getLangMessage("ranks-reset-success", Map.of("id", String.valueOf(rankId), "name", defaultName))); }
    public void handleRankNameInput(Player player, String input) { UUID playerUuid = player.getUniqueId(); if (!isPlayerEditingRank(playerUuid)) return; Integer rankId = playersEditingRank.get(playerUuid); String factionId = playerEditingFaction.get(playerUuid); playersEditingRank.remove(playerUuid); playerEditingFaction.remove(playerUuid); if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("отмена")) { Utils.msg(player, configManager.getLangMessage("ranks-edit-cancelled")); return; } Faction faction = factionManager.getFaction(factionId); if (faction == null) { Utils.msg(player, configManager.getLangMessage("internal-error")); return; } FactionRank rank = faction.getRank(rankId); if (rank == null) { Utils.msg(player, configManager.getLangMessage("internal-error")); return; } String newName = ChatColor.stripColor(input.trim()); if (newName.isEmpty()) { Utils.msg(player, configManager.getLangMessage("ranks-edit-name-empty")); return; } if (newName.length() > 32) { Utils.msg(player, configManager.getLangMessage("ranks-edit-name-long")); return; } rank.setDisplayName(newName); factionManager.saveFactionsConfig(); Utils.msg(player, configManager.getLangMessage("ranks-edit-success", Map.of("id", String.valueOf(rankId), "name", newName))); }

    // --- Вспомогательные методы ---
    private ItemStack createGuiItem(Material material, String name, @Nullable List<String> lore) { ItemStack item = new ItemStack(material, 1); ItemMeta meta = item.getItemMeta(); if (meta != null) { if (name != null) meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name)); if (lore != null && !lore.isEmpty()) { meta.setLore(lore.stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).collect(Collectors.toList())); } item.setItemMeta(meta); } return item; }
    private <T, Z> ItemStack addItemNbt(ItemStack item, NamespacedKey key, PersistentDataType<T, Z> type, Z value) { if (item == null || item.getType() == Material.AIR) return item; ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.getPersistentDataContainer().set(key, type, value); item.setItemMeta(meta); } return item; }
    private void fillEmptySlots(Inventory inventory, Material material) { ItemStack filler = createGuiItem(material, " ", null); for (int i = 0; i < inventory.getSize(); i++) { if (inventory.getItem(i) == null) { inventory.setItem(i, filler); } } }
}