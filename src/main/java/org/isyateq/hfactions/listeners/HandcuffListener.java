package org.isyateq.hfactions.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent; // Исправлен импорт
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.CuffManager;
import org.isyateq.hfactions.managers.ItemManager;
import org.isyateq.hfactions.util.Utils;

import java.util.UUID;

public class HandcuffListener implements Listener {

    private final HFactions plugin;
    private final ItemManager itemManager;
    private final CuffManager cuffManager;
    private final ConfigManager configManager;

    public HandcuffListener(HFactions plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.cuffManager = plugin.getCuffManager();
        this.configManager = plugin.getConfigManager();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity clickedEntity = event.getRightClicked();

        if (!(clickedEntity instanceof Player)) return;
        Player target = (Player) clickedEntity;
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        // 1. Надевание (ПКМ с наручниками)
        if (itemManager.isHandcuffsItem(itemInHand) && !player.isSneaking()) {
            event.setCancelled(true);
            cuffManager.cuffPlayer(player, target); // Сообщения и звук внутри метода
            return;
        }

        // 2. Снятие (Shift+ПКМ по связанному)
        if (cuffManager.isCuffed(target.getUniqueId()) && player.isSneaking()) {
            UUID cufferUuid = cuffManager.getCuffer(target.getUniqueId());
            // Снять может только тот, кто надел, или админ? Пока только тот, кто надел.
            // TODO: Добавить проверку прав hfactions.admin.uncuff.bypass или админа
            if (player.getUniqueId().equals(cufferUuid) /* || player.hasPermission("hfactions.admin.uncuff.bypass") */) {
                event.setCancelled(true);
                // --- ИСПРАВЛЕННЫЙ ВЫЗОВ ---
                if (cuffManager.uncuffPlayer(target.getUniqueId(), player)) { // Передаем player как второго аргумента
                    Utils.playSound(player.getLocation(), configManager.getHandcuffsSoundUncuff());
                    // Сообщения теперь обрабатываются внутри uncuffPlayer
                } else {
                    Utils.msg(player, configManager.getErrorColor() + "Не удалось снять наручники."); // TODO: lang
                }
            } else {
                // Если пытается снять не тот, кто надел (можно добавить сообщение)
                // Utils.msg(player, configManager.getErrorColor() + "Снять наручники может только тот, кто их надел.");
            }
            return; // Завершаем обработку
        }
    }

    // --- Запреты ---
    private boolean checkCuffedAndCancel(Player player, String cantDoMessage) {
        if (cuffManager.isCuffed(player.getUniqueId())) {
            Utils.msg(player, configManager.getErrorColor() + cantDoMessage);
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (configManager.disableCuffedItemDrop()
                && checkCuffedAndCancel(event.getPlayer(), "Вы не можете выбрасывать предметы в наручниках!")) { // TODO: lang
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (configManager.disableCuffedItemPickup() && cuffManager.isCuffed(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (configManager.disableCuffedBlockBreak()
                && checkCuffedAndCancel(event.getPlayer(), "Вы не можете ломать блоки в наручниках!")) { // TODO: lang
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (configManager.disableCuffedBlockPlace()
                && checkCuffedAndCancel(event.getPlayer(), "Вы не можете ставить блоки в наручниках!")) { // TODO: lang
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (configManager.disableCuffedInventoryOpen()
                && checkCuffedAndCancel(player, "Вы не можете открыть инвентарь в наручниках!")) { // TODO: lang
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event){
        if (configManager.disableCuffedItemSwitch()
                && checkCuffedAndCancel(event.getPlayer(), "Вы не можете сменить предмет в руке в наручниках!")) { // TODO: lang
            // Отмена здесь сложна, пока просто сообщение
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (configManager.disableCuffedInteraction()
                && event.hasBlock()
                && (event.getAction().isRightClick())
                && checkCuffedAndCancel(event.getPlayer(), "Вы не можете взаимодействовать с этим в наручниках!")) { // TODO: lang
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player damager = (Player) event.getDamager();
            if (cuffManager.isCuffed(damager.getUniqueId())) {
                Utils.msg(damager, configManager.getErrorColor() + "Вы не можете атаковать в наручниках!"); // TODO: lang
                event.setCancelled(true);
            }
        }
    }

    // @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    // public void onPlayerCommand(PlayerCommandPreprocessEvent event) { ... } // Команды пока не блокируем
}