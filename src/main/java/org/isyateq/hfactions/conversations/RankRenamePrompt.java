package org.isyateq.hfactions.conversations; // Новый пакет!

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.conversations.*;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.FactionManager;
import org.isyateq.hfactions.managers.GuiManager; // Для переоткрытия GUI
import org.isyateq.hfactions.util.Utils;

/**
 * Prompt для ввода нового имени ранга в чат.
 */
public class RankRenamePrompt extends StringPrompt {

    private final HFactions plugin;
    private final String factionId;
    private final int rankId;
    private final String oldName;

    public RankRenamePrompt(HFactions plugin, String factionId, int rankId, String oldName) {
        this.plugin = plugin;
        this.factionId = factionId;
        this.rankId = rankId;
        this.oldName = oldName;
    }

    @Override
    public String getPromptText(ConversationContext context) {
        // Сообщение уже отправлено при начале диалога
        // Можно добавить напоминание
        ConfigManager cm = plugin.getConfigManager();
        String prompt = cm != null ? cm.getMessage("ranks.rename_prompt", "&eEnter new name or 'cancel'. Current: {old_name}")
                .replace("{old_name}", oldName)
                : "&eEnter new name or 'cancel'. Current: " + oldName;
        return Utils.color(prompt);
    }

    @Override
    public Prompt acceptInput(ConversationContext context, String input) {
        Player player = (Player) context.getForWhom();
        ConfigManager cm = plugin.getConfigManager();
        FactionManager fm = plugin.getFactionManager();
        GuiManager gm = plugin.getGuiManager();

        if (input == null || fm == null || cm == null || gm == null) {
            // Отмена при ошибке
            if (player != null) player.sendMessage(ChatColor.RED + "Internal error during rename.");
            return Prompt.END_OF_CONVERSATION;
        }

        input = input.trim(); // Убираем лишние пробелы

        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage(cm.getMessage("ranks.rename_cancelled", "&eRank renaming cancelled."));
            // Открываем GUI обратно?
            // gm.openRanksGUI(player); // Может вызвать рекурсию или нежелательное поведение
            return Prompt.END_OF_CONVERSATION;
        }

        // Проверка длины и символов? (Опционально)
        if (input.length() > 32) { // Пример лимита
            player.sendMessage(cm.getMessage("ranks.rename_too_long", "&cNew name is too long (max 32 chars). Please try again or type 'cancel'."));
            return this; // Остаемся в этом же Prompt
        }
        if (input.isEmpty()) {
            player.sendMessage(cm.getMessage("ranks.rename_empty", "&cName cannot be empty. Please try again or type 'cancel'."));
            return this; // Остаемся в этом же Prompt
        }

        // Сохраняем новое имя
        fm.updateRankDisplayName(factionId, rankId, input);
        player.sendMessage(cm.getMessage("ranks.rename_success", "&aRank {rank_id} display name changed to: &f{new_name}")
                .replace("{rank_id}", String.valueOf(rankId))
                .replace("{new_name}", input));

        // Переоткрываем GUI рангов, чтобы показать изменения
        // Делаем с небольшой задержкой, чтобы сообщение успело отправиться
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) { // Проверяем, онлайн ли игрок
                gm.openRanksGUI(player);
            }
        }, 1L); // 1 тик задержки


        return Prompt.END_OF_CONVERSATION; // Завершаем диалог
    }
}