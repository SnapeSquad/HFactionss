package org.isyateq.hfactions.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.util.ItemStackBuilder;
import org.isyateq.hfactions.util.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class FactionRanksGUI {

    private final HFactions plugin;
    private final Faction faction;

    // Ключ для хранения ID ранга в NBT предмета
    private static final NamespacedKey RANK_ID_KEY = new NamespacedKey(HFactions.getInstance(), "hf_rank_id");

    public FactionRanksGUI(HFactions plugin, Faction faction) {
        this.plugin = plugin;
        this.faction = faction;
    }

    public static NamespacedKey getRankIdKey() {
        return RANK_ID_KEY;
    }

    public Inventory getInventory() {
        if (faction == null) return null;

        int rankCount = faction.getRanks().size();
        int size = Math.max(9, Math.min(54, (int) (Math.ceil(rankCount / 9.0) * 9))); // Кратный 9

        String title = Utils.color("&1Manage Ranks - " + faction.getName());
        Inventory inv = Bukkit.createInventory(null, size, title);

        // Сортируем ранги по ID
        List<FactionRank> sortedRanks = new ArrayList<>(faction.getRanks().values());
        sortedRanks.sort(Comparator.comparingInt(FactionRank::getInternalId));

        int slot = 0;
        for (FactionRank rank : sortedRanks) {
            if (slot >= size) break; // Не выходим за пределы GUI

            String displayName = rank.getDisplayName() != null ? rank.getDisplayName() : rank.getDefaultName();
            String salary = String.format("%.2f", rank.getSalary()); // Форматируем ЗП
            boolean isCustomName = rank.getDisplayName() != null;

            List<String> lore = new ArrayList<>();
            lore.add(Utils.color("&7Rank ID: &f" + rank.getInternalId()));
            lore.add(Utils.color("&7Salary: &a$" + salary));
            if (isCustomName) {
                lore.add(Utils.color("&7Default Name: &o" + rank.getDefaultName()));
            }
            lore.add("");
            lore.add(Utils.color("&eLeft-Click: &fSet Display Name"));
            lore.add(Utils.color("&eRight-Click: &fReset Display Name"));
            // Можно добавить отображение прав
            // lore.add(Utils.color("&7Permissions:"));
            // rank.getPermissions().forEach(perm -> lore.add(Utils.color("&8 - " + perm)));

            ItemStack item = new ItemStackBuilder(Material.NAME_TAG) // Или другой материал
                    .setName(Utils.color("&6Rank: &f" + displayName + (isCustomName ? " &7(&oCustom&7)" : "")))
                    .setLore(lore)
                    .setPersistentData(RANK_ID_KEY, PersistentDataType.INTEGER, rank.getInternalId()) // Сохраняем ID ранга
                    .build();

            inv.setItem(slot++, item);
        }

        return inv;
    }
}