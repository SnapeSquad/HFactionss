package org.isyateq.hfactions.integrations; // Убедись, что пакет правильный

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.isyateq.hfactions.HFactions; // Импорт главного класса

import java.util.UUID;

/**
 * Обертка для взаимодействия с Vault Economy API.
 */
public class VaultIntegration {

    private final Economy economy;

    public VaultIntegration(Economy economy) {
        this.economy = economy;
    }

    public Economy getEconomy() {
        return economy;
    }

    // --- Основные методы для работы с экономикой ---

    public boolean hasAccount(UUID playerUuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
        return economy.hasAccount(player);
    }

    public double getBalance(UUID playerUuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
        if (!hasAccount(playerUuid)) {
            // Создаем аккаунт, если его нет? Или возвращаем 0? Зависит от логики.
            economy.createPlayerAccount(player); // Создаем аккаунт по умолчанию
            HFactions.getInstance().getLogger().info("Created Vault account for " + playerUuid);
        }
        return economy.getBalance(player);
    }

    public boolean deposit(UUID playerUuid, double amount) {
        if (amount <= 0) return false;
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
        if (!hasAccount(playerUuid)) {
            economy.createPlayerAccount(player);
            HFactions.getInstance().getLogger().info("Created Vault account for " + playerUuid + " during deposit.");
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    public boolean withdraw(UUID playerUuid, double amount) {
        if (amount <= 0) return false;
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
        if (!hasAccount(playerUuid)) {
            // Не можем снять деньги, если нет аккаунта
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public boolean hasEnough(UUID playerUuid, double amount) {
        if (amount <= 0) return true; // Считаем, что 0 или меньше всегда "хватает"
        return getBalance(playerUuid) >= amount;
    }

    public String format(double amount) {
        return economy.format(amount);
    }
}