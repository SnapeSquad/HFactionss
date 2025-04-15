package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.HFactions;

import java.io.File;
import java.util.*;

public class DynmapManager {

    private final HFactions plugin;
    private final FactionManager factionManager;
    private final ConfigManager configManager;
    private DynmapAPI dynmapApi = null;
    private MarkerAPI markerApi = null;
    private MarkerSet markerSet = null;

    private File territoriesFile;
    private FileConfiguration territoriesConfig;

    private BukkitTask updateTask = null;
    private final boolean dynmapEnabled;

    // Структура TerritoryData
    public static class TerritoryData { // Сделаем публичным для команды list
        public final String zoneName;
        public final String factionId;
        public final String worldName;
        public final List<Location> corners = new ArrayList<>();
        public ConfigurationSection customStyle;

        TerritoryData(String zoneName, String factionId, String worldName) {
            this.zoneName = zoneName;
            this.factionId = factionId.toLowerCase();
            this.worldName = worldName;
        }
    }

    private final Map<String, TerritoryData> territories = new HashMap<>();

    public DynmapManager(HFactions plugin) {
        this.plugin = plugin;
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        this.dynmapEnabled = configManager.isDynmapEnabled();

        if (!dynmapEnabled) {
            plugin.logInfo("Dynmap integration is disabled.");
            return;
        }
        setupTerritoriesFile();
        setupDynmap();
    }

    // --- Инициализация ---
    private void setupTerritoriesFile() { /* ... (как раньше) ... */ }
    private FileConfiguration getTerritoriesConfig() { /* ... (как раньше) ... */ return territoriesConfig; }
    private void saveTerritoriesConfig() { /* ... (как раньше) ... */ }
    private void setupDynmap() {
        if (!dynmapEnabled) return;
        final org.bukkit.plugin.Plugin dynmapPlugin = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmapPlugin == null || !(dynmapPlugin instanceof DynmapAPI)) { /* ... ошибка ... */ return; }
        this.dynmapApi = (DynmapAPI) dynmapPlugin;
        this.markerApi = dynmapApi.getMarkerAPI();
        if (markerApi == null) { /* ... ошибка ... */ return; }

        String setId = configManager.getDynmapMarkerSetId();
        String setLabel = configManager.getDynmapMarkerSetLabel();
        markerSet = markerApi.getMarkerSet(setId);
        if (markerSet == null) markerSet = markerApi.createMarkerSet(setId, setLabel, null, false);
        if (markerSet == null) { /* ... ошибка ... */ return; }
        markerSet.setMarkerSetLabel(setLabel);

        plugin.logInfo("Successfully hooked into Dynmap.");
        loadTerritories();
        renderAllTerritories();
        startUpdateTask();
    }

    // --- Загрузка и Рендеринг ---
    public void loadTerritories() { /* ... (код как раньше, без изменений) ... */ }
    public void renderAllTerritories() {
        if (markerApi == null || markerSet == null) return;
        plugin.logInfo("Rendering territories on Dynmap...");
        markerSet.getAreaMarkers().forEach(AreaMarker::deleteMarker);

        for (TerritoryData data : territories.values()) {
            Faction faction = factionManager.getFaction(data.factionId);
            if (faction == null) continue;

            double[] xCorners = data.corners.stream().mapToDouble(Location::getX).toArray();
            double[] zCorners = data.corners.stream().mapToDouble(Location::getZ).toArray();

            AreaMarker marker = markerSet.createAreaMarker("hf_" + data.factionId + "_" + data.zoneName, data.zoneName, false, data.worldName, xCorners, zCorners, false);
            if (marker == null) continue;

            // Стиль
            ConfigurationSection styleCfg = (data.customStyle != null) ? data.customStyle : configManager.getDynmapDefaultStyleSection();
            if (styleCfg != null) {
                int fillColor = parseHexColor(faction.getColor(), styleCfg.getString("fill_color", "#000000"));
                double fillOpacity = styleCfg.getDouble("fill_opacity", 0.35);
                int strokeColor = parseHexColor(styleCfg.getString("stroke_color", "#FFFFFF"));
                double strokeOpacity = styleCfg.getDouble("stroke_opacity", 0.8);
                int strokeWeight = styleCfg.getInt("stroke_weight", 2);
                marker.setFillStyle(fillOpacity, fillColor);
                marker.setLineStyle(strokeWeight, strokeOpacity, strokeColor);
            } else {
                marker.setFillStyle(0.35, parseHexColor(faction.getColor()));
                marker.setLineStyle(2, 0.8, 0xFFFFFF);
            }

            // Popup
            String popupFormat = configManager.getDynmapDefaultPopupContent();
            String popupContent = popupFormat
                    .replace("{faction_name}", faction.getName())
                    .replace("{faction_id}", faction.getId())
                    .replace("{faction_prefix}", ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', faction.getRawPrefix())))
                    .replace("{zone_name}", data.zoneName)
                    .replace("{member_count}", String.valueOf(factionManager.getFactionMembers(faction.getId()).size()));
            marker.setDescription(popupContent);
        }
        // plugin.logInfo("Territory rendering complete."); // Можно убрать для автообновления
    }

    private int parseHexColor(String hex) { return parseHexColor(hex, "#000000"); }
    private int parseHexColor(String hex, String defaultHex) { /* ... (код как раньше) ... */ return 0; }

    // --- Автообновление ---
    private void startUpdateTask() {
        if (updateTask != null && !updateTask.isCancelled()) updateTask.cancel();
        long interval = configManager.getDynmapUpdateIntervalSeconds() * 20L;
        if (dynmapEnabled && markerApi != null && interval > 0) {
            updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::renderAllTerritories, interval, interval);
            plugin.logInfo("Dynmap auto-update task started (interval: " + (interval / 20) + "s).");
        }
    }

    // --- CRUD Территорий ---
    public boolean defineTerritory(String zoneName, String factionId, String worldName, List<Location> corners) { /* ... (код как раньше, использует get/saveTerritoriesConfig) ... */ return true; }
    public boolean deleteTerritory(String zoneName) { /* ... (код как раньше, использует get/saveTerritoriesConfig) ... */ return true; }
    public TerritoryData getTerritory(String zoneName) { return territories.get(zoneName.toLowerCase()); }
    public Collection<TerritoryData> getAllTerritories() { return Collections.unmodifiableCollection(territories.values()); }
    public List<TerritoryData> getFactionTerritories(String factionId) { /* ... (код как раньше) ... */ return new ArrayList<>(); }

    public void shutdown() {
        if (updateTask != null && !updateTask.isCancelled()) updateTask.cancel();
    }
}