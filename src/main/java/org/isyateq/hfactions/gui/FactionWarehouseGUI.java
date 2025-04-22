package org.isyateq.hfactions.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey; // Импорт NamespacedKey
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType; // Импорт PersistentDataType
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.util.ItemStackBuilder;
import org.isyateq.hfactions.util.Utils;

public class FactionWarehouseGUI {

    private final HFactions plugin;
    private final Faction faction;

    // Ключ для хранения номера страницы в NBT (теперь PersistentData) предмета-индикатора
    private static final NamespacedKey PAGE_KEY = new NamespacedKey(HFactions.getInstance(), "hf_wh_page");


    public FactionWarehouseGUI(HFactions plugin, Faction faction) {
        this.plugin = plugin;
        this.faction = faction;
    }

    public Inventory getInventory(int page) {
        if (faction == null) return null; // Безопасность

        int totalPages = getTotalPages(faction);
        // Корректируем номер страницы, если он выходит за пределы
        page = Math.max(1, Math.min(page, totalPages));

        String title = Utils.color("&1Faction Warehouse - Page " + page + "/" + totalPages);
        Inventory inv = Bukkit.createInventory(null, 54, title);

        // Заполнение предметами
        ItemStack[] contents = faction.getWarehouseContents(); // Получаем КОПИЮ
        int contentSlots = 45;
        int startIndex = (page - 1) * contentSlots;
        for (int i = 0; i < contentSlots; i++) {
            int contentIndex = startIndex + i;
            if (contentIndex < faction.getWarehouseSize() && contentIndex < contents.length && contents[contentIndex] != null) {
                inv.setItem(i, contents[contentIndex].clone()); // Кладем клон в GUI
            }
        }

        // Заполнение рамки и кнопок навигации
        ItemStack border = new ItemStackBuilder(Material.BLACK_STAINED_GLASS_PANE).setName(" ").build();
        for (int i = contentSlots; i < 54; i++) {
            inv.setItem(i, border);
        }

        // Кнопка Назад
        if (page > 1) {
            inv.setItem(45, new ItemStackBuilder(Material.ARROW)
                    .setName(Utils.color("&e<< Previous Page"))
                    .setLore(Utils.color("&7Click to go to page " + (page - 1)))
                    .setLocalizedName("prev_page") // Идентификатор для GuiManager
                    .build());
        } else {
            inv.setItem(45, new ItemStackBuilder(Material.GRAY_DYE)
                    .setName(Utils.color("&7<< Previous Page"))
                    .build());
        }

        // Индикатор страницы с использованием PersistentDataContainer
        ItemStack pageIndicator = new ItemStackBuilder(Material.PAPER)
                .setName(Utils.color("&fPage " + page + "/" + totalPages))
                .setPersistentData(PAGE_KEY, PersistentDataType.INTEGER, page) // Сохраняем номер страницы
                .build();
        inv.setItem(49, pageIndicator);

        // Кнопка Вперед
        if (page < totalPages) {
            inv.setItem(53, new ItemStackBuilder(Material.ARROW)
                    .setName(Utils.color("&eNext Page >>"))
                    .setLore(Utils.color("&7Click to go to page " + (page + 1)))
                    .setLocalizedName("next_page") // Идентификатор для GuiManager
                    .build());
        } else {
            inv.setItem(53, new ItemStackBuilder(Material.GRAY_DYE)
                    .setName(Utils.color("&7Next Page >>"))
                    .build());
        }

        return inv;
    }

    /**
     * Рассчитывает общее количество страниц склада.
     */
    public static int getTotalPages(Faction faction) {
        if (faction == null || faction.getWarehouseSize() <= 0) {
            return 1;
        }
        int contentSlotsPerPage = 45;
        return (int) Math.ceil((double) faction.getWarehouseSize() / contentSlotsPerPage);
    }

    /**
     * Получает номер текущей страницы из инвентаря, читая PersistentData индикатора.
     * @param inventory Инвентарь склада.
     * @return Номер страницы или 0, если не найден или ошибка.
     */
    public static int getCurrentPageFromInventory(Inventory inventory) {
        if (inventory == null || inventory.getSize() != 54) {
            return 0;
        }
        ItemStack pageIndicator = inventory.getItem(49); // Слот индикатора (центральный нижний)
        if (pageIndicator != null && pageIndicator.getType() == Material.PAPER && pageIndicator.hasItemMeta()) {
            ItemMeta meta = pageIndicator.getItemMeta();
            // Проверяем наличие ключа и типа данных
            if (meta != null && meta.getPersistentDataContainer().has(PAGE_KEY, PersistentDataType.INTEGER)) {
                Integer page = meta.getPersistentDataContainer().get(PAGE_KEY, PersistentDataType.INTEGER);
                return page != null ? page : 0; // Возвращаем 0 если значение null (хотя не должно быть)
            }
        }
        // Если индикатор не найден или не содержит данных, возвращаем 0 или 1?
        // Возвращаем 0, чтобы указать на возможную проблему
        return 0;
    }
}