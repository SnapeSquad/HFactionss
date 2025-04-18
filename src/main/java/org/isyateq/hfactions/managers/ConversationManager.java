package org.isyateq.hfactions.managers;

import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationFactory;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.conversations.FineAmountPrompt;
import org.isyateq.hfactions.conversations.RankRenamePrompt; // Импорт нашего Prompt
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.util.Utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Управляет беседами (Conversations API) для плагина.
 */
public class ConversationManager {

    private final HFactions plugin;
    private final ConversationFactory conversationFactory;
    private final Map<UUID, Conversation> activeConversations = new ConcurrentHashMap<>(); // Отслеживаем активные беседы

    public ConversationManager(HFactions plugin) {
        this.plugin = plugin;
        this.conversationFactory = new ConversationFactory(plugin)
                .withModality(true) // Блокирует чат для других сообщений во время беседы
                .withEscapeSequence("/cancel") // Позволяет выйти из беседы командой /cancel
                .withTimeout(60) // Таймаут беседы в секундах
                .thatExcludesNonPlayersWithMessage("Conversations only work for players.");
        // .withFirstPrompt(...) // Первый prompt задается при старте конкретной беседы
        // .withConversationCanceller(...) // Можно добавить свой обработчик отмены
        // .addConversationAbandonedListener(...) // Слушатель завершения/отмены
    }
    public void startFineConversation(Player officer, Player target) {
        if (officer == null || target == null) return;

        // Отменяем предыдущую беседу офицера, если есть
        cancelConversation(officer.getUniqueId());

        // Создаем и начинаем новую беседу
        Conversation conversation = conversationFactory
                .withFirstPrompt(new FineAmountPrompt(plugin, target)) // Начинаем с ввода суммы
                .buildConversation(officer);

        activeConversations.put(officer.getUniqueId(), conversation); // Сохраняем ссылку
        conversation.addConversationAbandonedListener(event -> {
            activeConversations.remove(officer.getUniqueId()); // Убираем ссылку
            if (!event.gracefulExit()) {
                if (officer.isOnline()) {
                    ConfigManager cm = plugin.getConfigManager();
                    officer.sendMessage(Utils.color(cm != null ? cm.getMessage("fine.conversation_cancelled", "&cFine process cancelled.")
                            : "&cFine process cancelled."));
                }
            }
        });
        conversation.begin();
    }

    /**
     * Начинает беседу для переименования ранга.
     */
    public void startRankRenameConversation(Player player, String factionId, int rankId) {
        if (player == null || factionId == null || plugin.getFactionManager() == null) return;

        Faction faction = plugin.getFactionManager().getFaction(factionId);
        if (faction == null) return;
        FactionRank rank = faction.getRank(rankId);
        if (rank == null) return;

        // Если у игрока уже есть активная беседа, отменяем ее
        cancelConversation(player.getUniqueId());

        // Создаем и начинаем новую беседу
        Conversation conversation = conversationFactory
                .withFirstPrompt(new RankRenamePrompt(plugin, factionId, rankId, rank.getDisplayName())) // Задаем первый Prompt
                .buildConversation(player);

        activeConversations.put(player.getUniqueId(), conversation); // Сохраняем ссылку
        conversation.addConversationAbandonedListener(event -> {
            activeConversations.remove(player.getUniqueId()); // Убираем ссылку при завершении/отмене
            if (!event.gracefulExit()) { // Если вышли не штатно (таймаут, /cancel, выход с сервера)
                if (player.isOnline()) {
                    player.sendMessage(Utils.color("&cRank renaming cancelled."));
                }
            }
        });
        conversation.begin();
    }

    /**
     * Отменяет активную беседу для игрока, если она есть.
     */
    public void cancelConversation(UUID playerUUID) {
        Conversation conversation = activeConversations.remove(playerUUID);
        if (conversation != null && conversation.getState() == Conversation.ConversationState.STARTED) {
            conversation.abandon();
        }
    }

    /**
     * Отменяет беседу переименования ранга (вызывается из GuiManager при закрытии GUI).
     */
    public void cancelRenameConversation(UUID playerUUID) {
        // Проверяем, является ли активная беседа именно беседой переименования?
        // Пока просто отменяем любую активную беседу этого игрока
        cancelConversation(playerUUID);
    }


}