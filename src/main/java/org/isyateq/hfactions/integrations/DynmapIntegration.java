package org.isyateq.hfactions.integrations;

// Bukkit API

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager;
import org.isyateq.hfactions.managers.FactionManager;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.util.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Управляет интеграцией с плагином Dynmap.
 * Отвечает за создание/обновление маркеров территорий фракций на карте,
 * загрузку/сохранение данных о территориях и обработку команд /hf territory.
 */
public final class DynmapIntegration { // Делаем класс final

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final FactionManager factionManager;

    // --- Dynmap API Объекты ---
    private DynmapAPI dynmapApi = null;
    private MarkerAPI markerApi = null;
    private MarkerSet markerSet = null; // Наш Layer/MarkerSet на карте

    // Константы для MarkerSet
    private static final String MARKER_SET_ID = "hfactions.territories"; // Уникальный ID слоя
    private static final String MARKER_SET_LABEL = "HFactions Territories"; // Имя слоя в панели Dynmap

    // --- Внутренние Кэши ---
    // Кэш маркеров Dynmap (ID маркера = имя зоны в lowercase -> Объект AreaMarker)
    private final Map<String, AreaMarker> areaMarkers = new ConcurrentHashMap<>();
    // Кэш данных о территориях из territories.yml (Имя зоны в lowercase -> Данные TerritoryData)
    private final Map<String, TerritoryData> territoryDataMap = new ConcurrentHashMap<>();

    // Флаг доступности и инициализации Dynmap API
    private boolean dynmapAvailable = false;

    // Данные для определения новой зоны игроком
    private final Map<UUID, List<Location>> definingCorners = new HashMap<>();
    private final Map<UUID, String> definingZoneName = new HashMap<>();


    public DynmapIntegration(HFactions plugin, ConfigManager configManager, FactionManager factionManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.factionManager = factionManager;
    }

