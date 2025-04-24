package org.isyateq.hfactions.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material; // Импорт для проверки инвентаря
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack; // Импорт для ItemStack
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.*;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.models.PendingInvite;
import org.isyateq.hfactions.util.Utils;
import org.isyateq.hfactions.managers.CooldownManager;

import java.util.*;
import java.util.stream.Collectors;

public class FactionCommand implements CommandExecutor, TabCompleter {

    private final HFactions plugin;
    private final FactionManager factionManager;
    private final PlayerManager playerManager;
    private final ConfigManager configManager;
    private final GuiManager guiManager;
    private final ItemManager itemManager;
    private final CuffManager cuffManager; // Для команды /hf uncuff
    private final DynmapManager dynmapManager; // Для команд /hf territory

    // Список основных подкоманд для автодополнения
    private final List<String> baseSubCommands = Arrays.asList(
            "help", "list", "info", "listrecipes", "leave", "chat", "c", "fc", "fchat",
            "invite", "kick", "promote", "demote", "setrank", "manageranks",
            "balance", "bal", "deposit", "dep", "withdraw", "wd", "warehouse", "wh",
            "fine",
            "territory",
            "create", "delete", "reload", "setbalance", "uncuff", "adminmode",
            "givetaser", "givehandcuffs", "giveprotocol"
    );

