package org.isyateq.hfactions.managers;

import org.isyateq.hfactions.HFactions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InviteManager {

    private final HFactions plugin;
    // Карта: UUID приглашенного игрока -> Название фракции (в нижнем регистре)
    private final Map<UUID, String> pendingInvites = new ConcurrentHashMap<>();
    // Карта: UUID приглашенного игрока -> Задача на удаление приглашения по таймауту
    private final Map<UUID, BukkitTask> inviteTimeouts = new ConcurrentHashMap<>();
    private final long inviteTimeoutTicks; // Таймаут в тиках

    public InviteManager(HFactions plugin) {
        this.plugin = plugin;
        this.inviteTimeoutTicks = plugin.getConfig().getLong("settings.invite.timeout-seconds", 120) * 20L;
    }

    public boolean hasInvite(UUID playerUUID, String factionName) {
        return factionName.equalsIgnoreCase(pendingInvites.get(playerUUID));
    }

    public void addInvite(UUID invitedPlayerUUID, String factionNameLower, String inviterName) {
        String normalizedFactionName = factionNameLower.toLowerCase();
        pendingInvites.put(invitedPlayerUUID, normalizedFactionName);

        // Отправляем сообщение приглашенному игроку, если он онлайн
        Player invitedPlayer = Bukkit.getPlayer(invitedPlayerUUID);
        if (invitedPlayer != null && invitedPlayer.isOnline()) {
            plugin.getMessageUtil().send(invitedPlayer, "invite.success-received",
                    MessageUtil.placeholders(
                            "faction_name", plugin.getFactionManager().getFaction(normalizedFactionName).getName(), // Получаем оригинальное имя
                            "inviter_name", inviterName,
                            "timeout", String.valueOf(inviteTimeoutTicks / 20L)
                    ));
        }

        // Отменяем предыдущий таймаут, если он был для этого игрока
        cancelTimeoutTask(invitedPlayerUUID);

        // Устанавливаем таймаут для удаления приглашения
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            removeInvite(invitedPlayerUUID, normalizedFactionName); // Удаляем только если это все еще то же приглашение
            plugin.getLogger().info("Приглашение для " + invitedPlayerUUID + " во фракцию " + normalizedFactionName + " истекло.");
            // Можно добавить сообщение игроку, если он онлайн
            Player p = Bukkit.getPlayer(invitedPlayerUUID);
            if (p != null && p.isOnline()){
                // messageUtil.send(p, "invite.expired", ...)
            }
        }, inviteTimeoutTicks);
        inviteTimeouts.put(invitedPlayerUUID, timeoutTask);
    }

    // Возвращает имя фракции, если инвайт был принят, иначе null
    public String acceptInvite(UUID playerUUID, String factionName) {
        String normalizedFactionName = factionName.toLowerCase();
        if (hasInvite(playerUUID, normalizedFactionName)) {
            pendingInvites.remove(playerUUID);
            cancelTimeoutTask(playerUUID);
            return normalizedFactionName;
        }
        return null;
    }

    public void removeInvite(UUID playerUUID) {
        pendingInvites.remove(playerUUID);
        cancelTimeoutTask(playerUUID);
    }

    // Удаляет инвайт, только если он совпадает с указанной фракцией (для таймаута)
    private void removeInvite(UUID playerUUID, String expectedFactionNameLower) {
        pendingInvites.remove(playerUUID, expectedFactionNameLower);
        inviteTimeouts.remove(playerUUID); // Удаляем задачу из карты таймаутов в любом случае
    }

    private void cancelTimeoutTask(UUID playerUUID) {
        BukkitTask existingTask = inviteTimeouts.remove(playerUUID);
        if (existingTask != null) {
            try {
                existingTask.cancel();
            } catch (IllegalStateException ignored) {
                // Задача уже могла выполниться или быть отмененной
            }
        }
    }

    // Вызывать при выключении плагина, чтобы отменить все таски
    public void clearAllInvitesAndTasks() {
        pendingInvites.clear();
        inviteTimeouts.values().forEach(task -> {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {}
        });
        inviteTimeouts.clear();
        plugin.getLogger().info("Все ожидающие приглашения во фракции очищены.");
    }
}