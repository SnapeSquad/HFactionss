package org.isyateq.hfactions.managers;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent; // Правильный импорт
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector; // Импорт Vector
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.util.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CuffManager {

    private final HFactions plugin;
    private final PlayerManager playerManager;
    private final ConfigManager configManager;

    // TargetUUID -> CufferUUID
    private final ConcurrentHashMap<UUID, UUID> cuffedPlayers = new ConcurrentHashMap<>();
    private BukkitTask leashCheckTask = null;
    private final long checkIntervalTicks;
    private final double maxLeashDistanceSquared;
    private final boolean handcuffsEnabled; // Кэшируем состояние

    public CuffManager(HFactions plugin) {
        this.plugin = plugin;
        this.playerManager = plugin.getPlayerManager();
        this.configManager = plugin.getConfigManager();
        this.handcuffsEnabled = configManager.isHandcuffsEnabled();

        if (this.handcuffsEnabled) {
            this.checkIntervalTicks = Math.max(1, configManager.getHandcuffsCheckIntervalTicks()); // Интервал минимум 1 тик
            double maxDist = Math.max(1.0, configManager.getHandcuffsMaxLeashDistance()); // Дистанция минимум 1 блок
            this.maxLeashDistanceSquared = maxDist * maxDist;
            startLeashCheckTask();
        } else {
            // Устанавливаем дефолты, но таск не запускаем
            this.checkIntervalTicks = 10;
            this.maxLeashDistanceSquared = 100.0;
        }
    }

    public void shutdown() {
        if (leashCheckTask != null && !leashCheckTask.isCancelled()) {
            leashCheckTask.cancel();
        }
        // Снимаем наручники со всех при выключении
        new HashSet<>(cuffedPlayers.keySet()).forEach(this::uncuffPlayerInternal); // Используем внутренний метод без звука/сообщений
        cuffedPlayers.clear(); // Очищаем карту
    }

    public boolean isCuffed(Player playerUuid) {
        return handcuffsEnabled && cuffedPlayers.containsKey(playerUuid);
    }

    public UUID getCuffer(UUID targetUuid) {
        return handcuffsEnabled ? cuffedPlayers.get(targetUuid) : null;
    }

    public Set<UUID> getCuffedPlayerUUIDs() {
        return Collections.unmodifiableSet(cuffedPlayers.keySet());
    }

    public boolean cuffPlayer(Player cuffer, Player target) {
        if (!handcuffsEnabled) return false; // Нельзя использовать, если выключено

        UUID cufferUuid = cuffer.getUniqueId();
        UUID targetUuid = target.getUniqueId();

        if (targetUuid.equals(cufferUuid)) return false;
        if (isCuffed(targetUuid)) {
            Utils.msg(cuffer, configManager.getErrorColor() + "Игрок " + target.getName() + " уже связан."); // TODO: lang
            return false;
        }
        if (target.getGameMode() == GameMode.CREATIVE || target.getGameMode() == GameMode.SPECTATOR) {
            Utils.msg(cuffer, configManager.getErrorColor() + "Нельзя надеть наручники на этого игрока."); // TODO: lang
            return false;
        }

        // Проверка фракций
        Faction cufferFaction = playerManager.getPlayerFaction(cufferUuid);
        Faction targetFaction = playerManager.getPlayerFaction(targetUuid);

        if (!configManager.allowHandcuffsFriendlyCuff() && cufferFaction != null && cufferFaction.equals(targetFaction)) {
            Utils.msg(cuffer, configManager.getErrorColor() + "Нельзя использовать наручники против своих."); // TODO: lang
            return false;
        }
        if (!configManager.allowHandcuffsStateOnStateCuff() && cufferFaction != null && cufferFaction.getType() == FactionType.STATE && targetFaction != null && targetFaction.getType() == FactionType.STATE) {
            Utils.msg(cuffer, configManager.getErrorColor() + "Гос. служащие не могут связывать друг друга."); // TODO: lang
            return false;
        }

        cuffedPlayers.put(targetUuid, cufferUuid);
        Utils.applyEffectsFromStringList(target, configManager.getHandcuffsCuffedEffects());
        Utils.msg(cuffer, configManager.getSuccessColor() + "Вы надели наручники на игрока " + target.getName() + "."); // TODO: lang
        Utils.msg(target, configManager.getErrorColor() + "На вас надели наручники!"); // TODO: lang
        Utils.playSound(cuffer.getLocation(), configManager.getHandcuffsSoundCuff());

        startLeashCheckTask();
        return true;
    }

    // Публичный метод для снятия с сообщениями и звуком
    public boolean uncuffPlayer(UUID targetUuid, Player remover) {
        UUID cufferUuid = cuffedPlayers.get(targetUuid); // Получаем того, кто надел
        if (uncuffPlayerInternal(targetUuid)) { // Вызываем внутренний метод
            if (remover != null) {
                Utils.playSound(remover.getLocation(), configManager.getHandcuffsSoundUncuff()); // Звук у того, кто снимает
                // Уведомляем того, кто надел (если это не тот же, кто снимает и он онлайн)
                if (cufferUuid != null && !cufferUuid.equals(remover.getUniqueId())) {
                    Player originalCuffer = Bukkit.getPlayer(cufferUuid);
                    Player targetPlayer = Bukkit.getPlayer(targetUuid); // Получаем цель для имени
                    if (originalCuffer != null && originalCuffer.isOnline() && targetPlayer != null) {
                        Utils.msg(originalCuffer, configManager.getHighlightColor() + remover.getName() + " снял наручники с " + targetPlayer.getName() + "."); // TODO: lang
                    }
                }
            }
            return true;
        }
        return false;
    }

    // Внутренний метод для снятия без сообщений и звуков (используется в shutdown и при ошибках)
    private boolean uncuffPlayerInternal(UUID targetUuid) {
        if (!handcuffsEnabled) return false;
        if (cuffedPlayers.remove(targetUuid) != null) {
            Player targetPlayer = Bukkit.getPlayer(targetUuid);
            if (targetPlayer != null && targetPlayer.isOnline()) {
                Utils.removeEffectsFromStringList(targetPlayer, configManager.getHandcuffsCuffedEffects());
                // Сообщение игроку отправляется в публичном методе uncuffPlayer
            }
            return true;
        }
        return false;
    }


    private void startLeashCheckTask() {
        if (!handcuffsEnabled) return;
        if (leashCheckTask == null || leashCheckTask.isCancelled()) {
            leashCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkLeashes, checkIntervalTicks, checkIntervalTicks);
            // plugin.logInfo("Handcuff leash check task started.");
        }
    }

    private void checkLeashes() {
        if (!handcuffsEnabled || cuffedPlayers.isEmpty()) { // Проверяем флаг и пустоту
            if (leashCheckTask != null && !leashCheckTask.isCancelled()) {
                leashCheckTask.cancel();
                // plugin.logInfo("Handcuff leash check task stopped.");
            }
            return;
        }

        new HashMap<>(cuffedPlayers).forEach((targetUuid, cufferUuid) -> {
            Player target = Bukkit.getPlayer(targetUuid);
            Player cuffer = Bukkit.getPlayer(cufferUuid);

            // Проверка, не сняли ли наручники уже в другом месте
            if (!cuffedPlayers.containsKey(targetUuid)) return;

            if (target == null || !target.isOnline() || cuffer == null || !cuffer.isOnline()) {
                plugin.logInfo("Uncuffing player " + targetUuid + " because cuffer/target logged out.");
                uncuffPlayerInternal(targetUuid); // Используем внутренний метод
                return;
            }

            if (!Objects.equals(target.getWorld(), cuffer.getWorld())) {
                plugin.logWarning("Uncuffing " + target.getName() + " because players are in different worlds!");
                uncuffPlayerInternal(targetUuid); // Используем внутренний метод
                Utils.msg(cuffer, configManager.getErrorColor() + "Игрок " + target.getName() + " переместился в другой мир, наручники сняты."); // TODO: lang
                return;
            }

            try {
                double distanceSquared = target.getLocation().distanceSquared(cuffer.getLocation());
                if (distanceSquared > maxLeashDistanceSquared) {
                    // Рассчитываем точку чуть позади ведущего
                    Location cufferLoc = cuffer.getLocation();
                    Vector directionToTarget = target.getLocation().toVector().subtract(cufferLoc.toVector()).normalize();
                    // Если вектор нулевой (стоят в одной точке), просто берем направление взгляда ведущего
                    if (directionToTarget.lengthSquared() < 0.01) {
                        directionToTarget = cufferLoc.getDirection().normalize().multiply(-1); // Направление назад
                    } else {
                        directionToTarget.multiply(-1.5); // Смещаем назад на 1.5 блока
                    }
                    Location teleportLocation = cufferLoc.clone().add(directionToTarget);

                    // Сохраняем поворот головы цели
                    teleportLocation.setPitch(target.getLocation().getPitch());
                    teleportLocation.setYaw(target.getLocation().getYaw());

                    Location safeLocation = Utils.findSafeLocationNear(teleportLocation, target.getWorld());

                    // Телепортация
                    if (HFactions.isPaperServer()) {
                        target.teleportAsync(safeLocation, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
                            if (success) {
                                Utils.playSound(safeLocation, configManager.getHandcuffsSoundLeashTeleport());
                            } else {
                                // Fallback на синхронный, если асинхронный не удался
                                Bukkit.getScheduler().runTask(plugin, () -> handleFailedTeleport(target, cuffer, safeLocation));
                            }
                        });
                    } else {
                        // Синхронный телепорт для Spigot
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (target.teleport(safeLocation, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                                Utils.playSound(safeLocation, configManager.getHandcuffsSoundLeashTeleport());
                            } else {
                                handleFailedTeleport(target, cuffer, safeLocation);
                            }
                        });
                    }
                }
            } catch (IllegalArgumentException e) {
                plugin.logWarning("Error calculating distance for cuffed player " + target.getName() + ": " + e.getMessage());
                uncuffPlayerInternal(targetUuid);
            }
        });
    }

    // Вызывается СИНХРОННО
    private void handleFailedTeleport(Player target, Player cuffer, Location attemptedLoc) {
        plugin.logError("Failed to teleport cuffed player " + target.getName() + " to " + attemptedLoc);
        if (cuffedPlayers.containsKey(target.getUniqueId())) {
            if (uncuffPlayerInternal(target.getUniqueId())) {
                Utils.msg(cuffer, configManager.getErrorColor() + "Не удалось телепортировать " + target.getName() + ", наручники сняты."); // TODO: lang
                // Сообщать ли цели? Она может быть в неподходящем месте.
                // Utils.msg(target, configManager.getErrorColor() + "Произошла ошибка при телепортации, наручники сняты."); // TODO: lang
            }
        }
    }
}