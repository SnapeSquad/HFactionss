package org.isyateq.hfactions.commands;

// Bukkit Imports
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

// HFactions Imports
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.*;
import org.isyateq.hfactions.models.*;
import org.isyateq.hfactions.integrations.*; // Импортируем все интеграции
import org.isyateq.hfactions.models.PendingInvite;
import org.isyateq.hfactions.util.Utils; // Для color

// Java Imports
import java.util.*;
import java.util.stream.Collectors;

public class FactionCommand implements CommandExecutor, TabCompleter {

    private final HFactions plugin;
    // --- Менеджеры и Интеграции ---
    private final ConfigManager configManager;
    private final FactionManager factionManager;
    private final PlayerManager playerManager;
    private final ItemManager itemManager;
    private final GuiManager guiManager; // Нужен для /hf manageranks
    private final CuffManager cuffManager; // Нужен для /hf uncuff
    private final VaultIntegration vaultIntegration; // Для экономики
    private final DynmapManager dynmapManager; // Для территорий (может быть null)
    // Oraxen и LuckPerms обычно не нужны прямо в командах, доступ через Player/ItemManager

    // Список основных подкоманд для автодополнения и справки
    private final List<String> baseSubCommands = Collections.unmodifiableList(Arrays.asList(
            "help", "list", "info", "listrecipes", "leave", "chat", "c", "fc", "fchat",
            "invite", "kick", "promote", "demote", "setrank", "manageranks",
            "balance", "bal", "deposit", "dep", "withdraw", "wd", "warehouse", "wh",
            "fine",
            "territory",
            "create", "delete", "reload", "setbalance", "uncuff", "adminmode",
            "givetaser", "givehandcuffs", "giveprotocol"
    ));

    public FactionCommand(HFactions plugin) {
        this.plugin = plugin;
        // --- Получаем все необходимые менеджеры и интеграции ---
        this.configManager = plugin.getConfigManager();
        this.factionManager = plugin.getFactionManager();
        this.playerManager = plugin.getPlayerManager();
        this.itemManager = plugin.getItemManager();
        this.guiManager = plugin.getGuiManager();
        this.cuffManager = plugin.getCuffManager();
        this.vaultIntegration = plugin.getVaultIntegration();

        // Получаем DynmapManager через DynmapIntegration, проверяя на null
        DynmapIntegration dynmapIntegrationInstance = plugin.getDynmapIntegration();
        this.dynmapManager = (dynmapIntegrationInstance != null) ? dynmapIntegrationInstance.getDynmapManager() : null;

        // Проверяем критические зависимости
        if (this.configManager == null) plugin.getLogger().severe("ConfigManager is null in FactionCommand!");
        if (this.factionManager == null) plugin.getLogger().severe("FactionManager is null in FactionCommand!");
        if (this.playerManager == null) plugin.getLogger().severe("PlayerManager is null in FactionCommand!");
        if (this.itemManager == null) plugin.getLogger().severe("ItemManager is null in FactionCommand!");
        if (this.guiManager == null) plugin.getLogger().severe("GuiManager is null in FactionCommand!");
        if (this.cuffManager == null) plugin.getLogger().severe("CuffManager is null in FactionCommand!");
        if (this.vaultIntegration == null) plugin.getLogger().warning("VaultIntegration is null in FactionCommand! Economy commands will fail.");
        if (this.dynmapManager == null) plugin.getLogger().info("DynmapManager is null in FactionCommand. Territory commands will be disabled.");

    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Проверка на null для основных менеджеров
        if (configManager == null || playerManager == null || factionManager == null) {
            sender.sendMessage(ChatColor.RED + "Internal plugin error. Please contact an administrator. (Null Manager)");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length); // Аргументы для подкоманды

        // --- Обработка подкоманд ---
        switch (subCommand) {
            // --- Информационные команды (в основном default: true) ---
            case "help":
                handleHelp(sender, label);
                break;
            case "list":
                handleList(sender);
                break;
            case "info":
                handleInfo(sender, subArgs, label);
                break;
            case "listrecipes":
                handleListRecipes(sender);
                break;

            // --- Команды игрока (требуют Player) ---
            case "leave":
                handleLeave(sender);
                break;
            case "chat": case "c": case "fc": case "fchat":
                handleChatToggle(sender);
                break;
            case "balance": case "bal":
                handleBalanceView(sender);
                break;
            case "deposit": case "dep":
                handleDeposit(sender, subArgs, label);
                break;
            case "withdraw": case "wd":
                handleWithdraw(sender, subArgs, label);
                break;
            case "warehouse": case "wh":
                handleWarehouseOpen(sender);
                break;

            // --- Команды управления фракцией (требуют Player + права ранга) ---
            case "invite":
                handleInvite(sender, subArgs, label);
                break;
            case "kick":
                handleKick(sender, subArgs, label);
                break;
            case "promote":
                handlePromote(sender, subArgs, label);
                break;
            case "demote":
                handleDemote(sender, subArgs, label);
                break;
            case "setrank":
                handleSetRank(sender, subArgs, label);
                break;
            case "manageranks":
                handleManageRanks(sender);
                break;

            // --- Команды специфичных фракций (PD) ---
            case "fine":
                handleFine(sender, subArgs, label);
                break;

            // --- Команды территорий (Dynmap) ---
            case "territory":
                handleTerritory(sender, subArgs, label);
                break;

            // --- Административные команды ---
            case "reload":
                handleReload(sender);
                break;
            case "create":
                handleCreate(sender, subArgs, label);
                break;
            case "delete":
                handleDelete(sender, subArgs, label);
                break;
            case "setbalance":
                handleSetBalance(sender, subArgs, label);
                break;
            case "uncuff":
                handleUncuff(sender, subArgs, label);
                break;
            case "adminmode":
                handleAdminMode(sender, subArgs, label);
                break;
            case "givetaser":
            case "givehandcuffs":
            case "giveprotocol":
                handleGiveItem(sender, subCommand, subArgs, label);
                break;

            default:
                sender.sendMessage(configManager.getMessage("command.unknown", "&cUnknown command: /" + label + " " + subCommand));
                sender.sendMessage(configManager.getMessage("command.usage_help", "&eUse '/" + label + " help' for assistance."));
                break;
        }
        return true;
    }

    // --- Обработчики подкоманд ---

    private void handleHelp(CommandSender sender, String label) {
        // Право: hfactions.command.help (default: true) - Проверять не обязательно, но можно
        sendHelp(sender); // Вызываем отдельный метод для чистоты
    }

    private void handleList(CommandSender sender) {
        // Право: hfactions.command.list (default: true)
        if (factionManager == null) {
            sender.sendMessage(ChatColor.RED + "Internal error: FactionManager not available.");
            return;
        }
        sender.sendMessage(Utils.color(configManager.getMessage("list.header", "&6--- Factions List ---")));
        Map<String, Faction> allFactions = factionManager.getAllFactions();
        if (allFactions.isEmpty()) {
            sender.sendMessage(Utils.color(configManager.getMessage("list.no_factions", "&7No factions have been created yet.")));
            return;
        }
        allFactions.values().stream()
                .sorted(Comparator.comparing(Faction::getName, String.CASE_INSENSITIVE_ORDER)) // Сортируем по имени
                .forEach(faction -> {
                    String prefix = faction.getPrefix() != null ? faction.getPrefix() : "";
                    String name = faction.getName() != null ? faction.getName() : "Unnamed";
                    String id = faction.getId();
                    String type = faction.getType() != null ? faction.getType().name() : "UNKNOWN";
                    String format = configManager.getMessage("list.entry", "{prefix} &f{name} &7(ID: {id}, Type: {type})");
                    sender.sendMessage(Utils.color(format
                            .replace("{prefix}", prefix)
                            .replace("{name}", name)
                            .replace("{id}", id)
                            .replace("{type}", type)
                    ));
                });
    }