    /**
     * Инициализирует интеграцию с Dynmap API и MarkerSet.
     * Вызывается один раз из HFactions.
     * @return true при успехе, false при ошибке или если Dynmap отключен.
     */
    public boolean initialize() {
        if (!configManager.isDynmapEnabled()) { plugin.getLogger().info("Dynmap integration disabled in config."); dynmapAvailable = false; return false; }
        try { Class.forName("org.dynmap.DynmapAPI"); } catch (ClassNotFoundException e) { plugin.getLogger().info("Dynmap API class not found."); dynmapAvailable = false; return false; }
        try {
            dynmapApi = (DynmapAPI) Bukkit.getServer().getPluginManager().getPlugin("dynmap");
            if (dynmapApi == null) { plugin.getLogger().info("Dynmap plugin not enabled."); dynmapAvailable = false; return false; }
            markerApi = dynmapApi.getMarkerAPI(); if (markerApi == null) { plugin.getLogger().severe("Failed to get Marker API!"); dynmapAvailable = false; return false; }
            markerSet = markerApi.getMarkerSet(MARKER_SET_ID); if (markerSet == null) markerSet = markerApi.createMarkerSet(MARKER_SET_ID, MARKER_SET_LABEL, null, false);
            if (markerSet == null) { plugin.getLogger().severe("Failed to create Marker Set!"); dynmapAvailable = false; return false; }
            markerSet.setMarkerSetLabel(MARKER_SET_LABEL); markerSet.setLayerPriority(10); markerSet.setHideByDefault(false);
            plugin.getLogger().info("Dynmap integration initialized."); dynmapAvailable = true; return true;
        } catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "Error initializing Dynmap integration", t); dynmapAvailable = false; return false; }
    }

    /** Проверяет, доступен ли Dynmap API */
    public boolean isDynmapApiAvailable() { return dynmapAvailable && dynmapApi != null && markerApi != null && markerSet != null; }

    // --- Загрузка, Рендеринг, Перезагрузка ---

    /** Загружает и рендерит территории при включении */
    public void loadAndRenderTerritories() { if (!isDynmapApiAvailable()) return; plugin.getLogger().info("Loading/Rendering Dynmap territories..."); loadTerritoriesFromConfig(); renderAllTerritories(); }
    /** Перезагружает территории из файла и обновляет карту */
    public void reloadTerritories() { if (!isDynmapApiAvailable()) return; plugin.getLogger().info("Reloading Dynmap territories..."); clearAllMarkers(); loadTerritoriesFromConfig(); renderAllTerritories(); plugin.getLogger().info("Dynmap territories reloaded."); }
    /** Загружает данные из territories.yml */
    private void loadTerritoriesFromConfig() { /* ... Реализация из предыдущего ответа ... */ }
    /** Рендерит все загруженные территории */
    private void renderAllTerritories() { /* ... Реализация из предыдущего ответа ... */ }
    /** Создает/обновляет маркер для зоны */
    private boolean createOrUpdateAreaMarker(String zoneName, TerritoryData data) { /* ... Реализация из предыдущего ответа ... */ return false; }
    /** Форматирует описание маркера */
    private String formatPopupDescription(String zoneName, Faction faction) { /* ... Реализация из предыдущего ответа ... */ return "";}
    /** Удаляет все маркеры плагина */
    public void clearAllMarkers() { /* ... Реализация из предыдущего ответа ... */ }
    /** Удаляет маркеры и данные для фракции */
    public void removeTerritoriesForFaction(String factionId) { /* ... Реализация из предыдущего ответа ... */ }

    // --- Методы Доступа к Данным Территорий (ГЕТТЕРЫ) ---

    /**
     * Возвращает НЕИЗМЕНЯЕМЫЙ набор имен всех определенных зон территорий (в lowercase).
     * @return Неизменяемый Set<String> имен зон.
     */
    public Set<String> getTerritoryZoneNames() {
        return Collections.unmodifiableSet(territoryDataMap.keySet());
    }

    /**
     * Возвращает данные о территории по её имени (регистронезависимо).
     * @param zoneName Имя зоны.
     * @return Объект TerritoryData или null, если зона не найдена.
     */
    public TerritoryData getTerritoryData(String zoneName) {
        if (zoneName == null) return null;
        return territoryDataMap.get(zoneName.toLowerCase());
    }

    /**
     * Возвращает объект AreaMarker Dynmap для указанной зоны.
     * Может быть полезно для API или продвинутых манипуляций.
     * @param zoneName Имя зоны (регистронезависимо).
     * @return AreaMarker или null, если маркер не найден или Dynmap недоступен.
     */
    public AreaMarker getAreaMarker(String zoneName) {
        if (zoneName == null || !isDynmapApiAvailable()) return null;
        return areaMarkers.get(zoneName.toLowerCase());
    }

    /**
     * Получает ID фракции, которой принадлежит указанная территория.
     * @param zoneName Имя зоны (регистронезависимо).
     * @return ID фракции (lowercase) или null, если зона не найдена или не принадлежит фракции.
     */
    public String getTerritoryFactionId(String zoneName) {
        TerritoryData data = getTerritoryData(zoneName);
        return (data != null) ? data.factionId : null;
    }

    /**
     * Возвращает НЕИЗМЕНЯЕМУЮ карту всех данных о территориях.
     * Ключ - имя зоны (lowercase), Значение - TerritoryData.
     * @return Неизменяемая карта Map<String, TerritoryData>.
     */
    public Map<String, TerritoryData> getAllTerritoryData() {
        return Collections.unmodifiableMap(territoryDataMap);
    }

    /**
     * Находит имя зоны территории, в которой находится указанная локация.
     * ВНИМАНИЕ: Этот метод может быть РЕСУРСОЕМКИМ, если зон много!
     * Он перебирает все зоны и проверяет координаты.
     * @param location Локация для проверки.
     * @return Имя зоны (lowercase) или null, если локация вне всех известных зон.
     */
    public String getZoneNameAt(Location location) {
        if (location == null || location.getWorld() == null) return null;
        String worldName = location.getWorld().getName();
        double x = location.getX();
        double z = location.getZ();

        // Итерируем по всем загруженным территориям
        for (Map.Entry<String, TerritoryData> entry : territoryDataMap.entrySet()) {
            TerritoryData data = entry.getValue();
            // Проверяем мир
            if (!worldName.equals(data.worldName)) continue;
            // Проверяем, находится ли точка внутри полигона (простая проверка)
            if (isInside(x, z, data.xCoords, data.zCoords)) {
                return entry.getKey(); // Возвращаем имя первой найденной зоны
            }
        }
        return null; // Не найдено ни одной зоны
    }

    // --- Обработка Команд /hf territory ... ---

    /** Обрабатывает команды территорий */
    public void handleTerritoryCommand(CommandSender sender, String label, String[] args) {
        // ... (Реализация парсинга подкоманд и вызова приватных методов) ...
        if (!isDynmapApiAvailable() && !"help".equalsIgnoreCase(args.length > 1 ? args[1] : "")) { sender.sendMessage(configManager.getMessage("dynmap.error.disabled")); return; }
        // Здесь должна быть полная логика для list, define, corner, clear, claim, delete, map, help
        sender.sendMessage(Utils.color("&eTerritory command handling not fully implemented yet. Use /"+label+" territory help"));
        if(args.length > 1 && "reload".equalsIgnoreCase(args[1]) && sender.hasPermission("hfactions.admin.territory")) { reloadTerritories(); sender.sendMessage(Utils.color("&aDynmap territories reloaded.")); }
    }

    /** Обрабатывает автодополнение для команд территорий */
    public List<String> handleTerritoryTabComplete(CommandSender sender, String label, String[] args) {
        // ... (Реализация автодополнения из предыдущего ответа) ...
        List<String> completions = new ArrayList<>(); String input = args[args.length - 1].toLowerCase(); if (args.length == 2) { List<String> subCommands = new ArrayList<>(Arrays.asList("list", "define", "corner", "clear", "claim", "delete", "map", "help")); if (sender.hasPermission("hfactions.admin.territory")) subCommands.add("reload"); subCommands.stream().filter(s -> s.startsWith(input)).forEach(completions::add); } else if (args.length == 3) { String subCmd = args[1].toLowerCase(); if (Arrays.asList("delete", "list").contains(subCmd)) { territoryDataMap.keySet().stream().filter(zone -> zone.startsWith(input)).forEach(completions::add); } } else if (args.length == 4 && "claim".equalsIgnoreCase(args[1])) { factionManager.getAllFactions().keySet().stream().filter(fid -> fid.startsWith(input)).forEach(completions::add); } return completions;
    }

    // --- Вспомогательные и Приватные Методы ---

    /** Проверяет, находится ли точка (x, z) внутри полигона (массивы xPoints, zPoints) */
    private boolean isInside(double x, double z, List<Double> xPoints, List<Double> zPoints) {
        int nPoints = xPoints.size();
        if (nPoints < 3) return false; // Не полигон
        boolean inside = false;
        for (int i = 0, j = nPoints - 1; i < nPoints; j = i++) {
            double xi = xPoints.get(i), zi = zPoints.get(i);
            double xj = xPoints.get(j), zj = zPoints.get(j);
            boolean intersect = ((zi > z) != (zj > z))
                    && (x < (xj - xi) * (z - zi) / (zj - zi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }


    // --- Внутренний Класс для Данных Территории ---
    /** Хранит данные о территории: фракция, мир, координаты углов */
    private static final class TerritoryData { // Делаем final
        final String factionId; // Может быть null
        final String worldName;
        final List<Double> xCoords; // Неизменяемые списки
        final List<Double> zCoords; // Неизменяемые списки

        TerritoryData(String factionId, String worldName, List<Double> xCoords, List<Double> zCoords) {
            this.factionId = (factionId != null && !factionId.trim().isEmpty()) ? factionId.toLowerCase() : null;
            this.worldName = Objects.requireNonNull(worldName);
            // Создаем неизменяемые копии списков при создании объекта
            this.xCoords = (xCoords != null) ? List.copyOf(xCoords) : List.of();
            this.zCoords = (zCoords != null) ? List.copyOf(zCoords) : List.of();
        }
    }

} // Конец класса DynmapManager