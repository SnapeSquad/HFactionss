package org.isyateq.hfactions.conversations; // Убедись, что пакет правильный

import org.bukkit.ChatColor; // Для запасных сообщений
import org.bukkit.conversations.*;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.integrations.VaultIntegration; // Нужен Vault
import org.isyateq.hfactions.util.Utils; // Для цвета


public class FineAmountPrompt extends NumericPrompt { // Наследуемся от NumericPrompt

    private final HFactions plugin;
    private final Player target; // Игрок, которого штрафуем

    public FineAmountPrompt(HFactions plugin, Player target) {
        this.plugin = plugin;
        this.target = target;
        // Проверка target на null не нужна здесь, т.к. она делается в ProtocolListener
    }

    @Override
    public String getPromptText(ConversationContext context) {
        ConfigManager cm = plugin.getConfigManager();
        // Получаем имя цели безопасно
        String targetName = target != null ? target.getName() : "Unknown Player";
        String prompt = "&eEnter the fine amount for {target_name}:"; // Дефолт
        if (cm != null) {
            prompt = cm.getMessage("fine.prompt.amount", prompt); // Получаем из конфига
        }
        return Utils.color(prompt.replace("{target_name}", targetName));
    }

    // Вызывается, если ввод является числом
    @Override
    protected Prompt acceptValidatedInput(ConversationContext context, Number input) {
        Player officer = (Player) context.getForWhom();
        ConfigManager cm = plugin.getConfigManager();
        VaultIntegration vault = plugin.getVaultIntegration(); // Получаем Vault

        // Проверяем доступность менеджеров
        if (cm == null || cm.getConfig() == null || vault == null) {
            officer.sendMessage(ChatColor.RED + "Internal error: Configuration or Vault not available.");
            return Prompt.END_OF_CONVERSATION;
        }

        double amount = input.doubleValue();

        // Проверка лимитов штрафа
        double minFine = cm.getConfig().getDouble("mechanics.fining.min_amount", 10.0);
        double maxFine = cm.getConfig().getDouble("mechanics.fining.max_amount", 5000.0);
        if (amount < minFine || amount > maxFine) {
            String msg = cm.getMessage("fine.amount_limit", "&cFine amount must be between {min} and {max}.");
            // Используем sendRawMessage, чтобы не добавлялся префикс беседы
            context.getForWhom().sendRawMessage(Utils.color(msg
                    .replace("{min}", vault.format(minFine))
                    .replace("{max}", vault.format(maxFine))));
            // Повторяем этот же Prompt для повторного ввода
            return this;
        }

        // Сохраняем сумму в контексте для следующего шага (ввод причины)
        context.setSessionData("fine_amount", amount);

        // Переходим к следующему Prompt - ввод причины
        // Убедимся, что target все еще доступен (хотя он final)
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Error: Target player is no longer valid.");
            return Prompt.END_OF_CONVERSATION;
        }
        return new FineReasonPrompt(plugin, target, amount); // Передаем цель и сумму
    }

    // Вызывается, если ввод НЕ является числом
    @Override
    protected String getInputNotNumericText(ConversationContext context, String invalidInput) {
        ConfigManager cm = plugin.getConfigManager();
        String message = "&cInvalid amount specified. Must be a number."; // Дефолт
        if (cm != null) {
            message = cm.getMessage("economy.invalid_amount_number", message);
        }
        // Возвращаем текст ошибки, который будет показан игроку перед повторным запросом prompt
        return Utils.color(message);
    }

    // Дополнительно можно переопределить getFailedValidationText, если NumericPrompt делает свои проверки
    @Override
    protected String getFailedValidationText(ConversationContext context, String invalidInput) {
        // По умолчанию NumericPrompt не имеет своей валидации, но на всякий случай
        return getInputNotNumericText(context, invalidInput);
    }

} // Конец класса FineAmountPrompt