    private void handleInfo(CommandSender sender, String[] args, String label) {
        // Право: hfactions.command.info (default: true)
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} info <faction_id>").replace("{label}", label));
            return;
        }
        String factionId = args[0].toLowerCase();
        if (factionManager == null) {
            sender.sendMessage(ChatColor.RED + "Internal error: FactionManager not available.");
            return;
        }
        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) {
            sender.sendMessage(configManager.getMessage("faction.not_found", "&cFaction with ID '{id}' not found.").replace("{id}", factionId));
            return;
        }

        String header = configManager.getMessage("info.header", "&6--- Faction Info: {prefix} {name} &6---")
                .replace("{prefix}", faction.getPrefix())
                .replace("{name}", faction.getName());
        sender.sendMessage(Utils.color(header));

        // Используем строки из конфига для каждой детали
        sender.sendMessage(Utils.color(configManager.getMessage("info.line.id", "&eID: &f{value}").replace("{value}", faction.getId())));
        sender.sendMessage(Utils.color(configManager.getMessage("info.line.type", "&eType: &f{value}").replace("{value}", faction.getType().name())));
        sender.sendMessage(Utils.color(configManager.getMessage("info.line.color", "&eColor: &f{value}").replace("{value}", faction.getColor())));
        sender.sendMessage(Utils.color(configManager.getMessage("info.line.prefix", "&ePrefix: &f{value} ({formatted})")
                .replace("{value}", faction.getPrefix())
                .replace("{formatted}", Utils.color(faction.getPrefix()))));

        // Баланс (проверяем Vault)
        if (vaultIntegration != null) {
            String balanceStr = vaultIntegration.format(faction.getBalance());
            sender.sendMessage(Utils.color(configManager.getMessage("info.line.balance", "&eBalance: &a{value}").replace("{value}", balanceStr)));
        } else {
            sender.sendMessage(Utils.color(configManager.getMessage("info.line.balance_no_vault", "&eBalance: &f{value} (Vault not found)").replace("{value}", String.format(Locale.US, "%.2f", faction.getBalance()))));
        }

        sender.sendMessage(Utils.color(configManager.getMessage("info.line.warehouse_size", "&eWarehouse Size: &f{value}").replace("{value}", String.valueOf(faction.getWarehouseSize()))));

        // Ранги
        sender.sendMessage(Utils.color(configManager.getMessage("info.line.ranks_header", "&eRanks ({count}):").replace("{count}", String.valueOf(faction.getRanks().size()))));
        String rankFormat = configManager.getMessage("info.line.rank_entry", "  &7- [{id}] {name} (Salary: &a{salary}&7)");
        faction.getRanks().values().stream()
                .sorted(Comparator.comparingInt(FactionRank::getInternalId))
                .forEach(rank -> sender.sendMessage(Utils.color(rankFormat
                        .replace("{id}", String.valueOf(rank.getInternalId()))
                        .replace("{name}", rank.getDisplayName())
                        .replace("{salary}", vaultIntegration != null ? vaultIntegration.format(rank.getSalary()) : String.format(Locale.US, "%.2f", rank.getSalary()))
                )));

        // Участники онлайн
        List<Player> onlineMembers = playerManager.getOnlineFactionMembers(faction.getId());
        sender.sendMessage(Utils.color(configManager.getMessage("info.line.members_online", "&eMembers Online ({count}): {list}")
                .replace("{count}", String.valueOf(onlineMembers.size()))
                .replace("{list}", onlineMembers.isEmpty() ? "&7None" : onlineMembers.stream().map(Player::getName).collect(Collectors.joining(", ")))
        ));
    }

    private void handleListRecipes(CommandSender sender) {
        // Право: hfactions.command.listrecipes (default: true)
        if (plugin.getCraftingManager() == null) {
            sender.sendMessage(ChatColor.RED + "Internal error: CraftingManager not available.");
            return;
        }
        sender.sendMessage(Utils.color(configManager.getMessage("recipes.header", "&6--- Custom Recipes ---")));
        Map<String, ?> customRecipes = plugin.getCraftingManager().getCustomRecipes(); // Получаем информацию о рецептах
        if (customRecipes.isEmpty()) {
            sender.sendMessage(Utils.color(configManager.getMessage("recipes.no_recipes", "&7No custom recipes are available.")));
            return;
        }
        // TODO: Реализовать красивый вывод рецептов (ингредиенты, результат)
        sender.sendMessage(Utils.color("&eAvailable recipe IDs: &f" + String.join(", ", customRecipes.keySet())));
        sender.sendMessage(Utils.color("&7(Detailed recipe view not implemented yet)"));
    }

    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.player.leave (default: true)
        playerManager.leaveFaction(player); // Логика и сообщения внутри PlayerManager
    }

    private void handleChatToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.player.chat (default: true)
        playerManager.toggleFactionChat(player); // Логика и сообщения внутри PlayerManager
    }

    private void handleBalanceView(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            // Консоль может смотреть баланс? Допустим, нет.
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.balance.view (выдается рангу)
        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        if (!player.hasPermission("hfactions.faction.balance.view")) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.balance.view")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
        }

        if (vaultIntegration != null) {
            String balanceStr = vaultIntegration.format(faction.getBalance());
            String msg = configManager.getMessage("balance.view", "&aFaction Balance: {balance}");
            player.sendMessage(Utils.color(msg.replace("{balance}", balanceStr)));
        } else {
            String msg = configManager.getMessage("balance.view_no_vault", "&eFaction Balance: {balance} (Vault not found)");
            player.sendMessage(Utils.color(msg.replace("{balance}", String.format(Locale.US, "%.2f", faction.getBalance()))));
        }
    }

    private void handleDeposit(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.deposit (выдается рангу)
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} deposit <amount>").replace("{label}", label));
            return;
        }

        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        if (!player.hasPermission("hfactions.faction.deposit")) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.deposit")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
        }

        if (vaultIntegration == null) {
            player.sendMessage(configManager.getMessage("economy.vault_missing", "&cEconomy features are disabled (Vault not found)."));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[0]);
            if (amount <= 0) {
                player.sendMessage(configManager.getMessage("economy.invalid_amount_positive", "&cAmount must be positive."));
                return;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(configManager.getMessage("economy.invalid_amount_number", "&cInvalid amount specified."));
            return;
        }

        // Снимаем деньги у игрока
        if (vaultIntegration.withdraw(player.getUniqueId(), amount)) {
            // Вносим во фракцию
            if (factionManager.depositToFaction(faction.getId(), amount)) {
                String formattedAmount = vaultIntegration.format(amount);
                String msg = configManager.getMessage("deposit.success", "&aSuccessfully deposited {amount} into the faction treasury.");
                player.sendMessage(Utils.color(msg.replace("{amount}", formattedAmount)));
                // Логирование во фракцию?
                String logMsg = configManager.getMessage("deposit.log", "&e{player} deposited {amount} into the treasury.");
                playerManager.broadcastToFaction(faction.getId(), Utils.color(logMsg
                        .replace("{player}", player.getName())
                        .replace("{amount}", formattedAmount)));
            } else {
                // Ошибка взноса (не должно случиться, если фракция есть) - возвращаем деньги игроку
                vaultIntegration.deposit(player.getUniqueId(), amount); // Возврат
                player.sendMessage(configManager.getMessage("deposit.error_faction", "&cInternal error depositing to faction. Money refunded."));
            }
        } else {
            player.sendMessage(configManager.getMessage("economy.not_enough_money", "&cYou don't have enough money to deposit that amount."));
        }
    }

    private void handleWithdraw(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.withdraw ИЛИ hfactions.faction.manage_balance
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} withdraw <amount>").replace("{label}", label));
            return;
        }

        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        // Проверка прав
        boolean canWithdraw = player.hasPermission("hfactions.faction.withdraw") || player.hasPermission("hfactions.faction.manage_balance");
        if (!canWithdraw) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank != null) {
                List<String> perms = rank.getPermissions();
                if (perms.contains("hfactions.faction.withdraw") || perms.contains("hfactions.faction.manage_balance")) {
                    canWithdraw = true;
                }
            }
        }
        if (!canWithdraw) {
            player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }


        if (vaultIntegration == null) {
            player.sendMessage(configManager.getMessage("economy.vault_missing", "&cEconomy features are disabled (Vault not found)."));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[0]);
            if (amount <= 0) {
                player.sendMessage(configManager.getMessage("economy.invalid_amount_positive", "&cAmount must be positive."));
                return;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(configManager.getMessage("economy.invalid_amount_number", "&cInvalid amount specified."));
            return;
        }

        // Снимаем деньги у фракции
        if (factionManager.withdrawFromFaction(faction.getId(), amount)) {
            // Начисляем игроку
            if (vaultIntegration.deposit(player.getUniqueId(), amount)) {
                String formattedAmount = vaultIntegration.format(amount);
                String msg = configManager.getMessage("withdraw.success", "&aSuccessfully withdrew {amount} from the faction treasury.");
                player.sendMessage(Utils.color(msg.replace("{amount}", formattedAmount)));
                // Логирование
                String logMsg = configManager.getMessage("withdraw.log", "&e{player} withdrew {amount} from the treasury.");
                playerManager.broadcastToFaction(faction.getId(), Utils.color(logMsg
                        .replace("{player}", player.getName())
                        .replace("{amount}", formattedAmount)));
            } else {
                // Ошибка начисления игроку - возвращаем фракции
                factionManager.depositToFaction(faction.getId(), amount); // Возврат
                player.sendMessage(configManager.getMessage("withdraw.error_player", "&cCould not deposit money into your account. Funds returned to faction."));
            }
        } else {
            player.sendMessage(configManager.getMessage("withdraw.not_enough_funds", "&cThe faction does not have enough funds to withdraw that amount."));
        }
    }

    private void handleWarehouseOpen(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.warehouse.open (проверяется внутри GuiManager)
        if (guiManager == null) {
            player.sendMessage(ChatColor.RED + "Internal error: GuiManager not available.");
            return;
        }
        guiManager.openWarehouseGUI(player, 1); // Открываем первую страницу
    }


    private void handleInvite(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.invite
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} invite <player_name>").replace("{label}", label));
            return;
        }

        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        // Проверка прав
        if (!player.hasPermission("hfactions.faction.invite")) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.invite")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(configManager.getMessage("command.player_not_found", "&cPlayer '{player}' not found or is offline.").replace("{player}", args[0]));
            return;
        }

        // Доп. проверки
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(configManager.getMessage("invite.cant_invite_self", "&cYou cannot invite yourself."));
            return;
        }
        if (playerManager.isInFaction(target)) {
            String targetFacName = playerManager.getPlayerFaction(target).getName();
            String msg = configManager.getMessage("invite.target_already_in", "&c{target_name} is already in the {faction_name} faction.");
            player.sendMessage(Utils.color(msg.replace("{target_name}", target.getName()).replace("{faction_name}", targetFacName)));
            return;
        }
        if (playerManager.getInvite(target) != null) {
            String msg = configManager.getMessage("invite.target_has_pending", "&c{target_name} already has a pending faction invite.");
            player.sendMessage(Utils.color(msg.replace("{target_name}", target.getName())));
            return;
        }

        // Создаем и отправляем приглашение
        PendingInvite invite = new PendingInvite(player.getUniqueId(), player.getName(), faction.getId(), faction.getName());
        playerManager.addInvite(target, invite); // Отправляет сообщение цели и запускает таймер

        // Открываем GUI у цели
        if (guiManager != null) {
            guiManager.openInviteGUI(target, invite);
        } else {
            target.sendMessage(Utils.color("&e(Internal error: Cannot open invite GUI)")); // Сообщаем об ошибке, если GUI не работает
        }


        String msg = configManager.getMessage("invite.sent", "&aInvite sent to {target_name}. They have {seconds} seconds to accept.");
        long expireSeconds = configManager.getConfig().getLong("faction.invite_expire_seconds", 60);
        player.sendMessage(Utils.color(msg.replace("{target_name}", target.getName()).replace("{seconds}", String.valueOf(expireSeconds))));

    }


    private void handleKick(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.kick
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} kick <player_name>").replace("{label}", label));
            return;
        }

        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        // Проверка прав
        boolean canKick = player.hasPermission("hfactions.faction.kick");
        if (!canKick) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.kick")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
            canKick = true; // Если право есть в ранге
        }


        // Используем getOfflinePlayer, чтобы можно было кикнуть оффлайн? ТЗ говорит "Исключить игрока" без уточнения
        // Пока сделаем для онлайн
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) { // Строго онлайн
            player.sendMessage(configManager.getMessage("command.player_not_found", "&cPlayer '{player}' not found or is offline.").replace("{player}", args[0]));
            return;
        }

        // Проверка, в той ли фракции цель
        String targetFactionId = playerManager.getPlayerFactionId(target);
        if (!faction.getId().equals(targetFactionId)) {
            String msg = configManager.getMessage("kick.target_not_in_your_faction", "&c{target_name} is not in your faction.");
            player.sendMessage(Utils.color(msg.replace("{target_name}", target.getName())));
            return;
        }

        // Проверка рангов (нельзя кикнуть того, кто выше или равен по рангу, если не админ)
        FactionRank kickerRank = playerManager.getPlayerRank(player);
        FactionRank targetRank = playerManager.getPlayerRank(target);
        if (!player.hasPermission("hfactions.admin.*")) { // Админы могут кикать любого
            if (kickerRank == null || targetRank == null || kickerRank.getInternalId() <= targetRank.getInternalId()) {
                player.sendMessage(configManager.getMessage("kick.rank_too_low", "&cYou cannot kick someone with an equal or higher rank."));
                return;
            }
        }

        // Кикаем
        playerManager.kickPlayer(player, target); // Логика и сообщения там
    }


    private void handlePromote(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.promote
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} promote <player_name>").replace("{label}", label));
            return;
        }

        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        // Проверка прав
        if (!player.hasPermission("hfactions.faction.promote")) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.promote")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(configManager.getMessage("command.player_not_found", "&cPlayer '{player}' not found or is offline.").replace("{player}", args[0]));
            return;
        }

        // Проверка, в той ли фракции цель
        String targetFactionId = playerManager.getPlayerFactionId(target);
        if (!faction.getId().equals(targetFactionId)) {
            String msg = configManager.getMessage("promote.target_not_in_your_faction", "&c{target_name} is not in your faction.");
            player.sendMessage(Utils.color(msg.replace("{target_name}", target.getName())));
            return;
        }

        // Проверка рангов (нельзя повысить того, кто выше или равен, нельзя повысить лидера)
        FactionRank promoterRank = playerManager.getPlayerRank(player);
        FactionRank targetRank = playerManager.getPlayerRank(target);
        if (targetRank == null) { // На всякий случай
            player.sendMessage(ChatColor.RED + "Target player has an invalid rank.");
            return;
        }
        if (!player.hasPermission("hfactions.admin.*")) { // Админы могут повышать любого (кроме лидера)
            if (promoterRank == null || promoterRank.getInternalId() <= targetRank.getInternalId()) {
                player.sendMessage(configManager.getMessage("promote.rank_too_low", "&cYou cannot promote someone with an equal or higher rank."));
                return;
            }
        }
        // Проверяем, не пытаемся ли повысить лидера (ранг 11)
        if (targetRank.getInternalId() >= 11) { // Используем >= на случай кастомных рангов выше 11
            player.sendMessage(configManager.getMessage("promote.cant_promote_leader", "&cYou cannot promote the highest rank."));
            return;
        }

        // Повышаем
        playerManager.promotePlayer(player, target);
    }

    private void handleDemote(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.demote
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} demote <player_name>").replace("{label}", label));
            return;
        }

        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        // Проверка прав
        if (!player.hasPermission("hfactions.faction.demote")) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.demote")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(configManager.getMessage("command.player_not_found", "&cPlayer '{player}' not found or is offline.").replace("{player}", args[0]));
            return;
        }

        // Проверка, в той ли фракции цель
        String targetFactionId = playerManager.getPlayerFactionId(target);
        if (!faction.getId().equals(targetFactionId)) {
            String msg = configManager.getMessage("demote.target_not_in_your_faction", "&c{target_name} is not in your faction.");
            player.sendMessage(Utils.color(msg.replace("{target_name}", target.getName())));
            return;
        }

        // Проверка рангов (нельзя понизить того, кто выше или равен, нельзя понизить ниже 1 ранга)
        FactionRank demoterRank = playerManager.getPlayerRank(player);
        FactionRank targetRank = playerManager.getPlayerRank(target);
        if (targetRank == null) {
            player.sendMessage(ChatColor.RED + "Target player has an invalid rank.");
            return;
        }
        if (!player.hasPermission("hfactions.admin.*")) { // Админы могут понижать
            if (demoterRank == null || demoterRank.getInternalId() <= targetRank.getInternalId()) {
                player.sendMessage(configManager.getMessage("demote.rank_too_low", "&cYou cannot demote someone with an equal or higher rank."));
                return;
            }
        }
        // Проверяем, не пытаемся ли понизить ниже 1 ранга
        if (targetRank.getInternalId() <= 1) {
            player.sendMessage(configManager.getMessage("demote.cant_demote_lowest", "&cYou cannot demote the lowest rank."));
            return;
        }

        // Понижаем
        playerManager.demotePlayer(player, target);
    }


    private void handleSetRank(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.setrank
        if (args.length < 2) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} setrank <player_name> <rank_id>").replace("{label}", label));
            return;
        }

        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        // Проверка прав
        if (!player.hasPermission("hfactions.faction.setrank")) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.setrank")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(configManager.getMessage("command.player_not_found", "&cPlayer '{player}' not found or is offline.").replace("{player}", args[0]));
            return;
        }

        // Проверка, в той ли фракции цель
        String targetFactionId = playerManager.getPlayerFactionId(target);
        if (!faction.getId().equals(targetFactionId)) {
            String msg = configManager.getMessage("setrank.target_not_in_your_faction", "&c{target_name} is not in your faction.");
            player.sendMessage(Utils.color(msg.replace("{target_name}", target.getName())));
            return;
        }

        int rankId;
        try {
            rankId = Integer.parseInt(args[1]);
            if (faction.getRank(rankId) == null) { // Проверяем существование ранга
                player.sendMessage(configManager.getMessage("faction.rank_not_exist", "&cRank ID {rank_id} does not exist.").replace("{rank_id}", args[1]));
                return;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(configManager.getMessage("setrank.invalid_rank_id", "&cInvalid rank ID specified. Must be a number."));
            return;
        }

        // Проверка ранга установщика (нельзя ставить ранг выше или равный своему, если не админ)
        FactionRank setterRank = playerManager.getPlayerRank(player);
        if (!player.hasPermission("hfactions.admin.*")) {
            if (setterRank == null || setterRank.getInternalId() < rankId ) { // Строго МЕНЬШЕ чем у устанавливающего
                player.sendMessage(configManager.getMessage("setrank.rank_too_low", "&cYou cannot set a rank equal to or higher than your own."));
                return;
            }
            // Особый случай - лидер (ранг 11) может ставить любой ранг ниже себя
            if(setterRank.getInternalId() == 11 && rankId == 11){
                player.sendMessage(configManager.getMessage("setrank.cant_set_leader_to_leader", "&cYou cannot set the leader rank to the leader again."));
                return;
            }
        }


        // Устанавливаем ранг
        playerManager.setPlayerRank(player, target, rankId);
    }


    private void handleManageRanks(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.faction.manage_ranks
        Faction faction = playerManager.getPlayerFaction(player);
        if (faction == null) {
            player.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
            return;
        }

        // Проверка прав
        if (!player.hasPermission("hfactions.faction.manage_ranks")) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.faction.manage_ranks")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
        }

        if (guiManager == null) {
            player.sendMessage(ChatColor.RED + "Internal error: GuiManager not available.");
            return;
        }
        guiManager.openRanksGUI(player); // Открываем GUI
    }

    private void handleFine(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.pd.fine (или другое, настроить в factions.yml)
        if (args.length < 3) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} fine <player_name> <amount> <reason...>").replace("{label}", label));
            return;
        }

        // Проверка, находится ли игрок в подходящей фракции (например, PD) и имеет ли право
        Faction playerFaction = playerManager.getPlayerFaction(player);
        // TODO: Сделать проверку фракции более гибкой (например, через тип STATE?)
        if (playerFaction == null /* || !playerFaction.getId().equalsIgnoreCase("pd") */ ) {
            player.sendMessage(configManager.getMessage("fine.not_in_pd", "&cOnly members of authorized factions can issue fines.")); // Сообщение может быть общим
            return;
        }

        // Проверка права из ранга
        boolean hasFinePerm = player.hasPermission("hfactions.pd.fine"); // Пример права
        if (!hasFinePerm) {
            FactionRank rank = playerManager.getPlayerRank(player);
            if (rank == null || !rank.getPermissions().contains("hfactions.pd.fine")) {
                player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
                return;
            }
        }


        if (vaultIntegration == null) {
            player.sendMessage(configManager.getMessage("economy.vault_missing", "&cEconomy features are disabled (Vault not found)."));
            return;
        }


        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(configManager.getMessage("command.player_not_found", "&cPlayer '{player}' not found or is offline.").replace("{player}", args[0]));
            return;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(configManager.getMessage("fine.cant_fine_self", "&cYou cannot fine yourself."));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            // Проверка лимитов штрафа из конфига
            double minFine = configManager.getConfig().getDouble("mechanics.fining.min_amount", 10.0);
            double maxFine = configManager.getConfig().getDouble("mechanics.fining.max_amount", 5000.0);
            if (amount < minFine || amount > maxFine) {
                String msg = configManager.getMessage("fine.amount_limit", "&cFine amount must be between {min} and {max}.");
                player.sendMessage(Utils.color(msg.replace("{min}", vaultIntegration.format(minFine)).replace("{max}", vaultIntegration.format(maxFine))));
                return;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(configManager.getMessage("economy.invalid_amount_number", "&cInvalid amount specified."));
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

        // Снимаем деньги у цели
        if (vaultIntegration.withdraw(target.getUniqueId(), amount)) {
            // Начисляем куда? Во фракцию офицера? В казну государства? На счет офицера?
            // Пример: начисление во фракцию офицера
            if (factionManager.depositToFaction(playerFaction.getId(), amount)) {
                String formattedAmount = vaultIntegration.format(amount);
                // Сообщение оштрафованному
                String targetMsg = configManager.getMessage("fine.target_fined", "&cYou have been fined {amount} by Officer {officer} for: {reason}");
                target.sendMessage(Utils.color(targetMsg
                        .replace("{amount}", formattedAmount)
                        .replace("{officer}", player.getName())
                        .replace("{reason}", reason)));
                // Сообщение офицеру
                String officerMsg = configManager.getMessage("fine.officer_success", "&aSuccessfully fined {target} for {amount}. Reason: {reason}");
                player.sendMessage(Utils.color(officerMsg
                        .replace("{target}", target.getName())
                        .replace("{amount}", formattedAmount)
                        .replace("{reason}", reason)));
                // Логирование во фракцию
                String logMsg = configManager.getMessage("fine.log", "&eOfficer {officer} fined {target} for {amount}. Reason: {reason}");
                playerManager.broadcastToFaction(playerFaction.getId(), Utils.color(logMsg
                        .replace("{officer}", player.getName())
                        .replace("{target}", target.getName())
                        .replace("{amount}", formattedAmount)
                        .replace("{reason}", reason)));
            } else {
                // Ошибка начисления во фракцию - возвращаем деньги цели
                vaultIntegration.deposit(target.getUniqueId(), amount);
                player.sendMessage(configManager.getMessage("fine.error_faction_deposit", "&cError depositing fine into faction treasury. Fine cancelled."));
            }
        } else {
            // Недостаточно средств у цели
            String msg = configManager.getMessage("fine.target_no_money", "&c{target} does not have enough money to pay the fine ({amount}).");
            player.sendMessage(Utils.color(msg
                    .replace("{target}", target.getName())
                    .replace("{amount}", vaultIntegration.format(amount))));
        }
    }

    // --- Территории ---
    private void handleTerritory(CommandSender sender, String[] args, String label) {
        if (dynmapManager == null) {
            sender.sendMessage(configManager.getMessage("territory.dynmap_disabled", "&cTerritory management is disabled (Dynmap not found or integration failed)."));
            return;
        }

        if (args.length == 0) {
            handleTerritoryHelp(sender, label); // Показываем помощь, если нет подкоманды
            return;
        }

        String territorySubCommand = args[0].toLowerCase();
        String[] territoryArgs = Arrays.copyOfRange(args, 1, args.length);

        // Проверка общих прав на использование /hf territory ?
        // if (!sender.hasPermission("hfactions.territory.base")) { ... }

        switch (territorySubCommand) {
            case "list":
                handleTerritoryList(sender, territoryArgs);
                break;
            case "define": // Только для админов или лидеров с правом manage.own
                handleTerritoryDefine(sender, territoryArgs, label); // Имя территории не нужно в define
                break;
            case "corner":
                handleTerritoryCorner(sender);
                break;
            case "clear":
                handleTerritoryClear(sender);
                break;
            case "claim":
                handleTerritoryClaim(sender, territoryArgs, label);
                break;
            case "delete":
                handleTerritoryDelete(sender, territoryArgs, label);
                break;
            case "map": // Обновление карты - админ
                handleTerritoryMap(sender);
                break;
            case "help":
                handleTerritoryHelp(sender, label);
                break;
            default:
                sender.sendMessage(configManager.getMessage("command.unknown", "&cUnknown command: /" + label + " territory " + territorySubCommand));
                handleTerritoryHelp(sender, label);
                break;
        }
    }

    private void handleTerritoryList(CommandSender sender, String[] args) {
        // Право: hfactions.territory.list или hfactions.admin.territory
        if (!sender.hasPermission("hfactions.territory.list") && !sender.hasPermission("hfactions.admin.territory")) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }
        sender.sendMessage(Utils.color(configManager.getMessage("territory.list.header", "&6--- Faction Territories ---")));
        Map<String, DynmapManager.TerritoryInfo> territories = dynmapManager.getAllTerritories(); // Получаем мапу Имя -> Инфо

        if (territories.isEmpty()) {
            sender.sendMessage(Utils.color(configManager.getMessage("territory.list.no_territories", "&7No territories have been claimed.")));
            return;
        }

        String filterFactionId = null;
        if (args.length > 0) {
            filterFactionId = args[0].toLowerCase();
            sender.sendMessage(Utils.color("&eFiltering territories for faction: " + filterFactionId));
        }

        String format = configManager.getMessage("territory.list.entry", "&e{name} &7(Owner: {owner_name} [{owner_id}], World: {world})");
        final String finalFilterFactionId = filterFactionId; // Для лямбды

        territories.values().stream()
                .filter(info -> finalFilterFactionId == null || info.factionId.equalsIgnoreCase(finalFilterFactionId)) // Фильтруем по ID фракции, если указан
                .sorted(Comparator.comparing(info -> info.territoryName, String.CASE_INSENSITIVE_ORDER)) // Сортируем по имени территории
                .forEach(info -> {
                    Faction owner = factionManager.getFaction(info.factionId);
                    String ownerName = owner != null ? owner.getName() : "Unknown";
                    sender.sendMessage(Utils.color(format
                            .replace("{name}", info.territoryName)
                            .replace("{owner_name}", ownerName)
                            .replace("{owner_id}", info.factionId)
                            .replace("{world}", info.worldName)
                    ));
                });
    }

    private void handleTerritoryDefine(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.territory.manage.own (лидер) ИЛИ hfactions.admin.territory
        boolean isAdmin = player.hasPermission("hfactions.admin.territory");
        boolean isLeaderWithPerm = false;
        if (!isAdmin) {
            Faction faction = playerManager.getPlayerFaction(player);
            if (faction != null) {
                FactionRank rank = playerManager.getPlayerRank(player);
                // Проверяем, что это лидер (ранг 11) и у него есть право manage.own
                if (rank != null && rank.getInternalId() == 11 && rank.getPermissions().contains("hfactions.territory.manage.own")) {
                    isLeaderWithPerm = true;
                }
            }
        }

        if (!isAdmin && !isLeaderWithPerm) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cOnly Admins or Faction Leaders with permission can define territories."));
            return;
        }

        dynmapManager.startDefining(player); // Сообщение внутри метода
    }

    private void handleTerritoryCorner(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право проверяется в startDefining (т.е. если начал, то может добавлять углы)
        dynmapManager.addCorner(player); // Сообщение внутри метода
    }

    private void handleTerritoryClear(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право проверяется в startDefining
        dynmapManager.clearCorners(player); // Сообщение внутри метода
    }

    private void handleTerritoryClaim(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.territory.manage.own (лидер) ИЛИ hfactions.admin.territory
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} territory claim <territory_name> [faction_id_if_admin]").replace("{label}", label));
            return;
        }

        String territoryName = args[0];
        String factionIdToClaim = null;

        boolean isAdmin = player.hasPermission("hfactions.admin.territory");
        boolean isLeaderWithPerm = false;
        Faction playerFaction = playerManager.getPlayerFaction(player);

        if (isAdmin) {
            // Админ может указать ID фракции вторым аргументом
            if (args.length >= 2) {
                factionIdToClaim = args[1].toLowerCase();
                if (factionManager.getFaction(factionIdToClaim) == null) {
                    sender.sendMessage(configManager.getMessage("faction.not_found", "&cFaction with ID '{id}' not found.").replace("{id}", factionIdToClaim));
                    return;
                }
            } else {
                // Если админ не указал ID, используем его фракцию (если есть) или требуем указать
                if (playerFaction != null) {
                    factionIdToClaim = playerFaction.getId();
                } else {
                    sender.sendMessage(configManager.getMessage("command.usage", "&cUsage for Admin: /{label} territory claim <territory_name> <faction_id>").replace("{label}", label));
                    return;
                }
            }
        } else {
            // Лидер может клеймить только для СВОЕЙ фракции
            if (playerFaction == null) {
                sender.sendMessage(configManager.getMessage("faction.not_in", "&cYou are not in a faction."));
                return;
            }
            FactionRank rank = playerManager.getPlayerRank(player);
            // Проверяем, что это лидер (ранг 11) и у него есть право manage.own
            if (rank != null && rank.getInternalId() == 11 && rank.getPermissions().contains("hfactions.territory.manage.own")) {
                isLeaderWithPerm = true;
                factionIdToClaim = playerFaction.getId();
            }
        }


        if (!isAdmin && !isLeaderWithPerm) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cOnly Admins or Faction Leaders with permission can claim territories."));
            return;
        }

        if (factionIdToClaim == null) { // Должно быть установлено выше
            player.sendMessage(ChatColor.RED + "Internal error: Could not determine faction ID for claim.");
            return;
        }

        dynmapManager.claimTerritory(player, territoryName, factionIdToClaim); // Сообщения внутри
    }

    private void handleTerritoryDelete(CommandSender sender, String[] args, String label) {
        // Право: hfactions.territory.manage.own (лидер СВОЕЙ зоны) ИЛИ hfactions.admin.territory (любую зону)
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} territory delete <territory_name>").replace("{label}", label));
            return;
        }
        String territoryName = args[0];

        DynmapManager.TerritoryInfo territoryInfo = dynmapManager.getTerritoryInfo(territoryName);
        if (territoryInfo == null) {
            sender.sendMessage(configManager.getMessage("territory.not_found", "&cTerritory with name '{name}' not found.").replace("{name}", territoryName));
            return;
        }

        boolean canDelete = false;
        if (sender instanceof Player player) {
            boolean isAdmin = player.hasPermission("hfactions.admin.territory");
            boolean isOwnerLeaderWithPerm = false;
            String playerFactionId = playerManager.getPlayerFactionId(player);

            // Проверяем, является ли игрок лидером фракции-владельца территории
            if (territoryInfo.factionId.equals(playerFactionId)) {
                FactionRank rank = playerManager.getPlayerRank(player);
                if (rank != null && rank.getInternalId() == 11 && rank.getPermissions().contains("hfactions.territory.manage.own")) {
                    isOwnerLeaderWithPerm = true;
                }
            }

            if (isAdmin || isOwnerLeaderWithPerm) {
                canDelete = true;
            }
        } else {
            // Консоль может удалять? Допустим, да (эквивалент админа)
            canDelete = true;
        }


        if (!canDelete) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission to delete this territory."));
            return;
        }

        if (dynmapManager.deleteTerritory(territoryName)) {
            sender.sendMessage(configManager.getMessage("territory.deleted", "&aTerritory '{name}' deleted successfully.").replace("{name}", territoryName));
        } else {
            // Сообщение об ошибке уже было отправлено из deleteTerritory
        }
    }

    private void handleTerritoryMap(CommandSender sender) {
        // Право: hfactions.admin.territory
        if (!sender.hasPermission("hfactions.admin.territory")) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }
        // Команда Dynmap для полного рендера карты (если нужна)
        // Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dynmap fullrender");
        // Или просто пауза/возобновление для обновления маркеров (быстрее)
        sender.sendMessage(Utils.color("&eAttempting to trigger Dynmap update... (Use Dynmap commands for full control)"));
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dynmap pause all");
            // Небольшая задержка перед возобновлением
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dynmap resume all");
                sender.sendMessage(Utils.color("&aDynmap update triggered. Map should refresh soon."));
            }, 40L); // 2 секунды задержки
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to execute Dynmap commands: " + e.getMessage());
        }
    }


    private void handleTerritoryHelp(CommandSender sender, String label) {
        // Право: hfactions.territory.help или любое другое право на территории
        if (!sender.hasPermission("hfactions.territory.help") && !sender.hasPermission("hfactions.territory.list") && !sender.hasPermission("hfactions.territory.manage.own") && !sender.hasPermission("hfactions.admin.territory")) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for territory commands."));
            return;
        }
        String prefix = "/" + label + " territory ";
        sender.sendMessage(Utils.color("&6--- Territory Commands ---"));
        sender.sendMessage(Utils.color("&e" + prefix + "list [faction_id] &7- List claimed territories"));
        // Показываем команды управления только если есть права
        if (sender.hasPermission("hfactions.territory.manage.own") || sender.hasPermission("hfactions.admin.territory")) {
            sender.sendMessage(Utils.color("&e" + prefix + "define &7- Start defining corners for a new territory"));
            sender.sendMessage(Utils.color("&e" + prefix + "corner &7- Add a corner at your current location"));
            sender.sendMessage(Utils.color("&e" + prefix + "clear &7- Clear currently defined corners"));
            sender.sendMessage(Utils.color("&e" + prefix + "claim <name> [faction_id] &7- Claim the defined area"));
            sender.sendMessage(Utils.color("&e" + prefix + "delete <name> &7- Delete a claimed territory"));
        }
        if (sender.hasPermission("hfactions.admin.territory")) {
            sender.sendMessage(Utils.color("&c" + prefix + "map &7- Trigger a Dynmap map update"));
        }
        sender.sendMessage(Utils.color("&e" + prefix + "help &7- Show this help message"));
    }

    // --- Админка ---

    private void handleReload(CommandSender sender) {
        // Право: hfactions.admin.reload
        if (!sender.hasPermission("hfactions.admin.reload")) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }
        sender.sendMessage(Utils.color(configManager.getMessage("reload.start", "&eReloading HFactions configuration...")));
        long startTime = System.currentTimeMillis();

        // Сохраняем измененные фракции перед перезагрузкой? Нет, reload должен сбрасывать несохраненное.
        // factionManager.saveModifiedFactions();

        // Перезагружаем конфиги
        if (configManager != null) configManager.reloadConfigs();
        // Перезагружаем настройки менеджеров
        if (cuffManager != null) cuffManager.loadConfigSettings();
        if (itemManager != null) itemManager.loadAndCacheItems();
        // Перезагружаем данные из файлов (фракции, рецепты, территории)
        if (factionManager != null) factionManager.reloadFactions();
        if (plugin.getCraftingManager() != null) plugin.getCraftingManager().loadRecipes(); // Перезагружаем рецепты
        if (dynmapManager != null) dynmapManager.loadTerritories(); // Перезагружаем территории

        long endTime = System.currentTimeMillis();
        sender.sendMessage(Utils.color(configManager.getMessage("reload.success", "&aHFactions configuration reloaded successfully! ({time}ms)").replace("{time}", String.valueOf(endTime - startTime))));
        sender.sendMessage(Utils.color(configManager.getMessage("reload.warning", "&cWarning: Player data is NOT reloaded from DB. Use server restart for full reload.")));
    }


    private void handleCreate(CommandSender sender, String[] args, String label) {
        // Право: hfactions.admin.create
        if (!sender.hasPermission("hfactions.admin.create")) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }
        // /hf create <id> "<Назв>" <тип> [баланс] [размер_склада]
        if (args.length < 3) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} create <id> \"<Name>\" <STATE|CRIMINAL|OTHER> [balance] [warehouse_size]").replace("{label}", label));
            return;
        }

        String id = args[0].toLowerCase();
        // Парсинг имени в кавычках
        String name = parseQuotedString(args, 1);
        if (name == null) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} create <id> \"<Name>\" <TYPE>... Name must be in quotes if it contains spaces.").replace("{label}", label));
            return;
        }
        int argsUsedForName = countArgsForQuotedString(args, 1);
        int typeArgIndex = 1 + argsUsedForName;

        if (args.length <= typeArgIndex) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} create <id> \"<Name>\" <STATE|CRIMINAL|OTHER> ...").replace("{label}", label));
            return;
        }

        FactionType type;
        try {
            type = FactionType.valueOf(args[typeArgIndex].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(configManager.getMessage("create.invalid_type", "&cInvalid faction type: {type}. Use STATE, CRIMINAL, or OTHER.").replace("{type}", args[typeArgIndex]));
            return;
        }

        double balance = 0.0;
        int balanceArgIndex = typeArgIndex + 1;
        if (args.length > balanceArgIndex) {
            try {
                balance = Double.parseDouble(args[balanceArgIndex]);
            } catch (NumberFormatException e) {
                sender.sendMessage(configManager.getMessage("economy.invalid_amount_number", "&cInvalid balance specified. Must be a number."));
                return;
            }
        }

        int warehouseSize = 54; // Размер по умолчанию
        int sizeArgIndex = balanceArgIndex + 1;
        if (args.length > sizeArgIndex) {
            try {
                warehouseSize = Integer.parseInt(args[sizeArgIndex]);
                if (warehouseSize <= 0 || warehouseSize % 9 != 0) {
                    sender.sendMessage(configManager.getMessage("create.invalid_size", "&cWarehouse size must be a positive multiple of 9."));
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(configManager.getMessage("create.invalid_size_format", "&cInvalid warehouse size specified. Must be a number."));
                return;
            }
        }


        // Проверка на существующую фракцию
        if (factionManager.getFaction(id) != null) {
            sender.sendMessage(configManager.getMessage("create.already_exists", "&cFaction with ID '{id}' already exists.").replace("{id}", id));
            return;
        }

        // Создаем фракцию
        // TODO: Получить дефолтный цвет и префикс из конфига?
        String defaultColor = "#FFFFFF";
        String defaultPrefix = "[" + id.toUpperCase() + "]";
        factionManager.createFaction(id, name, type, defaultColor, defaultPrefix, balance, warehouseSize);
        sender.sendMessage(configManager.getMessage("create.success", "&aFaction '{name}' (ID: {id}) created successfully!").replace("{name}", name).replace("{id}", id));

    }
    // Вспомогательные методы для парсинга имени в кавычках
    private String parseQuotedString(String[] args, int startIndex) {
        if (startIndex >= args.length) return null;
        if (args[startIndex].startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            sb.append(args[startIndex].substring(1)); // Убираем первую кавычку
            for (int i = startIndex + 1; i < args.length; i++) {
                sb.append(" ");
                if (args[i].endsWith("\"")) {
                    sb.append(args[i], 0, args[i].length() - 1); // Добавляем без последней кавычки
                    return sb.toString();
                } else {
                    sb.append(args[i]);
                }
            }
            return null; // Не нашли закрывающую кавычку
        } else {
            // Если нет кавычек, считаем, что имя - одно слово
            if(args[startIndex].contains(" ")) return null; // Не позволяем пробелы без кавычек
            return args[startIndex];
        }
    }
    private int countArgsForQuotedString(String[] args, int startIndex) {
        if (startIndex >= args.length) return 0;
        if (args[startIndex].startsWith("\"")) {
            for (int i = startIndex; i < args.length; i++) {
                if (args[i].endsWith("\"")) {
                    return (i - startIndex) + 1;
                }
            }
            return args.length - startIndex; // Не нашли закрывающую кавычку - считаем все до конца
        } else {
            return 1; // Одно слово
        }
    }


    private void handleDelete(CommandSender sender, String[] args, String label) {
        // Право: hfactions.admin.delete
        if (!sender.hasPermission("hfactions.admin.delete")) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} delete <faction_id>").replace("{label}", label));
            return;
        }
        String id = args[0].toLowerCase();

        if (factionManager.getFaction(id) == null) {
            sender.sendMessage(configManager.getMessage("faction.not_found", "&cFaction with ID '{id}' not found.").replace("{id}", id));
            return;
        }

        if (factionManager.deleteFaction(id)) {
            sender.sendMessage(configManager.getMessage("delete.success", "&aFaction '{id}' deleted successfully.").replace("{id}", id));
        } else {
            sender.sendMessage(configManager.getMessage("delete.error", "&cFailed to delete faction '{id}'.").replace("{id}", id));
        }
    }


    private void handleSetBalance(CommandSender sender, String[] args, String label) {
        // Право: hfactions.admin.setbalance
        if (!sender.hasPermission("hfactions.admin.setbalance")) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} setbalance <faction_id> <amount>").replace("{label}", label));
            return;
        }
        String id = args[0].toLowerCase();
        Faction faction = factionManager.getFaction(id);
        if (faction == null) {
            sender.sendMessage(configManager.getMessage("faction.not_found", "&cFaction with ID '{id}' not found.").replace("{id}", id));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount < 0) { // Баланс может быть 0, но не отрицательным
                sender.sendMessage(configManager.getMessage("economy.invalid_amount_non_negative", "&cAmount cannot be negative."));
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(configManager.getMessage("economy.invalid_amount_number", "&cInvalid amount specified."));
            return;
        }

        factionManager.setFactionBalance(id, amount); // Метод внутри пометит для сохранения
        String formattedAmount = vaultIntegration != null ? vaultIntegration.format(amount) : String.format(Locale.US, "%.2f", amount);
        sender.sendMessage(configManager.getMessage("setbalance.success", "&aSet balance for faction '{id}' to {amount}.")
                .replace("{id}", id)
                .replace("{amount}", formattedAmount));
    }


    private void handleUncuff(CommandSender sender, String[] args, String label) {
        // Право: hfactions.admin.uncuff
        if (!sender.hasPermission("hfactions.admin.uncuff")) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} uncuff <player_name>").replace("{label}", label));
            return;
        }

        if (cuffManager == null) {
            sender.sendMessage(ChatColor.RED + "Internal error: CuffManager not available.");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(configManager.getMessage("command.player_not_found", "&cPlayer '{player}' not found or is offline.").replace("{player}", args[0]));
            return;
        }

        // Используем принудительное снятие
        if (cuffManager.uncuffPlayer(target, (sender instanceof Player ? (Player)sender : null), true)) {
            sender.sendMessage(configManager.getMessage("uncuff.success", "&aSuccessfully uncuffed {target}.").replace("{target}", target.getName()));
        } else {
            // Сообщение об ошибке (что игрок не был в наручниках) выдается внутри uncuffPlayer
            // sender.sendMessage(configManager.getMessage("uncuff.not_cuffed", "&c{target} was not cuffed.").replace("{target}", target.getName()));
        }
    }


    private void handleAdminMode(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("command.player_only", "&cThis command can only be run by a player."));
            return;
        }
        // Право: hfactions.admin.adminmode
        if (!player.hasPermission("hfactions.admin.adminmode")) {
            player.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }

        if (playerManager.isAdminInMode(player)) {
            // Если уже в режиме, выходим
            playerManager.exitAdminMode(player, false); // false = не тихий выход
        } else {
            // Входим в режим
            if (args.length < 1) {
                player.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} adminmode <faction_id>").replace("{label}", label));
                return;
            }
            String id = args[0].toLowerCase();
            Faction faction = factionManager.getFaction(id);
            if (faction == null) {
                player.sendMessage(configManager.getMessage("faction.not_found", "&cFaction with ID '{id}' not found.").replace("{id}", id));
                return;
            }
            playerManager.enterAdminMode(player, faction);
        }
    }

    private void handleGiveItem(CommandSender sender, String subCommand, String[] args, String label) {
        // Права: hfactions.admin.givetaser, hfactions.admin.givehandcuffs, hfactions.admin.giveprotocol
        String itemName = subCommand.substring(4); // taser, handcuffs, protocol
        String permission = "hfactions.admin.give" + itemName;

        if (!sender.hasPermission(permission)) {
            sender.sendMessage(configManager.getMessage("command.no_permission", "&cYou don't have permission for this command."));
            return;
        }

        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(configManager.getMessage("command.player_not_found", "&cPlayer '{player}' not found or is offline.").replace("{player}", args[0]));
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender; // Выдаем себе, если игрок и не указал цель
        } else {
            sender.sendMessage(configManager.getMessage("command.usage", "&cUsage: /{label} {subcommand} [player_name]").replace("{label}", label).replace("{subcommand}", subCommand));
            return;
        }

        if (itemManager == null) {
            sender.sendMessage(ChatColor.RED + "Internal error: ItemManager not available.");
            return;
        }

        ItemStack itemToGive = null;
        String itemNameFriendly = "Unknown Item";
        switch (itemName) {
            case "taser":
                itemToGive = itemManager.getTaserItem();
                itemNameFriendly = "Taser";
                break;
            case "handcuffs":
                itemToGive = itemManager.getHandcuffsItem();
                itemNameFriendly = "Handcuffs";
                break;
            case "protocol":
                itemToGive = itemManager.getProtocolItem();
                itemNameFriendly = "Protocol";
                break;
        }

        if (itemToGive == null || itemToGive.getType() == Material.AIR) {
            sender.sendMessage(configManager.getMessage("give.item_not_configured", "&cThe {item_name} item is not configured correctly.").replace("{item_name}", itemNameFriendly));
            return;
        }

        // Выдаем предмет
        target.getInventory().addItem(itemToGive);

        String targetMsg = configManager.getMessage("give.target_received", "&aYou received a {item_name}.");
        target.sendMessage(Utils.color(targetMsg.replace("{item_name}", itemNameFriendly)));

        if (!sender.equals(target)) {
            String senderMsg = configManager.getMessage("give.sender_gave", "&aYou gave a {item_name} to {target_name}.");
            sender.sendMessage(Utils.color(senderMsg.replace("{item_name}", itemNameFriendly).replace("{target_name}", target.getName())));
        }
    }


    // --- Вспомогательный метод для вывода помощи ---
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Utils.color("&6--- HFactions Help (Page 1) ---"));
        String cmdPrefix = "/" + label + " ";
        // Группируем команды для читаемости
        sender.sendMessage(Utils.color("&eInformation:"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "help &f- Show this message"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "list &f- List all factions"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "info <id> &f- Show faction details"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "listrecipes &f- List custom recipes"));
        sender.sendMessage(Utils.color("&ePlayer Actions:"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "leave &f- Leave your current faction"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "chat (c, fc) &f- Toggle faction chat"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "balance (bal) &f- Check faction treasury balance"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "deposit <amount> (dep) &f- Deposit money"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "withdraw <amount> (wd) &f- Withdraw money"));
        sender.sendMessage(Utils.color(" &7" + cmdPrefix + "warehouse (wh) &f- Open faction warehouse"));

        // Показываем команды управления только если есть хоть какие-то права
        if (sender.hasPermission("hfactions.faction.invite") || sender.hasPermission("hfactions.faction.kick") || sender.hasPermission("hfactions.faction.promote") || sender.hasPermission("hfactions.faction.demote") || sender.hasPermission("hfactions.faction.setrank") || sender.hasPermission("hfactions.faction.manage_ranks")) {
            sender.sendMessage(Utils.color("&eFaction Management:"));
            if (sender.hasPermission("hfactions.faction.invite")) sender.sendMessage(Utils.color(" &7" + cmdPrefix + "invite <player> &f- Invite player"));
            if (sender.hasPermission("hfactions.faction.kick")) sender.sendMessage(Utils.color(" &7" + cmdPrefix + "kick <player> &f- Kick player"));
            if (sender.hasPermission("hfactions.faction.promote")) sender.sendMessage(Utils.color(" &7" + cmdPrefix + "promote <player> &f- Promote player"));
            if (sender.hasPermission("hfactions.faction.demote")) sender.sendMessage(Utils.color(" &7" + cmdPrefix + "demote <player> &f- Demote player"));
            if (sender.hasPermission("hfactions.faction.setrank")) sender.sendMessage(Utils.color(" &7" + cmdPrefix + "setrank <player> <rank_id> &f- Set player rank"));
            if (sender.hasPermission("hfactions.faction.manage_ranks")) sender.sendMessage(Utils.color(" &7" + cmdPrefix + "manageranks &f- Manage rank display names"));
        }
        // Команды PD
        if (sender.hasPermission("hfactions.pd.fine")) {
            sender.sendMessage(Utils.color("&ePolice Actions:"));
            sender.sendMessage(Utils.color(" &7" + cmdPrefix + "fine <player> <amount> <reason> &f- Issue a fine"));
        }
        // Команды Территорий
        if (sender.hasPermission("hfactions.territory.help") || sender.hasPermission("hfactions.territory.list") || sender.hasPermission("hfactions.territory.manage.own") || sender.hasPermission("hfactions.admin.territory")) {
            sender.sendMessage(Utils.color("&eTerritories: &7Use " + cmdPrefix + "territory help"));
        }
        // Команды Админа
        if (sender.hasPermission("hfactions.admin.*")) { // Показываем все, если есть общий перм
            sender.sendMessage(Utils.color("&cAdmin Commands:"));
            sender.sendMessage(Utils.color(" &7" + cmdPrefix + "reload &f- Reload configurations"));
            sender.sendMessage(Utils.color(" &7" + cmdPrefix + "create <id> \"<N>\" <T> [bal] [size] &f- Create faction"));
            sender.sendMessage(Utils.color(" &7" + cmdPrefix + "delete <id> &f- Delete faction"));
            sender.sendMessage(Utils.color(" &7" + cmdPrefix + "setbalance <id> <amount> &f- Set faction balance"));
            sender.sendMessage(Utils.color(" &7" + cmdPrefix + "uncuff <player> &f- Force uncuff player"));
            sender.sendMessage(Utils.color(" &7" + cmdPrefix + "adminmode [id] &f- Toggle admin mode"));
            sender.sendMessage(Utils.color(" &7" + cmdPrefix + "give<item> [player] &f- Give special item"));
            // Команду territory map можно сюда добавить, если она только для админов
        }
    }


    // --- Автодополнение ---
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        List<String> possibilities = new ArrayList<>();

        // Автодополнение для первой подкоманды
        if (args.length == 1) {
            possibilities.addAll(baseSubCommands);
        }
        // Автодополнение для аргументов подкоманд
        else if (args.length >= 2) {
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "info":
                case "delete":
                case "setbalance":
                case "adminmode":
                    // Предлагаем ID фракций
                    if (args.length == 2 && factionManager != null) {
                        possibilities.addAll(factionManager.getAllFactions().keySet());
                    }
                    break;
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
                    // Предлагаем ники онлайн игроков
                    if (args.length == 2) {
                        Bukkit.getOnlinePlayers().forEach(p -> possibilities.add(p.getName()));
                    }
                    // Для setrank предлагаем ID рангов третьим аргументом
                    else if (args.length == 3 && subCommand.equals("setrank")) {
                        // Получаем фракцию цели (если можем)
                        Player target = Bukkit.getPlayerExact(args[1]);
                        if (target != null && playerManager != null) {
                            Faction targetFaction = playerManager.getPlayerFaction(target);
                            if (targetFaction != null) {
                                targetFaction.getRanks().keySet().stream().map(String::valueOf).forEach(possibilities::add);
                            } else { // Или все ранги 1-11, если не можем определить фракцию
                                for (int i = 1; i <= 11; i++) possibilities.add(String.valueOf(i));
                            }
                        } else { // Или все ранги 1-11
                            for (int i = 1; i <= 11; i++) possibilities.add(String.valueOf(i));
                        }
                    }
                    break;
                case "create":
                    // 4-й аргумент - тип фракции
                    if (args.length == 4) {
                        Arrays.stream(FactionType.values()).map(Enum::name).forEach(possibilities::add);
                    }
                    break;
                case "territory":
                    handleTerritoryTabComplete(sender, args, possibilities);
                    break;
                // Добавить другие по необходимости
            }
        }

        // Фильтруем и возвращаем результат
        String currentArg = args[args.length - 1].toLowerCase();
        for (String possibility : possibilities) {
            if (possibility.toLowerCase().startsWith(currentArg)) {
                // Проверка прав перед добавлением в автодополнение (базовая)
                if (hasPermissionForSubCommand(sender, args[0].toLowerCase(), possibility, args.length)) {
                    completions.add(possibility);
                }
            }
        }
        // Сортируем для удобства
        // Collections.sort(completions, String.CASE_INSENSITIVE_ORDER);
        return completions;
    }

    // Вспомогательный метод для проверки прав для автодополнения (упрощенный)
    private boolean hasPermissionForSubCommand(CommandSender sender, String subCommand, String possibility, int argLength) {
        // На первом уровне проверяем права на саму подкоманду
        if (argLength == 1) {
            String neededPerm = switch (subCommand) {
                case "reload", "create", "delete", "setbalance", "uncuff", "adminmode", "givetaser", "givehandcuffs", "giveprotocol" -> "hfactions.admin." + subCommand;
                case "territory" -> "hfactions.territory.help"; // Базовое право для доступа к /hf territory
                case "fine" -> "hfactions.pd.fine";
                case "invite", "kick", "promote", "demote", "setrank", "manageranks" -> "hfactions.faction." + subCommand;
                case "balance", "bal" -> "hfactions.faction.balance.view";
                case "deposit", "dep" -> "hfactions.faction.deposit";
                case "withdraw", "wd" -> "hfactions.faction.withdraw"; // Или manage_balance
                case "warehouse", "wh" -> "hfactions.faction.warehouse.open";
                // Для default:true команд права не проверяем
                default -> null;
            };
            // Если право не определено (default:true) или есть право - разрешаем
            return neededPerm == null || sender.hasPermission(neededPerm) || sender.hasPermission("hfactions.admin.*");
        }
        // Для следующих уровней пока разрешаем всё (можно усложнить проверку)
        return true;
    }

    // Автодополнение для /hf territory
    private void handleTerritoryTabComplete(CommandSender sender, String[] args, List<String> possibilities) {
        if (args.length == 2) { // Предлагаем подкоманды территории
            List<String> subs = Arrays.asList("list", "define", "corner", "clear", "claim", "delete", "map", "help");
            possibilities.addAll(subs);
        } else if (args.length == 3) {
            String territorySub = args[1].toLowerCase();
            // Предлагаем имена территорий для list, delete
            if (territorySub.equals("list") || territorySub.equals("delete")) {
                if (dynmapManager != null) possibilities.addAll(dynmapManager.getTerritoryNames());
            }
            // Предлагаем имя для claim
            else if (territorySub.equals("claim")) {
                // Нет смысла автодополнять имя новой территории
            }
        } else if (args.length == 4) {
            String territorySub = args[1].toLowerCase();
            // Предлагаем ID фракции для claim, если админ
            if (territorySub.equals("claim") && sender.hasPermission("hfactions.admin.territory")) {
                if (factionManager != null) possibilities.addAll(factionManager.getAllFactions().keySet());
            }
        }
    }
}