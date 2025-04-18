package org.isyateq.hfactions.tasks;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.integrations.VaultIntegration; // Нужен Vault
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.FactionManager;
import org.isyateq.hfactions.managers.PlayerManager;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.util.Utils; // Для color

import java.util.UUID;
import java.util.logging.Level;

public class PaydayTask extends BukkitRunnable {

    private final HFactions plugin;
    private final PlayerManager playerManager;
    private final FactionManager factionManager;
    private final VaultIntegration vaultIntegration; // Получаем интеграцию
    private final ConfigManager configManager;

    private final boolean requireOnline;
    private final String paydayMessage;
    private final String paydayErrorNoAccount;
    private final String paydayErrorNoFactionFunds;

    public PaydayTask(HFactions plugin) {
        this.plugin = plugin;
        // Получаем зависимости
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();
        this.vaultIntegration = plugin.getVaultIntegration();
        this.configManager = plugin.getConfigManager();

        // Проверки на null
        if (this.playerManager == null) plugin.getLogger().severe("PlayerManager is null in PaydayTask!");
        if (this.factionManager == null) plugin.getLogger().severe("FactionManager is null in PaydayTask!");
        if (this.vaultIntegration == null) plugin.getLogger().warning("VaultIntegration is null in PaydayTask! Payday will likely fail."); // Warning, т.к. Vault может быть не установлен
        if (this.configManager == null || configManager.getConfig() == null) {
            plugin.getLogger().severe("ConfigManager or config is null in PaydayTask! Using default settings.");
            // Задаем дефолты, чтобы избежать NPE
            this.requireOnline = true;
            this.paydayMessage = Utils.color("&a[PayDay] You received ${amount} salary.");
            this.paydayErrorNoAccount = Utils.color("&c[PayDay] Error: Could not access your bank account.");
            this.paydayErrorNoFactionFunds = Utils.color("&c[PayDay] Error: Your faction doesn't have enough funds to pay your salary.");
        } else {
            FileConfiguration config = configManager.getConfig();
            this.requireOnline = config.getBoolean("payday.require_online", true);
            this.paydayMessage = Utils.color(config.getString("payday.message", "&a[PayDay] You received {amount} salary."));
            this.paydayErrorNoAccount = Utils.color(config.getString("payday.error_no_account", "&c[PayDay] Error: Could not access your bank account."));
            this.paydayErrorNoFactionFunds = Utils.color(config.getString("payday.error_no_faction_funds", "&c[PayDay] Error: Your faction doesn't have enough funds to pay salary."));
        }

    }

    @Override
    public void run() {
        plugin.getLogger().info("Processing PayDay...");
        if (playerManager == null || factionManager == null || vaultIntegration == null) {
            plugin.getLogger().severe("Cannot process PayDay: One or more managers are null.");
            return;
        }

        int paidCount = 0;
        int skippedOffline = 0;
        int skippedNoFaction = 0;
        int skippedNoRank = 0;
        int skippedNoSalary = 0;
        int skippedNoFunds = 0;
        int skippedNoAccount = 0;

        // Кого обрабатываем: всех онлайн или всех, кто есть в базе?
        // ТЗ не уточняет, но require_online намекает на обработку только онлайн игроков.
        // Если нужно платить оффлайн, логику нужно будет сильно усложнить (загрузка данных оффлайн игроков).
        // Пока реализуем для онлайн игроков.

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            String factionId = playerManager.getPlayerFactionId(player);
            Integer rankId = playerManager.getPlayerRankId(player);

            if (factionId == null) {
                skippedNoFaction++;
                continue; // Игрок не во фракции
            }
            if (rankId == null) {
                skippedNoRank++;
                plugin.getLogger().warning("Player " + player.getName() + " is in faction " + factionId + " but has null rank ID during payday.");
                continue; // Ошибка данных
            }

            Faction faction = factionManager.getFaction(factionId);
            if (faction == null) {
                plugin.getLogger().warning("Faction " + factionId + " not found during payday for player " + player.getName());
                continue; // Фракция удалена?
            }

            FactionRank rank = faction.getRank(rankId);
            if (rank == null) {
                skippedNoRank++;
                plugin.getLogger().warning("Rank ID " + rankId + " not found in faction " + factionId + " during payday for player " + player.getName());
                continue; // Ранг удален?
            }

            double salary = rank.getSalary();
            if (salary <= 0) {
                skippedNoSalary++;
                continue; // Зарплата не установлена или равна нулю
            }

            // Проверяем баланс фракции
            if (!factionManager.withdrawFromFaction(factionId, salary)) { // Метод withdraw уже помечает для сохранения
                skippedNoFunds++;
                player.sendMessage(paydayErrorNoFactionFunds);
                plugin.getLogger().warning("Faction " + factionId + " has insufficient funds (" + faction.getBalance() + ") to pay salary (" + salary + ") to " + player.getName());
                continue; // Недостаточно средств
            }

            // Начисляем зарплату игроку через Vault
            if (vaultIntegration.deposit(uuid, salary)) {
                paidCount++;
                String formattedAmount = vaultIntegration.format(salary); // Форматируем сумму
                player.sendMessage(paydayMessage.replace("{amount}", formattedAmount));
            } else {
                skippedNoAccount++;
                player.sendMessage(paydayErrorNoAccount);
                plugin.getLogger().severe("Failed to deposit payday salary (" + salary + ") to player " + player.getName() + " (UUID: " + uuid + "). Refunding faction...");
                // Возвращаем деньги фракции, если не удалось начислить игроку
                factionManager.depositToFaction(factionId, salary); // Метод deposit помечает для сохранения
            }
        }

        // Если нужно было платить оффлайн, здесь была бы логика загрузки данных из БД.

        plugin.getLogger().info("PayDay processing complete. Paid: " + paidCount +
                ", No Faction: " + skippedNoFaction +
                ", No Rank/Salary: " + (skippedNoRank + skippedNoSalary) +
                ", No Faction Funds: " + skippedNoFunds +
                ", Bank Account Error: " + skippedNoAccount +
                (requireOnline ? "" : ", Skipped Offline: " + skippedOffline) // Показываем пропущенных оффлайн только если не требовался онлайн
        );

        // Планируем сохранение измененных фракций (если были изменения баланса)
        // FactionManager сам запланирует сохранение при вызове withdraw/deposit через markAsModified
        // factionManager.saveModifiedFactions(); // Не нужно вызывать здесь напрямую
    }
}