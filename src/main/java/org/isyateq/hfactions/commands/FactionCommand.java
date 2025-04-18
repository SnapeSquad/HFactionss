package org.isyateq.hfactions.commands;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta; // Добавлен импорт
import org.isyateq.hfactions.*;
import org.isyateq.hfactions.managers.*;
import org.isyateq.hfactions.managers.CraftingManager;
import org.isyateq.hfactions.managers.DynmapManager;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.util.Utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit; // Добавлен импорт
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class FactionCommand implements CommandExecutor, TabCompleter {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final FactionManager factionManager;
    private final PlayerManager playerManager;
    private final ItemManager itemManager;
    private final CuffManager cuffManager;
    private final GuiManager guiManager;
    private final CraftingManager craftingManager;
    private final DynmapManager dynmapManager;
    private final Economy economy;

    private final DecimalFormat moneyFormat = new DecimalFormat("#,##0.00");

    // Структуры для штрафа
    private static class PendingFine {
        final UUID officerUuid; final UUID targetUuid; String state = "amount"; double amount = 0.0;
        PendingFine(UUID officer, UUID target) { this.officerUuid = officer; this.targetUuid = target; }
    }
    private final Map<UUID, PendingFine> playersIssuingFine = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> fineGlobalCooldowns = new HashMap<>();
    private final Map<UUID, Map<UUID, Instant>> fineTargetCooldowns = new HashMap<>();

    // Углы территории
    private final Map<UUID, List<Location>> territoryCorners = new HashMap<>();

    public FactionCommand(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.factionManager = plugin.getFactionManager();
        this.playerManager = plugin.getPlayerManager();
        this.itemManager = plugin.getItemManager();
        this.cuffManager = plugin.getCuffManager();
        this.guiManager = plugin.getGuiManager();
        this.craftingManager = plugin.getCraftingManager();
        this.dynmapManager = plugin.getDynmapManager();
        this.economy = HFactions.getEconomy();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        plugin.logInfo("[DEBUG] FactionCommand.onCommand by " + sender.getName() + " label '" + label + "' args: " + String.join(" ", args));

        String subCommand = args.length > 0 ? args[0].toLowerCase() : "";
        final String commandLabel = command.getLabel().toLowerCase();

        // Обработка алиасов
        if (configManager.isFactionChatEnabled() && configManager.getFactionChatAliases().contains(commandLabel)) { plugin.logInfo("[DEBUG] Executing as chat alias '" + commandLabel + "'"); return toggleFactionChatCommand(sender); }
        if (configManager.isFiningEnabled() && configManager.getFineCommandAliases().contains(commandLabel)) { plugin.logInfo("[DEBUG] Executing as fine alias '" + commandLabel + "'"); String[] newArgs = new String[args.length + 1]; newArgs[0] = "fine"; System.arraycopy(args, 0, newArgs, 1, args.length); args = newArgs; subCommand = "fine"; }

        final String[] finalArgs = args;

        if (finalArgs.length == 0) { plugin.logInfo("[DEBUG] No subcommand by " + sender.getName()); if (sender instanceof Player) return showFactionInfo(sender, null); else return showHelp(sender); }

        if (subCommand.equals("territory")) { plugin.logInfo("[DEBUG] Executing 'territory' main command for " + sender.getName()); if (finalArgs.length == 1) return usage(sender, commandLabel + " territory <subcommand> [args]"); final String territorySubCommand = finalArgs[1].toLowerCase(); final String[] territoryArgs = Arrays.copyOfRange(finalArgs, 2, finalArgs.length); return handleTerritoryCommand(sender, territorySubCommand, territoryArgs); }

        if (!(sender instanceof Player) && needsPlayer(subCommand)) { plugin.logInfo("[DEBUG] Command '" + subCommand + "' needs player."); return requiresPlayer(sender); }
        if (economy == null && needsEconomy(subCommand)) { plugin.logInfo("[DEBUG] Command '" + subCommand + "' needs economy."); Utils.msg(sender, configManager.getLangMessage("economy-disabled", "&cЭкономическая система неактивна.")); return true; } // Исправлено

        plugin.logInfo("[DEBUG] Processing subcommand '" + subCommand + "' for " + sender.getName());
        switch (subCommand) {
            case "help": return checkPermissionAndExecute(sender, "hfactions.command.help", () -> showHelp(sender));
            case "list": return checkPermissionAndExecute(sender, "hfactions.command.list", () -> listFactions(sender));
            case "info": final String targetFactionIdInfo = (finalArgs.length > 1) ? finalArgs[1] : null; return checkPermissionAndExecute(sender, "hfactions.command.info", () -> showFactionInfo(sender, targetFactionIdInfo));
            case "listrecipes": return checkPermissionAndExecute(sender, "hfactions.command.listrecipes", () -> listCustomRecipes(sender));
            case "leave": return checkPermissionAndExecute(sender, "hfactions.player.leave", () -> leaveFaction(sender));
            case "invite": if (finalArgs.length < 2) return usage(sender, commandLabel + " invite <ник>"); final String targetInvite = finalArgs[1]; return invitePlayer(sender, targetInvite);
            case "kick": if (finalArgs.length < 2) return usage(sender, commandLabel + " kick <ник>"); final String targetKick = finalArgs[1]; return kickPlayer(sender, targetKick);
            case "promote": if (finalArgs.length < 2) return usage(sender, commandLabel + " promote <ник>"); final String targetPromote = finalArgs[1]; return promotePlayer(sender, targetPromote);
            case "demote": if (finalArgs.length < 2) return usage(sender, commandLabel + " demote <ник>"); final String targetDemote = finalArgs[1]; return demotePlayer(sender, targetDemote);
            case "setrank": if (finalArgs.length < 3) return usage(sender, commandLabel + " setrank <ник> <id>"); final String targetSetRank = finalArgs[1]; final String rankIdStrSetRank = finalArgs[2]; return setPlayerRankCommand(sender, targetSetRank, rankIdStrSetRank);
            case "manageranks": return manageRanksGui(sender);
            case "balance": case "bal": return viewBalance(sender);
            case "deposit": case "dep": if (finalArgs.length < 2) return usage(sender, commandLabel + " deposit <сумма>"); final String amountStrDep = finalArgs[1]; return depositMoney(sender, amountStrDep);
            case "withdraw": case "wd": if (finalArgs.length < 2) return usage(sender, commandLabel + " withdraw <сумма>"); final String amountStrWd = finalArgs[1]; return withdrawMoney(sender, amountStrWd);
            case "chat": case "c": return toggleFactionChatCommand(sender);
            case "warehouse": case "wh": return openWarehouse(sender);
            case "fine": if (!configManager.isFiningEnabled()) { Utils.msg(sender, configManager.getLangMessage("fine-disabled", "&cСистема штрафов отключена.")); return true; } if (finalArgs.length < 4) return usage(sender, commandLabel + " fine <ник> <сумма> <причина>"); final String targetFine = finalArgs[1]; final String amountStrFine = finalArgs[2]; final String reasonFine = String.join(" ", Arrays.copyOfRange(finalArgs, 3, finalArgs.length)); return issueFine(sender, targetFine, amountStrFine, reasonFine);
            case "uncuff": if (finalArgs.length < 2) return usage(sender, commandLabel + " uncuff <ник>"); final String targetUncuff = finalArgs[1]; return checkPermissionAndExecute(sender, "hfactions.admin.uncuff", () -> uncuffCommand(sender, targetUncuff));
            case "create": if (finalArgs.length < 4) return usage(sender, commandLabel + " create <id> \"<Назв>\" <тип>"); final String nameCreate = parseQuotedString(finalArgs, 2); final int nameEndIdxCreate = findQuotedStringEndIndex(finalArgs, 2); if (nameCreate == null || nameEndIdxCreate == -1 || finalArgs.length <= nameEndIdxCreate) return usage(sender, commandLabel + " create <id> \"<Назв>\" <тип> (Название!)"); final String typeStrCreate = finalArgs[nameEndIdxCreate]; final String idCreate = finalArgs[1]; return checkPermissionAndExecute(sender, "hfactions.admin.create", () -> createFaction(sender, idCreate, nameCreate, typeStrCreate));
            case "delete": if (finalArgs.length < 2) return usage(sender, commandLabel + " delete <id>"); final String deleteId = finalArgs[1]; return checkPermissionAndExecute(sender, "hfactions.admin.delete", () -> deleteFaction(sender, deleteId));
            case "reload": return checkPermissionAndExecute(sender, "hfactions.admin.reload", () -> reloadPlugin(sender));
            case "setbalance": if (finalArgs.length < 3) return usage(sender, commandLabel + " setbalance <id> <сумма>"); final String sbFactionId = finalArgs[1]; final String sbAmountStr = finalArgs[2]; return checkPermissionAndExecute(sender, "hfactions.admin.setbalance", () -> setBalanceAdmin(sender, sbFactionId, sbAmountStr));
            case "adminmode": final String targetFactionIdAdminMode = (finalArgs.length > 1) ? finalArgs[1] : null; return checkPermissionAndExecute(sender, "hfactions.admin.adminmode", () -> toggleAdminMode(sender, targetFactionIdAdminMode));
            case "givetaser": final String targetGiveTaser = (finalArgs.length > 1) ? finalArgs[1] : null; return giveItem(sender, "hfactions.admin.givetaser", itemManager::getTaserItem, "Тайзер", targetGiveTaser);
            case "givehandcuffs": final String targetGiveCuffs = (finalArgs.length > 1) ? finalArgs[1] : null; return giveItem(sender, "hfactions.admin.givehandcuffs", itemManager::getHandcuffsItem, "Наручники", targetGiveCuffs);
            case "giveprotocol": final String targetGiveProto = (finalArgs.length > 1) ? finalArgs[1] : null; return giveItem(sender, "hfactions.admin.giveprotocol", itemManager::getProtocolItem, "Протокол", targetGiveProto);
            // --- ИСПРАВЛЕННЫЙ ВЫЗОВ GETLANGMESSAGE ---
            default: plugin.logInfo("[DEBUG] Unknown subcommand '" + subCommand + "' by " + sender.getName()); Utils.msg(sender, configManager.getLangMessage("unknown-command", Map.of("command", commandLabel), "&cНеизвестная команда. /" + commandLabel + " help")); return true;
        }
    }

    // --- Обработчик подкоманд /hf territory ---
    private boolean handleTerritoryCommand(CommandSender sender, String subCommand, final String[] args) {
        plugin.logInfo("[DEBUG] Executing 'territory' subcommand '" + subCommand + "' for " + sender.getName());
        // --- ИСПРАВЛЕННЫЕ ВЫЗОВЫ GETLANGMESSAGE ---
        if (!configManager.isDynmapEnabled()) { Utils.msg(sender, configManager.getLangMessage("territory-dynmap-disabled", "&cИнтеграция с Dynmap отключена.")); return true; }
        if (dynmapManager == null) { Utils.msg(sender, configManager.getLangMessage("territory-dynmap-error", "&cОшибка интеграции с Dynmap.")); plugin.logError("DynmapManager is null!"); return true; }
        boolean isAdmin = sender.hasPermission("hfactions.admin.territory") || sender.hasPermission("hfactions.admin.*"); boolean isLeader = isLeader(sender); Faction senderFaction = (sender instanceof Player) ? playerManager.getPlayerFaction(((Player)sender).getUniqueId()) : null;
        switch (subCommand) {
            case "list": final String listFactionId = (args.length > 0) ? args[0] : (isLeader && senderFaction != null ? senderFaction.getId() : null); return listTerritories(sender, listFactionId);
            case "define": if (!isAdmin && !isLeader) return noPermissionTerritory(sender); if (!(sender instanceof Player)) return requiresPlayerTerritory(sender); if (args.length < 1) return usage(sender, "hf territory define <название_зоны>"); final String defineZoneName = args[0]; return startDefiningTerritory(sender, defineZoneName);
            case "corner": if (!isAdmin && !isLeader) return noPermissionTerritory(sender); if (!(sender instanceof Player)) return requiresPlayerTerritory(sender); return addTerritoryCorner(sender);
            case "clear": if (!isAdmin && !isLeader) return noPermissionTerritory(sender); if (!(sender instanceof Player)) return requiresPlayerTerritory(sender); return clearTerritoryCorners(sender);
            case "claim":
                if (!isAdmin && !isLeader) return noPermissionTerritory(sender); if (!(sender instanceof Player)) return requiresPlayerTerritory(sender); if (args.length < 1) return usage(sender, "hf territory claim <название_зоны> [id_фракции (админ)]");
                final String claimZoneName = args[0]; String tempClaimFactionId = null; if (isLeader && senderFaction != null) tempClaimFactionId = senderFaction.getId(); if (isAdmin && args.length >= 2) tempClaimFactionId = args[1];
                final String claimFactionId = tempClaimFactionId;
                if (claimFactionId == null) { Utils.msg(sender, configManager.getLangMessage("territory-claim-no-faction-admin", "&cУкажите ID фракции (админ).")); return true; } return claimTerritory(sender, claimZoneName, claimFactionId);
            case "delete":
                if (!isAdmin && !isLeader) return noPermissionTerritory(sender); if (args.length < 1) return usage(sender, "hf territory delete <название_зоны>");
                final String deleteZoneName = args[0]; final Faction finalSenderFaction = senderFaction; final boolean finalIsLeader = isLeader;
                return deleteTerritory(sender, deleteZoneName, finalSenderFaction, finalIsLeader);
            case "map": if (!isAdmin) return noPermissionTerritory(sender); return updateMap(sender);
            case "help": return showTerritoryHelp(sender);
            // --- ИСПРАВЛЕННЫЙ ВЫЗОВ GETLANGMESSAGE ---
            default: Utils.msg(sender, configManager.getLangMessage("territory-unknown-subcommand", "&cНеизвестная подкоманда территории. /hf territory help")); return true;
        }
    }


    // --- Реализация подкоманд ---

    private boolean showHelp(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running showHelp for " + sender.getName());
        // --- ИСПРАВЛЕННЫЕ ВЫЗОВЫ GETLANGMESSAGE ---
        Utils.msg(sender, configManager.getLangMessage("help-header", "&b--- HFactions Помощь ---"));
        String helpFormat = configManager.getLangMessage("help-format", "&7{command} - {description}");
        if (sender.hasPermission("hfactions.command.list")) Utils.msg(sender, helpFormat.replace("{command}", "/hf list").replace("{description}", "Список фракций"));
        if (sender.hasPermission("hfactions.command.info")) Utils.msg(sender, helpFormat.replace("{command}", "/hf info [id]").replace("{description}", "Инфо о фракции"));
        if (sender.hasPermission("hfactions.command.listrecipes")) Utils.msg(sender, helpFormat.replace("{command}", "/hf listrecipes").replace("{description}", "Доступные крафты"));
        if (sender instanceof Player && sender.hasPermission("hfactions.player.leave")) Utils.msg(sender, helpFormat.replace("{command}", "/hf leave").replace("{description}", "Покинуть фракцию"));
        if (sender instanceof Player && sender.hasPermission("hfactions.faction.invite")) Utils.msg(sender, helpFormat.replace("{command}", "/hf invite <ник>").replace("{description}", "Пригласить"));
        if (sender instanceof Player && sender.hasPermission("hfactions.faction.kick")) Utils.msg(sender, helpFormat.replace("{command}", "/hf kick <ник>").replace("{description}", "Исключить"));
        if (sender instanceof Player && sender.hasPermission("hfactions.faction.promote")) Utils.msg(sender, helpFormat.replace("{command}", "/hf promote <ник>").replace("{description}", "Повысить"));
        if (sender instanceof Player && sender.hasPermission("hfactions.faction.demote")) Utils.msg(sender, helpFormat.replace("{command}", "/hf demote <ник>").replace("{description}", "Понизить"));
        if (sender instanceof Player && sender.hasPermission("hfactions.faction.setrank")) Utils.msg(sender, helpFormat.replace("{command}", "/hf setrank <ник> <id>").replace("{description}", "Установить ранг"));
        if (sender instanceof Player && sender.hasPermission("hfactions.faction.manage_ranks")) Utils.msg(sender, helpFormat.replace("{command}", "/hf manageranks").replace("{description}", "Управление рангами"));
        if (configManager.isWarehouseEnabled() && sender.hasPermission(configManager.getWarehouseOpenPermission())) Utils.msg(sender, helpFormat.replace("{command}", "/hf warehouse (wh)").replace("{description}", "Склад фракции"));
        if (economy != null) {
            if (sender instanceof Player && sender.hasPermission("hfactions.faction.balance.view")) Utils.msg(sender, helpFormat.replace("{command}", "/hf balance (bal)").replace("{description}", "Баланс фракции"));
            if (sender instanceof Player && sender.hasPermission("hfactions.faction.deposit")) Utils.msg(sender, helpFormat.replace("{command}", "/hf deposit <сумма>").replace("{description}", "Внести"));
            if (sender instanceof Player && sender.hasPermission("hfactions.faction.withdraw")) Utils.msg(sender, helpFormat.replace("{command}", "/hf withdraw <сумма>").replace("{description}", "Снять"));
        }
        if (configManager.isFactionChatEnabled()) Utils.msg(sender, helpFormat.replace("{command}", "/hf chat (c, fc)").replace("{description}", "Фракционный чат"));
        if (configManager.isFiningEnabled() && sender.hasPermission(configManager.getFinePermissionNode())) Utils.msg(sender, helpFormat.replace("{command}", "/hf fine <ник> <сумма> <причина>").replace("{description}", "Штраф (PD)"));
        if (configManager.isDynmapEnabled() && (sender.hasPermission("hfactions.admin.territory") || isLeader(sender))) Utils.msg(sender, helpFormat.replace("{command}", "/hf territory help").replace("{description}", "Команды территорий"));
        if (sender.hasPermission("hfactions.admin.uncuff")) Utils.msg(sender, helpFormat.replace("{command}", "/hf uncuff <ник>").replace("{description}", "Снять наручники (админ)"));
        if (sender.hasPermission("hfactions.admin.create")) Utils.msg(sender, helpFormat.replace("{command}", "/hf create <id> \"<Назв>\" <тип>").replace("{description}", "Создать фракцию"));
        if (sender.hasPermission("hfactions.admin.delete")) Utils.msg(sender, helpFormat.replace("{command}", "/hf delete <id>").replace("{description}", "Удалить фракцию"));
        if (sender.hasPermission("hfactions.admin.reload")) Utils.msg(sender, helpFormat.replace("{command}", "/hf reload").replace("{description}", "Перезагрузить конфиги"));
        if (sender.hasPermission("hfactions.admin.setbalance")) Utils.msg(sender, helpFormat.replace("{command}", "/hf setbalance <id> <сумма>").replace("{description}", "Установить баланс"));
        if (sender.hasPermission("hfactions.admin.adminmode")) Utils.msg(sender, helpFormat.replace("{command}", "/hf adminmode [id]").replace("{description}", "Админ режим фракции"));
        return true;
    }

    private boolean listFactions(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running listFactions for " + sender.getName());
        Collection<Faction> factions = factionManager.getAllFactions();
        // --- ИСПРАВЛЕННЫЙ ВЫЗОВ GETLANGMESSAGE ---
        if (factions.isEmpty()) { Utils.msg(sender, configManager.getLangMessage("list-empty", "&7На сервере пока нет фракций.")); return true; }
        Utils.msg(sender, configManager.getLangMessage("list-header", "&b--- Список Фракций ---"));
        for (Faction faction : factions) {
            List<UUID> members = factionManager.getFactionMembers(faction.getId());
            UUID leaderUUID = members.stream().filter(uuid -> Integer.valueOf(11).equals(playerManager.getPlayerRankId(uuid))).findFirst().orElse(null);
            // --- ИСПРАВЛЕННЫЙ ВЫЗОВ GETLANGMESSAGE ---
            String leaderName = configManager.getLangMessage("list-leader-none", "Нет");
            if (leaderUUID != null) { OfflinePlayer lo = Bukkit.getOfflinePlayer(leaderUUID); leaderName = lo.getName() != null ? lo.getName() : "???"; }
            // --- ИСПРАВЛЕННЫЙ ВЫЗОВ GETLANGMESSAGE ---
            Utils.msg(sender, configManager.getLangMessage("list-entry", Map.of("prefix_colored", faction.getFormattedPrefix().trim(), "name", faction.getName(), "id", faction.getId(), "members", String.valueOf(members.size()), "leader", leaderName), "{prefix_colored} {name} &7({id}): &7Участников: {members}, Лидер: {leader}"));
        }
        return true;
    }

    private boolean showFactionInfo(CommandSender sender, String targetFactionId) {
        plugin.logInfo("[DEBUG] Running showFactionInfo for " + sender.getName() + (targetFactionId != null ? " target: " + targetFactionId : " (self)"));
        Faction faction; boolean viewingOwn = false;
        if (targetFactionId == null) {
            if (!(sender instanceof Player)) return usage(sender, "hf info <id_фракции>");
            Player player = (Player) sender; faction = playerManager.getPlayerFaction(player.getUniqueId());
            if (faction == null) { Utils.msg(sender, configManager.getLangMessage("must-be-in-faction", "&cВы не состоите во фракции.")); return true; } viewingOwn = true;
        } else {
            faction = factionManager.getFaction(targetFactionId);
            if (faction == null) { Utils.msg(sender, configManager.getLangMessage("faction-not-found", Map.of("id", targetFactionId), "&cФракция с ID '{id}' не найдена.")); return true; }
            if (sender instanceof Player) viewingOwn = faction.equals(playerManager.getPlayerFaction(((Player) sender).getUniqueId()));
        }
        Utils.msg(sender, configManager.getLangMessage("info-header", Map.of("highlight", configManager.getHighlightColor(), "faction_name", faction.getName()), "&b--- Инфо: {highlight}{faction_name}&b ---"));
        Utils.msg(sender, configManager.getLangMessage("info-id", Map.of("highlight", configManager.getHighlightColor(), "id", faction.getId()), "&7ID: {highlight}{id}"));
        Utils.msg(sender, configManager.getLangMessage("info-type", Map.of("highlight", configManager.getHighlightColor(), "type", faction.getType().name()), "&7Тип: {highlight}{type}"));
        Utils.msg(sender, configManager.getLangMessage("info-prefix", Map.of("prefix_colored", faction.getFormattedPrefix().trim()), "&7Префикс: {prefix_colored}"));
        List<UUID> members = factionManager.getFactionMembers(faction.getId());
        Utils.msg(sender, configManager.getLangMessage("info-members", Map.of("highlight", configManager.getHighlightColor(), "count", String.valueOf(members.size())), "&7Участников: {highlight}{count}"));
        UUID leaderUUID = members.stream().filter(uuid -> Integer.valueOf(11).equals(playerManager.getPlayerRankId(uuid))).findFirst().orElse(null);
        String leaderName = configManager.getLangMessage("list-leader-none", "Нет"); if (leaderUUID != null) { OfflinePlayer lo = Bukkit.getOfflinePlayer(leaderUUID); leaderName = lo.getName() != null ? lo.getName() : "???"; }
        Utils.msg(sender, configManager.getLangMessage("info-leader", Map.of("highlight", configManager.getHighlightColor(), "name", leaderName), "&7Лидер: {highlight}{name}"));
        if (economy != null && (viewingOwn || sender.hasPermission("hfactions.admin.*"))) { Utils.msg(sender, configManager.getLangMessage("info-balance", Map.of("highlight", configManager.getHighlightColor(), "amount", moneyFormat.format(faction.getBalance()), "currency", economy.currencyNamePlural()), "&7Баланс: {highlight}{amount} {currency}")); }
        if (viewingOwn && sender instanceof Player) { Player player = (Player) sender; Integer rankId = playerManager.getPlayerRankId(player.getUniqueId()); if (rankId != null) { FactionRank rank = faction.getRank(rankId); if (rank != null) { Utils.msg(sender, configManager.getLangMessage("info-your-rank", Map.of("highlight", configManager.getHighlightColor(), "rank_name", rank.getDisplayName(), "rank_id", String.valueOf(rankId)), "&7Ваш ранг: {highlight}{rank_name} ({rank_id})")); } } }
        return true;
    }

    private boolean listCustomRecipes(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running listCustomRecipes for " + sender.getName());
        Map<String, CustomCraftingRecipe> recipes = craftingManager.getCustomRecipes();
        if (recipes.isEmpty()) { Utils.msg(sender, configManager.getLangMessage("recipes-empty", "&7Нет доступных кастомных рецептов.")); return true; }
        Utils.msg(sender, configManager.getLangMessage("recipes-header", "&b--- Кастомные Рецепты HFactions ---"));
        boolean isPlayer = sender instanceof Player; Player player = isPlayer ? (Player) sender : null;
        for (CustomCraftingRecipe customRecipe : recipes.values()) {
            StringBuilder details = new StringBuilder(); ItemStack result = customRecipe.resultItem; String resultName;
            if (result.hasItemMeta()) { ItemMeta resultMeta = result.getItemMeta(); if (resultMeta != null && resultMeta.hasDisplayName() && !resultMeta.getDisplayName().isEmpty()) resultName = resultMeta.getDisplayName(); else resultName = configManager.getHighlightColor() + result.getType().name().toLowerCase().replace('_', ' '); } else resultName = configManager.getHighlightColor() + result.getType().name().toLowerCase().replace('_', ' ');
            details.append(ChatColor.translateAlternateColorCodes('&', resultName));
            List<String> requirements = new ArrayList<>(); boolean playerCanCraftThis = true;
            if (customRecipe.permissionRequired) { if (customRecipe.permissionNode == null || customRecipe.permissionNode.isEmpty()) { requirements.add(configManager.getLangMessage("recipes-config-error-perm", "&cПраво не задано!")); playerCanCraftThis = false; } else { boolean hasPerm = isPlayer && player.hasPermission(customRecipe.permissionNode); requirements.add(configManager.getLangMessage("recipes-requirement-perm", Map.of("status_color", hasPerm ? configManager.getSuccessColor() : configManager.getErrorColor(), "permission", customRecipe.permissionNode), "{status_color}Право: {permission}")); if (!hasPerm) playerCanCraftThis = false; } }
            if (!customRecipe.allowedFactionIds.isEmpty()) { Faction playerFaction = isPlayer ? playerManager.getPlayerFaction(player.getUniqueId()) : null; boolean inAllowedFaction = playerFaction != null && customRecipe.allowedFactionIds.contains(playerFaction.getId()); String factionList = String.join(", ", customRecipe.allowedFactionIds).toUpperCase(); requirements.add(configManager.getLangMessage("recipes-requirement-faction", Map.of("status_color", inAllowedFaction ? configManager.getSuccessColor() : configManager.getErrorColor(), "factions", factionList), "{status_color}Фракции: {factions}")); if (!inAllowedFaction || (isPlayer && playerFaction == null)) playerCanCraftThis = false; }
            if (!requirements.isEmpty()) { String reqString = String.join(configManager.getLangMessage("recipes-req-separator", "&7, "), requirements); String prefix = configManager.getLangMessage("recipes-requirements-prefix", Map.of("status_color", isPlayer ? (playerCanCraftThis ? configManager.getSuccessColor() : configManager.getErrorColor()) : configManager.getSecondaryColor()), " {status_color}[&fТребования: "); String suffix = configManager.getLangMessage("recipes-requirements-suffix", Map.of("status_color", isPlayer ? (playerCanCraftThis ? configManager.getSuccessColor() : configManager.getErrorColor()) : configManager.getSecondaryColor()), "{status_color}]"); details.append(" ").append(prefix).append(reqString).append(suffix); }
            else { details.append(configManager.getLangMessage("recipes-available-all", " &a[Доступно всем]")); }
            Utils.msg(sender, details.toString());
        }
        return true;
    }

    private boolean leaveFaction(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running leaveFaction for " + sender.getName());
        Player player = (Player) sender; Faction faction = playerManager.getPlayerFaction(player.getUniqueId());
        if (faction == null) { Utils.msg(player, configManager.getLangMessage("must-be-in-faction")); return true; }
        if (isLeader(sender)) { Utils.msg(player, configManager.getLangMessage("leave-leader")); return true; }
        if (playerManager.setPlayerFactionAndRank(player.getUniqueId(), null, 0)) { Utils.msg(player, configManager.getLangMessage("leave-success", String.valueOf(Map.of("highlight", configManager.getHighlightColor(), "faction_name", faction.getName())))); }
        else { Utils.msg(player, configManager.getLangMessage("leave-fail")); }
        return true;
    }

    private boolean invitePlayer(CommandSender sender, String targetPlayerName) {
        plugin.logInfo("[DEBUG] Running invitePlayer for " + sender.getName() + " target: " + targetPlayerName);
        Player inviter = (Player) sender; EffectiveFactionData effData = getEffectiveFactionData(inviter);
        if (!effData.isInFaction()) { Utils.msg(inviter, configManager.getLangMessage("invite-no-faction")); return true; }
        if (!hasRankPermission(inviter, effData.faction(), effData.rankId(), "hfactions.faction.invite") && !inviter.hasPermission("hfactions.admin.*")) return noPermission(inviter);
        Player targetPlayer = Bukkit.getPlayerExact(targetPlayerName); if (targetPlayer == null) { Utils.msg(inviter, configManager.getLangMessage("player-not-found", String.valueOf(Map.of("player", targetPlayerName)))); return true; }
        if (inviter.getUniqueId().equals(targetPlayer.getUniqueId())) { Utils.msg(inviter, configManager.getLangMessage("cannot-do-self")); return true; }
        if (playerManager.getPlayerFaction(targetPlayer.getUniqueId()) != null) { Utils.msg(inviter, configManager.getLangMessage("target-already-in-faction", String.valueOf(Map.of("player", targetPlayer.getName())))); return true; }
        if (playerManager.hasPendingInvite(targetPlayer.getUniqueId())) { Utils.msg(inviter, configManager.getLangMessage("invite-already-pending", String.valueOf(Map.of("player", targetPlayer.getName())))); return true; }
        if (playerManager.createInvite(targetPlayer.getUniqueId(), inviter.getUniqueId(), effData.faction().getId())) { Utils.msg(inviter, configManager.getLangMessage("invite-success-sender", String.valueOf(Map.of("highlight", configManager.getHighlightColor(), "player", targetPlayer.getName(), "faction_name", effData.faction().getName())))); Utils.msg(targetPlayer, configManager.getLangMessage("invite-success-target-header")); Utils.msg(targetPlayer, configManager.getLangMessage("invite-success-target-body", Map.of("highlight", configManager.getHighlightColor(), "inviter", inviter.getName(), "faction_name", effData.faction().getName()))); Utils.msg(targetPlayer, configManager.getLangMessage("invite-success-target-header")); guiManager.openInviteGui(targetPlayer, inviter.getName(), effData.faction().getId()); }
        else { Utils.msg(inviter, configManager.getLangMessage("invite-fail-create")); }
        return true;
    }

    private boolean kickPlayer(CommandSender sender, String targetPlayerName) {
        plugin.logInfo("[DEBUG] Running kickPlayer for " + sender.getName() + " target: " + targetPlayerName);
        Player kicker = (Player) sender; EffectiveFactionData effData = getEffectiveFactionData(kicker);
        if (!effData.isInFaction()) { Utils.msg(kicker, configManager.getLangMessage("kick-no-faction")); return true; }
        boolean isAdminMode = playerManager.isInAdminMode(kicker.getUniqueId()); boolean canKick = hasRankPermission(kicker, effData.faction(), effData.rankId(), "hfactions.faction.kick") || isAdminMode || kicker.hasPermission("hfactions.admin.*");
        if (!canKick) return noPermission(kicker);
        OfflinePlayer targetOffline = Utils.getOfflinePlayer(targetPlayerName); if (targetOffline == null || (!targetOffline.hasPlayedBefore() && !targetOffline.isOnline())) { Utils.msg(kicker, configManager.getLangMessage("player-never-played", Map.of("player", targetPlayerName))); return true; }
        UUID targetUuid = targetOffline.getUniqueId(); String targetName = targetOffline.getName() != null ? targetOffline.getName() : targetPlayerName; if (kicker.getUniqueId().equals(targetUuid)) { Utils.msg(kicker, configManager.getLangMessage("cannot-do-self")); return true; }
        Faction targetFaction = playerManager.getPlayerFaction(targetUuid); if (targetFaction == null || !targetFaction.getId().equals(effData.faction().getId())) { Utils.msg(kicker, configManager.getLangMessage("kick-target-not-in-faction", Map.of("player", targetName, "admin_suffix", isAdminMode ? " админ. режима." : "."))); return true; }
        Integer targetRankId = playerManager.getPlayerRankId(targetUuid); if (targetRankId == null) { plugin.logError("Target rank ID null in kickPlayer!"); return true; }
        if (!isAdminMode && targetRankId >= effData.rankId() && !kicker.hasPermission("hfactions.admin.*")) { Utils.msg(kicker, configManager.getLangMessage("kick-rank-hierarchy")); return true; }
        if (playerManager.setPlayerFactionAndRank(targetUuid, null, 0)) { Utils.msg(kicker, configManager.getLangMessage("kick-success-kicker", String.valueOf(Map.of("highlight", configManager.getHighlightColor(), "player", targetName)))); if (targetOffline.isOnline() && targetOffline.getPlayer() != null) { Utils.msg(targetOffline.getPlayer(), configManager.getLangMessage("kick-success-target", Map.of("highlight", configManager.getHighlightColor(), "faction_name", effData.faction().getName()))); } }
        else { Utils.msg(kicker, configManager.getLangMessage("kick-fail", Map.of("player", targetName))); }
        return true;
    }

    private boolean promotePlayer(CommandSender sender, String targetPlayerName) {
        plugin.logInfo("[DEBUG] Running promotePlayer for " + sender.getName() + " target: " + targetPlayerName);
        Player promoter = (Player) sender; EffectiveFactionData effData = getEffectiveFactionData(promoter);
        if (!effData.isInFaction()) { Utils.msg(promoter, configManager.getLangMessage("rank-change-no-faction")); return true; }
        boolean isAdminMode = playerManager.isInAdminMode(promoter.getUniqueId()); boolean canPromote = hasRankPermission(promoter, effData.faction(), effData.rankId(), "hfactions.faction.promote") || isAdminMode || promoter.hasPermission("hfactions.admin.*");
        if (!canPromote) return noPermission(promoter);
        OfflinePlayer targetOffline = Utils.getOfflinePlayer(targetPlayerName); if (targetOffline == null || (!targetOffline.hasPlayedBefore() && !targetOffline.isOnline())) { Utils.msg(promoter, configManager.getLangMessage("player-never-played", Map.of("player", targetPlayerName))); return true; }
        UUID targetUuid = targetOffline.getUniqueId(); String targetName = targetOffline.getName() != null ? targetOffline.getName() : targetPlayerName; if (promoter.getUniqueId().equals(targetUuid)) { Utils.msg(promoter, configManager.getLangMessage("cannot-do-self")); return true; }
        Faction targetFaction = playerManager.getPlayerFaction(targetUuid); if (targetFaction == null || !targetFaction.getId().equals(effData.faction().getId())) { Utils.msg(promoter, configManager.getLangMessage("rank-change-target-not-in-faction", Map.of("player", targetName, "admin_suffix", isAdminMode ? " админ. режима." : "."))); return true; }
        Integer targetRankId = playerManager.getPlayerRankId(targetUuid); if (targetRankId == null) return true;
        if (!isAdminMode && targetRankId >= effData.rankId() && !promoter.hasPermission("hfactions.admin.*")) { Utils.msg(promoter, configManager.getLangMessage("rank-change-hierarchy-self")); return true; }
        int maxRankId = 11; if (targetRankId >= maxRankId) { Utils.msg(promoter, configManager.getLangMessage("rank-change-max-rank", Map.of("player", targetName))); return true; }
        int newRankId = targetRankId + 1; if (!isAdminMode && newRankId > effData.rankId() && !promoter.hasPermission("hfactions.admin.*")) { Utils.msg(promoter, configManager.getLangMessage("rank-change-hierarchy-target")); return true; }
        if (playerManager.changePlayerRank(targetUuid, newRankId)) { FactionRank newRank = effData.faction().getRank(newRankId); String newRankName = (newRank != null) ? newRank.getDisplayName() : String.valueOf(newRankId); Utils.msg(promoter, configManager.getLangMessage("promote-success-promoter", Map.of("highlight", configManager.getHighlightColor(), "player", targetName, "rank_name", newRankName))); if (targetOffline.isOnline() && targetOffline.getPlayer() != null) { Utils.msg(targetOffline.getPlayer(), configManager.getLangMessage("promote-success-target", Map.of("highlight", configManager.getHighlightColor(), "rank_name", newRankName))); } }
        else { Utils.msg(promoter, configManager.getLangMessage("promote-fail", Map.of("player", targetName))); }
        return true;
    }

    private boolean demotePlayer(CommandSender sender, String targetPlayerName) {
        plugin.logInfo("[DEBUG] Running demotePlayer for " + sender.getName() + " target: " + targetPlayerName);
        Player demoter = (Player) sender; EffectiveFactionData effData = getEffectiveFactionData(demoter);
        if (!effData.isInFaction()) { Utils.msg(demoter, configManager.getLangMessage("rank-change-no-faction")); return true; }
        boolean isAdminMode = playerManager.isInAdminMode(demoter.getUniqueId()); boolean canDemote = hasRankPermission(demoter, effData.faction(), effData.rankId(), "hfactions.faction.demote") || isAdminMode || demoter.hasPermission("hfactions.admin.*");
        if (!canDemote) return noPermission(demoter);
        OfflinePlayer targetOffline = Utils.getOfflinePlayer(targetPlayerName); if (targetOffline == null || (!targetOffline.hasPlayedBefore() && !targetOffline.isOnline())) { Utils.msg(demoter, configManager.getLangMessage("player-never-played", Map.of("player", targetPlayerName))); return true; }
        UUID targetUuid = targetOffline.getUniqueId(); String targetName = targetOffline.getName() != null ? targetOffline.getName() : targetPlayerName; if (demoter.getUniqueId().equals(targetUuid)) { Utils.msg(demoter, configManager.getLangMessage("cannot-do-self")); return true; }
        Faction targetFaction = playerManager.getPlayerFaction(targetUuid); if (targetFaction == null || !targetFaction.getId().equals(effData.faction().getId())) { Utils.msg(demoter, configManager.getLangMessage("rank-change-target-not-in-faction", Map.of("player", targetName, "admin_suffix", isAdminMode ? " админ. режима." : "."))); return true; }
        Integer targetRankId = playerManager.getPlayerRankId(targetUuid); if (targetRankId == null) return true;
        if (!isAdminMode && targetRankId >= effData.rankId() && !demoter.hasPermission("hfactions.admin.*")) { Utils.msg(demoter, configManager.getLangMessage("rank-change-hierarchy-self")); return true; }
        int minRankId = 1; if (targetRankId <= minRankId) { Utils.msg(demoter, configManager.getLangMessage("rank-change-min-rank", Map.of("player", targetName))); return true; }
        int newRankId = targetRankId - 1;
        if (playerManager.changePlayerRank(targetUuid, newRankId)) { FactionRank newRank = effData.faction().getRank(newRankId); String newRankName = (newRank != null) ? newRank.getDisplayName() : String.valueOf(newRankId); Utils.msg(demoter, configManager.getLangMessage("demote-success-demoter", Map.of("highlight", configManager.getHighlightColor(), "player", targetName, "rank_name", newRankName))); if (targetOffline.isOnline() && targetOffline.getPlayer() != null) { Utils.msg(targetOffline.getPlayer(), configManager.getLangMessage("demote-success-target", Map.of("highlight", configManager.getHighlightColor(), "rank_name", newRankName))); } }
        else { Utils.msg(demoter, configManager.getLangMessage("demote-fail", Map.of("player", targetName))); }
        return true;
    }

    private boolean setPlayerRankCommand(CommandSender sender, String targetPlayerName, String rankIdStr) {
        plugin.logInfo("[DEBUG] Running setPlayerRankCommand for " + sender.getName() + " target: " + targetPlayerName + " rank: " + rankIdStr);
        Player setter = (Player) sender; EffectiveFactionData effData = getEffectiveFactionData(setter);
        if (!effData.isInFaction()) { Utils.msg(setter, configManager.getLangMessage("rank-change-no-faction")); return true; }
        boolean isAdminMode = playerManager.isInAdminMode(setter.getUniqueId()); boolean canSetRank = hasRankPermission(setter, effData.faction(), effData.rankId(), "hfactions.faction.setrank") || isAdminMode || setter.hasPermission("hfactions.admin.*");
        if (!canSetRank) return noPermission(setter);
        OfflinePlayer targetOffline = Utils.getOfflinePlayer(targetPlayerName); if (targetOffline == null || (!targetOffline.hasPlayedBefore() && !targetOffline.isOnline())) { Utils.msg(setter, configManager.getLangMessage("player-never-played", Map.of("player", targetPlayerName))); return true; }
        UUID targetUuid = targetOffline.getUniqueId(); String targetName = targetOffline.getName() != null ? targetOffline.getName() : targetPlayerName;
        if (setter.getUniqueId().equals(targetUuid) && !isAdminMode && !setter.hasPermission("hfactions.admin.*")) { Utils.msg(setter, configManager.getLangMessage("cannot-do-self")); return true; }
        Faction targetFaction = playerManager.getPlayerFaction(targetUuid); if (targetFaction == null || !targetFaction.getId().equals(effData.faction().getId())) { Utils.msg(setter, configManager.getLangMessage("rank-change-target-not-in-faction", Map.of("player", targetName, "admin_suffix", isAdminMode ? " админ. режима." : "."))); return true; }
        Integer targetRankId = playerManager.getPlayerRankId(targetUuid); if (targetRankId == null) return true;
        int newRankId; try { newRankId = Integer.parseInt(rankIdStr); } catch (NumberFormatException e) { Utils.msg(setter, configManager.getLangMessage("invalid-number")); return true; }
        if (effData.faction().getRank(newRankId) == null) { Utils.msg(setter, configManager.getLangMessage("rank-change-invalid-id", Map.of("id", String.valueOf(newRankId)))); return true; }
        if (!isAdminMode && !setter.hasPermission("hfactions.admin.*")) { if (newRankId >= effData.rankId()) { Utils.msg(setter, configManager.getLangMessage("rank-change-hierarchy-target")); return true; } if (targetRankId >= effData.rankId()) { Utils.msg(setter, configManager.getLangMessage("rank-change-hierarchy-self")); return true; } }
        if (playerManager.changePlayerRank(targetUuid, newRankId)) { FactionRank newRank = effData.faction().getRank(newRankId); String newRankName = newRank.getDisplayName(); Utils.msg(setter, configManager.getLangMessage("setrank-success-setter", Map.of("highlight", configManager.getHighlightColor(), "player", targetName, "rank_name", newRankName, "rank_id", String.valueOf(newRankId)))); if (targetOffline.isOnline() && targetOffline.getPlayer() != null) { Utils.msg(targetOffline.getPlayer(), configManager.getLangMessage("setrank-success-target", Map.of("highlight", configManager.getHighlightColor(), "rank_name", newRankName))); } }
        else { Utils.msg(setter, configManager.getLangMessage("setrank-fail", Map.of("player", targetName))); }
        return true;
    }

    private boolean manageRanksGui(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running manageRanksGui for " + sender.getName());
        Player player = (Player) sender; EffectiveFactionData effData = getEffectiveFactionData(player);
        if (!effData.isInFaction()) { Utils.msg(player, configManager.getLangMessage("rank-change-no-faction")); return true; }
        boolean isAdminMode = playerManager.isInAdminMode(player.getUniqueId());
        if (!isAdminMode && !isLeader(sender)) { if (!hasRankPermission(player, effData.faction(), effData.rankId(), "hfactions.faction.manage_ranks")) return noPermission(player); }
        guiManager.openRankManagementGui(player, effData.faction());
        return true;
    }

    private boolean viewBalance(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running viewBalance for " + sender.getName());
        Player player = (Player) sender; EffectiveFactionData effData = getEffectiveFactionData(player);
        if (!effData.isInFaction()) { Utils.msg(player, configManager.getLangMessage("must-be-in-faction")); return true; }
        Utils.msg(player, configManager.getLangMessage("balance-info", Map.of("faction_name", effData.faction().getName(), "success", configManager.getSuccessColor(), "amount", moneyFormat.format(effData.faction().getBalance()), "currency", economy.currencyNamePlural())));
        return true;
    }

    private boolean depositMoney(CommandSender sender, String amountStr) {
        plugin.logInfo("[DEBUG] Running depositMoney for " + sender.getName() + " amount: " + amountStr);
        Player player = (Player) sender; Faction faction = playerManager.getPlayerFaction(player.getUniqueId());
        if (faction == null) { Utils.msg(player, configManager.getLangMessage("must-be-in-faction")); return true; }
        if (!player.hasPermission(configManager.getWarehouseDepositPermission()) && !player.hasPermission("hfactions.admin.*")) return noPermission(sender);
        double amount; try { amount = Double.parseDouble(amountStr); if (amount <= 0) throw new NumberFormatException(); amount = Math.round(amount * 100.0) / 100.0; } catch (NumberFormatException e) { Utils.msg(player, configManager.getLangMessage("invalid-amount-positive")); return true; }
        double playerBalance = economy.getBalance(player); if (playerBalance < amount) { Utils.msg(player, configManager.getLangMessage("deposit-not-enough-money", Map.of("player_balance", moneyFormat.format(playerBalance), "amount_needed", moneyFormat.format(amount)))); return true; }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        if (response.transactionSuccess()) { faction.deposit(amount); faction.addTransactionLog("deposit", player.getName(), amount, "Внесение средств"); factionManager.saveFactionsConfig(); Utils.msg(player, configManager.getLangMessage("deposit-success", Map.of("highlight", configManager.getHighlightColor(), "amount", moneyFormat.format(amount), "currency", economy.currencyNamePlural()))); Utils.msg(player, configManager.getLangMessage("deposit-new-balance", Map.of("highlight", configManager.getHighlightColor(), "amount", moneyFormat.format(faction.getBalance())))); }
        else { Utils.msg(player, configManager.getLangMessage("deposit-fail-withdraw", Map.of("error", response.errorMessage))); }
        return true;
    }

    private boolean withdrawMoney(CommandSender sender, String amountStr) {
        plugin.logInfo("[DEBUG] Running withdrawMoney for " + sender.getName() + " amount: " + amountStr);
        Player player = (Player) sender; EffectiveFactionData effData = getEffectiveFactionData(player);
        if (!effData.isInFaction()) { Utils.msg(player, configManager.getLangMessage("must-be-in-faction")); return true; }
        boolean isAdminMode = playerManager.isInAdminMode(player.getUniqueId()); boolean canWithdraw = hasRankPermission(player, effData.faction(), effData.rankId(), "hfactions.faction.withdraw") || hasRankPermission(player, effData.faction(), effData.rankId(), "hfactions.faction.manage_balance") || isAdminMode || player.hasPermission("hfactions.admin.*");
        if (!canWithdraw) return noPermission(sender);
        double amount; try { amount = Double.parseDouble(amountStr); if (amount <= 0) throw new NumberFormatException(); amount = Math.round(amount * 100.0) / 100.0; } catch (NumberFormatException e) { Utils.msg(player, configManager.getLangMessage("invalid-amount-positive")); return true; }
        double factionBalance = effData.faction().getBalance(); if (factionBalance < amount) { Utils.msg(player, configManager.getLangMessage("withdraw-not-enough-faction", Map.of("faction_balance", moneyFormat.format(factionBalance), "amount_needed", moneyFormat.format(amount)))); return true; }
        if (effData.faction().withdraw(amount)) { EconomyResponse response = economy.depositPlayer(player, amount); if (response.transactionSuccess()) { effData.faction().addTransactionLog("withdraw", player.getName(), -amount, "Снятие средств"); factionManager.saveFactionsConfig(); Utils.msg(player, configManager.getLangMessage("withdraw-success", Map.of("highlight", configManager.getHighlightColor(), "amount", moneyFormat.format(amount), "faction_name", effData.faction().getName()))); Utils.msg(player, configManager.getLangMessage("withdraw-new-balance", Map.of("highlight", configManager.getHighlightColor(), "amount", moneyFormat.format(effData.faction().getBalance())))); } else { effData.faction().deposit(amount); Utils.msg(player, configManager.getLangMessage("withdraw-fail-deposit")); plugin.logError("Failed to deposit withdrawn money to " + player.getName() + ": " + response.errorMessage); } }
        else { Utils.msg(player, configManager.getLangMessage("withdraw-fail-faction")); }
        return true;
    }

    private boolean toggleFactionChatCommand(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running toggleFactionChatCommand for " + sender.getName());
        Player player = (Player) sender; if (!configManager.isFactionChatEnabled()) { Utils.msg(player, configManager.getLangMessage("chat-disabled")); return true; } if (playerManager.getPlayerFaction(player.getUniqueId()) == null) { Utils.msg(player, configManager.getLangMessage("must-be-in-faction")); return true; }
        playerManager.toggleFactionChat(player.getUniqueId()); boolean isInChat = playerManager.isPlayerInFactionChat(player.getUniqueId());
        Utils.msg(player, configManager.getLangMessage(isInChat ? "chat-toggle-on" : "chat-toggle-off", Map.of("highlight", configManager.getHighlightColor())));
        return true;
    }

    private boolean openWarehouse(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running openWarehouse for " + sender.getName());
        Player player = (Player) sender; if (!configManager.isWarehouseEnabled()) { Utils.msg(player, configManager.getLangMessage("warehouse-disabled")); return true; }
        EffectiveFactionData effData = getEffectiveFactionData(player); if (!effData.isInFaction()) { Utils.msg(player, configManager.getLangMessage("must-be-in-faction")); return true; }
        if (!player.hasPermission(configManager.getWarehouseOpenPermission()) && !player.hasPermission("hfactions.admin.*")) return noPermission(player);
        guiManager.openWarehouseGui(player, effData.faction(), 1);
        return true;
    }

    private boolean issueFine(CommandSender sender, String targetPlayerName, String amountStr, String reason) {
        plugin.logInfo("[DEBUG] Running issueFine for " + sender.getName() + " target: " + targetPlayerName + " amount: " + amountStr);
        Player officer = (Player) sender; if (economy == null) { Utils.msg(officer, configManager.getLangMessage("economy-disabled")); return true; }
        String requiredFactionId = configManager.getFineAllowedFactionId(); Faction officerFaction = playerManager.getPlayerFaction(officer.getUniqueId()); if (officerFaction == null || !officerFaction.getId().equals(requiredFactionId)) { Utils.msg(officer, configManager.getLangMessage("fine-wrong-faction", Map.of("faction_id", requiredFactionId.toUpperCase()))); return true; }
        Integer officerRankId = playerManager.getPlayerRankId(officer.getUniqueId()); if (officerRankId == null) return true; String permNode = configManager.getFinePermissionNode(); int minRankId = configManager.getFineMinRankId(); if (!officer.hasPermission(permNode)) return noPermission(officer); if (minRankId > 0 && officerRankId < minRankId && !officer.hasPermission("hfactions.admin.*")) { Utils.msg(officer, configManager.getLangMessage("fine-rank-too-low", Map.of("min_rank", String.valueOf(minRankId)))); return true; }
        if (isOnFineGlobalCooldown(officer.getUniqueId())) { Utils.msg(officer, configManager.getLangMessage("fine-global-cooldown", Map.of("seconds", String.valueOf(getFineGlobalCooldownSecondsLeft(officer.getUniqueId()))))); return true; }
        Player targetPlayer = Bukkit.getPlayerExact(targetPlayerName); if (targetPlayer == null) { Utils.msg(officer, configManager.getLangMessage("player-not-found", Map.of("player", targetPlayerName))); return true; } UUID targetUuid = targetPlayer.getUniqueId();
        if (isOnFineTargetCooldown(officer.getUniqueId(), targetUuid)) { Utils.msg(officer, configManager.getLangMessage("fine-target-cooldown", Map.of("time_left", getFineTargetCooldownTimeLeft(officer.getUniqueId(), targetUuid)))); return true; }
        if (officer.getUniqueId().equals(targetUuid)) { Utils.msg(officer, configManager.getLangMessage("cannot-do-self")); return true; }
        double amount; double minAmount = configManager.getFineMinAmount(); double maxAmount = configManager.getFineMaxAmount(); try { amount = Double.parseDouble(amountStr); if (amount < minAmount || amount > maxAmount) throw new NumberFormatException(); amount = Math.round(amount * 100.0) / 100.0; } catch (NumberFormatException e) { Utils.msg(officer, configManager.getLangMessage("invalid-amount-range", Map.of("min", moneyFormat.format(minAmount), "max", moneyFormat.format(maxAmount)))); return true; }
        if (reason.trim().isEmpty()) { Utils.msg(officer, configManager.getLangMessage("fine-invalid-reason")); return true; }
        double targetBalance = economy.getBalance(targetPlayer); if (targetBalance < amount) { Utils.msg(officer, configManager.getLangMessage("fine-target-not-enough-money", Map.of("player", targetPlayer.getName(), "target_balance", moneyFormat.format(targetBalance), "amount", moneyFormat.format(amount)))); return true; }
        EconomyResponse withdrawalResponse = economy.withdrawPlayer(targetPlayer, amount); if (!withdrawalResponse.transactionSuccess()) { Utils.msg(officer, configManager.getLangMessage("fine-fail-withdraw-target", Map.of("player", targetPlayer.getName(), "error", withdrawalResponse.errorMessage))); return true; }
        String destinationType = configManager.getFineMoneyDestination(); String depositTargetName = "никуда (удалено)"; boolean savedFaction = false; Faction recipientFaction = null; // Для лога
        switch (destinationType) { case "FACTION": officerFaction.deposit(amount); depositTargetName = "фракцию " + officerFaction.getName(); savedFaction=true; recipientFaction = officerFaction; break; case "GOVERNMENT": String govId = configManager.getFineGovernmentFactionId(); Faction gov = factionManager.getFaction(govId); if (gov != null) { gov.deposit(amount); depositTargetName = "фракцию " + gov.getName(); savedFaction=true; recipientFaction = gov;} else { depositTargetName="никуда (GOV не найдена)"; } break; case "PLAYER": EconomyResponse dr=economy.depositPlayer(officer, amount); if (!dr.transactionSuccess()) { economy.depositPlayer(targetPlayer, amount); Utils.msg(officer, configManager.getLangMessage("fine-fail-deposit-officer")); return true; } depositTargetName="ваш счет"; break; default: break; }
        if(savedFaction) factionManager.saveFactionsConfig(); // Сохраняем баланс
        if(recipientFaction != null) { recipientFaction.addTransactionLog("fine", targetPlayer.getName(), amount, "Штраф от " + officer.getName() + ": " + reason); factionManager.saveFactionsConfig(); } // Сохраняем лог
        setFineCooldowns(officer.getUniqueId(), targetUuid);
        Map<String, String> fineInfo = Map.of("highlight", configManager.getHighlightColor(), "player", targetPlayer.getName(), "amount", moneyFormat.format(amount), "currency", economy.currencyNamePlural(), "reason", reason); Utils.msg(officer, configManager.getLangMessage("fine-success-officer", fineInfo)); Utils.msg(officer, configManager.getLangMessage("fine-success-officer-destination", Map.of("destination", depositTargetName)));
        Map<String, String> fineInfoTarget = Map.of("highlight", configManager.getHighlightColor(), "officer", officer.getName(), "amount", moneyFormat.format(amount), "currency", economy.currencyNamePlural(), "reason", reason); Utils.msg(targetPlayer, configManager.getLangMessage("fine-success-target", fineInfoTarget)); Utils.msg(targetPlayer, configManager.getLangMessage("fine-success-target-balance", Map.of("highlight", configManager.getHighlightColor(), "amount", moneyFormat.format(economy.getBalance(targetPlayer)))));
        if (configManager.logFines()) plugin.logInfo(configManager.getFineLogFormat().replace("{officer}", officer.getName()).replace("{target}", targetPlayer.getName()).replace("{amount}", moneyFormat.format(amount)).replace("{reason}", reason));
        return true;
    }

    private boolean uncuffCommand(CommandSender sender, String targetPlayerName) {
        plugin.logInfo("[DEBUG] Running uncuffCommand for " + sender.getName() + " target: " + targetPlayerName);
        OfflinePlayer targetOffline = Utils.getOfflinePlayer(targetPlayerName); if (targetOffline == null || (!targetOffline.hasPlayedBefore() && !targetOffline.isOnline())) { Utils.msg(sender, configManager.getLangMessage("player-never-played", Map.of("player", targetPlayerName))); return true; }
        UUID targetUuid = targetOffline.getUniqueId(); String targetName = targetOffline.getName() != null ? targetOffline.getName() : targetPlayerName;
        if (!cuffManager.isCuffed(targetUuid)) { Utils.msg(sender, configManager.getLangMessage("uncuff-target-not-cuffed", Map.of("player", targetName))); return true; }
        Player remover = (sender instanceof Player) ? (Player) sender : null;
        if (cuffManager.uncuffPlayer(targetUuid, remover)) { Utils.msg(sender, configManager.getLangMessage("uncuff-success", Map.of("highlight", configManager.getHighlightColor(), "player", targetName))); }
        else { Utils.msg(sender, configManager.getLangMessage("uncuff-fail", Map.of("player", targetName))); }
        return true;
    }

    private boolean createFaction(CommandSender sender, String id, String name, String typeStr) {
        plugin.logInfo("[DEBUG] Running createFaction for " + sender.getName() + " id: " + id);
        if (!id.matches("^[a-zA-Z0-9_]+$")) { Utils.msg(sender, configManager.getLangMessage("create-id-format")); return true; }
        if (id.length() > 16) { Utils.msg(sender, configManager.getLangMessage("create-id-length")); return true; }
        if (factionManager.factionExists(id)) { Utils.msg(sender, configManager.getLangMessage("create-id-exists", Map.of("id", id))); return true; }
        FactionType type; try { type = FactionType.valueOf(typeStr.toUpperCase()); } catch (IllegalArgumentException e) { Utils.msg(sender, configManager.getLangMessage("create-invalid-type")); return true; }
        Faction newFaction = factionManager.createFaction(id, name, type);
        if (newFaction != null) { Utils.msg(sender, configManager.getLangMessage("create-success", Map.of("name", name, "id", id))); }
        else { Utils.msg(sender, configManager.getLangMessage("create-fail")); }
        return true;
    }

    private boolean deleteFaction(CommandSender sender, String id) {
        plugin.logInfo("[DEBUG] Running deleteFaction for " + sender.getName() + " id: " + id);
        Faction faction = factionManager.getFaction(id); if (faction == null) { Utils.msg(sender, configManager.getLangMessage("faction-not-found", Map.of("id", id))); return true; }
        String name = faction.getName();
        if (factionManager.deleteFaction(id)) { Utils.msg(sender, configManager.getLangMessage("delete-success", Map.of("name", name, "id", id))); }
        else { Utils.msg(sender, configManager.getLangMessage("delete-fail", Map.of("id", id))); }
        return true;
    }

    private boolean reloadPlugin(CommandSender sender) {
        plugin.logInfo("[DEBUG] Running reloadPlugin for " + sender.getName());
        plugin.logInfo("Reloading HFactions requested by " + sender.getName());
        try {
            if (playerManager != null) playerManager.saveAllPlayerData(); if (factionManager != null) factionManager.saveFactionsConfig();
            if (configManager != null) configManager.loadConfigs(); if (factionManager != null) factionManager.loadFactions();
            if (playerManager != null) playerManager.loadAllPlayerData(); if (craftingManager != null) craftingManager.loadRecipesFromConfig();
            if (dynmapManager != null && configManager.isDynmapEnabled()) { dynmapManager.loadTerritories(); dynmapManager.renderAllTerritories(); }
            plugin.startPaydayTask(); // Перезапускаем Payday
            Utils.msg(sender, configManager.getLangMessage("reload-success"));
        } catch (Exception e) { Utils.msg(sender, configManager.getLangMessage("reload-fail")); plugin.logError("Error during plugin reload:"); e.printStackTrace(); }
        return true;
    }

    private boolean setBalanceAdmin(CommandSender sender, String factionId, String amountStr) {
        plugin.logInfo("[DEBUG] Running setBalanceAdmin for " + sender.getName() + " faction: " + factionId + " amount: " + amountStr);
        Faction faction = factionManager.getFaction(factionId); if (faction == null) { Utils.msg(sender, configManager.getLangMessage("faction-not-found", Map.of("id", factionId))); return true; }
        double amount; try { amount = Double.parseDouble(amountStr); if (amount < 0) throw new NumberFormatException(); amount = Math.round(amount * 100.0) / 100.0; } catch (NumberFormatException e) { Utils.msg(sender, configManager.getLangMessage("invalid-number")); return true; }
        faction.setBalance(amount);
        faction.addTransactionLog("admin_set", sender.getName(), amount - faction.getBalance() /* Дельта */, "Установка баланса админом");
        factionManager.saveFactionsConfig();
        Utils.msg(sender, configManager.getLangMessage("setbalance-success", Map.of("name", faction.getName(), "amount", moneyFormat.format(amount))));
        plugin.logInfo("Faction " + faction.getId() + " balance set to " + amount + " by admin " + sender.getName());
        return true;
    }

    private boolean toggleAdminMode(CommandSender sender, @Nullable String factionId) {
        plugin.logInfo("[DEBUG] Running toggleAdminMode for " + sender.getName() + (factionId != null ? " target: " + factionId : " (exit)"));
        if (!(sender instanceof Player)) return requiresPlayer(sender); Player admin = (Player) sender;
        String messageKey = playerManager.toggleAdminMode(admin, factionId); // Возвращает ключ для сообщения
        Faction targetFaction = factionId != null ? factionManager.getFaction(factionId) : null;
        String defaultMsg = "&cОшибка обработки режима админа."; // Дефолт на случай невалидного ключа
        Utils.msg(admin, configManager.getLangMessage(messageKey, Map.of("highlight", configManager.getHighlightColor(), "faction_id", Objects.toString(factionId, ""), "faction_name", targetFaction != null ? targetFaction.getName() : "" ), defaultMsg ));
        return true;
    }

    private boolean giveItem(CommandSender sender, String permission, Supplier<ItemStack> itemSupplier, String itemName, @Nullable String targetName) {
        plugin.logInfo("[DEBUG] Running giveItem for " + sender.getName() + " item: " + itemName + (targetName != null ? " target: " + targetName : ""));
        if (!sender.hasPermission(permission)) return noPermission(sender); Player target = null; if (targetName != null) { target = Bukkit.getPlayerExact(targetName); if (target == null) { Utils.msg(sender, configManager.getLangMessage("player-not-found", Map.of("player", targetName))); return true; } } else if (sender instanceof Player) { target = (Player) sender; }
        if (target == null) return requiresPlayerOrArg(sender); ItemStack item = itemSupplier.get(); if (item == null || item.getType() == Material.BARRIER) { Utils.msg(sender, configManager.getLangMessage("item-give-fail", Map.of("item", itemName))); return true; }
        HashMap<Integer, ItemStack> didntFit = target.getInventory().addItem(item);
        if (!didntFit.isEmpty()){ Utils.msg(sender, configManager.getLangMessage("item-give-inventory-full", Map.of("player", target.getName()))); }
        else { Utils.msg(sender, configManager.getLangMessage("item-give-success-sender", Map.of("item", itemName, "player", target.getName()))); Utils.msg(target, configManager.getLangMessage("item-give-success-target", Map.of("item", itemName))); }
        return true;
    }

    // --- Методы для подкоманд территории ---
    private boolean listTerritories(CommandSender sender, String factionIdFilter) { plugin.logInfo("[DEBUG][Territory] Running listTerritories"); Collection<TerritoryData> tList; String title; String hC=configManager.getHighlightColor(), pC=configManager.getPrimaryColor(), sC=configManager.getSecondaryColor(); if(factionIdFilter!=null){ Faction f=factionManager.getFaction(factionIdFilter); if(f==null){Utils.msg(sender, configManager.getLangMessage("faction-not-found", Map.of("id", factionIdFilter))); return true;} tList=dynmapManager.getFactionTerritories(f.getId()); title=configManager.getLangMessage("territory-list-header", Map.of("faction_name", f.getName(), "highlight", hC, "primary", pC));}else{ tList=dynmapManager.getAllTerritories(); title=configManager.getLangMessage("territory-list-header-all");} if(tList.isEmpty()){Utils.msg(sender, configManager.getLangMessage("territory-list-empty", sC+(factionIdFilter!=null?"Нет территорий.":"Нет захваченных."))); return true;} Utils.msg(sender, title); for(TerritoryData d:tList){Faction o=factionManager.getFaction(d.factionId); String oN=(o!=null)?o.getName():"???"; Utils.msg(sender, configManager.getLangMessage("territory-list-entry", Map.of("highlight", hC, "secondary", sC, "primary", pC, "zone", d.zoneName, "id", d.factionId, "owner", oN, "world", d.worldName, "corners", String.valueOf(d.corners.size()))));} return true; }
    private boolean startDefiningTerritory(CommandSender sender, String zoneName) { plugin.logInfo("[DEBUG][Territory] Running startDefiningTerritory"); Player p=(Player)sender; UUID uuid=p.getUniqueId(); if(!zoneName.matches("^[a-zA-Z0-9_]+$")){Utils.msg(p, configManager.getLangMessage("territory-define-name-format"));return true;} if(zoneName.length()>32){Utils.msg(p, configManager.getLangMessage("territory-define-name-length"));return true;} if(dynmapManager.getTerritory(zoneName)!=null){Utils.msg(p, configManager.getLangMessage("territory-define-already-exists", Map.of("name", zoneName)));return true;} territoryCorners.put(uuid, new ArrayList<>()); Utils.msg(p, configManager.getLangMessage("territory-define-started", Map.of("highlight", configManager.getHighlightColor(), "name", zoneName, "success", configManager.getSuccessColor()))); Utils.msg(p, configManager.getLangMessage("territory-define-prompt-corner")); Utils.msg(p, configManager.getLangMessage("territory-define-prompt-clear")); Utils.msg(p, configManager.getLangMessage("territory-define-prompt-claim", Map.of("name", zoneName))); return true; }
    private boolean addTerritoryCorner(CommandSender sender) { plugin.logInfo("[DEBUG][Territory] Running addTerritoryCorner"); Player p=(Player)sender; UUID uuid=p.getUniqueId(); if(!territoryCorners.containsKey(uuid)){Utils.msg(p, configManager.getLangMessage("territory-corner-not-started")); return true;} List<Location> corners=territoryCorners.get(uuid); Location cLoc=p.getLocation(); if(!corners.isEmpty()&&!Objects.equals(corners.get(0).getWorld(),cLoc.getWorld())){Utils.msg(p, configManager.getLangMessage("territory-corner-wrong-world", Map.of("current", cLoc.getWorld().getName(), "first", corners.get(0).getWorld().getName()))); Utils.msg(p, configManager.getLangMessage("territory-corner-wrong-world")); return true;} corners.add(cLoc); Utils.msg(p, configManager.getLangMessage("territory-corner-added", Map.of("count", String.valueOf(corners.size()), "highlight", configManager.getHighlightColor(), "success", configManager.getSuccessColor(), "x", String.format("%.1f", cLoc.getX()), "z", String.format("%.1f", cLoc.getZ()), "world", cLoc.getWorld().getName()))); if(corners.size()>=3){Utils.msg(p, configManager.getLangMessage("territory-corner-can-claim"));} return true; }
    private boolean clearTerritoryCorners(CommandSender sender) { plugin.logInfo("[DEBUG][Territory] Running clearTerritoryCorners"); Player p=(Player)sender; UUID uuid=p.getUniqueId(); if(!territoryCorners.containsKey(uuid)){Utils.msg(p, configManager.getLangMessage("territory-clear-not-started")); return true;} territoryCorners.remove(uuid); Utils.msg(p, configManager.getLangMessage("territory-clear-success")); return true; }
    private boolean claimTerritory(CommandSender sender, String zoneName, String forcedFactionId) { plugin.logInfo("[DEBUG][Territory] Running claimTerritory"); Player p=(Player)sender; UUID uuid=p.getUniqueId(); if(!territoryCorners.containsKey(uuid)){Utils.msg(p, configManager.getLangMessage("territory-claim-not-started")); return true;} List<Location> corners=territoryCorners.get(uuid); if(corners.size()<3){Utils.msg(p, configManager.getLangMessage("territory-claim-not-enough-corners")); return true;} Faction targetFaction=factionManager.getFaction(forcedFactionId); if(targetFaction==null){Utils.msg(p, configManager.getLangMessage("faction-not-found", Map.of("id", forcedFactionId))); return true;} String ownerFactionId=targetFaction.getId(); String worldName=corners.get(0).getWorld().getName(); for(Location loc:corners)if(!Objects.equals(loc.getWorld().getName(),worldName)){Utils.msg(p, configManager.getLangMessage("territory-claim-wrong-world")); return true;} if(dynmapManager.defineTerritory(zoneName, ownerFactionId, worldName, corners)){Utils.msg(p, configManager.getLangMessage("territory-claim-success", Map.of("highlight", configManager.getHighlightColor(), "success", configManager.getSuccessColor(), "zone", zoneName, "faction_id", ownerFactionId.toUpperCase()))); territoryCorners.remove(uuid);} else {Utils.msg(p, configManager.getLangMessage("territory-claim-fail", Map.of("zone", zoneName)));} return true; }
    private boolean deleteTerritory(CommandSender sender, String zoneName, Faction senderFaction, boolean isSenderLeader) { plugin.logInfo("[DEBUG][Territory] Running deleteTerritory"); TerritoryData territory=dynmapManager.getTerritory(zoneName); if(territory==null){Utils.msg(sender, configManager.getLangMessage("territory-delete-not-found", Map.of("zone", zoneName))); return true;} boolean isAdmin=sender.hasPermission("hfactions.admin.territory"); boolean canDelete=isAdmin; if(!canDelete&&isSenderLeader&&senderFaction!=null&&territory.factionId.equals(senderFaction.getId()))canDelete=true; if(!canDelete) return noPermissionTerritory(sender); if(dynmapManager.deleteTerritory(zoneName)){Utils.msg(sender, configManager.getLangMessage("territory-delete-success", Map.of("highlight", configManager.getHighlightColor(), "success", configManager.getSuccessColor(), "zone", zoneName, "faction_id", territory.factionId.toUpperCase())));} else {Utils.msg(sender, configManager.getLangMessage("territory-delete-fail", Map.of("zone", zoneName)));} return true; }
    private boolean updateMap(CommandSender sender) { plugin.logInfo("[DEBUG][Territory] Running updateMap"); Utils.msg(sender, configManager.getLangMessage("territory-map-start")); Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { dynmapManager.renderAllTerritories(); Bukkit.getScheduler().runTask(plugin, () -> Utils.msg(sender, configManager.getLangMessage("territory-map-success"))); }); return true; }
    private boolean showTerritoryHelp(CommandSender sender) { plugin.logInfo("[DEBUG][Territory] Running showTerritoryHelp"); Utils.msg(sender, configManager.getLangMessage("territory-help-header")); Utils.msg(sender, configManager.getLangMessage("territory-help-entry", Map.of("command", "list [id]", "description", "Список территорий"))); Utils.msg(sender, configManager.getLangMessage("territory-help-entry", Map.of("command", "define <назв>", "description", "Начать определение"))); Utils.msg(sender, configManager.getLangMessage("territory-help-entry", Map.of("command", "corner", "description", "Добавить угол"))); Utils.msg(sender, configManager.getLangMessage("territory-help-entry", Map.of("command", "clear", "description", "Очистить углы"))); Utils.msg(sender, configManager.getLangMessage("territory-help-entry", Map.of("command", "claim <назв> [id]", "description", "Сохранить зону"))); Utils.msg(sender, configManager.getLangMessage("territory-help-entry", Map.of("command", "delete <назв>", "description", "Удалить зону"))); Utils.msg(sender, configManager.getLangMessage("territory-help-entry", Map.of("command", "map", "description", "Обновить карту (админ)"))); return true; }


    // --- Логика оформления штрафа через предмет/чат ---
    public boolean isPlayerIssuingFine(UUID officerUuid) { return playersIssuingFine.containsKey(officerUuid); }
    public void startFineProcess(Player officer, Player target) { plugin.logInfo("[DEBUG] Starting fine process for officer " + officer.getName() + " on target " + target.getName()); UUID officerUuid = officer.getUniqueId(); UUID targetUuid = target.getUniqueId(); if (economy == null) { Utils.msg(officer, configManager.getLangMessage("economy-disabled")); return; } String requiredFactionId = configManager.getFineAllowedFactionId(); Faction officerFaction = playerManager.getPlayerFaction(officerUuid); if (officerFaction == null || !officerFaction.getId().equals(requiredFactionId)) { Utils.msg(officer, configManager.getLangMessage("fine-wrong-faction", Map.of("faction_id", requiredFactionId.toUpperCase()))); return; } Integer officerRankId = playerManager.getPlayerRankId(officerUuid); if (officerRankId == null) return; String permNode = configManager.getFinePermissionNode(); int minRankId = configManager.getFineMinRankId(); if (!officer.hasPermission(permNode)) { Utils.msg(officer, configManager.getLangMessage("no-permission", Map.of("permission", permNode))); return; } if (minRankId > 0 && officerRankId < minRankId && !officer.hasPermission("hfactions.admin.*")) { Utils.msg(officer, configManager.getLangMessage("fine-rank-too-low", Map.of("min_rank", String.valueOf(minRankId)))); return; } if (isOnFineGlobalCooldown(officerUuid)) { Utils.msg(officer, configManager.getLangMessage("fine-global-cooldown", Map.of("seconds", String.valueOf(getFineGlobalCooldownSecondsLeft(officerUuid))))); return; } if (isPlayerIssuingFine(officerUuid)) { Utils.msg(officer, configManager.getLangMessage("fine-already-issuing")); return; } if (officerUuid.equals(targetUuid)) { Utils.msg(officer, configManager.getLangMessage("cannot-do-self")); return; } PendingFine pendingFine = new PendingFine(officerUuid, targetUuid); playersIssuingFine.put(officerUuid, pendingFine); Utils.msg(officer, configManager.getLangMessage("fine-started", Map.of("highlight", configManager.getHighlightColor(), "player", target.getName()))); Utils.msg(officer, configManager.getLangMessage("fine-prompt-amount", Map.of("min", moneyFormat.format(configManager.getFineMinAmount()), "max", moneyFormat.format(configManager.getFineMaxAmount())))); Utils.msg(officer, configManager.getLangMessage("fine-prompt-cancel")); }
    public void cancelFineProcess(UUID officerUuid) { plugin.logInfo("[DEBUG] Cancelling fine process for officer " + officerUuid); if (playersIssuingFine.remove(officerUuid) != null) { Player officer = Bukkit.getPlayer(officerUuid); if (officer != null) Utils.msg(officer, configManager.getLangMessage("fine-cancelled")); } }
    public void handleFineInput(Player officer, String input) { plugin.logInfo("[DEBUG] Handling fine input '" + input + "' from officer " + officer.getName()); UUID officerUuid = officer.getUniqueId(); if (!isPlayerIssuingFine(officerUuid)) return; PendingFine pendingFine = playersIssuingFine.get(officerUuid); Player target = Bukkit.getPlayer(pendingFine.targetUuid); if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("отмена")) { cancelFineProcess(officerUuid); return; } if (target == null || !target.isOnline()) { cancelFineProcess(officerUuid); Utils.msg(officer, configManager.getLangMessage("player-not-found", Map.of("player", "?"))); return; } if ("amount".equals(pendingFine.state)) { double amount; double minAmount=configManager.getFineMinAmount(); double maxAmount=configManager.getFineMaxAmount(); try { amount=Double.parseDouble(input); if(amount<minAmount||amount>maxAmount)throw new NumberFormatException(); amount=Math.round(amount*100.0)/100.0; } catch (NumberFormatException e) { Utils.msg(officer, configManager.getLangMessage("invalid-amount-range", Map.of("min", moneyFormat.format(minAmount), "max", moneyFormat.format(maxAmount)))); return; } if (isOnFineTargetCooldown(officerUuid, pendingFine.targetUuid)) { Utils.msg(officer, configManager.getLangMessage("fine-target-cooldown", Map.of("time_left", getFineTargetCooldownTimeLeft(officerUuid, pendingFine.targetUuid)))); cancelFineProcess(officerUuid); return; } if (economy.getBalance(target) < amount) { Utils.msg(officer, configManager.getLangMessage("fine-target-not-enough-money", Map.of("player", target.getName(), "target_balance", moneyFormat.format(economy.getBalance(target)), "amount", moneyFormat.format(amount)))); cancelFineProcess(officerUuid); return; } pendingFine.amount = amount; pendingFine.state = "reason"; Utils.msg(officer, configManager.getLangMessage("fine-amount-set", Map.of("highlight", configManager.getHighlightColor(), "amount", moneyFormat.format(amount)))); Utils.msg(officer, configManager.getLangMessage("fine-prompt-reason")); Utils.msg(officer, configManager.getLangMessage("fine-prompt-cancel")); } else if ("reason".equals(pendingFine.state)) { String reason = input.trim(); if (reason.isEmpty()) { Utils.msg(officer, configManager.getLangMessage("fine-invalid-reason")); return; } playersIssuingFine.remove(officerUuid); EconomyResponse withdrawalResponse = economy.withdrawPlayer(target, pendingFine.amount); if (!withdrawalResponse.transactionSuccess()) { Utils.msg(officer, configManager.getLangMessage("fine-fail-withdraw-target", Map.of("player", target.getName(), "error", withdrawalResponse.errorMessage))); return; } String destinationType = configManager.getFineMoneyDestination(); String depositTargetName = "никуда (удалено)"; boolean savedFaction = false; Faction officerFaction = playerManager.getPlayerFaction(officerUuid); Faction recipientFaction = null; switch (destinationType) { case "FACTION": if(officerFaction!=null){officerFaction.deposit(pendingFine.amount); depositTargetName = "фракцию " + officerFaction.getName(); savedFaction=true; recipientFaction = officerFaction;} break; case "GOVERNMENT": String govId = configManager.getFineGovernmentFactionId(); Faction gov = factionManager.getFaction(govId); if (gov != null) { gov.deposit(pendingFine.amount); depositTargetName = "фракцию " + gov.getName(); savedFaction=true; recipientFaction = gov;} else { depositTargetName="никуда (GOV не найдена)"; } break; case "PLAYER": EconomyResponse dr=economy.depositPlayer(officer, pendingFine.amount); if (!dr.transactionSuccess()) { economy.depositPlayer(target, pendingFine.amount); Utils.msg(officer, configManager.getLangMessage("fine-fail-deposit-officer")); return; } depositTargetName="ваш счет"; break; default: break; } if(savedFaction) factionManager.saveFactionsConfig(); if(recipientFaction != null) { recipientFaction.addTransactionLog("fine", target.getName(), pendingFine.amount, "Штраф от " + officer.getName() + ": " + reason); factionManager.saveFactionsConfig(); } setFineCooldowns(officerUuid, pendingFine.targetUuid); Map<String, String> fineInfo = Map.of("highlight", configManager.getHighlightColor(), "player", target.getName(), "amount", moneyFormat.format(pendingFine.amount), "currency", economy.currencyNamePlural(), "reason", reason); Utils.msg(officer, configManager.getLangMessage("fine-success-officer", fineInfo)); Utils.msg(officer, configManager.getLangMessage("fine-success-officer-destination", Map.of("destination", depositTargetName))); Map<String, String> fineInfoTarget = Map.of("highlight", configManager.getHighlightColor(), "officer", officer.getName(), "amount", moneyFormat.format(pendingFine.amount), "currency", economy.currencyNamePlural(), "reason", reason); Utils.msg(target, configManager.getLangMessage("fine-success-target", fineInfoTarget)); Utils.msg(target, configManager.getLangMessage("fine-success-target-balance", Map.of("highlight", configManager.getHighlightColor(), "amount", moneyFormat.format(economy.getBalance(target))))); if (configManager.logFines()) plugin.logInfo(configManager.getFineLogFormat().replace("{officer}", officer.getName()).replace("{target}", target.getName()).replace("{amount}", moneyFormat.format(pendingFine.amount)).replace("{reason}", reason)); } }
    // --- Методы управления кулдаунами штрафов ---
    private boolean isOnFineGlobalCooldown(UUID officerUuid) { Instant end = fineGlobalCooldowns.get(officerUuid); return end != null && Instant.now().isBefore(end); }
    private long getFineGlobalCooldownSecondsLeft(UUID officerUuid) { Instant end = fineGlobalCooldowns.get(officerUuid); if (end == null) return 0; Instant now = Instant.now(); return now.isBefore(end) ? ChronoUnit.SECONDS.between(now, end) + 1 : 0; }
    private boolean isOnFineTargetCooldown(UUID officerUuid, UUID targetUuid) { Map<UUID, Instant> map = fineTargetCooldowns.get(officerUuid); if (map == null) return false; Instant end = map.get(targetUuid); return end != null && Instant.now().isBefore(end); }
    private String getFineTargetCooldownTimeLeft(UUID officerUuid, UUID targetUuid) { Map<UUID, Instant> map = fineTargetCooldowns.get(officerUuid); if (map == null) return "0 сек."; Instant end = map.get(targetUuid); if (end == null) return "0 сек."; Instant now = Instant.now(); if (now.isBefore(end)) { long min = ChronoUnit.MINUTES.between(now, end); long sec = ChronoUnit.SECONDS.between(now, end) % 60; return (min > 0 ? min + " мин. " : "") + (sec + 1) + " сек."; } return "0 сек."; }
    private void setFineCooldowns(UUID officerUuid, UUID targetUuid) { Instant now = Instant.now(); long globalCd = configManager.getFineGlobalCooldownSeconds(); long targetCd = configManager.getFineTargetCooldownMinutes(); fineGlobalCooldowns.put(officerUuid, now.plusSeconds(globalCd)); fineTargetCooldowns.computeIfAbsent(officerUuid, k -> new HashMap<>()).put(targetUuid, now.plus(targetCd, ChronoUnit.MINUTES)); }


    // --- Вспомогательные методы ---
    private boolean checkPermissionAndExecute(CommandSender sender, String permission, Runnable action) { plugin.logInfo("[DEBUG] Checking perm '" + permission + "' for " + sender.getName()); if (!sender.hasPermission(permission)) { plugin.logWarning("[DEBUG] Permission DENIED for " + sender.getName() + " (" + permission + ")"); return noPermission(sender, permission); } plugin.logInfo("[DEBUG] Permission GRANTED for " + permission + ". Running action."); try { action.run(); } catch (Exception e) { Utils.msg(sender, configManager.getLangMessage("internal-error")); plugin.logError("Error executing " + permission); e.printStackTrace(); } return true;}
    private boolean hasRankPermission(Player player, Faction faction, @Nullable Integer rankId, String baseNode) { if (player == null || faction == null || rankId == null) return false; if (player.hasPermission(baseNode)) return true; if (player.hasPermission(baseNode + "." + faction.getId())) return true; FactionRank rank = faction.getRank(rankId); if (rank != null && rank.getPermissions() != null) { if (rank.getPermissions().contains(baseNode) || rank.getPermissions().contains(baseNode + "." + faction.getId())) return true; } return false; }
    private String parseQuotedString(String[] args, int start) { if(args.length<=start||!args[start].startsWith("\""))return null; StringBuilder sb=new StringBuilder(); for(int i=start;i<args.length;i++){ String a=args[i]; if(i==start){if(a.length()>1&&a.endsWith("\""))return a.substring(1,a.length()-1);sb.append(a.substring(1));}else{if(a.endsWith("\"")){sb.append(" ").append(a.substring(0,a.length()-1));return sb.toString();}else{sb.append(" ").append(a);}}} return null; }
    private int findQuotedStringEndIndex(String[] args, int start) { if(args.length<=start||!args[start].startsWith("\""))return -1; for(int i=start;i<args.length;i++)if(args[i].endsWith("\""))return i+1; return -1; }
    private boolean needsPlayer(String sub) { return Arrays.asList("invite","leave","kick","promote","demote","setrank","manageranks","balance","bal","deposit","dep","withdraw","wd","chat","c","warehouse","wh","fine","territory","adminmode").contains(sub); }
    private boolean needsEconomy(String sub) { return Arrays.asList("balance","bal","deposit","dep","withdraw","wd","fine").contains(sub); }
    // Исправленные методы сообщений об ошибках
    private boolean noPermission(CommandSender s) { Utils.msg(s, configManager.getLangMessage("no-permission", Map.of("permission","Недостаточно прав"))); return true; }
    private boolean noPermission(CommandSender s, String permission) { Utils.msg(s, configManager.getLangMessage("no-permission", Map.of("permission", permission), "&cУ вас нет прав ({permission}).")); return true; }
    private boolean requiresPlayer(CommandSender s) { Utils.msg(s, configManager.getLangMessage("player-only-command", "&cТолько для игроков.")); return true; }
    private boolean requiresPlayerOrArg(CommandSender s) { Utils.msg(s, configManager.getLangMessage("requires-player-or-arg", "&cУкажите ник или выполните как игрок.")); return true; }
    private boolean usage(CommandSender s, String u) { Utils.msg(s, configManager.getLangMessage("usage", "&cИспользование: /{usage}").replace("{usage}", u)); return true; }
    private boolean isLeader(CommandSender s) { if(!(s instanceof Player p))return false; return Integer.valueOf(11).equals(playerManager.getPlayerRankId(p.getUniqueId())); }
    private boolean noPermissionTerritory(CommandSender s) { plugin.logInfo("[DEBUG][Territory] Permission denied for " + s.getName()); Utils.msg(s, configManager.getLangMessage("territory-no-perms", "&cНет прав на команды территории.")); return true; }
    private boolean requiresPlayerTerritory(CommandSender s) { plugin.logInfo("[DEBUG][Territory] Command requires player."); Utils.msg(s, configManager.getLangMessage("territory-player-only", "&cКоманды территории только для игроков.")); return true; }
    private record EffectiveFactionData(boolean isInFaction, @Nullable Faction faction, @Nullable Integer rankId) {}
    private EffectiveFactionData getEffectiveFactionData(Player player) { UUID uuid = player.getUniqueId(); String adminModeFactionId = playerManager.getAdminModeFactionId(uuid); if (adminModeFactionId != null) { Faction adminFaction = factionManager.getFaction(adminModeFactionId); if (adminFaction != null) return new EffectiveFactionData(true, adminFaction, 11); else { playerManager.toggleAdminMode(player, null); Utils.msg(player, configManager.getLangMessage("adminmode-error-no-faction")); return new EffectiveFactionData(false, null, null); } } else { Faction faction = playerManager.getPlayerFaction(uuid); Integer rankId = (faction != null) ? playerManager.getPlayerRankId(uuid) : null; return new EffectiveFactionData(faction != null, faction, rankId); } }


    // --- Tab Completer ---
    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>(); List<String> suggestions = new ArrayList<>(); String currentArg = args[args.length - 1].toLowerCase(); String commandLabel = command.getLabel().toLowerCase();
        if (configManager.getFactionChatAliases().contains(commandLabel) || configManager.getFineCommandAliases().contains(commandLabel)) { return completions; }
        if (args.length == 1) { if (sender.hasPermission("hfactions.command.help")) suggestions.add("help"); if (sender.hasPermission("hfactions.command.list")) suggestions.add("list"); if (sender.hasPermission("hfactions.command.info")) suggestions.add("info"); if (sender.hasPermission("hfactions.command.listrecipes")) suggestions.add("listrecipes"); if (sender.hasPermission("hfactions.player.leave")) suggestions.add("leave"); if (sender.hasPermission("hfactions.faction.invite")) suggestions.add("invite"); if (sender.hasPermission("hfactions.faction.kick")) suggestions.add("kick"); if (sender.hasPermission("hfactions.faction.promote")) suggestions.add("promote"); if (sender.hasPermission("hfactions.faction.demote")) suggestions.add("demote"); if (sender.hasPermission("hfactions.faction.setrank")) suggestions.add("setrank"); if (sender.hasPermission("hfactions.faction.manage_ranks")) suggestions.add("manageranks"); if (configManager.isWarehouseEnabled() && sender.hasPermission(configManager.getWarehouseOpenPermission())) { suggestions.add("warehouse"); suggestions.add("wh"); } if (economy != null) { if (sender.hasPermission("hfactions.faction.balance.view")) { suggestions.add("balance"); suggestions.add("bal"); } if (sender.hasPermission("hfactions.faction.deposit")) { suggestions.add("deposit"); suggestions.add("dep"); } if (sender.hasPermission("hfactions.faction.withdraw")) { suggestions.add("withdraw"); suggestions.add("wd"); } } if (configManager.isFactionChatEnabled()) { suggestions.add("chat"); suggestions.add("c"); } if (configManager.isFiningEnabled() && sender.hasPermission(configManager.getFinePermissionNode())) { suggestions.add("fine"); } if (configManager.isDynmapEnabled() && (sender.hasPermission("hfactions.admin.territory") || isLeader(sender))) { suggestions.add("territory"); } if (sender.hasPermission("hfactions.admin.uncuff")) suggestions.add("uncuff"); if (sender.hasPermission("hfactions.admin.create")) suggestions.add("create"); if (sender.hasPermission("hfactions.admin.delete")) suggestions.add("delete"); if (sender.hasPermission("hfactions.admin.reload")) suggestions.add("reload"); if (sender.hasPermission("hfactions.admin.setbalance")) suggestions.add("setbalance"); if (sender.hasPermission("hfactions.admin.adminmode")) suggestions.add("adminmode"); if (sender.hasPermission("hfactions.admin.givetaser")) suggestions.add("givetaser"); if (sender.hasPermission("hfactions.admin.givehandcuffs")) suggestions.add("givehandcuffs"); if (sender.hasPermission("hfactions.admin.giveprotocol")) suggestions.add("giveprotocol"); }
        else if (args.length >= 2) { String subCmd = args[0].toLowerCase(); if (subCmd.equals("territory")) { if (args.length == 2) { boolean isAdmin = sender.hasPermission("hfactions.admin.territory"); boolean isLdr = isLeader(sender); suggestions.add("list"); if (isAdmin || isLdr) suggestions.addAll(Arrays.asList("define", "corner", "clear", "claim", "delete")); if (isAdmin) suggestions.add("map"); suggestions.add("help"); } else if (args.length == 3) { String terSubCmd = args[1].toLowerCase(); if (Arrays.asList("list", "delete", "claim").contains(terSubCmd) && dynmapManager != null) { suggestions.addAll(dynmapManager.getAllTerritories().stream().map(data -> data.zoneName).toList()); } else if (terSubCmd.equals("define")) { suggestions.add("<название_зоны>"); } } else if (args.length == 4 && args[1].equalsIgnoreCase("claim")) { if (sender.hasPermission("hfactions.admin.territory")) { suggestions.addAll(factionManager.getAllFactions().stream().map(Faction::getId).toList()); } } } else if (Arrays.asList("info", "delete", "setbalance", "adminmode").contains(subCmd)) { if (args.length == 2) suggestions.addAll(factionManager.getAllFactions().stream().map(Faction::getId).toList()); } else if (Arrays.asList("invite", "kick", "promote", "demote", "setrank", "fine").contains(subCmd)) { if (args.length == 2) suggestions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> !name.equals(sender.getName())).toList()); } else if (subCmd.equals("setrank")) { if (args.length == 3) { Faction userFaction = null; if (sender instanceof Player) userFaction = playerManager.getPlayerFaction(((Player)sender).getUniqueId()); EffectiveFactionData effData = (sender instanceof Player) ? getEffectiveFactionData((Player)sender) : null; if (effData != null && effData.isInFaction()) { effData.faction().getRanks().keySet().forEach(rankId -> suggestions.add(String.valueOf(rankId))); } else if (sender.hasPermission("hfactions.admin.*")) for(int i=1; i<=11; i++) suggestions.add(String.valueOf(i)); } } else if (subCmd.equals("uncuff")) { if (args.length == 2) suggestions.addAll(cuffManager.getCuffedPlayerUUIDs().stream().map(Bukkit::getOfflinePlayer).filter(Objects::nonNull).map(OfflinePlayer::getName).filter(Objects::nonNull).toList()); } else if (subCmd.equals("create")) { if (args.length == 4 && !args[2].startsWith("\"")) suggestions.add("\"<Название>\""); else if (args.length >= 4) { int nameEndIdx = findQuotedStringEndIndex(args, 2); if (nameEndIdx != -1 && args.length == nameEndIdx + 1) suggestions.addAll(Arrays.stream(FactionType.values()).map(Enum::name).toList()); } } else if (Arrays.asList("givetaser", "givehandcuffs", "giveprotocol").contains(subCmd)) { if (args.length == 2) suggestions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()); } else if (Arrays.asList("deposit", "dep", "withdraw", "wd", "setbalance").contains(subCmd)) { if (args.length == 2 && subCmd.equals("setbalance")) suggestions.addAll(factionManager.getAllFactions().stream().map(Faction::getId).toList()); else if (args.length == (subCmd.equals("setbalance") ? 3 : 2)) suggestions.add("<сумма>"); } else if (subCmd.equals("fine")) { if (args.length == 3) suggestions.add("<сумма>"); else if (args.length == 4) suggestions.add("<причина>"); } }
        String currentArgLower = currentArg.toLowerCase(); for (String suggestion : suggestions) { if (suggestion.toLowerCase().startsWith(currentArgLower)) { completions.add(suggestion); } } return completions;
    }
}