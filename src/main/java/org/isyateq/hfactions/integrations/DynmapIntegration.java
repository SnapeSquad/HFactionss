package org.isyateq.hfactions.integrations; // Убедись, что пакет правильный

import org.bukkit.Bukkit;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.DynmapManager; // Импортируем DynmapManager

import java.util.logging.Level;

/**
 * Основной класс для интеграции с Dynmap.
 * Получает API и инициализирует DynmapManager.
 */
public class DynmapIntegration {

    private final HFactions plugin;
    private DynmapAPI dynmapAPI;
    private MarkerAPI markerAPI;
    private DynmapManager dynmapManager; // Менеджер для работы с территориями

    private boolean initialized = false;

    // Константы для маркеров
    public static final String MARKER_SET_ID = "hfactions.territories";
    public static final String MARKER_SET_LABEL = "Faction Territories";

    public DynmapIntegration(HFactions plugin) {
        this.plugin = plugin;
    }

    /**
     * Инициализирует интеграцию с Dynmap.
     * @return true если инициализация успешна, иначе false.
     */
    public boolean initialize() {
        try {
            // Получаем DynmapAPI
            dynmapAPI = (DynmapAPI) Bukkit.getServer().getPluginManager().getPlugin("dynmap");
            if (dynmapAPI == null) {
                plugin.getLogger().warning("Could not retrieve DynmapAPI instance.");
                return false;
            }

            // Получаем MarkerAPI
            markerAPI = dynmapAPI.getMarkerAPI();
            if (markerAPI == null) {
                plugin.getLogger().warning("Could not retrieve MarkerAPI instance.");
                return false;
            }

            // Инициализируем наш менеджер для работы с картой
            dynmapManager = new DynmapManager(plugin);

            // Создаем или получаем набор маркеров для территорий
            MarkerSet markerSet = markerAPI.getMarkerSet(MARKER_SET_ID);
            if (markerSet == null) {
                markerSet = markerAPI.createMarkerSet(MARKER_SET_ID, MARKER_SET_LABEL, null, false);
                if (markerSet == null) {
                    plugin.getLogger().severe("Failed to create Dynmap marker set!");
                    return false;
                }
                plugin.getLogger().info("Created Dynmap marker set: " + MARKER_SET_LABEL);
            } else {
                plugin.getLogger().info("Found existing Dynmap marker set: " + MARKER_SET_LABEL);
            }
            // Здесь можно настроить параметры markerSet, если нужно (например, слой по умолчанию)
            // markerSet.setDefaultMarkerSet(true); // Пример

            initialized = true;
            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "An unexpected error occurred during Dynmap initialization", e);
            return false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public DynmapAPI getDynmapAPI() {
        return dynmapAPI;
    }

    public MarkerAPI getMarkerAPI() {
        return markerAPI;
    }

    /**
     * Возвращает менеджер, отвечающий за создание/удаление/обновление маркеров территорий.
     */
    public DynmapManager getDynmapManager() {
        return dynmapManager;
    }
}