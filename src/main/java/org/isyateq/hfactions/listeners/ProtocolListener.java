package org.isyateq.hfactions.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.commands.FactionCommand;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.ItemManager;
import org.isyateq.hfactions.util.Utils;

import java.util.Objects;

public class ProtocolListener implements Listener {

    private final HFactions plugin;
    private final ItemManager itemManager;
    private final FactionCommand factionCommand;
    private final ConfigManager configManager;

    public ProtocolListener(HFactions plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.configManager = plugin.getConfigManager();

        Object executor = Objects.requireNonNull(plugin.getCommand("hfactions")).getExecutor();
        if (executor instanceof FactionCommand) {
            this.factionCommand = (FactionCommand) executor;
        } else {
            this.factionCommand = null;
            plugin.logError("FATAL: Could not get FactionCommand instance for ProtocolListener!");
        }
    }

    @EventHandler
    public void onPlayerUseProtocol(PlayerInteractEntityEvent event) {
        // Проверяем, включено ли использование предмета
        if (!configManager.isFiningEnabled() || !configManager.useProtocolItemForFines()) {
            return;
        }

        Player player = event.getPlayer();
        Entity clickedEntity = event.getRightClicked();

        if (!(clickedEntity instanceof Player)) return;
        Player target = (Player) clickedEntity;

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (!itemManager.isProtocolItem(itemInHand)) {
            return;
        }

        event.setCancelled(true); // Отменяем стандартное действие

        if (factionCommand != null) {
            factionCommand.startFineProcess(player, target); // Запускаем процесс через команду
        } else {
            Utils.msg(player, configManager.getErrorColor() + "Ошибка: Не удалось инициализировать систему штрафов."); // TODO: lang
        }
    }
}