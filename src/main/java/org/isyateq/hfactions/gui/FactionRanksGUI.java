package org.isyateq.hfactions.gui; // Пакет gui!

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.util.ItemStackBuilder;
import org.isyateq.hfactions.util.Utils;

import java.util.*;

public class FactionRanksGUI implements InventoryHolder {

    private final HFactions plugin;
    private Inventory gui;

    public FactionRanksGUI(HFactions plugin) {
        this.plugin = plugin;
    }

    /**
     * Создает и возвращает инвентарь GUI управления рангами.
     * @param faction Фракция, чьи ранги отображаем.
     * @return Созданный инвентарь или null при ошибке.
     */
    public Inventory getInventory(Faction faction) {
        if (faction == null) return null;

        // Рассчитываем размер инвентаря (минимум 18, максимум 54, кратно 9)
        // +1 слот для информации
        int rankCount = faction.getRanks().size();
        int guiSize = Math.min(54, Math.max(18, (int) (Math.ceil((double) (rankCount + 1) / 9.0) * 9)));

        gui = Bukkit.createInventory(this, guiSize, Utils.color("&1Manage Ranks: " + faction.getName()));

        // Сортируем ранги по ID
        List<FactionRank> sortedRanks = new ArrayList<>(faction.getRanks().values());
        sortedRanks.sort(Comparator.comparingInt(FactionRank::getInternalId));

        int slotIndex = 0;
        for (FactionRank rank : sortedRanks) {
            if (slotIndex >= guiSize) break; // На случай, если рангов больше, чем слотов

            // Создаем предмет для ранга
            ItemStackBuilder rankItemBuilder = new ItemStackBuilder(Material.NAME_TAG) // Или другой материал
                    .setName(Utils.color("&eRank " + rank.getInternalId() + ": &f" + rank.getDisplayName()))
                    .setLore(
                            Utils.color("&7Internal ID: &f" + rank.getInternalId()),
                            Utils.color("&7Default Name: &f" + rank.getDefaultName()),
                            Utils.color("&7Salary: &a$" + String.format(Locale.US, "%.2f", rank.getSalary())), // Форматируем зарплату
                            Utils.color("&7Permissions: &f" + (rank.getPermissions().isEmpty() ? "None" : String.join(", ", rank.getPermissions()))),
                            " ", // Пустая строка для разделения
                            Utils.color("&bLeft-Click: &7Change display name"),
                            Utils.color("&bRight-Click: &7Reset display name")
                    )
                    .addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
                    // Используем LocalizedName для хранения ID ранга
                    .setLocalizedName(String.valueOf(rank.getInternalId()));

            // Добавляем зачарование лидеру? (Опционально)
            if (rank.getInternalId() == 11) { // Предполагаем, что 11 - лидер
                // builder.addEnchant(Enchantment.LUCK, 1);
                // builder.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            gui.setItem(slotIndex++, rankItemBuilder.build());
        }

        // Добавляем информационный предмет в конец (если есть место)
        if (slotIndex < guiSize) {
            ItemStack infoItem = new ItemStackBuilder(Material.BOOK)
                    .setName(Utils.color("&6Information"))
                    .setLore(
                            Utils.color("&7Manage display names for your faction ranks."),
                            Utils.color("&eUse LKM to set a new name via chat."),
                            Utils.color("&eUse RKM to reset the name to its default.")
                    )
                    .build();
            gui.setItem(guiSize - 1, infoItem); // Последний слот
        }


        return gui;
    }

    @Override
    public Inventory getInventory() {
        return gui;
    }
}