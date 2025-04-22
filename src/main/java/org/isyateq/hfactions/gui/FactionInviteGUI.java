package org.isyateq.hfactions.gui; // Пакет gui!

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder; // Реализуем InventoryHolder
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions; // Для доступа к плагину
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.PendingInvite; // Модель приглашения
import org.isyateq.hfactions.util.ItemStackBuilder;
import org.isyateq.hfactions.util.Utils; // Для Utils.color


public class FactionInviteGUI implements InventoryHolder { // Реализуем InventoryHolder

    private final HFactions plugin; // Сохраняем ссылку на плагин (может понадобиться для ключей и т.д.)
    private Inventory gui; // Сам инвентарь

    public FactionInviteGUI(HFactions plugin, PendingInvite invite, Faction inviteFaction) {
        this.plugin = plugin;
    }

    /**
     * Создает и возвращает инвентарь GUI приглашения.
     * @param invite Объект приглашения с данными.
     * @return Созданный инвентарь или null при ошибке.
     */
    public Inventory getInventory(PendingInvite invite) {
        if (invite == null) return null;

        // Создаем инвентарь на 27 слотов (3 ряда)
        gui = Bukkit.createInventory(this, 27, Utils.color("&1Faction Invite")); // this - InventoryHolder

        // Заполняем рамку (например, черным стеклом)
        ItemStack border = new ItemStackBuilder(Material.BLACK_STAINED_GLASS_PANE).setName(" ").build();
        for (int i = 0; i < gui.getSize(); i++) {
            // Заполняем углы и края
            if (i < 9 || i >= 18 || i % 9 == 0 || i % 9 == 8) {
                gui.setItem(i, border);
            }
        }

        // Информационный предмет (например, бумага)
        ItemStack infoItem = new ItemStackBuilder(Material.PAPER)
                .setName(Utils.color("&bInvite Information"))
                .setLore(
                        Utils.color("&7From: &e" + invite.getInviterName()),
                        Utils.color("&7Faction: &e" + invite.getFactionName()),
                        Utils.color("&7Expires in: &e" + calculateTimeLeft(invite) + " seconds") // Примерное время
                )
                .addItemFlags(ItemFlag.HIDE_ATTRIBUTES) // Скрываем лишнее
                .build();
        gui.setItem(13, infoItem); // Центральный слот

        // Кнопка "Принять" (зеленая шерсть/бетон)
        ItemStack acceptButton = new ItemStackBuilder(Material.GREEN_WOOL)
                .setName(Utils.color("&a&lAccept Invite"))
                .setLore(Utils.color("&7Click to join the &e" + invite.getFactionName() + "&7 faction."))
                // Используем LocalizedName для простой идентификации клика
                .setLocalizedName("accept_invite")
                .build();
        gui.setItem(11, acceptButton); // Слот слева от центра

        // Кнопка "Отклонить" (красная шерсть/бетон)
        ItemStack declineButton = new ItemStackBuilder(Material.RED_WOOL)
                .setName(Utils.color("&c&lDecline Invite"))
                .setLore(Utils.color("&7Click to decline this invitation."))
                // Используем LocalizedName
                .setLocalizedName("decline_invite")
                .build();
        gui.setItem(15, declineButton); // Слот справа от центра

        return gui;
    }

    // Вспомогательный метод для расчета оставшегося времени (примерный)
    private long calculateTimeLeft(PendingInvite invite) {
        // Получаем время жизни из конфига
        long expireSeconds = plugin.getConfigManager().getConfig().getLong("faction.invite_expire_seconds", 60);
        long timePassedMillis = System.currentTimeMillis() - invite.getTimestamp();
        long timeLeftMillis = (expireSeconds * 1000) - timePassedMillis;
        return Math.max(0, timeLeftMillis / 1000); // Возвращаем секунды (не меньше 0)
    }


    // Реализация метода getInventory() из InventoryHolder
    // Позволяет Bukkit связывать этот объект с инвентарем
    @Override
    public Inventory getInventory() {
        // Мы создаем инвентарь в другом методе, но Bukkit требует этот
        // Возвращаем последний созданный инвентарь или null
        return gui;
    }
}