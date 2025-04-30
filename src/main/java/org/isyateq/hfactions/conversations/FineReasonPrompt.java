package org.isyateq.hfactions.conversations; // Убедись, что пакет правильный

import org.bukkit.ChatColor; // Для запасных сообщений
import org.bukkit.conversations.*;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.FactionManager;
import org.isyateq.hfactions.managers.PlayerManager;
import org.isyateq.hfactions.integrations.VaultIntegration; // Нужен Vault
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.util.Utils; // Для цвета
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Prompt для ввода причины штрафа.
 */
public class FineReasonPrompt extends StringPrompt { // Наследуемся от StringPrompt

    private final HFactions plugin;
    private final Player target;
    private final double amount; // Сумма из предыдущего шага

    public FineReasonPrompt(HFactions plugin, Player target, double amount) {
        this.plugin = plugin;
        this.target = target;
        this.amount = amount;
    }

    @Override
    public @NotNull String getPromptText(@NotNull ConversationContext context) {
        ConfigManager cm = plugin.getConfigManager();
        VaultIntegration vault = plugin.getVaultIntegration(); // Для форматирования суммы
        String formattedAmount = vault != null ? vault.format(amount) : String.format(Locale.US, "%.2f", amount);
        String prompt = "&eEnter the reason for the fine ({amount}):"; // Дефолт
        if (cm != null) {
            prompt = cm.getMessage("fine.prompt.reason");
        }
        return Utils.color(prompt.replace("{amount}", formattedAmount));
    }

    // Вызывается после ввода строки причины
    @Override
    public Prompt acceptInput(ConversationContext context, String input) {
        Player officer = (Player) context.getForWhom();
        ConfigManager cm = plugin.getConfigManager();
        VaultIntegration vault = plugin.getVaultIntegration();
        FactionManager fm = plugin.getFactionManager();
        PlayerManager pm = plugin.getPlayerManager();

        // Проверяем доступность менеджеров
        if (cm == null || vault == null || fm == null || pm == null) {
            officer.sendMessage(ChatColor.RED + "Internal error: Managers not available. Cannot process fine.");
            return Prompt.END_OF_CONVERSATION;
        }
        // Проверяем цель (могла выйти)
        if (target == null || !target.isOnline()) {
            officer.sendMessage(Utils.color(cm.getMessage("fine.target_offline")));
            return Prompt.END_OF_CONVERSATION;
        }


        String reason = input != null ? input.trim() : "No reason specified";
        if (reason.isEmpty()) reason = "No reason specified"; // Не позволяем пустую причину

        // Получаем фракцию офицера (для начисления штрафа)
        Faction officerFaction = pm.getPlayerFaction(officer);
        if (officerFaction == null) {
            officer.sendMessage(Utils.color(cm.getMessage("fine.error_officer_faction")));
            return Prompt.END_OF_CONVERSATION;
        }

        // --- Повторяем логику выписки штрафа (как в команде /fine) ---
        String formattedAmount = vault.format(amount); // Форматируем для сообщений

        // Снимаем деньги у цели
        if (vault.withdraw(target.getUniqueId(), amount)) {
            plugin.getLogger().info("Withdrew " + formattedAmount + " from " + target.getName() + " for fine by " + officer.getName());
            // Начисляем во фракцию офицера
            if (fm.depositToFaction(officerFaction.getId(), amount)) {
                plugin.getLogger().info("Deposited fine amount " + formattedAmount + " to faction " + officerFaction.getId());
                // Сообщение оштрафованному
                String targetMsg = cm.getMessage("fine.target_fined");
                target.sendMessage(Utils.color(targetMsg
                        .replace("{amount}", formattedAmount)
                        .replace("{officer}", officer.getName())
                        .replace("{reason}", reason)));
                // Сообщение офицеру
                String officerMsg = cm.getMessage("fine.officer_success");
                officer.sendMessage(Utils.color(officerMsg
                        .replace("{target}", target.getName())
                        .replace("{amount}", formattedAmount)
                        .replace("{reason}", reason)));
                // Логирование во фракцию
                String logMsg = cm.getMessage("fine.log");
                pm.broadcastToFaction(officerFaction.getId(), Utils.color(logMsg
                        .replace("{officer}", officer.getName())
                        .replace("{target}", target.getName())
                        .replace("{amount}", formattedAmount)
                        .replace("{reason}", reason)));
            } else {
                plugin.getLogger().severe("Failed to deposit fine amount " + formattedAmount + " to faction " + officerFaction.getId() + ". Refunding target...");
                // Ошибка начисления во фракцию - возвращаем деньги цели
                if (vault.deposit(target.getUniqueId(), amount)) {
                    plugin.getLogger().info("Refunded " + formattedAmount + " to target " + target.getName());
                    target.sendMessage(Utils.color(cm.getMessage("fine.error_refunded")));
                } else {
                    plugin.getLogger().severe("CRITICAL ERROR: Failed to refund fine amount " + formattedAmount + " to target " + target.getName());
                    target.sendMessage(Utils.color("&cCRITICAL ERROR: Could not refund your fine amount. Contact admin!"));
                }
                officer.sendMessage(cm.getMessage("fine.error_faction_deposit"));
            }
        } else {
            plugin.getLogger().info("Target " + target.getName() + " did not have enough funds (" + formattedAmount + ") for fine by " + officer.getName());
            // Недостаточно средств у цели
            String msg = cm.getMessage("fine.target_no_money");
            officer.sendMessage(Utils.color(msg
                    .replace("{target}", target.getName())
                    .replace("{amount}", formattedAmount)));
        }

        return Prompt.END_OF_CONVERSATION; // Завершаем диалог в любом случае
    }

} // Конец класса FineReasonPrompt