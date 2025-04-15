package org.isyateq.hfactions.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.commands.FactionCommand;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.FactionManager;
import org.isyateq.hfactions.managers.GuiManager;
import org.isyateq.hfactions.managers.PlayerManager;

import java.util.List;
import java.util.UUID;

public class PlayerChatListener implements Listener {

    private final HFactions plugin;
    private final GuiManager guiManager;
    private final PlayerManager playerManager;
    private final FactionManager factionManager;
    private final FactionCommand factionCommand; // Ссылка на команды для обработки ввода штрафа
    private final ConfigManager configManager;

    public PlayerChatListener(HFactions plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        // Получаем FactionCommand
        Object executor = plugin.getCommand("hfactions").getExecutor();
        if (executor instanceof FactionCommand) {
            this.factionCommand = (FactionCommand) executor;
        } else {
            this.factionCommand = null;
            plugin.logError("FATAL: Could not get FactionCommand instance for PlayerChatListener!");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        // 1. Проверка на режим редактирования ранга
        if (guiManager.isPlayerEditingRank(playerUuid)) {
            event.setCancelled(true);
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(plugin, () -> {
                guiManager.handleRankNameInput(player, message);
            });
            return;
        }

        // 2. Проверка на режим оформления штрафа
        if (factionCommand != null && factionCommand.isPlayerIssuingFine(playerUuid)) {
            event.setCancelled(true);
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(plugin, () -> {
                factionCommand.handleFineInput(player, message);
            });
            return;
        }

        // 3. Проверка на режим фракционного чата
        if (configManager.isFactionChatEnabled() && playerManager.isPlayerInFactionChat(playerUuid)) {
            event.setCancelled(true);

            Faction faction = playerManager.getPlayerFaction(playerUuid);
            Integer rankId = playerManager.getPlayerRankId(playerUuid);

            if (faction == null || rankId == null) {
                playerManager.setFactionChatState(playerUuid, false); // Выключаем режим
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', configManager.getErrorColor() + "Ошибка фракционного чата. Режим выключен.")); // TODO: lang
                });
                plugin.logWarning("Player " + player.getName() + " was in faction chat but data was missing!");
                return;
            }

            String format = configManager.getFactionChatFormat();
            FactionRank rank = faction.getRank(rankId);
            String rankName = (rank != null) ? rank.getDisplayName() : "???";
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());

            String formattedMessage = ChatColor.translateAlternateColorCodes('&', format
                    .replace("{prefix}", faction.getFormattedPrefix().trim())
                    .replace("{rank}", rankName)
                    .replace("{player}", player.getName())
                    .replace("{message}", message)); // Цвет сообщения должен быть в format

            // Отправка членам фракции
            List<UUID> memberUuids = factionManager.getFactionMembers(faction.getId());
            Bukkit.getScheduler().runTask(plugin, () -> { // Отправка сообщений в основном потоке
                for (UUID memberUuid : memberUuids) {
                    Player memberPlayer = Bukkit.getPlayer(memberUuid);
                    if (memberPlayer != null && memberPlayer.isOnline()) {
                        memberPlayer.sendMessage(formattedMessage);
                    }
                }
                plugin.logInfo("[FactionChat:" + faction.getId() + "] " + player.getName() + ": " + message); // Лог
            });
        }
        // Если ни одно условие не сработало, сообщение идет в глобальный чат
    }
}