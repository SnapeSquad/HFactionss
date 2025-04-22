package org.isyateq.hfactions.managers;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.integrations.OraxenIntegration; // Нужен для Oraxen
import org.isyateq.hfactions.util.ItemStackBuilder; // Используем ItemStackBuilder
import org.bukkit.persistence.PersistentDataType;
import org.isyateq.hfactions.util.Utils; // Для Utils.color

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ItemManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final OraxenIntegration oraxenIntegration; // Может быть null

    // Ключи для PersistentDataContainer, чтобы идентифицировать наши предметы
    private final NamespacedKey taserKey;
    private final NamespacedKey handcuffsKey;
    private final NamespacedKey protocolKey;

    // Кэшированные экземпляры предметов для быстрого получения
    private ItemStack cachedTaserItem = null;
    private ItemStack cachedHandcuffsItem = null;
    private ItemStack cachedProtocolItem = null;

    public ItemManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.oraxenIntegration = plugin.getOraxenIntegration(); // Получаем интеграцию

        // Инициализация ключей
        this.taserKey = new NamespacedKey(plugin, "hf_item_taser");
        this.handcuffsKey = new NamespacedKey(plugin, "hf_item_handcuffs");
        this.protocolKey = new NamespacedKey(plugin, "hf_item_protocol");

        // Загружаем и кэшируем предметы при инициализации
        loadAndCacheItems();
    }

    /**
     * Загружает настройки предметов из конфига и кэширует их.
     * Вызывается при запуске и перезагрузке конфига (если нужно).
     */
    public void loadAndCacheItems() {
        plugin.getLogger().info("Loading special item configurations...");
        if (configManager == null || configManager.getConfig() == null) {
            plugin.getLogger().severe("Cannot load item configurations: ConfigManager or config is null.");
            // Устанавливаем null, чтобы is...Item() возвращал false
            cachedTaserItem = null;
            cachedHandcuffsItem = null;
            cachedProtocolItem = null;
            return;
        }
        FileConfiguration config = configManager.getConfig();
        cachedTaserItem = loadItemFromConfig(config, "items.taser", taserKey);
        cachedHandcuffsItem = loadItemFromConfig(config, "items.handcuffs", handcuffsKey);
        cachedProtocolItem = loadItemFromConfig(config, "items.protocol", protocolKey);
        plugin.getLogger().info("Special item configurations loaded.");
    }

    /**
     * Загружает один предмет из указанной секции конфига.
     * @param config Конфигурационный файл.
     * @param path Путь к секции предмета (e.g., "items.taser").
     * @param identifierKey Ключ NamespacedKey для добавления в PersistentDataContainer.
     * @return Загруженный ItemStack или null, если не удалось загрузить.
     */
    private ItemStack loadItemFromConfig(FileConfiguration config, String path, NamespacedKey identifierKey) {
        ConfigurationSection itemSection = config.getConfigurationSection(path);
        if (itemSection == null) {
            plugin.getLogger().warning("Item configuration section not found: " + path);
            return createDefaultFallbackItem(path); // Возвращаем запасной вариант
        }

        String oraxenId = itemSection.getString("oraxen_id");
        // Пытаемся загрузить Oraxen предмет, ЕСЛИ интеграция включена И ID указан
        if (oraxenIntegration != null && oraxenIntegration.isEnabled() && oraxenId != null && !oraxenId.isEmpty()) {
            ItemStack oraxenItem = oraxenIntegration.getOraxenItemById(oraxenId);
            if (oraxenItem != null) {
                plugin.getLogger().info("Loaded Oraxen item '" + oraxenId + "' for " + path);
                // Добавляем наш идентификатор к Oraxen предмету
                ItemMeta meta = oraxenItem.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(identifierKey, PersistentDataType.BYTE, (byte) 1);
                    oraxenItem.setItemMeta(meta);
                }
                return oraxenItem;
            } else {
                plugin.getLogger().warning("Oraxen item ID '" + oraxenId + "' specified for " + path + " but not found. Falling back to vanilla configuration.");
            }
        }

        // Если Oraxen не используется или не найден, грузим ванильные настройки
        String materialName = itemSection.getString("material");
        if (materialName == null || materialName.isEmpty()) {
            plugin.getLogger().warning("Missing 'material' for vanilla item configuration at " + path);
            return createDefaultFallbackItem(path);
        }

        Material material = Material.matchMaterial(materialName.toUpperCase());
        if (material == null) {
            plugin.getLogger().warning("Invalid vanilla material '" + materialName + "' at " + path);
            return createDefaultFallbackItem(path);
        }

        ItemStackBuilder builder = new ItemStackBuilder(material);

        // Название
        String name = itemSection.getString("name");
        if (name != null) {
            builder.setName(Utils.color(name));
        }

        // Описание (Lore)
        List<String> lore = itemSection.getStringList("lore");
        if (lore != null && !lore.isEmpty()) {
            builder.setLore(lore.stream().map(Utils::color).collect(Collectors.toList()));
        }

        // Custom Model Data
        if (itemSection.contains("custom_model_data")) {
            builder.setCustomModelData(itemSection.getInt("custom_model_data"));
        }

        // Неразрушимость (Unbreakable)
        if (itemSection.getBoolean("unbreakable", false)) {
            builder.setUnbreakable(true);
        }

        // Скрыть флаги (Hide Flags)
        List<String> flagsToHide = itemSection.getStringList("hide_flags");
        if (flagsToHide != null && !flagsToHide.isEmpty()) {
            ItemFlag[] flags = flagsToHide.stream()
                    .map(flagName -> {
                        try { return ItemFlag.valueOf(flagName.toUpperCase()); }
                        catch (IllegalArgumentException e) { return null; }
                    })
                    .filter(Objects::nonNull)
                    .toArray(ItemFlag[]::new);
            builder.addItemFlags(flags);
        }


        // Добавляем наш идентификатор
        builder.setPersistentData(identifierKey, PersistentDataType.BYTE, (byte) 1);

        plugin.getLogger().info("Loaded vanilla item '" + material.name() + "' for " + path);
        return builder.build();
    }

    /**
     * Создает запасной предмет, если конфигурация не найдена или невалидна.
     */
    private ItemStack createDefaultFallbackItem(String path) {
        Material fallbackMat = Material.BARRIER;
        String name = "&cError: Item " + path + " not configured!";
        if (path.contains("taser")) { fallbackMat = Material.STICK; name = "&eDefault Taser"; }
        else if (path.contains("handcuffs")) { fallbackMat = Material.IRON_INGOT; name = "&7Default Handcuffs"; }
        else if (path.contains("protocol")) { fallbackMat = Material.PAPER; name = "&fDefault Protocol"; }

        plugin.getLogger().severe("Item configuration error at '" + path + "'. Using default fallback item: " + fallbackMat.name());
        return new ItemStackBuilder(fallbackMat).setName(Utils.color(name)).build();
    }


    // --- Методы для получения предметов ---

    public ItemStack getTaserItem() {
        // Возвращаем клон, чтобы изменения в одном стаке не влияли на другие
        return (cachedTaserItem != null) ? cachedTaserItem.clone() : createDefaultFallbackItem("items.taser");
    }

    public ItemStack getHandcuffsItem() {
        return (cachedHandcuffsItem != null) ? cachedHandcuffsItem.clone() : createDefaultFallbackItem("items.handcuffs");
    }

    public ItemStack getProtocolItem() {
        return (cachedProtocolItem != null) ? cachedProtocolItem.clone() : createDefaultFallbackItem("items.protocol");
    }

    // --- Методы для проверки предметов ---

    public boolean isTaser(ItemStack item) {
        return isSpecialItem(item, taserKey);
    }

    public boolean isHandcuffs(ItemStack item) {
        return isSpecialItem(item, handcuffsKey);
    }

    public boolean isProtocol(ItemStack item) {
        return isSpecialItem(item, protocolKey);
    }

    /**
     * Проверяет, является ли предмет одним из специальных предметов HFactions,
     * используя PersistentDataContainer.
     * @param item Предмет для проверки.
     * @param key Ключ NamespacedKey, который должен присутствовать.
     * @return true, если ключ найден, иначе false.
     */
    private boolean isSpecialItem(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        // Проверяем наличие ключа в PersistentDataContainer
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public ItemStack getManagedItem(String resultItemId) {
    }
}