package org.isyateq.hfactions.managers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.util.Utils; // Для форматирования цвета

import java.io.File;
import java.io.IOException;
import java.util.Collections; // Для unmodifiableMap
import java.util.HashSet; // Для removeAll в saveModified
import java.util.Map;
import java.util.Set; // Для HashSet
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;


public class DynmapManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final FactionManager factionManager;

    private DynmapAPI dynmapApi;
    private MarkerAPI markerApi;
    private MarkerSet markerSet; // Наш набор маркеров для территорий

    private final Map<String, DynmapTerritory> territories = new ConcurrentHashMap<>();
    private final File territoriesFile;

    public DynmapManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.factionManager = plugin.getFactionManager();
        this.territoriesFile = new File(plugin.getDataFolder(), "territories.yml");
    }

    /**
     * Проверяет, успешно ли инициализирована интеграция с Dynmap.
     * @return true, если Dynmap API и MarkerSet доступны, иначе false.
     */
    public boolean isDynmapEnabled() {
        // Проверяем, что все необходимые компоненты Dynmap были успешно получены при инициализации
        return this.dynmapApi != null && this.markerApi != null && this.markerSet != null;
    }

    /**
     * Инициализирует соединение с Dynmap API и настраивает MarkerSet.
     * Вызывается один раз из HFactions.setupDynmap().
     * @return true в случае успеха, false в случае ошибки или если Dynmap недоступен.
     */
    public boolean initialize() {
        // 1. Получаем плагин Dynmap
        Plugin dynmapPlugin = plugin.getServer().getPluginManager().getPlugin("dynmap");

        // 2. Проверяем, активен ли плагин и является ли он экземпляром DynmapAPI
        if (dynmapPlugin == null || !dynmapPlugin.isEnabled()) {
            plugin.getLogger().info("Dynmap plugin not found or not enabled. Dynmap integration disabled.");
            return false; // Dynmap не найден или выключен
        }
        if (!(dynmapPlugin instanceof DynmapAPI)) {
            plugin.getLogger().warning("Found Dynmap plugin, but it's not an instance of DynmapAPI (wrong version?). Dynmap integration disabled.");
            return false; // Неожиданный тип плагина
        }
        this.dynmapApi = (DynmapAPI) dynmapPlugin;
        plugin.getLogger().fine("Successfully obtained DynmapAPI instance.");

        // 3. Получаем MarkerAPI
        this.markerApi = dynmapApi.getMarkerAPI();
        if (this.markerApi == null) {
            plugin.getLogger().severe("Failed to get Dynmap Marker API! Dynmap integration disabled.");
            this.dynmapApi = null; // Сбрасываем API, если маркеры недоступны
            return false;
        }
        plugin.getLogger().fine("Successfully obtained Dynmap MarkerAPI instance.");

        // 4. Получаем или создаем наш MarkerSet
        // Берем ID и имя из конфига, с дефолтными значениями
        String markerSetId = configManager.getConfig().getString("integrations.dynmap.marker_set_id", "hfactions_territories");
        String markerSetName = configManager.getConfig().getString("integrations.dynmap.marker_set_name", "Faction Territories");

        this.markerSet = markerApi.getMarkerSet(markerSetId);
        if (this.markerSet == null) {
            plugin.getLogger().info("MarkerSet '" + markerSetId + "' not found, creating new one...");
            // Создаем новый сет. null = нет иконки по умолчанию, false = маркеры управляются плагином, а не сохраняются самим Dynmap
            this.markerSet = markerApi.createMarkerSet(markerSetId, markerSetName, null, false);
            if (this.markerSet == null) {
                plugin.getLogger().severe("Failed to create Dynmap MarkerSet: " + markerSetId + ". Dynmap integration disabled.");
                this.dynmapApi = null;
                this.markerApi = null;
                return false;
            }
            plugin.getLogger().info("Created new MarkerSet: " + markerSetName + " (ID: " + markerSetId + ")");
        } else {
            plugin.getLogger().info("Found existing MarkerSet: " + this.markerSet.getMarkerSetLabel() + " (ID: " + this.markerSet.getMarkerSetID() + ")");
            // Можно обновить имя сета из конфига, если оно изменилось
            if (!this.markerSet.getMarkerSetLabel().equals(markerSetName)) {
                this.markerSet.setMarkerSetLabel(markerSetName);
                plugin.getLogger().info("Updated MarkerSet label to: " + markerSetName);
            }
        }

        // 5. Успешная инициализация
        plugin.getLogger().info("Dynmap integration initialized successfully.");
        return true;
    }


    /**
     * Отображает все загруженные территории на карте Dynmap.
     * Удаляет старые маркеры из нашего MarkerSet и создает новые на основе текущих данных.
     */
    public void renderAllTerritories() {
        // Проверяем, готова ли интеграция
        if (!isDynmapEnabled()) {
            plugin.getLogger().fine("Cannot render territories: Dynmap integration is not enabled.");
            return;
        }

        plugin.getLogger().info("Rendering all faction territories on Dynmap...");

        // 1. Очистка старых маркеров ИЗ НАШЕГО СЕТА
        // Получаем текущий список маркеров (может быть null, если сет только что создан)
        Set<AreaMarker> existingMarkers = markerSet.getAreaMarkers();
        if (existingMarkers != null && !existingMarkers.isEmpty()) {
            plugin.getLogger().fine("Deleting " + existingMarkers.size() + " existing area markers from MarkerSet '" + markerSet.getMarkerSetID() + "'...");
            // Создаем копию для итерации, чтобы избежать ConcurrentModificationException
            Set<AreaMarker> markersToDelete = new HashSet<>(existingMarkers);
            for (AreaMarker marker : markersToDelete) {
                marker.deleteMarker(); // Удаляем маркер с карты и из сета
            }
            plugin.getLogger().fine("Finished deleting existing markers.");
        } else {
            plugin.getLogger().fine("No existing markers found in the set to delete.");
        }


        // 2. Создание новых маркеров на основе загруженных территорий
        int renderedCount = 0;
        int skippedCount = 0;
        if (territories.isEmpty()) {
            plugin.getLogger().info("No territories loaded to render.");
            return;
        }

        plugin.getLogger().fine("Rendering " + territories.size() + " loaded territories...");
        for (DynmapTerritory territory : territories.values()) {
            if (renderTerritory(territory)) { // Вызываем метод рендера для каждой
                renderedCount++;
            } else {
                skippedCount++; // Считаем те, что не удалось отрендерить
            }
        }

        plugin.getLogger().info("Finished rendering territories: " + renderedCount + " rendered, " + skippedCount + " skipped/failed.");
    }


    // --- Остальные методы (loadTerritories, saveTerritories, renderTerritory, deleteTerritoryMarker, etc.) ---
    // --- Они были в предыдущем ответе и остаются в основном такими же ---

    // Загрузка территорий из файла
    public void loadTerritories() {
        territories.clear();
        if (!territoriesFile.exists()) {
            plugin.getLogger().info("territories.yml not found, skipping territory loading.");
            return;
        }
        FileConfiguration territoriesYaml = YamlConfiguration.loadConfiguration(territoriesFile);
        ConfigurationSection territoriesSection = territoriesYaml.getConfigurationSection("territories");
        if (territoriesSection == null) {
            plugin.getLogger().info("No 'territories' section found in territories.yml");
            return;
        }

        int count = 0;
        for (String territoryName : territoriesSection.getKeys(false)) {
            ConfigurationSection data = territoriesSection.getConfigurationSection(territoryName);
            if (data != null) {
                DynmapTerritory territory = DynmapTerritory.deserialize(territoryName, data);
                if (territory != null) {
                    territories.put(territoryName.toLowerCase(), territory);
                    count++;
                } else {
                    plugin.getLogger().warning("Failed to deserialize territory: " + territoryName);
                }
            }
        }
        plugin.getLogger().info("Loaded " + count + " territories from territories.yml");
    }

    // Сохранение территорий в файл
    public void saveTerritories() {
        if (territories.isEmpty()) {
            plugin.getLogger().fine("No territories in memory to save.");
            // Если файл существует и пуст, можно его удалить или оставить пустым
            if (territoriesFile.exists()) {
                // territoriesFile.delete(); // Или оставить пустым
            }
            return;
        }

        FileConfiguration territoriesYaml = new YamlConfiguration();
        ConfigurationSection territoriesSection = territoriesYaml.createSection("territories");

        for (DynmapTerritory territory : territories.values()) {
            ConfigurationSection data = territoriesSection.createSection(territory.getName()); // Используем оригинальное имя
            territory.serialize(data);
        }

        try {
            territoriesYaml.save(territoriesFile);
            plugin.getLogger().info("Saved " + territories.size() + " territories to territories.yml");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save territories to territories.yml", e);
        }
    }

    // Рендер одной территории (логика из предыдущего ответа)
    public boolean renderTerritory(DynmapTerritory territory) {
        if (!isDynmapEnabled() || territory == null) return false;

        String markerId = "hf_" + territory.getName().toLowerCase().replaceAll("[^a-z0-9_]", ""); // Очищаем ID
        String markerLabel = territory.getLabel(); // Метка на карте

        double[] xCorners = territory.getCornersX();
        double[] zCorners = territory.getCornersZ();

        if (xCorners == null || zCorners == null || xCorners.length < 3) {
            plugin.getLogger().warning("Cannot render territory '" + territory.getName() + "': requires at least 3 corners.");
            return false;
        }
        if (territory.getWorldName() == null || territory.getWorldName().isEmpty()) {
            plugin.getLogger().warning("Cannot render territory '" + territory.getName() + "': world name is missing.");
            return false;
        }

        // Удаляем старый маркер с таким ID, если он есть
        AreaMarker existingMarker = markerSet.findAreaMarker(markerId);
        if (existingMarker != null) {
            existingMarker.deleteMarker();
        }

        // Создаем новый маркер
        AreaMarker marker = markerSet.createAreaMarker(
                markerId, markerLabel, false, territory.getWorldName(),
                xCorners, zCorners, false // persistent = false
        );

        if (marker == null) {
            plugin.getLogger().warning("Failed to create Dynmap AreaMarker for territory: " + territory.getName() + " (ID: " + markerId + ")");
            return false;
        }

        // Настройка стиля
        Faction faction = factionManager.getFaction(territory.getFactionId());
        int fillColor = 0x808080; // Серый по умолчанию для не захваченных
        double fillOpacity = configManager.getConfig().getDouble("integrations.dynmap.style.fill_opacity", 0.3);

        if (faction != null) {
            try {
                // Убираем '#' и парсим HEX
                fillColor = Integer.parseInt(faction.getColor().startsWith("#") ? faction.getColor().substring(1) : faction.getColor(), 16);
            } catch (NumberFormatException | NullPointerException | StringIndexOutOfBoundsException e) {
                plugin.getLogger().warning("Invalid faction color format for " + faction.getId() + ": " + faction.getColor() + ". Using default fill.");
                fillColor = 0x808080; // Возвращаем серый при ошибке
            }
        }

        // Стиль обводки из конфига
        int strokeColor = 0x000000; // Черный по умолчанию
        try {
            String strokeColorHex = configManager.getConfig().getString("integrations.dynmap.style.stroke_color", "#000000");
            strokeColor = Integer.parseInt(strokeColorHex.startsWith("#") ? strokeColorHex.substring(1) : strokeColorHex, 16);
        } catch (NumberFormatException | NullPointerException | StringIndexOutOfBoundsException e) {
            plugin.getLogger().warning("Invalid stroke_color format in config. Using default black.");
        }

        double strokeOpacity = configManager.getConfig().getDouble("integrations.dynmap.style.stroke_opacity", 0.8);
        int strokeWeight = configManager.getConfig().getInt("integrations.dynmap.style.stroke_weight", 3);

        marker.setFillStyle(fillOpacity, fillColor);
        marker.setLineStyle(strokeWeight, strokeOpacity, strokeColor);

        // Всплывающее окно (popup)
        marker.setDescription(territory.getDescription()); // Используем HTML из getDescription()

        plugin.getLogger().fine("Rendered territory '" + territory.getName() + "' with marker ID '" + markerId + "'");
        return true;
    }


    // Удаление маркера с карты
    public void deleteTerritoryMarker(String territoryName) {
        if (!isDynmapEnabled()) return;
        String markerId = "hf_" + territoryName.toLowerCase().replaceAll("[^a-z0-9_]", "");
        AreaMarker marker = markerSet.findAreaMarker(markerId);
        if (marker != null) {
            marker.deleteMarker();
            plugin.getLogger().info("Deleted Dynmap marker for territory: " + territoryName);
        }
    }


    // --- Методы для команд (остаются как есть) ---

    public void addOrUpdateTerritory(DynmapTerritory territory) {
        if (territory == null) return;
        territories.put(territory.getName().toLowerCase(), territory);
        renderTerritory(territory);
        saveTerritories();
    }

    public boolean deleteTerritory(String territoryName) {
        String lowerName = territoryName.toLowerCase();
        if (territories.containsKey(lowerName)) {
            territories.remove(lowerName);
            deleteTerritoryMarker(territoryName);
            saveTerritories();
            return true;
        }
        return false;
    }

    public DynmapTerritory getTerritory(String territoryName) {
        return territories.get(territoryName.toLowerCase());
    }

    public Map<String, DynmapTerritory> getAllTerritories() {
        return Collections.unmodifiableMap(territories);
    }

    // TODO: Реализовать методы для команд /hf territory define, corner, clear, claim...
    // Они будут создавать/изменять объекты DynmapTerritory и вызывать addOrUpdateTerritory/deleteTerritory.

}