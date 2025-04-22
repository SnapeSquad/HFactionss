package org.isyateq.hfactions.managers;

import io.th0rgal.oraxen.api.OraxenItems; // Убедись что импорт есть, если Oraxen включен
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.integrations.OraxenIntegration;
import org.isyateq.hfactions.util.ItemStackBuilder;
import org.isyateq.hfactions.util.Utils; // Убедись, что Utils импортирован

import java.util.logging.Level;

public class ItemManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final OraxenIntegration oraxenIntegration;

    // Ключ для NBT тега, идентифицирующего наши предметы
    public static final NamespacedKey ITEM_TYPE_KEY = new NamespacedKey(HFactions.getInstance(), "hfactions_item_type");

    public ItemManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.oraxenIntegration = plugin.getOraxenIntegration(); // Получаем инстанс интеграции
    }

    // --- Тайзер ---
    public ItemStack createTaser() {
        ItemStack taserItem = null;
        if (configManager.isOraxenEnabled() && oraxenIntegration != null) {
            String oraxenId = configManager.getTaserOraxenId();
            if (oraxenId != null && !oraxenId.isEmpty()) {
                taserItem = oraxenIntegration.getItem(oraxenId); // Получаем предмет Oraxen
                if (taserItem == null) {
                    plugin.getLogger().warning("Oraxen item with ID '" + oraxenId + "' for Taser not found! Falling back to vanilla.");
                } else {
                    plugin.getLogger().fine("Using Oraxen item '" + oraxenId + "' for Taser."); // Debug
                }
            } else {
                plugin.getLogger().warning("Oraxen support is enabled, but Taser Oraxen ID is missing in config! Falling back to vanilla.");
            }
        }

        // Если Oraxen выключен, не найден, или ID не указан, используем ваниль
        if (taserItem == null) {
            plugin.getLogger().fine("Using vanilla item for Taser."); // Debug
            String materialName = configManager.getTaserMaterial();
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().severe("Invalid vanilla material specified for Taser in config: " + materialName + "! Cannot create Taser.");
                return null; // Не можем создать предмет
            }
            int customModelData = configManager.getTaserCustomModelData();

            taserItem = new ItemStackBuilder(material)
                    .setName(configManager.getTaserName())
                    .setLore(configManager.getTaserLore())
                    .setCustomModelData(customModelData)
                    .setPersistentData(ItemManager.ITEM_TYPE_KEY, PersistentDataType.STRING, "taser")
                    .build();
        } else {
            // Если предмет Oraxen найден, добавим наш NBT-тег для унификации проверки
            ItemMeta meta = taserItem.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(ITEM_TYPE_KEY, PersistentDataType.STRING, "taser");
                taserItem.setItemMeta(meta);
            }
        }

        return taserItem;
    }

    // --- Наручники ---
    public ItemStack createHandcuffs() {
        ItemStack handcuffItem = null;
        if (configManager.isOraxenEnabled() && oraxenIntegration != null) {
            String oraxenId = configManager.getHandcuffsOraxenId();
            if (oraxenId != null && !oraxenId.isEmpty()) {
                handcuffItem = oraxenIntegration.getItem(oraxenId);
                if (handcuffItem == null) {
                    plugin.getLogger().warning("Oraxen item with ID '" + oraxenId + "' for Handcuffs not found! Falling back to vanilla.");
                } else {
                    plugin.getLogger().fine("Using Oraxen item '" + oraxenId + "' for Handcuffs.");
                }
            } else {
                plugin.getLogger().warning("Oraxen support is enabled, but Handcuffs Oraxen ID is missing in config! Falling back to vanilla.");
            }
        }

        if (handcuffItem == null) {
            plugin.getLogger().fine("Using vanilla item for Handcuffs.");
            String materialName = configManager.getHandcuffsMaterial();
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().severe("Invalid vanilla material specified for Handcuffs in config: " + materialName + "! Cannot create Handcuffs.");
                return null;
            }
            int customModelData = configManager.getHandcuffsCustomModelData();

            handcuffItem = new ItemStackBuilder(material)
                    .setName(configManager.getHandcuffsName())
                    .setLore(configManager.getHandcuffsLore())
                    .setCustomModelData(customModelData)
                    .setPersistentData(ITEM_TYPE_KEY, PersistentDataType.STRING, "handcuffs")
                    .build();
        } else {
            ItemMeta meta = handcuffItem.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(ITEM_TYPE_KEY, PersistentDataType.STRING, "handcuffs");
                handcuffItem.setItemMeta(meta);
            }
        }
        return handcuffItem;
    }

    // --- Протокол ---
    public ItemStack createProtocol() {
        ItemStack protocolItem = null;
        if (configManager.isOraxenEnabled() && oraxenIntegration != null) {
            String oraxenId = configManager.getProtocolOraxenId();
            if (oraxenId != null && !oraxenId.isEmpty()) {
                protocolItem = oraxenIntegration.getItem(oraxenId);
                if (protocolItem == null) {
                    plugin.getLogger().warning("Oraxen item with ID '" + oraxenId + "' for Protocol not found! Falling back to vanilla.");
                } else {
                    plugin.getLogger().fine("Using Oraxen item '" + oraxenId + "' for Protocol.");
                }
            } else {
                plugin.getLogger().warning("Oraxen support is enabled, but Protocol Oraxen ID is missing in config! Falling back to vanilla.");
            }
        }

        if (protocolItem == null) {
            plugin.getLogger().fine("Using vanilla item for Protocol.");
            String materialName = configManager.getProtocolMaterial();
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().severe("Invalid vanilla material specified for Protocol in config: " + materialName + "! Cannot create Protocol.");
                return null;
            }
            int customModelData = configManager.getProtocolCustomModelData();

            protocolItem = new ItemStackBuilder(material)
                    .setName(configManager.getProtocolName())
                    .setLore(configManager.getProtocolLore())
                    .setCustomModelData(customModelData)
                    .setPersistentData(ITEM_TYPE_KEY, PersistentDataType.STRING, "protocol")
                    .build();
        } else {
            ItemMeta meta = protocolItem.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(ITEM_TYPE_KEY, PersistentDataType.STRING, "protocol");
                protocolItem.setItemMeta(meta);
            }
        }
        return protocolItem;
    }

    // --- Проверки ---

    public boolean isTaserItem(ItemStack item) {
        return isFactionItem(item, "taser");
    }

    public boolean isHandcuffItem(ItemStack item) {
        return isFactionItem(item, "handcuffs");
    }

    public boolean isProtocolItem(ItemStack item) {
        return isFactionItem(item, "protocol");
    }

    /**
     * Универсальный метод проверки, является ли предмет предметом HFactions указанного типа.
     * Проверяет Oraxen ID (если включено) и NBT-тег.
     * @param item Предмет для проверки.
     * @param type Тип предмета ("taser", "handcuffs", "protocol").
     * @return true, если предмет соответствует типу.
     */
    private boolean isFactionItem(ItemStack item, String type) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }

        // 1. Проверка через Oraxen (если включено)
        if (configManager.isOraxenEnabled() && oraxenIntegration != null) {
            String oraxenId = oraxenIntegration.getItemId(item);
            if (oraxenId != null) {
                // Получаем ожидаемый Oraxen ID из конфига для этого типа
                String expectedOraxenId = null;
                switch (type) {
                    case "taser": expectedOraxenId = configManager.getTaserOraxenId(); break;
                    case "handcuffs": expectedOraxenId = configManager.getHandcuffsOraxenId(); break;
                    case "protocol": expectedOraxenId = configManager.getProtocolOraxenId(); break;
                }
                // Если Oraxen ID совпадает с ожидаемым ИЗ КОНФИГА, считаем, что это наш предмет
                if (oraxenId.equals(expectedOraxenId)) {
                    return true;
                }
                // Если у предмета есть Oraxen ID, но он не совпадает с нужным,
                // то это не наш предмет этого типа (даже если NBT есть - Oraxen важнее)
                else if (expectedOraxenId != null && !expectedOraxenId.isEmpty()){
                    return false;
                }
                // Если для этого типа Oraxen ID в конфиге не указан, продолжаем проверку NBT
            }
        }

        // 2. Проверка через NBT-тег (PersistentDataContainer) - основная проверка для ванили и Oraxen без ID в конфиге
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(ITEM_TYPE_KEY, PersistentDataType.STRING)) {
                String itemType = meta.getPersistentDataContainer().get(ITEM_TYPE_KEY, PersistentDataType.STRING);
                return type.equals(itemType);
            }
        }

        return false;
    }
}