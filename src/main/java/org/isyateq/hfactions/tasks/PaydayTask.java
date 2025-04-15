package org.isyateq.hfactions.tasks; // Или .tasks

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.FactionManager;
import org.isyateq.hfactions.managers.PlayerManager;
import org.isyateq.hfactions.util.Utils;

import java.text.DecimalFormat;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class PaydayTask extends BukkitRunnable {

    private final HFactions plugin;
    private final PlayerManager playerManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager;
    private final Economy economy;
    private final boolean requireOnline;
    private final boolean logPayments;
    private final String messageFormat;
    private final String logFormat;
    private final DecimalFormat moneyFormat = new DecimalFormat("#,##0.00"); // Форматтер

    public PaydayTask(HFactions plugin) {
        this.plugin = plugin;
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        this.economy = HFactions.getEconomy(); // Получаем экономику

        // Загружаем настройки из конфига
        this.requireOnline = configManager.getConfig().getBoolean("payday.require_online", true);
        this.logPayments = configManager.getConfig().getBoolean("payday.log_payments", true);
        this.messageFormat = configManager.getLangMessage("usage", "payday.message", "&7{command} - {description}"); // Используем getLangMessage
        this.logFormat = configManager.getConfig().getString("payday.log_format", "[Payday] Paid {amount} to {player} (Faction: {faction_id}, Rank: {rank_id})");
    }

    @Override
    public void run() {
        if (economy == null) {
            plugin.logWarning("Payday task running, but Vault Economy is not available. Skipping payday.");
            return; // Не можем платить без экономики
        }

        plugin.logInfo("Processing payday...");
        int paidCount = 0;
        double totalPaid = 0.0;

        // Получаем список всех игроков, о которых мы знаем (из кэша PlayerManager)
        // Это включает и оффлайн игроков, если require_online = false
        Collection<UUID> playersToProcess;
        if (requireOnline) {
            // Берем только онлайн игроков
            playersToProcess = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toList());
        } else {
            // Берем всех игроков из нашего кэша
            playersToProcess = playerManager.getAllPlayerFactionEntries().keySet();
        }

        for (UUID playerUuid : playersToProcess) {
            String factionId = playerManager.getPlayerFactionId(playerUuid);
            Integer rankId = playerManager.getPlayerRankId(playerUuid);

            // Проверяем, состоит ли игрок во фракции и есть ли у него ранг
            if (factionId != null && rankId != null) {
                Faction faction = factionManager.getFaction(factionId);
                if (faction == null) continue; // Фракция не найдена? Пропускаем

                FactionRank rank = faction.getRank(rankId);
                if (rank == null) continue; // Ранг не найден? Пропускаем

                double salary = rank.getSalary();
                if (salary <= 0) continue; // Зарплата нулевая или отрицательная, пропускаем

                // --- Выплата Зарплаты ---
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid); // Получаем OfflinePlayer для Vault
                if (!requireOnline || offlinePlayer.isOnline()) { // Проверяем онлайн, если нужно
                    EconomyResponse response = economy.depositPlayer(offlinePlayer, salary); // Пытаемся заплатить

                    if (response.transactionSuccess()) {
                        paidCount++;
                        totalPaid += salary;

                        // Отправляем сообщение игроку, если он онлайн
                        Player onlinePlayer = offlinePlayer.getPlayer();
                        if (onlinePlayer != null) {
                            Map<String, String> replacements = Map.of(
                                    "amount", moneyFormat.format(salary),
                                    "currency", economy.currencyNamePlural(),
                                    "faction_name", faction.getName(),
                                    "rank_name", rank.getDisplayName()
                            );
                            Utils.msg(onlinePlayer, configManager.getLangMessage("payday.message", replacements));
                        }

                        // Логирование
                        if (logPayments) {
                            String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerUuid.toString();
                            String logMsg = logFormat
                                    .replace("{amount}", moneyFormat.format(salary))
                                    .replace("{player}", playerName)
                                    .replace("{faction_id}", factionId)
                                    .replace("{rank_id}", String.valueOf(rankId));
                            plugin.logInfo(logMsg);
                        }
                    } else {
                        // Ошибка выплаты
                        plugin.logWarning("Failed to pay salary to " + (offlinePlayer.getName() != null ? offlinePlayer.getName() : playerUuid) + ": " + response.errorMessage);
                    }
                }
                // Если requireOnline = true и игрок оффлайн, просто пропускаем его
            }
        }

        plugin.logInfo("Payday processing finished. Paid " + moneyFormat.format(totalPaid) + " to " + paidCount + " players.");
    }
}