package org.isyateq.hfactions.managers;

import io.th0rgal.oraxen.api.OraxenItems;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.isyateq.hfactions.HFactions;

import java.util.List;
import java.util.stream.Collectors;

public class ItemManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    public final boolean oraxenEnabled; // Сделаем публичным для CraftingManager

    // Ключ для идентификации наших спец. предметов
    public static final NamespacedKey HF_ITEM_KEY = new NamespacedKey(HFactions.getInstance(), "hf_item_type");
    public static final String HF_ITEM_TASER = "taser";
    public static final String HF_ITEM_HANDCUFFS = "handcuffs";
    public static final String HF_ITEM_PROTOCOL = "protocol";

    public ItemManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.oraxenEnabled = checkOraxen();
    }

    private boolean checkOraxen() {
        boolean useOraxen = configManager.useOraxenForTaser() || configManager.useOraxenForHandcuffs() || configManager.useOraxenForProtocol();
        if (useOraxen && Bukkit.getPluginManager().getPlugin("Oraxen") != null) {
            try {
                // Пробуем вызвать метод Oraxen API, чтобы убедиться, что он загружен корректно
                OraxenItems.exists("test");
                plugin.logInfo("Oraxen found and integration enabled for configured items.");
                return true;
            } catch (NoClassDefFoundError | Exception e) {
                plugin.logError("Oraxen plugin found, but API interaction failed! Disabling Oraxen integration.");
                return false;
            }
        }
        if (useOraxen) {
            plugin.logWarning("Oraxen integration enabled in config, but Oraxen plugin not found!");
        }
        return false;
    }

    // --- Получение предметов ---

    public ItemStack getTaserItem() {
        if (!configManager.isTaserEnabled()) return null;
        if (oraxenEnabled && configManager.useOraxenForTaser()) {
            return buildOraxenItem(configManager.getOraxenTaserId(), HF_ITEM_TASER, this::buildDefaultTaserItem);
        }
        return buildDefaultTaserItem();
    }

    public ItemStack getHandcuffsItem() {
        if (!configManager.isHandcuffsEnabled()) return null;
        if (oraxenEnabled && configManager.useOraxenForHandcuffs()) {
            return buildOraxenItem(configManager.getOraxenHandcuffsId(), HF_ITEM_HANDCUFFS, this::buildDefaultHandcuffsItem);
        }
        return buildDefaultHandcuffsItem();
    }

    public ItemStack getProtocolItem() {
        if (!configManager.isProtocolEnabled()) return null;
        if (oraxenEnabled && configManager.useOraxenForProtocol()) {
            return buildOraxenItem(configManager.getOraxenProtocolId(), HF_ITEM_PROTOCOL, this::buildDefaultProtocolItem);
        }
        return buildDefaultProtocolItem();
    }

    // --- Методы для создания дефолтных (ванильных) предметов ---

    private ItemStack buildDefaultTaserItem() {
        return buildDefaultItem(
                configManager.getTaserMaterial(),
                configManager.getTaserModelData(),
                configManager.getTaserName(),
                configManager.getTaserLore(),
                HF_ITEM_TASER
        );
    }

    private ItemStack buildDefaultHandcuffsItem() {
        return buildDefaultItem(
                configManager.getHandcuffsMaterial(),
                configManager.getHandcuffsModelData(),
                configManager.getHandcuffsName(),
                configManager.getHandcuffsLore(),
                HF_ITEM_HANDCUFFS
        );
    }

    private ItemStack buildDefaultProtocolItem() {
        return buildDefaultItem(
                configManager.getProtocolMaterial(),
                configManager.getProtocolModelData(),
                configManager.getProtocolName(),
                configManager.getProtocolLore(),
                HF_ITEM_PROTOCOL
        );
    }

    // --- Вспомогательные методы ---

    private ItemStack buildOraxenItem(String oraxenId, String hfItemType, java.util.function.Supplier<ItemStack> fallback) {
        if (oraxenId != null && OraxenItems.exists(oraxenId)) {
            ItemStack item = OraxenItems.getItemById(oraxenId).build();
            // Добавляем наш тег к предмету Oraxen
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(HF_ITEM_KEY, PersistentDataType.STRING, hfItemType);
                item.setItemMeta(meta);
            }
            return item;
        } else {
            plugin.logWarning("Oraxen item ID '" + oraxenId + "' for type '" + hfItemType + "' not found! Falling back to default item.");
            return fallback.get(); // Возвращаем дефолтный предмет
        }
    }

    private ItemStack buildDefaultItem(String materialName, int modelData, String name, List<String> lore, String hfItemType) {
        try {
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null || material == Material.AIR) {
                throw new IllegalArgumentException("Invalid material name");
            }
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                if (lore != null && !lore.isEmpty()) {
                    meta.setLore(lore.stream()
                            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                            .collect(Collectors.toList()));
                }
                if (modelData > 0) meta.setCustomModelData(modelData);
                // Добавляем наш идентификатор
                meta.getPersistentDataContainer().set(HF_ITEM_KEY, PersistentDataType.STRING, hfItemType);
                item.setItemMeta(meta);
            }
            return item;
        } catch (IllegalArgumentException e) {
            plugin.logError("Invalid material specified for " + hfItemType + " item: " + materialName);
            return new ItemStack(Material.BARRIER); // Ошибка
        }
    }

    // --- Методы проверки типа предмета ---

    public boolean isTaserItem(ItemStack item) { return isHfItem(item, HF_ITEM_TASER, configManager::useOraxenForTaser, configManager::getOraxenTaserId); }
    public boolean isHandcuffsItem(ItemStack item) { return isHfItem(item, HF_ITEM_HANDCUFFS, configManager::useOraxenForHandcuffs, configManager::getOraxenHandcuffsId); }
    public boolean isProtocolItem(ItemStack item) { return isHfItem(item, HF_ITEM_PROTOCOL, configManager::useOraxenForProtocol, configManager::getOraxenProtocolId); }

    private boolean isHfItem(ItemStack item, String expectedType, java.util.function.BooleanSupplier useOraxenCheck, java.util.function.Supplier<String> oraxenIdSupplier) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && expectedType.equals(meta.getPersistentDataContainer().get(HF_ITEM_KEY, PersistentDataType.STRING))) {
            return true; // Нашли по нашему NBT тегу
        }
        // Фолбэк на проверку Oraxen ID, если NBT тег отсутствует и интеграция включена
        if (oraxenEnabled && useOraxenCheck.getAsBoolean()) {
            String expectedOraxenId = oraxenIdSupplier.get();
            try {
                return expectedOraxenId != null && expectedOraxenId.equals(OraxenItems.getIdByItem(item));
            } catch (NoClassDefFoundError | Exception e) {
                // Oraxen API недоступно
                return false;
            }
        }
        return false;
    }
}