    public FactionCommand(HFactions plugin) {
        this.plugin = plugin;
        this.factionManager = plugin.getFactionManager();
        this.playerManager = plugin.getPlayerManager();
        this.configManager = plugin.getConfigManager();
        this.guiManager = plugin.getGuiManager();
        this.itemManager = plugin.getItemManager();
        this.cuffManager = plugin.getCuffManager();
        this.dynmapManager = plugin.getDynmapManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        Player player = (sender instanceof Player) ? (Player) sender : null; // Определяем игрока заранее, если возможно

        // --- Обработка подкоманд ---
        switch (subCommand) {
            //<editor-fold desc="Информационные команды">
            case "help":
                if (!sender.hasPermission("hfactions.command.help")) {
                    sender.sendMessage(Utils.color("&cYou don't have permission for this command."));
                    return true;
                }
                sendHelp(sender);
                break;

            case "list":
                if (!sender.hasPermission("hfactions.command.list")) {
                    sender.sendMessage(Utils.color("&cYou don't have permission for this command."));
                    return true;
                }
                listFactions(sender);
                break;

            case "info":
                if (!sender.hasPermission("hfactions.command.info")) {
                    sender.sendMessage(Utils.color("&cYou don't have permission for this command."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Utils.color("&cUsage: /" + label + " info <faction_id>"));
                    return true;
                }
                showFactionInfo(sender, args[1]);
                break;

            case "listrecipes":
                if (!sender.hasPermission("hfactions.command.listrecipes")) {
                    sender.sendMessage(Utils.color("&cYou don't have permission for this command."));
                    return true;
                }
                // TODO: Реализовать показ кастомных рецептов из CraftingManager
                sender.sendMessage(Utils.color("&eCustom recipe listing is not implemented yet."));
                break;
            //</editor-fold>

            //<editor-fold desc="Команды игрока">
            case "leave":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (!player.hasPermission("hfactions.player.leave")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                playerManager.leaveFaction(player);
                break;

            case "chat":
            case "c":
            case "fc":
            case "fchat":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (!player.hasPermission("hfactions.player.chat")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                playerManager.toggleFactionChat(player);
                break;
            //</editor-fold>

            //<editor-fold desc="Управление фракцией (требуют прав ранга)">
            case "invite":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " invite <player>")); return true; }
                if (!checkFactionPermission(player, "hfactions.faction.invite")) return true;

                Player targetInvite = Bukkit.getPlayerExact(args[1]);
                if (targetInvite == null || !targetInvite.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }
                if (player == targetInvite) { sender.sendMessage(Utils.color("&cYou cannot invite yourself.")); return true; }
                if (playerManager.isInFaction(targetInvite)) { sender.sendMessage(Utils.color("&c" + targetInvite.getName() + " is already in a faction.")); return true; }
                if (playerManager.getInvite(targetInvite) != null) { sender.sendMessage(Utils.color("&c" + targetInvite.getName() + " already has a pending invite.")); return true; }

                Faction inviteFaction = playerManager.getPlayerFaction(player);
                if (inviteFaction == null && !playerManager.isAdminInMode(player)) { sender.sendMessage(Utils.color("&cYou are not in a faction to invite from.")); return true; }

                // Если админ в режиме, берем фракцию режима
                String adminFactionId = playerManager.getAdminModeFactionId(player);
                if(adminFactionId != null) {
                    inviteFaction = factionManager.getFaction(adminFactionId);
                    if (inviteFaction == null) { sender.sendMessage(Utils.color("&cAdmin mode faction '" + adminFactionId + "' not found.")); return true; }
                }

                long expireSeconds = configManager.getConfig().getLong("faction.invite_expire_seconds", 60);
                PendingInvite invite = new PendingInvite(player.getUniqueId(), player.getName(), inviteFaction.getId(), System.currentTimeMillis() + (expireSeconds * 1000));

                playerManager.addInvite(targetInvite, invite);
                guiManager.openInviteGUI(targetInvite, invite); // Открываем GUI у цели
                sender.sendMessage(Utils.color("&aInvite sent to " + targetInvite.getName() + " for faction " + inviteFaction.getName() + ". It expires in " + expireSeconds + " seconds."));
                targetInvite.sendMessage(Utils.color("&eYou received a faction invite from " + player.getName() + " to join " + inviteFaction.getName() + ". Use the menu or type /hf accept/decline.")); // Доп. инфо
                break;

            case "kick":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " kick <player>")); return true; }
                if (!checkFactionPermission(player, "hfactions.faction.kick")) return true;

                Player targetKick = Bukkit.getPlayerExact(args[1]);
                if (targetKick == null || !targetKick.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }
                if (player == targetKick) { sender.sendMessage(Utils.color("&cYou cannot kick yourself.")); return true; }

                String kickerFactionId = playerManager.getPlayerFactionId(player);
                String targetKickFactionId = playerManager.getPlayerFactionId(targetKick);
                String adminModeKickFaction = playerManager.getAdminModeFactionId(player);

                // Определяем фракцию, из которой кикаем (либо своя, либо админская)
                String effectiveKickFactionId = adminModeKickFaction != null ? adminModeKickFaction : kickerFactionId;
                if (effectiveKickFactionId == null) { sender.sendMessage(Utils.color("&cYou are not in a faction to kick from.")); return true; }
                if (!effectiveKickFactionId.equals(targetKickFactionId)) { sender.sendMessage(Utils.color("&c" + targetKick.getName() + " is not in your faction (or your admin mode faction).")); return true; }

                // Проверка ранга (нельзя кикнуть игрока с таким же или более высоким рангом, если не админ)
                if(adminModeKickFaction == null) { // Только если не админ
                    FactionRank kickerRank = playerManager.getPlayerRank(player);
                    FactionRank targetKickRank = playerManager.getPlayerRank(targetKick);
                    if (kickerRank == null || targetKickRank == null || kickerRank.getInternalId() <= targetKickRank.getInternalId()) {
                        sender.sendMessage(Utils.color("&cYou cannot kick a player with the same or higher rank."));
                        return true;
                    }
                }

                playerManager.kickPlayer(player, targetKick); // Логика кика в PlayerManager
                break;

            case "promote":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " promote <player>")); return true; }
                if (!checkFactionPermission(player, "hfactions.faction.promote")) return true;

                Player targetPromote = Bukkit.getPlayerExact(args[1]);
                if (targetPromote == null || !targetPromote.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }
                if (player == targetPromote) { sender.sendMessage(Utils.color("&cYou cannot promote yourself.")); return true; }

                String promoterFactionId = playerManager.getPlayerFactionId(player);
                String targetPromoteFactionId = playerManager.getPlayerFactionId(targetPromote);
                String adminModePromoteFaction = playerManager.getAdminModeFactionId(player);
                String effectivePromoteFactionId = adminModePromoteFaction != null ? adminModePromoteFaction : promoterFactionId;

                if (effectivePromoteFactionId == null) { sender.sendMessage(Utils.color("&cYou are not in a faction to promote from.")); return true; }
                if (!effectivePromoteFactionId.equals(targetPromoteFactionId)) { sender.sendMessage(Utils.color("&c" + targetPromote.getName() + " is not in your faction (or your admin mode faction).")); return true; }

                FactionRank targetPromoteRank = playerManager.getPlayerRank(targetPromote);
                if (targetPromoteRank == null) { sender.sendMessage(Utils.color("&cCould not determine rank for " + targetPromote.getName())); return true; }

                // Проверка ранга (нельзя повысить игрока до своего или выше ранга, если не админ)
                if (adminModePromoteFaction == null) {
                    FactionRank promoterRank = playerManager.getPlayerRank(player);
                    if (promoterRank == null || promoterRank.getInternalId() <= targetPromoteRank.getInternalId() + 1) { // +1 т.к. повышаем НА следующий ранг
                        sender.sendMessage(Utils.color("&cYou cannot promote a player to the same or higher rank than yourself."));
                        return true;
                    }
                }
                // Дальнейшая логика повышения в PlayerManager
                playerManager.promotePlayer(player, targetPromote);
                break;

            case "demote":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " demote <player>")); return true; }
                if (!checkFactionPermission(player, "hfactions.faction.demote")) return true;

                Player targetDemote = Bukkit.getPlayerExact(args[1]);
                if (targetDemote == null || !targetDemote.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }
                if (player == targetDemote) { sender.sendMessage(Utils.color("&cYou cannot demote yourself.")); return true; }

                String demoterFactionId = playerManager.getPlayerFactionId(player);
                String targetDemoteFactionId = playerManager.getPlayerFactionId(targetDemote);
                String adminModeDemoteFaction = playerManager.getAdminModeFactionId(player);
                String effectiveDemoteFactionId = adminModeDemoteFaction != null ? adminModeDemoteFaction : demoterFactionId;

                if (effectiveDemoteFactionId == null) { sender.sendMessage(Utils.color("&cYou are not in a faction to demote from.")); return true; }
                if (!effectiveDemoteFactionId.equals(targetDemoteFactionId)) { sender.sendMessage(Utils.color("&c" + targetDemote.getName() + " is not in your faction (or your admin mode faction).")); return true; }

                // Проверка ранга (нельзя понизить игрока с таким же или более высоким рангом, если не админ)
                if (adminModeDemoteFaction == null) {
                    FactionRank demoterRank = playerManager.getPlayerRank(player);
                    FactionRank targetDemoteRank = playerManager.getPlayerRank(targetDemote);
                    if (demoterRank == null || targetDemoteRank == null || demoterRank.getInternalId() <= targetDemoteRank.getInternalId()) {
                        sender.sendMessage(Utils.color("&cYou cannot demote a player with the same or higher rank than yourself."));
                        return true;
                    }
                }
                // Дальнейшая логика в PlayerManager
                playerManager.demotePlayer(player, targetDemote);
                break;

            case "setrank":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (args.length < 3) { sender.sendMessage(Utils.color("&cUsage: /" + label + " setrank <player> <rank_id>")); return true; }
                if (!checkFactionPermission(player, "hfactions.faction.setrank")) return true;

                Player targetSetRank = Bukkit.getPlayerExact(args[1]);
                if (targetSetRank == null || !targetSetRank.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }
                if (player == targetSetRank) { sender.sendMessage(Utils.color("&cYou cannot set your own rank using this command.")); return true; }

                int rankId;
                try {
                    rankId = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Utils.color("&cInvalid rank ID: '" + args[2] + "'. Must be a number."));
                    return true;
                }

                String setterFactionId = playerManager.getPlayerFactionId(player);
                String targetSetRankFactionId = playerManager.getPlayerFactionId(targetSetRank);
                String adminModeSetRankFaction = playerManager.getAdminModeFactionId(player);
                String effectiveSetRankFactionId = adminModeSetRankFaction != null ? adminModeSetRankFaction : setterFactionId;

                if (effectiveSetRankFactionId == null) { sender.sendMessage(Utils.color("&cYou are not in a faction to set rank from.")); return true; }
                if (!effectiveSetRankFactionId.equals(targetSetRankFactionId)) { sender.sendMessage(Utils.color("&c" + targetSetRank.getName() + " is not in your faction (or your admin mode faction).")); return true; }

                Faction setRankFaction = factionManager.getFaction(effectiveSetRankFactionId);
                if (setRankFaction == null) { sender.sendMessage(Utils.color("&cError: Faction " + effectiveSetRankFactionId + " not found internally.")); return true; }
                if (setRankFaction.getRank(rankId) == null) { sender.sendMessage(Utils.color("&cRank ID " + rankId + " does not exist in faction " + setRankFaction.getName() + ".")); return true; }

                // Проверка ранга (нельзя ставить ранг выше своего, если не админ)
                if (adminModeSetRankFaction == null) {
                    FactionRank setterRank = playerManager.getPlayerRank(player);
                    if (setterRank == null || setterRank.getInternalId() < rankId) {
                        sender.sendMessage(Utils.color("&cYou cannot set a player's rank higher than your own rank."));
                        return true;
                    }
                }
                // Дальнейшая логика в PlayerManager
                playerManager.setPlayerRank(player, targetSetRank, rankId);
                break;

            case "manageranks":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (!checkFactionPermission(player, "hfactions.faction.manage_ranks")) return true;

                Faction manageRanksFaction = playerManager.getPlayerFaction(player);
                String adminModeManageRanksFaction = playerManager.getAdminModeFactionId(player);
                if(adminModeManageRanksFaction != null) {
                    manageRanksFaction = factionManager.getFaction(adminModeManageRanksFaction);
                }

                if (manageRanksFaction == null) { sender.sendMessage(Utils.color("&cCould not determine faction to manage ranks for.")); return true; }

                guiManager.openRanksGUI(player, manageRanksFaction);
                break;

            case "balance":
            case "bal":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (!checkFactionPermission(player, "hfactions.faction.balance.view")) return true;

                Faction balanceFaction = playerManager.getPlayerFaction(player);
                String adminModeBalanceFaction = playerManager.getAdminModeFactionId(player);
                if(adminModeBalanceFaction != null) {
                    balanceFaction = factionManager.getFaction(adminModeBalanceFaction);
                }

                if (balanceFaction == null) { sender.sendMessage(Utils.color("&cCould not determine faction to view balance for.")); return true; }

                sender.sendMessage(Utils.color("&eFaction Balance (" + balanceFaction.getName() + "): &a$" + String.format("%.2f", balanceFaction.getBalance())));
                break;

            case "deposit":
            case "dep":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " deposit <amount>")); return true; }
                if (!checkFactionPermission(player, "hfactions.faction.deposit")) return true;

                double depositAmount;
                try {
                    depositAmount = Double.parseDouble(args[1]);
                    if (depositAmount <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    sender.sendMessage(Utils.color("&cInvalid amount. Please enter a positive number."));
                    return true;
                }

                Faction depositFaction = playerManager.getPlayerFaction(player);
                String adminModeDepositFaction = playerManager.getAdminModeFactionId(player);
                if(adminModeDepositFaction != null) {
                    depositFaction = factionManager.getFaction(adminModeDepositFaction);
                }

                if (depositFaction == null) { sender.sendMessage(Utils.color("&cCould not determine faction to deposit to.")); return true; }

                // Проверка баланса игрока и снятие через Vault
                if (!plugin.getVaultIntegration().hasEnough(player, depositAmount)) {
                    sender.sendMessage(Utils.color("&cYou don't have enough money to deposit $" + String.format("%.2f", depositAmount)));
                    return true;
                }

                if (plugin.getVaultIntegration().withdrawPlayer(player, depositAmount)) {
                    factionManager.depositToFaction(depositFaction.getId(), depositAmount);
                    factionManager.saveModifiedFactions(); // Сохраняем изменение баланса
                    sender.sendMessage(Utils.color("&aSuccessfully deposited $" + String.format("%.2f", depositAmount) + " into the faction treasury."));
                    playerManager.broadcastToFaction(depositFaction.getId(), Utils.color("&e" + player.getName() + " deposited $" + String.format("%.2f", depositAmount) + " into the treasury. New balance: $" + String.format("%.2f", depositFaction.getBalance())));
                } else {
                    sender.sendMessage(Utils.color("&cFailed to withdraw money from your account. Transaction cancelled."));
                }
                break;

            case "withdraw":
            case "wd":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " withdraw <amount>")); return true; }
                // Проверяем withdraw ИЛИ manage_balance
                if (!checkFactionPermission(player, "hfactions.faction.withdraw", "hfactions.faction.manage_balance")) return true;


                double withdrawAmount;
                try {
                    withdrawAmount = Double.parseDouble(args[1]);
                    if (withdrawAmount <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    sender.sendMessage(Utils.color("&cInvalid amount. Please enter a positive number."));
                    return true;
                }

                Faction withdrawFaction = playerManager.getPlayerFaction(player);
                String adminModeWithdrawFaction = playerManager.getAdminModeFactionId(player);
                if(adminModeWithdrawFaction != null) {
                    withdrawFaction = factionManager.getFaction(adminModeWithdrawFaction);
                }

                if (withdrawFaction == null) { sender.sendMessage(Utils.color("&cCould not determine faction to withdraw from.")); return true; }

                // Снятие из казны фракции
                if (factionManager.withdrawFromFaction(withdrawFaction.getId(), withdrawAmount)) {
                    factionManager.saveModifiedFactions(); // Сохраняем изменение баланса
                    // Зачисление игроку через Vault
                    if (plugin.getVaultIntegration().depositPlayer(player, withdrawAmount)) {
                        sender.sendMessage(Utils.color("&aSuccessfully withdrew $" + String.format("%.2f", withdrawAmount) + " from the faction treasury."));
                        playerManager.broadcastToFaction(withdrawFaction.getId(), Utils.color("&e" + player.getName() + " withdrew $" + String.format("%.2f", withdrawAmount) + " from the treasury. New balance: $" + String.format("%.2f", withdrawFaction.getBalance())));
                    } else {
                        sender.sendMessage(Utils.color("&cFailed to deposit money into your account. Refunding faction..."));
                        // Возвращаем деньги фракции, если не удалось зачислить игроку
                        factionManager.depositToFaction(withdrawFaction.getId(), withdrawAmount);
                        factionManager.saveModifiedFactions();
                    }
                } else {
                    sender.sendMessage(Utils.color("&cFailed to withdraw from faction treasury. Insufficient funds? Current balance: $" + String.format("%.2f", withdrawFaction.getBalance())));
                }
                break;

            case "warehouse":
            case "wh":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                // Право на ОТКРЫТИЕ склада
                if (!checkFactionPermission(player, "hfactions.faction.warehouse.open")) return true;
                guiManager.openWarehouseGUI(player, 1); // Открываем первую страницу
                break;
            //</editor-fold>

            //<editor-fold desc="Штрафы (PD)">
            case "fine":
                // Право: hfactions.pd.fine (и ранг, если настроено)
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (!player.hasPermission("hfactions.pd.fine")) { sender.sendMessage(Utils.color("&cYou don't have permission to issue fines.")); return true; }

                // Проверка минимального ранга для штрафа
                int minRank = configManager.getFineMinRank();
                FactionRank playerFineRank = playerManager.getPlayerRank(player);
                if (playerFineRank == null || playerFineRank.getInternalId() < minRank) {
                    sender.sendMessage(Utils.color("&cYour rank is too low to issue fines. Required rank: " + minRank));
                    return true;
                }

                if (args.length < 4) {
                    sender.sendMessage(Utils.color("&cUsage: /" + label + " fine <player> <amount> <reason>"));
                    return true;
                }

                Player targetFine = Bukkit.getPlayerExact(args[1]);
                if (targetFine == null || !targetFine.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }
                if (targetFine == player) { sender.sendMessage(Utils.color("&cYou cannot fine yourself.")); return true; }

                double fineAmount;
                try {
                    fineAmount = Double.parseDouble(args[2]);
                    if (fineAmount <= 0) throw new NumberFormatException();
                    // TODO: Проверка максимальной суммы штрафа из конфига?
                } catch (NumberFormatException e) {
                    sender.sendMessage(Utils.color("&cInvalid fine amount: '" + args[2] + "'."));
                    return true;
                }

                String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

                // Проверка кулдауна на цель
                if (plugin.getCooldownManager().isOnCooldown("fine_target_" + targetFine.getUniqueId())) {
                    long remaining = plugin.getCooldownManager().getRemainingCooldown("fine_target_" + targetFine.getUniqueId());
                    sender.sendMessage(Utils.color("&cYou must wait " + String.format("%.1f", remaining / 1000.0) + " seconds before fining this player again."));
                    return true;
                }

                // Выполнение штрафа (логика из ProtocolListener.performFine)
                if (!plugin.getVaultIntegration().hasEnough(targetFine, fineAmount)) {
                    sender.sendMessage(Utils.color("&c" + targetFine.getName() + " does not have enough money to pay the fine ($" + String.format("%.2f", fineAmount) + ")."));
                    return true;
                }

                if (plugin.getVaultIntegration().withdrawPlayer(targetFine, fineAmount)) {
                    // Определяем получателя
                    String recipientType = configManager.getFineRecipientType();
                    boolean paid = false;
                    if ("player".equalsIgnoreCase(recipientType)) {
                        plugin.getVaultIntegration().depositPlayer(player, fineAmount);
                        paid = true;
                    } else if ("faction".equalsIgnoreCase(recipientType)) {
                        Faction playerFaction = playerManager.getPlayerFaction(player);
                        if (playerFaction != null) {
                            factionManager.depositToFaction(playerFaction.getId(), fineAmount);
                            factionManager.saveModifiedFactions();
                            paid = true;
                        } else {
                            sender.sendMessage(Utils.color("&cCould not deposit fine to faction treasury (you are not in a faction?). Fine cancelled, money returned to target."));
                            plugin.getVaultIntegration().depositPlayer(targetFine, fineAmount); // Возврат денег
                            return true;
                        }
                    } else { // По умолчанию "server" (деньги сгорают)
                        paid = true; // Считаем что успешно "уплачено" серверу
                    }

                    if(paid) {
                        targetFine.sendMessage(Utils.color("&cYou have been fined $" + String.format("%.2f", fineAmount) + " by " + player.getName() + ". Reason: " + reason));
                        player.sendMessage(Utils.color("&aSuccessfully fined " + targetFine.getName() + " $" + String.format("%.2f", fineAmount) + ". Reason: " + reason));
                        // Логирование
                        plugin.getLogger().info("FINE: " + player.getName() + " fined " + targetFine.getName() + " $" + String.format("%.2f", fineAmount) + " Reason: " + reason);
                        // Установить кулдаун на цель
                        plugin.getCooldownManager().setCooldown("fine_target_" + targetFine.getUniqueId(), configManager.getFineCooldownTargetSeconds() * 1000L);
                    }

                } else {
                    sender.sendMessage(Utils.color("&cFailed to withdraw money from " + targetFine.getName() + "'s account."));
                }
                break;
            //</editor-fold>

            //<editor-fold desc="Территории (Dynmap)">
            case "territory":
                if (dynmapManager == null || !dynmapManager.isDynmapEnabled()) {
                    sender.sendMessage(Utils.color("&cDynmap integration is disabled or Dynmap plugin not found. Territory commands are unavailable."));
                    return true;
                }
                // Передаем управление DynmapManager'у
                dynmapManager.handleTerritoryCommand(sender, label, args);
                break;
            //</editor-fold>

            //<editor-fold desc="Административные команды">
            case "create":
                if (!sender.hasPermission("hfactions.admin.create")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                if (args.length < 4) { sender.sendMessage(Utils.color("&cUsage: /" + label + " create <id> \"<Name>\" <STATE|CRIMINAL|OTHER>")); return true; }

                String createId = args[1].toLowerCase();
                String createTypeStr = args[args.length - 1].toUpperCase();
                FactionType createType;
                try { createType = FactionType.valueOf(createTypeStr); }
                catch (IllegalArgumentException e) { /* ... обработка ошибки типа ... */ return true; }

                String createName;
                if (args.length == 4) { createName = args[2]; }
                else { createName = String.join(" ", Arrays.copyOfRange(args, 2, args.length - 1)); }
                if (createName.startsWith("\"") && createName.endsWith("\"") && createName.length() > 1) { createName = createName.substring(1, createName.length() - 1); }
                if (createName.isEmpty()) { /* ... ошибка пустого имени ... */ return true; }
                if (factionManager.getFaction(createId) != null) { /* ... ошибка существования ID ... */ return true; }

                String defaultColor = configManager.getConfig().getString("faction.defaults.color", "#FFFFFF");
                String defaultPrefix = configManager.getConfig().getString("faction.defaults.prefix", "[" + createId.toUpperCase() + "]");
                double defaultBalance = configManager.getConfig().getDouble("faction.defaults.balance", 0.0);
                int defaultWarehouseSize = configManager.getConfig().getInt("faction.defaults.warehouse_size", 54);

                factionManager.createFaction(createId, createName, createType, defaultColor, defaultPrefix, defaultBalance, defaultWarehouseSize);
                sender.sendMessage(Utils.color("&aFaction '" + createName + "' (ID: " + createId + ", Type: " + createType + ") created successfully!"));
                break;

            case "delete":
                if (!sender.hasPermission("hfactions.admin.delete")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " delete <faction_id>")); return true; }
                String deleteId = args[1].toLowerCase();
                if (factionManager.getFaction(deleteId) == null) { sender.sendMessage(Utils.color("&cFaction with ID '" + deleteId + "' not found.")); return true; }

                // TODO: Добавить подтверждение?
                factionManager.deleteFaction(deleteId); // Метод уже содержит логирование и сохранение
                sender.sendMessage(Utils.color("&aFaction with ID '" + deleteId + "' has been deleted."));
                break;

            case "reload":
                if (!sender.hasPermission("hfactions.admin.reload")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                reloadPlugin(sender);
                break;

            case "setbalance":
                if (!sender.hasPermission("hfactions.admin.setbalance")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                if (args.length < 3) { sender.sendMessage(Utils.color("&cUsage: /" + label + " setbalance <faction_id> <amount>")); return true; }
                String balId = args[1].toLowerCase();
                double balAmount;
                try { balAmount = Double.parseDouble(args[2]); if(balAmount < 0) throw new NumberFormatException(); }
                catch (NumberFormatException e) { sender.sendMessage(Utils.color("&cInvalid amount. Must be a non-negative number.")); return true; }

                Faction balFaction = factionManager.getFaction(balId);
                if (balFaction == null) { sender.sendMessage(Utils.color("&cFaction with ID '" + balId + "' not found.")); return true; }

                factionManager.setFactionBalance(balId, balAmount);
                factionManager.saveModifiedFactions(); // Сохраняем изменение
                sender.sendMessage(Utils.color("&aBalance for faction " + balFaction.getName() + " set to $" + String.format("%.2f", balAmount)));
                break;

            case "uncuff":
                if (!sender.hasPermission("hfactions.admin.uncuff")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " uncuff <player>")); return true; }
                Player targetUncuff = Bukkit.getPlayerExact(args[1]);
                if (targetUncuff == null || !targetUncuff.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }

                if (!cuffManager.isCuffed(targetUncuff)) { sender.sendMessage(Utils.color("&c" + targetUncuff.getName() + " is not cuffed.")); return true; }

                cuffManager.uncuffPlayer(targetUncuff, sender.getName()); // Снимаем от имени администратора
                sender.sendMessage(Utils.color("&aYou have uncuffed " + targetUncuff.getName() + "."));
                targetUncuff.sendMessage(Utils.color("&eYou have been uncuffed by an administrator (" + sender.getName() + ")."));
                break;

            case "adminmode":
                if (player == null) { sender.sendMessage(Utils.color("&cThis command can only be run by a player.")); return true; }
                if (!player.hasPermission("hfactions.admin.adminmode")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }

                if (playerManager.isAdminInMode(player)) {
                    playerManager.exitAdminMode(player, false); // Выход с сообщением
                } else {
                    if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " adminmode <faction_id>")); return true; }
                    String adminFactionModeId = args[1].toLowerCase();
                    Faction adminModeTargetFaction = factionManager.getFaction(adminFactionModeId);
                    if (adminModeTargetFaction == null) { sender.sendMessage(Utils.color("&cFaction with ID '" + adminFactionModeId + "' not found.")); return true; }
                    playerManager.enterAdminMode(player, adminModeTargetFaction);
                }
                break;

            case "givetaser":
                if (!sender.hasPermission("hfactions.admin.givetaser")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " givetaser <player>")); return true; }
                Player targetTaser = Bukkit.getPlayerExact(args[1]);
                if (targetTaser == null || !targetTaser.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }

                ItemStack taser = itemManager.createTaser();
                if (taser == null) { sender.sendMessage(Utils.color("&cCould not create Taser item. Check server logs.")); return true; }

                HashMap<Integer, ItemStack> leftoverTaser = targetTaser.getInventory().addItem(taser);
                if (leftoverTaser.isEmpty()) {
                    sender.sendMessage(Utils.color("&aTaser given to " + targetTaser.getName() + "."));
                    targetTaser.sendMessage(Utils.color("&eYou received a Taser from " + sender.getName() + "."));
                } else {
                    targetTaser.getWorld().dropItemNaturally(targetTaser.getLocation(), taser);
                    sender.sendMessage(Utils.color("&aTaser given to " + targetTaser.getName() + " (dropped on ground, inventory full)."));
                    targetTaser.sendMessage(Utils.color("&eYou received a Taser from " + sender.getName() + ", but inventory full. Dropped nearby."));
                }
                break;

            case "givehandcuffs":
                if (!sender.hasPermission("hfactions.admin.givehandcuffs")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " givehandcuffs <player>")); return true; }
                Player targetCuffs = Bukkit.getPlayerExact(args[1]);
                if (targetCuffs == null || !targetCuffs.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }

                ItemStack cuffs = itemManager.createHandcuffs();
                if (cuffs == null) { sender.sendMessage(Utils.color("&cCould not create Handcuffs item. Check server logs.")); return true; }

                HashMap<Integer, ItemStack> leftoverCuffs = targetCuffs.getInventory().addItem(cuffs);
                if (leftoverCuffs.isEmpty()) {
                    sender.sendMessage(Utils.color("&aHandcuffs given to " + targetCuffs.getName() + "."));
                    targetCuffs.sendMessage(Utils.color("&eYou received Handcuffs from " + sender.getName() + "."));
                } else {
                    targetCuffs.getWorld().dropItemNaturally(targetCuffs.getLocation(), cuffs);
                    sender.sendMessage(Utils.color("&aHandcuffs given to " + targetCuffs.getName() + " (dropped on ground, inventory full)."));
                    targetCuffs.sendMessage(Utils.color("&eYou received Handcuffs from " + sender.getName() + ", but inventory full. Dropped nearby."));
                }
                break;

            case "giveprotocol":
                if (!sender.hasPermission("hfactions.admin.giveprotocol")) { sender.sendMessage(Utils.color("&cYou don't have permission.")); return true; }
                if (args.length < 2) { sender.sendMessage(Utils.color("&cUsage: /" + label + " giveprotocol <player>")); return true; }
                Player targetProto = Bukkit.getPlayerExact(args[1]);
                if (targetProto == null || !targetProto.isOnline()) { sender.sendMessage(Utils.color("&cPlayer '" + args[1] + "' not found or offline.")); return true; }

                ItemStack proto = itemManager.createProtocol();
                if (proto == null) { sender.sendMessage(Utils.color("&cCould not create Protocol item. Check server logs.")); return true; }

                HashMap<Integer, ItemStack> leftoverProto = targetProto.getInventory().addItem(proto);
                if (leftoverProto.isEmpty()) {
                    sender.sendMessage(Utils.color("&aProtocol given to " + targetProto.getName() + "."));
                    targetProto.sendMessage(Utils.color("&eYou received a Protocol from " + sender.getName() + "."));
                } else {
                    targetProto.getWorld().dropItemNaturally(targetProto.getLocation(), proto);
                    sender.sendMessage(Utils.color("&aProtocol given to " + targetProto.getName() + " (dropped on ground, inventory full)."));
                    targetProto.sendMessage(Utils.color("&eYou received a Protocol from " + sender.getName() + ", but inventory full. Dropped nearby."));
                }
                break;
            //</editor-fold>

            default:
                sender.sendMessage(Utils.color("&cUnknown command: /" + label + " " + subCommand));
                sender.sendMessage(Utils.color("&eUse '/" + label + " help' for a list of commands."));
                break;
        }

        return true;
    }

    //<editor-fold desc="Вспомогательные методы команд">
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Utils.color("&6--- HFactions Help ---"));
        // Основные
        sender.sendMessage(Utils.color("&e/hf help &7- Show this help"));
        sender.sendMessage(Utils.color("&e/hf list &7- List all factions"));
        sender.sendMessage(Utils.color("&e/hf info <id> &7- Show faction details"));
        sender.sendMessage(Utils.color("&e/hf listrecipes &7- List custom craft recipes"));
        sender.sendMessage(Utils.color("&e/hf leave &7- Leave your current faction"));
        sender.sendMessage(Utils.color("&e/hf chat &7- Toggle faction chat"));
        // Управление (если есть права)
        if (hasAnyFactionPermission(sender, "invite", "kick", "promote", "demote", "setrank", "manageranks")) {
            sender.sendMessage(Utils.color("&6--- Faction Management ---"));
            if (hasFactionPermission(sender, "invite")) sender.sendMessage(Utils.color("&e/hf invite <player>"));
            if (hasFactionPermission(sender, "kick")) sender.sendMessage(Utils.color("&e/hf kick <player>"));
            if (hasFactionPermission(sender, "promote")) sender.sendMessage(Utils.color("&e/hf promote <player>"));
            if (hasFactionPermission(sender, "demote")) sender.sendMessage(Utils.color("&e/hf demote <player>"));
            if (hasFactionPermission(sender, "setrank")) sender.sendMessage(Utils.color("&e/hf setrank <player> <rank_id>"));
            if (hasFactionPermission(sender, "manageranks")) sender.sendMessage(Utils.color("&e/hf manageranks"));
        }
        // Экономика и Склад (если есть права)
        if (hasAnyFactionPermission(sender, "balance.view", "deposit", "withdraw", "warehouse.open")) {
            sender.sendMessage(Utils.color("&6--- Treasury & Warehouse ---"));
            if (hasFactionPermission(sender, "balance.view")) sender.sendMessage(Utils.color("&e/hf balance"));
            if (hasFactionPermission(sender, "deposit")) sender.sendMessage(Utils.color("&e/hf deposit <amount>"));
            if (hasFactionPermission(sender, "withdraw")) sender.sendMessage(Utils.color("&e/hf withdraw <amount>"));
            if (hasFactionPermission(sender, "warehouse.open")) sender.sendMessage(Utils.color("&e/hf warehouse"));
        }
        // Штрафы (если есть права)
        if (sender.hasPermission("hfactions.pd.fine")) {
            sender.sendMessage(Utils.color("&6--- Police ---"));
            sender.sendMessage(Utils.color("&e/hf fine <player> <amount> <reason>"));
        }
        // Территории (если включен Dynmap и есть права)
        if (dynmapManager != null && dynmapManager.isDynmapEnabled() && (sender.hasPermission("hfactions.territory.help") || sender.hasPermission("hfactions.admin.territory"))) {
            sender.sendMessage(Utils.color("&6--- Territories (Dynmap) ---"));
            sender.sendMessage(Utils.color("&e/hf territory help"));
        }
        // Админ (если есть права)
        if (sender.hasPermission("hfactions.admin.reload") || sender.hasPermission("hfactions.admin.create")) { // Добавь другие админ права по аналогии
            sender.sendMessage(Utils.color("&c--- Admin ---"));
            if (sender.hasPermission("hfactions.admin.create")) sender.sendMessage(Utils.color("&e/hf create <id> \"<Name>\" <Type>"));
            if (sender.hasPermission("hfactions.admin.delete")) sender.sendMessage(Utils.color("&e/hf delete <id>"));
            if (sender.hasPermission("hfactions.admin.reload")) sender.sendMessage(Utils.color("&e/hf reload"));
            if (sender.hasPermission("hfactions.admin.setbalance")) sender.sendMessage(Utils.color("&e/hf setbalance <id> <amount>"));
            if (sender.hasPermission("hfactions.admin.uncuff")) sender.sendMessage(Utils.color("&e/hf uncuff <player>"));
            if (sender.hasPermission("hfactions.admin.adminmode")) sender.sendMessage(Utils.color("&e/hf adminmode [id]"));
            if (sender.hasPermission("hfactions.admin.givetaser")) sender.sendMessage(Utils.color("&e/hf givetaser <player>"));
            if (sender.hasPermission("hfactions.admin.givehandcuffs")) sender.sendMessage(Utils.color("&e/hf givehandcuffs <player>"));
            if (sender.hasPermission("hfactions.admin.giveprotocol")) sender.sendMessage(Utils.color("&e/hf giveprotocol <player>"));
            if (sender.hasPermission("hfactions.admin.territory")) sender.sendMessage(Utils.color("&e/hf territory ... (Admin commands)"));
        }
    }

    private void listFactions(CommandSender sender) {
        sender.sendMessage(Utils.color("&6--- Factions List ---"));
        Collection<Faction> allFactions = factionManager.getAllFactions().values();
        if (allFactions.isEmpty()) {
            sender.sendMessage(Utils.color("&7No factions have been created yet."));
            return;
        }
        allFactions.stream()
                .sorted(Comparator.comparing(Faction::getName)) // Сортируем по имени
                .forEach(faction -> {
                    String colorPrefix = faction.getPrefix() != null ? Utils.color(faction.getPrefix()) : "";
                    sender.sendMessage(colorPrefix + " " + faction.getName() + Utils.color(" &7(ID: " + faction.getId() + ", Type: " + faction.getType() + ")"));
                });
    }

    private void showFactionInfo(CommandSender sender, String factionId) {
        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) {
            sender.sendMessage(Utils.color("&cFaction with ID '" + factionId + "' not found."));
            return;
        }

        sender.sendMessage(Utils.color("&6--- Faction Info: " + Utils.color(faction.getPrefix()) + " " + faction.getName() + "&6 ---"));
        sender.sendMessage(Utils.color("&eID: &f" + faction.getId()));
        sender.sendMessage(Utils.color("&eType: &f" + faction.getType()));
        // Показываем цвет HEX и сам цвет
        try {
            net.md_5.bungee.api.ChatColor hexColor = net.md_5.bungee.api.ChatColor.of(faction.getColor());
            sender.sendMessage(Utils.color("&eColor: &f" + faction.getColor() + " (" + hexColor + "■&f)"));
        } catch (Exception e) { // Если цвет некорректный
            sender.sendMessage(Utils.color("&eColor: &f" + faction.getColor() + " &c(Invalid HEX)"));
        }
        sender.sendMessage(Utils.color("&ePrefix: &f" + faction.getPrefix() + " (" + Utils.color(faction.getPrefix()) + "&f)"));
        sender.sendMessage(Utils.color("&eBalance: &a$" + String.format("%.2f", faction.getBalance())));
        sender.sendMessage(Utils.color("&eWarehouse Size: &f" + faction.getWarehouseSize()));
        sender.sendMessage(Utils.color("&eRanks ("+ faction.getRanks().size() + "):"));
        faction.getRanks().values().stream()
                .sorted(Comparator.comparingInt(FactionRank::getInternalId))
                .forEach(rank -> sender.sendMessage(Utils.color("  &7- [&f" + rank.getInternalId() + "&7] &f" + rank.getDisplayName() + " &7(Salary: &a$" + String.format("%.2f", rank.getSalary()) + "&7)")));

        // Показ участников онлайн
        List<Player> onlineMembers = playerManager.getOnlineFactionMembers(faction.getId());
        if (!onlineMembers.isEmpty()) {
            sender.sendMessage(Utils.color("&eOnline Members (&a" + onlineMembers.size() + "&e):"));
            String membersList = onlineMembers.stream()
                    .map(p -> {
                        FactionRank r = playerManager.getPlayerRank(p);
                        return "&a" + p.getName() + "&7[&f" + (r != null ? r.getInternalId() : "?") + "&7]";
                    })
                    .collect(Collectors.joining(Utils.color("&f, ")));
            sender.sendMessage("  " + Utils.color(membersList));
        } else {
            sender.sendMessage(Utils.color("&eOnline Members: &cNone"));
        }
        // TODO: Показ оффлайн участников (требует запроса к БД по faction_id)
    }

    private void reloadPlugin(CommandSender sender) {
        sender.sendMessage(Utils.color("&eReloading HFactions configuration..."));
        // Сохраняем измененные фракции перед перезагрузкой
        factionManager.saveModifiedFactions();
        // Перезагружаем конфиги
        configManager.reloadConfigs();
        // Перезагружаем фракции
        factionManager.reloadFactions();
        // Перезагружаем данные онлайн игроков (новые данные из БД перечитаются при входе)
        // playerManager.reloadPlayerData(); // Этот метод больше не нужен
        // Перезагружаем крафты, если есть CraftingManager
        if (plugin.getCraftingManager() != null) {
            plugin.getCraftingManager().loadRecipes(); // Пример
            sender.sendMessage(Utils.color("&eCrafting recipes reloaded."));
        }
        // Перезагружаем территории Dynmap
        if (dynmapManager != null && dynmapManager.isDynmapEnabled()) {
            dynmapManager.reloadTerritories();
            sender.sendMessage(Utils.color("&eDynmap territories reloaded."));
        }

        sender.sendMessage(Utils.color("&aHFactions configuration reloaded successfully!"));
        sender.sendMessage(Utils.color("&eNote: Player data is now handled by the database and is not fully reloaded by this command. Online players' permissions might need manual refresh if rank permissions changed significantly."));
    }

    /**
     * Проверяет, имеет ли игрок право фракции (базовое право ИЛИ право ранга ИЛИ он админ в режиме).
     * @param player Игрок
     * @param basePermission Базовое право (e.g., hfactions.faction.invite)
     * @return true, если право есть, иначе false (и отправляет сообщение игроку)
     */
    private boolean checkFactionPermission(Player player, String basePermission) {
        // 1. Проверка админского режима
        if (playerManager.isAdminInMode(player)) {
            return true; // Админ в режиме может все
        }
        // 2. Проверка базового права LuckPerms
        if (player.hasPermission(basePermission)) {
            return true;
        }
        // 3. Проверка прав ранга
        FactionRank rank = playerManager.getPlayerRank(player);
        if (rank != null && rank.getPermissions().contains(basePermission)) {
            return true;
        }
        // Если ни одно условие не выполнено
        player.sendMessage(Utils.color("&cYou do not have permission for this action. Required: '" + basePermission + "' or appropriate rank."));
        return false;
    }

    /**
     * Проверяет, имеет ли игрок ОДНО ИЗ прав фракции.
     * @param player Игрок
     * @param permissions Список базовых прав для проверки
     * @return true, если есть хотя бы одно право
     */
    private boolean checkFactionPermission(Player player, String... permissions) {
        if (playerManager.isAdminInMode(player)) return true; // Админ может все
        if (Arrays.stream(permissions).anyMatch(player::hasPermission)) return true; // Проверка базовых прав

        FactionRank rank = playerManager.getPlayerRank(player);
        if (rank != null && Arrays.stream(permissions).anyMatch(perm -> rank.getPermissions().contains(perm))) {
            return true; // Проверка прав ранга
        }

        player.sendMessage(Utils.color("&cYou do not have permission for this action. Required one of: " + String.join(", ", permissions) + " or appropriate rank."));
        return false;
    }

    /**
     * Вспомогательный метод для проверки прав для /hf help
     */
    private boolean hasFactionPermission(CommandSender sender, String permissionSuffix) {
        String basePermission = "hfactions.faction." + permissionSuffix;
        if (sender.hasPermission(basePermission)) return true;
        if (sender instanceof Player) {
            Player p = (Player) sender;
            if (playerManager.isAdminInMode(p)) return true;
            FactionRank rank = playerManager.getPlayerRank(p);
            if (rank != null && rank.getPermissions().contains(basePermission)) return true;
        }
        return false;
    }

    /**
     * Вспомогательный метод для проверки наличия ХОТЯ БЫ ОДНОГО права для /hf help
     */
    private boolean hasAnyFactionPermission(CommandSender sender, String... permissionSuffixes) {
        for (String suffix : permissionSuffixes) {
            if (hasFactionPermission(sender, suffix)) return true;
        }
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Tab Completer">
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        String input = args[args.length - 1].toLowerCase(); // Последний введенный аргумент

        // Автодополнение для первой подкоманды
        if (args.length == 1) {
            baseSubCommands.stream()
                    .filter(sub -> sub.startsWith(input))
                    // Фильтруем по правам (упрощенно, т.к. полная проверка сложна)
                    .filter(sub -> checkTabPermission(sender, sub))
                    .forEach(completions::add);
        }
        // Автодополнение для последующих аргументов
        else if (args.length >= 2) {
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                // Команды, ожидающие ID фракции
                case "info":
                case "delete":
                case "setbalance":
                case "adminmode":
                    if (args.length == 2) {
                        factionManager.getAllFactions().keySet().stream()
                                .filter(id -> id.toLowerCase().startsWith(input))
                                .forEach(completions::add);
                    }
                    break;

                // Команды, ожидающие ник игрока
                case "invite":
                case "kick":
                case "promote":
                case "demote":
                case "setrank":
                case "fine":
                case "uncuff":
                case "givetaser":
                case "givehandcuffs":
                case "giveprotocol":
                    if (args.length == 2) {
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(name -> name.toLowerCase().startsWith(input))
                                .forEach(completions::add);
                    }
                    // Для setrank ожидаем ID ранга третьим аргументом
                    else if (subCommand.equals("setrank") && args.length == 3) {
                        // Предлагаем ID рангов (1-11), если ID фракции уже введен
                        String targetPlayerName = args[1];
                        Player targetPlayer = Bukkit.getPlayerExact(targetPlayerName);
                        if (targetPlayer != null) {
                            String targetFactionId = playerManager.getPlayerFactionId(targetPlayer);
                            if(targetFactionId != null) {
                                Faction targetFaction = factionManager.getFaction(targetFactionId);
                                if (targetFaction != null) {
                                    targetFaction.getRanks().keySet().stream() // Получаем ID существующих рангов
                                            .map(String::valueOf)
                                            .filter(idStr -> idStr.startsWith(input))
                                            .forEach(completions::add);
                                }
                            }
                        }
                        // Как альтернатива - всегда предлагать 1-11
                         /*
                         for(int i=1; i<=11; i++) {
                             String rankIdStr = String.valueOf(i);
                             if(rankIdStr.startsWith(input)) completions.add(rankIdStr);
                         }
                         */
                    }
                    break;

                // Команда /hf create <id> "<Name>" <TYPE>
                case "create":
                    // Третий аргумент - начало имени (нет смысла автодополнять)
                    // Последний аргумент - тип
                    if (args.length >= 4 && args.length == (input.isEmpty() ? 4 : args.length) ) { // Если вводят последний аргумент
                        Arrays.stream(FactionType.values())
                                .map(Enum::name)
                                .filter(name -> name.toLowerCase().startsWith(input))
                                .forEach(completions::add);
                    }
                    break;

                // Команда /hf territory ...
                case "territory":
                    if (dynmapManager != null && dynmapManager.isDynmapEnabled()) {
                        // Передаем управление автодополнением DynmapManager'у
                        return dynmapManager.handleTerritoryTabComplete(sender, label, args);
                    }
                    break;

            }
        }


        // Сортируем и возвращаем результат
        completions.sort(String.CASE_INSENSITIVE_ORDER);
        return completions;
    }

    // Упрощенная проверка прав для TabCompleter
    private boolean checkTabPermission(CommandSender sender, String subCommand) {
        switch (subCommand) {
            // Админские
            case "create": case "delete": case "reload": case "setbalance":
            case "uncuff": case "adminmode": case "givetaser": case "givehandcuffs": case "giveprotocol":
                return sender.hasPermission("hfactions.admin." + subCommand);
            // Территории (базовые)
            case "territory":
                return sender.hasPermission("hfactions.territory.help") || sender.hasPermission("hfactions.admin.territory");
            // Полиция
            case "fine":
                return sender.hasPermission("hfactions.pd.fine");
            // Фракционные (проверяем хотя бы одно, т.к. точную проверку ранга сделать сложно)
            case "invite": case "kick": case "promote": case "demote":
            case "setrank": case "manageranks": case "balance": case "bal":
            case "deposit": case "dep": case "withdraw": case "wd":
            case "warehouse": case "wh":
                // Достаточно проверить базовое право, т.к. все равно покажем, если есть право ранга
                return hasAnyFactionPermission(sender, subCommand.replace("bal","balance").replace("dep","deposit").replace("wd","withdraw").replace("wh","warehouse.open")); // warehouse -> warehouse.open
            // Общие (по умолчанию true в plugin.yml)
            case "help": case "list": case "info": case "listrecipes": case "leave": case "chat": case "c": case "fc": case "fchat":
                return true; // Права по умолчанию
            default:
                return true; // Неизвестные команды не фильтруем здесь
        }
    }
    //</editor-fold>
}