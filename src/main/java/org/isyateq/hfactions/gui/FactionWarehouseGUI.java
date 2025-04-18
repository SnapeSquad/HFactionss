package org.isyateq.hfactions.gui; // Пакет gui!

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.util.ItemStackBuilder;
import org.isyateq.hfactions.util.Utils;

public class FactionWarehouseGUI implements InventoryHolder {

    private final HFactions plugin; // Нужен для ключа NamespacedKey
    private final Faction faction;
    private Inventory gui;

    // Ключ для хранения номера страницы в NBT предмета-индикатора
    // Важно: Ключ должен быть уникальным для плагина
    private static NamespacedKey PAGE_KEY = null; // Инициализируем статически один раз

    public FactionWarehouseGUI(HFactions plugin, Faction faction) {
        this.plugin = plugin;
        this.faction = faction;
        // Инициализируем ключ, если он еще не создан
        if (PAGE_KEY == null) {
            PAGE_KEY = new NamespacedKey(plugin, "hf_wh_page");
        }
    }

    /**
     * Создает и возвращает инвентарь GUI склада для указанной страницы.
     * @param page Номер страницы (начиная с 1).
     * @return Созданный инвентарь или null при ошибке.
     */
    public Inventory getInventory(int page) {
        if (faction == null || PAGE_KEY == null) return null;

        int warehouseSize = faction.getWarehouseSize();
        int itemsPerPage = 45; // 54 слота - 9 слотов управления
        int totalPages = getTotalPages(faction); // Используем статический метод

        // Корректируем номер страницы, если он выходит за рамки
        page = Math.max(1, Math.min(page, totalPages));

        String title = Utils.color("&1Warehouse: " + faction.getName() + " - Page " + page + "/" + totalPages);
        gui = Bukkit.createInventory(this, 54, title);

        // Заполнение предметами со склада для текущей страницы
        ItemStack[] contents = faction.getWarehouseContents();
        if (contents != null) { // Проверка на null
            int startIndex = (page - 1) * itemsPerPage;
            for (int i = 0; i < itemsPerPage; i++) {
                int contentIndex = startIndex + i;
                // Копируем предмет, только если индекс в пределах массива склада
                if (contentIndex < contents.length && contents[contentIndex] != null) {
                    gui.setItem(i, contents[contentIndex].clone()); // Кладём клон
                }
            }
        }

        // Заполнение рамки и кнопок навигации
        ItemStack border = new ItemStackBuilder(Material.BLACK_STAINED_GLASS_PANE).setName(" ").build();
        for (int i = itemsPerPage; i < 54; i++) {
            gui.setItem(i, border); // Заполняем нижний ряд рамкой по умолчанию
        }

        // Кнопка "Назад"
        if (page > 1) {
            gui.setItem(45, new ItemStackBuilder(Material.ARROW) // Левый край нижнего ряда
                    .setName(Utils.color("&e<< Previous Page"))
                    .setLore(Utils.color("&7Click to go to page " + (page - 1)))
                    .setLocalizedName("prev_page") // ID для клика
                    .build());
        } else {
            // Можно поставить серый краситель или стекло
            gui.setItem(45, new ItemStackBuilder(Material.GRAY_STAINED_GLASS_PANE).setName(" ").build());
        }

        // Индикатор страницы (сохраняем номер страницы в NBT)
        ItemStack pageIndicator = new ItemStackBuilder(Material.PAPER)
                .setName(Utils.color("&fPage " + page + "/" + totalPages))
                .setLore(Utils.color("&7Faction: &e" + faction.getName()))
                // Используем PersistentDataContainer для хранения номера страницы
                .setPersistentData(PAGE_KEY, PersistentDataType.INTEGER, page)
                .addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
                .build();
        gui.setItem(49, pageIndicator); // Центральный слот нижнего ряда

        // Кнопка "Вперед"
        if (page < totalPages) {
            gui.setItem(53, new ItemStackBuilder(Material.ARROW) // Правый край нижнего ряда
                    .setName(Utils.color("&eNext Page >>"))
                    .setLore(Utils.color("&7Click to go to page " + (page + 1)))
                    .setLocalizedName("next_page") // ID для клика
                    .build());
        } else {
            gui.setItem(53, new ItemStackBuilder(Material.GRAY_STAINED_GLASS_PANE).setName(" ").build());
        }

        return gui;
    }

    /**
     * Рассчитывает общее количество страниц склада.
     */
    public static int getTotalPages(Faction faction) {
        if (faction == null || faction.getWarehouseSize() <= 0) {
            return 1; // Минимум 1 страница
        }
        int itemsPerPage = 45;
        // Деление с округлением вверх
        return (int) Math.ceil((double) faction.getWarehouseSize() / itemsPerPage);
    }

    /**
     * Получает номер текущей страницы из инвентаря (читая NBT индикатора).
     * @param inventory Инвентарь склада.
     * @return Номер страницы или 0, если не найден или ключ не инициализирован.
     */
    public static int getCurrentPageFromInventory(Inventory inventory) {
        // Проверяем ключ перед использованием
        if (inventory == null || inventory.getSize() != 54 || PAGE_KEY == null) {
            return 0;
        }
        ItemStack pageIndicator = inventory.getItem(49); // Слот индикатора (центр нижнего ряда)
        if (pageIndicator != null && pageIndicator.getType() == Material.PAPER && pageIndicator.hasItemMeta()) {
            ItemMeta meta = pageIndicator.getItemMeta();
            // Проверяем наличие ключа и получаем значение
            if (meta != null && meta.getPersistentDataContainer().has(PAGE_KEY, PersistentDataType.INTEGER)) {
                Integer page = meta.getPersistentDataContainer().get(PAGE_KEY, PersistentDataType.INTEGER);
                return page != null ? page : 0; // Возвращаем 0, если значение null
            }
        }
        return 0; // Не удалось определить страницу
    }

    @Override
    public Inventory getInventory() {
        return gui;
    }
}