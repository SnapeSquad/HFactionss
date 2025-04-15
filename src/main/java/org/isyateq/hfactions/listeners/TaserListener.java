package org.isyateq.hfactions.listeners;

import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.FactionManager;
import org.isyateq.hfactions.managers.ItemManager;
import org.isyateq.hfactions.managers.PlayerManager;
import org.isyateq.hfactions.util.Utils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TaserListener implements Listener {

    private final HFactions plugin;
    private final ItemManager itemManager;
    private final PlayerManager playerManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final long taserCooldownMillis;

    public TaserListener(HFactions plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        this.taserCooldownMillis = configManager.getTaserCooldownSeconds() * 1000L;
    }

    @EventHandler
    public void onPlayerUseTaser(PlayerInteractEvent event) {
        if (!configManager.isTaserEnabled() || !event.getAction().isRightClick()) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!itemManager.isTaserItem(item)) {
            return;
        }

        event.setCancelled(true); // Отменяем действие предмета

        // Проверка кулдауна
        if (isOnCooldown(player.getUniqueId())) {
            long timeLeft = getCooldownSecondsLeft(player.getUniqueId());
            Utils.msg(player, configManager.getErrorColor() + "Тайзер перезаряжается... Повторите через " + timeLeft + " сек."); // TODO: lang
            return;
        }

        // --- Логика выстрела ---
        World world = player.getWorld();
        Location eyeLocation = player.getEyeLocation();
        Vector direction = eyeLocation.getDirection();
        double range = configManager.getTaserRange();

        RayTraceResult result = world.rayTraceEntities(
                eyeLocation, direction, range, 0.4,
                entity -> entity instanceof LivingEntity && !entity.getUniqueId().equals(player.getUniqueId()) && canTarget(player, entity)
        );

        setCooldown(player.getUniqueId()); // Устанавливаем кулдаун СРАЗУ
        Utils.playSound(player.getLocation(), configManager.getTaserSoundFire()); // Звук выстрела

        if (result != null && result.getHitEntity() instanceof LivingEntity) {
            LivingEntity target = (LivingEntity) result.getHitEntity();
            Utils.playSound(target.getLocation(), configManager.getTaserSoundHitEntity());
            Utils.spawnParticle(target.getLocation().add(0, target.getHeight() / 2, 0), configManager.getTaserParticleHitEntity());
            Utils.applyEffectsFromStringList(target, configManager.getTaserHitEffects()); // Применяем эффекты

            if (target instanceof Player) {
                Utils.msg(player, configManager.getSuccessColor() + "Вы успешно применили тайзер к игроку " + target.getName() + "!"); // TODO: lang
                Utils.msg((Player) target, configManager.getErrorColor() + "Вас ударили тайзером!"); // TODO: lang
            } else {
                Utils.msg(player, configManager.getHighlightColor() + "Вы применили тайзер к " + target.getType().name().toLowerCase() + "."); // TODO: lang
            }
        } else if (result != null && result.getHitBlock() != null) {
            Utils.playSound(result.getHitPosition().toLocation(world), configManager.getTaserSoundHitBlock());
            Utils.msg(player, configManager.getSecondaryColor() + "Выстрел тайзера попал в блок."); // TODO: lang
        } else {
            Utils.msg(player, configManager.getSecondaryColor() + "Вы промахнулись."); // TODO: lang
        }
    }

    // --- Управление кулдауном ---
    private boolean isOnCooldown(UUID uuid) {
        return cooldowns.getOrDefault(uuid, 0L) > System.currentTimeMillis();
    }

    private long getCooldownSecondsLeft(UUID uuid) {
        long endTime = cooldowns.getOrDefault(uuid, 0L);
        long currentTime = System.currentTimeMillis();
        if (currentTime < endTime) {
            return ((endTime - currentTime) / 1000) + 1;
        }
        return 0;
    }

    private void setCooldown(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis() + taserCooldownMillis);
    }


    /** Проверяет, можно ли атаковать данную цель */
    private boolean canTarget(Player attacker, Entity target) {
        if (!(target instanceof Player)) return true; // Мобы - ок
        Player targetPlayer = (Player) target;

        // Проверка на своих
        if (!configManager.allowTaserFriendlyFire()) {
            Faction attackerFaction = playerManager.getPlayerFaction(attacker.getUniqueId());
            Faction targetFaction = playerManager.getPlayerFaction(targetPlayer.getUniqueId());
            if (attackerFaction != null && attackerFaction.equals(targetFaction)) {
                Utils.msg(attacker, configManager.getErrorColor() + "Нельзя использовать тайзер против своих."); // TODO: lang
                return false;
            }
        }
        // Проверка гос -> гос
        if (!configManager.allowTaserStateOnStateFire()) {
            Faction attackerFaction = playerManager.getPlayerFaction(attacker.getUniqueId());
            Faction targetFaction = playerManager.getPlayerFaction(targetPlayer.getUniqueId());
            if (attackerFaction != null && attackerFaction.getType() == FactionType.STATE &&
                    targetFaction != null && targetFaction.getType() == FactionType.STATE) {
                Utils.msg(attacker, configManager.getErrorColor() + "Гос. служащие не могут применять тайзер друг против друга."); // TODO: lang
                return false;
            }
        }
        return true;
    }
}