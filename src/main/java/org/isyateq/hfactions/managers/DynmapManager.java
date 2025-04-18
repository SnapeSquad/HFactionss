package org.isyateq.hfactions.managers;

// Bukkit & Dynmap Imports
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

// HFactions Imports
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.integrations.DynmapIntegration; // Для констант и API
import org.isyateq.hfactions.models.Faction;

// Java Util Imports
import java.awt.Color; // Используем java.awt.Color для парсинга
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap; // Используем HashMap для getTerritoryInfo/getAllTerritories
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class DynmapManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final FactionManager factionManager;
    private final MarkerAPI markerAPI;
    private final MarkerSet markerSet; // Наш набор маркеров

    // Хранение временных данных для определения зон
    private final Map<UUID, List<Location>> definingCorners = new ConcurrentHashMap<>();
    // Хранение информации о созданных территориях (ID маркера -> Информация о зоне)
    private final Map<String, TerritoryInfo> territoryMarkers = new ConcurrentHashMap<>();


    // Класс для хранения данных о территории
    private static class TerritoryInfo {
        final String markerId;
        final String territoryName;
        final String factionId;
        final String worldName;
        final List<Location> corners;
        AreaMarker dynmapMarker; // Ссылка на сам маркер Dynmap

        TerritoryInfo(String markerId, String territoryName, String factionId, String worldName, List<Location> corners, AreaMarker dynmapMarker) {
            this.markerId = markerId;
            this.territoryName = territoryName;
            this.factionId = factionId;
            this.worldName = worldName;
            this.corners = new ArrayList<>(corners); // Создаем копию
            this.dynmapMarker = dynmapMarker;
        }
    }

    public DynmapManager(HFactions plugin, MarkerAPI markerAPI) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.factionManager = plugin.getFactionManager();
        this.markerAPI = markerAPI; // Получаем из DynmapIntegration

        if (this.markerAPI == null) {
            plugin.getLogger().severe("Dynmap MarkerAPI is null! Cannot initialize DynmapManager.");
            // Устанавливаем markerSet в null, чтобы избежать NPE дальше
            this.markerSet = null;
            // Возможно, стоит как-то сигнализировать об ошибке выше
        } else {
            // Получаем наш маркер сет
            this.markerSet = this.markerAPI.getMarkerSet(DynmapIntegration.MARKER_SET_ID);
            if (this.markerSet == null) {
                plugin.getLogger().severe("Dynmap MarkerSet '" + DynmapIntegration.MARKER_SET_ID + "' could not be found or created!");
                // Это критично, дальнейшая работа с картой невозможна
            }
        }
        // Проверяем наличие FactionManager и ConfigManager (хотя они должны быть)
        if (this.configManager == null) {
            plugin.getLogger().severe("ConfigManager is null in DynmapManager!");
        }
        if (this.factionManager == null) {
            plugin.getLogger().severe("FactionManager is null in DynmapManager!");
        }
    }

    /**
     * Загружает определения территорий из territories.yml и создает маркеры на карте.
     */
    public void loadTerritories() {
        if (markerSet == null) {
            plugin.getLogger().severe("Cannot load territories: Dynmap MarkerSet is null.");
            return; // Не можем продолжать без маркер-сета
        }
        // Очищаем старые маркеры перед загрузкой
        clearAllTerritoryMarkers(); // Этот метод сам очистит и territoryMarkers
        // territoryMarkers.clear(); // Не нужно, clearAllTerritoryMarkers() это сделает

        FileConfiguration territoriesConfig = configManager.getTerritoriesConfig();
        if (territoriesConfig == null) {
            plugin.getLogger().severe("Cannot load territories: territories.yml config is null.");
            return;
        }
        ConfigurationSection territoriesSection = territoriesConfig.getConfigurationSection("territories");
        if (territoriesSection == null) {
            plugin.getLogger().info("No 'territories' section found in territories.yml. No territories loaded.");
            return;
        }

        int loadedCount = 0;
        for (String territoryName : territoriesSection.getKeys(false)) {
            ConfigurationSection data = territoriesSection.getConfigurationSection(territoryName);
            if (data == null) {
                plugin.getLogger().warning("Invalid configuration section for territory name: " + territoryName);
                continue;
            }

            try {
                String factionId = data.getString("factionId");
                String worldName = data.getString("world");
                List<String> cornerStrings = data.getStringList("corners");

                if (factionId == null || worldName == null || cornerStrings.isEmpty()) {
                    plugin.getLogger().warning("Incomplete data for territory: " + territoryName + " (factionId, world, or corners missing). Skipping.");
                    continue;
                }

                // Проверяем FactionManager перед использованием
                if (factionManager == null) {
                    plugin.getLogger().severe("FactionManager is null, cannot load territory " + territoryName);
                    continue;
                }
                Faction faction = factionManager.getFaction(factionId);
                if (faction == null) {
                    plugin.getLogger().warning("Faction '" + factionId + "' for territory '" + territoryName + "' not found. Skipping.");
                    continue;
                }

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("World '" + worldName + "' for territory '" + territoryName + "' not found. Skipping.");
                    continue;
                }

                // Парсим углы
                List<Location> corners = new ArrayList<>();
                for (String cornerStr : cornerStrings) {
                    String[] parts = cornerStr.split(",");
                    if (parts.length >= 2) {
                        try {
                            double x = Double.parseDouble(parts[0].trim());
                            double z = Double.parseDouble(parts[1].trim());
                            corners.add(new Location(world, x, 0, z)); // Y = 0
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning("Invalid corner number format '" + cornerStr + "' in territory '" + territoryName + "'. Skipping corner.");
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Error parsing corner '" + cornerStr + "' in territory '" + territoryName + "'.", e);
                        }
                    } else {
                        plugin.getLogger().warning("Invalid corner string format '" + cornerStr + "' (expected 'x, z') in territory '" + territoryName + "'. Skipping corner.");
                    }
                }

                if (corners.size() < 3) {
                    plugin.getLogger().warning("Territory '" + territoryName + "' has less than 3 valid corners (" + corners.size() + "). Skipping.");
                    continue;
                }

                // Создаем маркер
                createOrUpdateTerritoryMarker(territoryName, faction, worldName, corners);
                loadedCount++;

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Unexpected error loading territory: " + territoryName, e);
            }
        }
        plugin.getLogger().info("Loaded " + loadedCount + " territories from territories.yml.");
    }

    /**
     * Сохраняет текущие определения территорий в territories.yml.
     */
    public void saveTerritories() {
        plugin.getLogger().fine("Attempting to save Dynmap territories...");
        // Проверяем ConfigManager перед использованием
        if (configManager == null) {
            plugin.getLogger().severe("Cannot save territories: ConfigManager is null.");
            return;
        }
        FileConfiguration territoriesConfig = configManager.getTerritoriesConfig();
        if (territoriesConfig == null) {
            plugin.getLogger().severe("Cannot save territories: territories.yml config object is null.");
            return;
        }
        // Очищаем старую секцию перед записью
        territoriesConfig.set("territories", null);
        ConfigurationSection territoriesSection = territoriesConfig.createSection("territories");

        int savedCount = 0;
        // Проходим по нашей мапе с информацией о территориях
        for (TerritoryInfo info : territoryMarkers.values()) {
            // Проверяем целостность данных перед сохранением
            if (info == null || info.territoryName == null || info.factionId == null || info.worldName == null || info.corners == null || info.corners.isEmpty()) {
                plugin.getLogger().warning("Skipping saving invalid TerritoryInfo (markerId: " + (info != null ? info.markerId : "null") + ")");
                continue;
            }
            try {
                ConfigurationSection data = territoriesSection.createSection(info.territoryName);
                data.set("factionId", info.factionId);
                data.set("world", info.worldName);
                // Сохраняем углы как список строк "x,z"
                List<String> cornerStrings = info.corners.stream()
                        .map(loc -> String.format(Locale.US, "%.2f, %.2f", loc.getX(), loc.getZ()))
                        .collect(Collectors.toList());
                data.set("corners", cornerStrings);
                savedCount++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error preparing territory data for saving: " + info.territoryName, e);
            }
        }

        configManager.saveTerritoriesConfig(); // Вызываем сохранение файла
        if (savedCount > 0 || territoryMarkers.isEmpty()) { // Логируем даже если сохранили 0 из 0
            plugin.getLogger().info("Saved " + savedCount + " territories to territories.yml.");
        }
    }

    /**
     * Создает или обновляет маркер территории на карте Dynmap и сохраняет информацию о нем.
     */
    public void createOrUpdateTerritoryMarker(String territoryName, Faction faction, String worldName, List<Location> corners) {
        if (markerSet == null) {
            plugin.getLogger().severe("Cannot create/update territory marker '" + territoryName + "': MarkerSet is null!");
            return;
        }
        if (corners == null || corners.size() < 3) {
            plugin.getLogger().warning("Cannot create/update territory '" + territoryName + "': Less than 3 corners provided or corners list is null.");
            return;
        }
        if (faction == null) {
            plugin.getLogger().warning("Cannot create/update territory '" + territoryName + "': Faction is null.");
            return;
        }
        if (territoryName == null || territoryName.trim().isEmpty()) {
            plugin.getLogger().warning("Cannot create/update territory: Territory name is empty or null.");
            return;
        }
        if (worldName == null || worldName.trim().isEmpty()) {
            plugin.getLogger().warning("Cannot create/update territory '" + territoryName + "': World name is empty or null.");
            return;
        }


        String markerId = "hf_territory_" + territoryName.toLowerCase().replace(" ", "_"); // Генерируем ID маркера

        // Удаляем старый маркер, если он есть (для обновления)
        AreaMarker existingMarker = markerSet.findAreaMarker(markerId);
        if (existingMarker != null) {
            plugin.getLogger().fine("Deleting existing Dynmap marker before update: " + markerId);
            existingMarker.deleteMarker();
        }

        // Получаем координаты X и Z
        double[] xCoords = corners.stream().mapToDouble(Location::getX).toArray();
        double[] zCoords = corners.stream().mapToDouble(Location::getZ).toArray();

        // Создаем новый маркер зоны
        String markerLabel = faction.getName() + " Territory"; // Название на карте
        AreaMarker areaMarker = markerSet.createAreaMarker(markerId, markerLabel, false, worldName, xCoords, zCoords, false);

        if (areaMarker == null) {
            plugin.getLogger().severe("Failed to create Dynmap AreaMarker for territory: " + territoryName + " (ID: " + markerId + ")");
            territoryMarkers.remove(markerId); // Убедимся, что старые данные удалены
            return;
        }

        // Настройка стиля маркера
        applyMarkerStyle(areaMarker, faction);

        // Настройка всплывающего окна (info window)
        String infoWindowContent = buildInfoWindow(faction, territoryName);
        areaMarker.setDescription(infoWindowContent);

        // Сохраняем или обновляем информацию о маркере в нашей карте
        TerritoryInfo info = new TerritoryInfo(markerId, territoryName, faction.getId(), worldName, corners, areaMarker);
        territoryMarkers.put(markerId, info); // Используем markerId как ключ

        plugin.getLogger().fine("Created/Updated Dynmap marker for territory: " + territoryName + " (ID: " + markerId + ")");
    }

    /**
     * Удаляет маркер территории с карты и из файла конфигурации.
     */
    public boolean deleteTerritory(String territoryName) {
        if (territoryName == null || territoryName.trim().isEmpty()) {
            plugin.getLogger().warning("Cannot delete territory: Name is null or empty.");
            return false;
        }
        String markerId = "hf_territory_" + territoryName.toLowerCase().replace(" ", "_");
        TerritoryInfo info = territoryMarkers.remove(markerId); // Ищем и удаляем из нашей мапы

        boolean removedFromMap = false;
        if (info != null && info.dynmapMarker != null) {
            info.dynmapMarker.deleteMarker(); // Удаляем маркер с карты
            plugin.getLogger().info("Deleted Dynmap marker for territory: " + territoryName);
            removedFromMap = true;
        } else if (markerSet != null) {
            // Дополнительная проверка, вдруг маркер есть на карте, но нет в нашей мапе
            AreaMarker existingMarker = markerSet.findAreaMarker(markerId);
            if (existingMarker != null) {
                existingMarker.deleteMarker();
                plugin.getLogger().info("Deleted orphaned Dynmap marker for territory: " + territoryName);
                removedFromMap = true; // Считаем, что удалили с карты
            }
        }

        // Удаляем из конфига в любом случае, если он там есть
        boolean removedFromConfig = false;
        // Проверяем ConfigManager перед использованием
        if (configManager != null) {
            FileConfiguration territoriesConfig = configManager.getTerritoriesConfig();
            if (territoriesConfig != null && territoriesConfig.contains("territories." + territoryName)) {
                territoriesConfig.set("territories." + territoryName, null);
                removedFromConfig = true; // Помечаем, что удалили из конфига
            }
        } else {
            plugin.getLogger().severe("Cannot remove territory from config: ConfigManager is null.");
        }


        // Сохраняем конфиг ТОЛЬКО если что-то реально удалили (из карты или из конфига)
        if (removedFromConfig) {
            if (configManager != null) {
                configManager.saveTerritoriesConfig(); // Сохраняем файл
                if (!removedFromMap) { // Если удалили только из конфига
                    plugin.getLogger().info("Removed territory '" + territoryName + "' from config (marker was not found on map).");
                }
            }
        } else if (removedFromMap) {
            // Если удалили с карты, но в конфиге не было - можно залогировать
            plugin.getLogger().info("Deleted marker for territory '" + territoryName + "' which was not found in config.");
        }


        if (!removedFromMap && !removedFromConfig) {
            plugin.getLogger().warning("Territory '" + territoryName + "' not found for deletion (neither on map nor in config).");
            return false; // Ничего не удалили
        }

        return true; // Что-то удалили
    }


    /**
     * Применяет стиль к маркеру зоны на основе настроек фракции и конфига.
     */
    private void applyMarkerStyle(AreaMarker marker, Faction faction) {
        if (marker == null || faction == null) return;
        if (configManager == null) {
            plugin.getLogger().severe("Cannot apply marker style: ConfigManager is null.");
            return;
        }
        FileConfiguration currentConfig = configManager.getConfig();
        if (currentConfig == null) {
            plugin.getLogger().severe("Cannot apply marker style: config.yml object is null.");
            return;
        }
        // --- Настройки из config.yml ---
        double fillOpacity = currentConfig.getDouble("integrations.dynmap.style.fill_opacity", 0.35);
        int strokeWeight = currentConfig.getInt("integrations.dynmap.style.stroke_weight", 1);
        double strokeOpacity = currentConfig.getDouble("integrations.dynmap.style.stroke_opacity", 0.8);
        String defaultStrokeColorHex = currentConfig.getString("integrations.dynmap.style.stroke_color", "#FFFFFF");
        boolean boostFlag = currentConfig.getBoolean("integrations.dynmap.style.boost_flag", false);

        // --- Настройки от фракции ---
        String fillColorHex = faction.getColor();

        // Преобразуем HEX цвета в RGB int
        int fillColor = parseHexColorToInt(fillColorHex, 0x0000FF); // Синий по умолчанию при ошибке
        int strokeColor = parseHexColorToInt(defaultStrokeColorHex, 0xFFFFFF); // Белый по умолчанию

        marker.setFillStyle(fillOpacity, fillColor);
        marker.setLineStyle(strokeWeight, strokeOpacity, strokeColor);
        marker.setBoostFlag(boostFlag);
    }

    /**
     * Строит HTML-содержимое для всплывающего окна маркера.
     */
    private String buildInfoWindow(Faction faction, String territoryName) {
        if (faction == null || territoryName == null) return "";
        // Экранируем для безопасности
        String safeFactionName = escapeHTML(faction.getName());
        String safeTerritoryName = escapeHTML(territoryName);
        String factionColor = faction.getColor() != null ? faction.getColor() : "#FFFFFF"; // Цвет фракции

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-weight: bold; font-size: 120%; color: ").append(factionColor).append(";\">");
        sb.append(safeFactionName);
        sb.append("</div>");
        sb.append("<div>Territory: ").append(safeTerritoryName).append("</div>");

        return sb.toString();
    }

    /**
     * Экранирует основные HTML символы.
     */
    private String escapeHTML(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '>' || c == '<' || c == '&' || c == '"' || c == '\'') {
                out.append('&');
                out.append('#');
                out.append((int) c);
                out.append(';');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }


    /**
     * Парсит HEX строку цвета (#RRGGBB) в RGB integer.
     */
    private int parseHexColorToInt(String hexColor, int defaultColor) {
        if (hexColor == null || !hexColor.matches("^#[a-fA-F0-9]{6}$")) {
            return defaultColor;
        }
        try {
            // Используем java.awt.Color для парсинга, он надежнее
            Color color = Color.decode(hexColor);
            return color.getRGB() & 0xFFFFFF; // Возвращаем только RGB часть
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Failed to parse HEX color '" + hexColor + "': " + e.getMessage());
            return defaultColor;
        }
    }


    /**
     * Очищает все маркеры зон, созданные этим плагином.
     */
    private void clearAllTerritoryMarkers() {
        if (markerSet != null) {
            List<AreaMarker> markersToRemove = new ArrayList<>(markerSet.getAreaMarkers()); // Копия для итерации
            int removedCount = 0;
            for (AreaMarker marker : markersToRemove) {
                if (marker != null && marker.getMarkerID().startsWith("hf_territory_")) {
                    marker.deleteMarker();
                    removedCount++;
                }
            }
            if(removedCount > 0) {
                plugin.getLogger().info("Cleared " + removedCount + " existing HFactions territory markers from Dynmap.");
            }
        } else {
            // Логируем только если пытаемся очистить, но сет=null
            plugin.getLogger().warning("Cannot clear territory markers: MarkerSet is null.");
        }
        territoryMarkers.clear(); // Очищаем нашу внутреннюю мапу в любом случае
    }

    // --- Методы для команды /hf territory define ---

    public void startDefining(Player player) {
        if (player == null) return;
        definingCorners.put(player.getUniqueId(), new ArrayList<>());
        if (configManager != null) {
            player.sendMessage(configManager.getMessage("territory.define.start", "&aStarted defining territory..."));
        } else {
            player.sendMessage(ChatColor.GREEN + "Started defining territory..."); // Запасной вариант
        }
    }

    public void addCorner(Player player) {
        if (player == null) return;
        List<Location> corners = definingCorners.get(player.getUniqueId());
        if (corners == null) {
            if (configManager != null) {
                player.sendMessage(configManager.getMessage("territory.define.not_started", "&cYou haven't started defining a territory yet!"));
            } else {
                player.sendMessage(ChatColor.RED + "You haven't started defining a territory yet!");
            }
            return;
        }
        Location cornerLoc = player.getLocation();
        corners.add(cornerLoc);
        if (configManager != null) {
            String message = configManager.getMessage("territory.define.corner_added", "&aCorner #{number} added at X:{x} Z:{z}.");
            message = message.replace("{number}", String.valueOf(corners.size()))
                    .replace("{x}", String.format(Locale.US, "%.1f", cornerLoc.getX()))
                    .replace("{z}", String.format(Locale.US, "%.1f", cornerLoc.getZ()));
            player.sendMessage(message);
        } else {
            player.sendMessage(ChatColor.GREEN + "Corner #" + corners.size() + " added.");
        }
    }

    public void clearCorners(Player player) {
        if (player == null) return;
        if (definingCorners.remove(player.getUniqueId()) != null) {
            if (configManager != null) {
                player.sendMessage(configManager.getMessage("territory.define.cleared", "&aTerritory definition corners cleared."));
            } else {
                player.sendMessage(ChatColor.GREEN + "Territory definition corners cleared.");
            }
        } else {
            if (configManager != null) {
                player.sendMessage(configManager.getMessage("territory.define.not_started", "&cYou haven't started defining a territory yet!"));
            } else {
                player.sendMessage(ChatColor.RED + "You haven't started defining a territory yet!");
            }
        }
    }

    public void claimTerritory(Player player, String territoryName, String factionId) {
        if (player == null || territoryName == null || factionId == null) return;
        List<Location> corners = definingCorners.remove(player.getUniqueId());
        if (corners == null) {
            if (configManager != null) {
                player.sendMessage(configManager.getMessage("territory.define.not_started_claim", "&cYou haven't started defining..."));
            } else {
                player.sendMessage(ChatColor.RED + "You haven't started defining...");
            }
            return;
        }
        if (corners.size() < 3) {
            if (configManager != null) {
                String message = configManager.getMessage("territory.define.not_enough_corners", "&cNeed >= 3 corners. Have: {count}");
                player.sendMessage(message.replace("{count}", String.valueOf(corners.size())));
            } else {
                player.sendMessage(ChatColor.RED + "Need >= 3 corners. Have: " + corners.size());
            }
            definingCorners.put(player.getUniqueId(), corners);
            return;
        }
        if (factionManager == null) {
            plugin.getLogger().severe("FactionManager is null! Cannot claim territory.");
            player.sendMessage(ChatColor.RED+"Internal error: FactionManager not found.");
            definingCorners.put(player.getUniqueId(), corners);
            return;
        }

        Faction faction = factionManager.getFaction(factionId);
        if (faction == null) {
            if (configManager != null) {
                String message = configManager.getMessage("faction.not_found", "&cFaction '{id}' not found.");
                player.sendMessage(message.replace("{id}", factionId));
            } else {
                player.sendMessage(ChatColor.RED + "Faction '" + factionId + "' not found.");
            }
            definingCorners.put(player.getUniqueId(), corners);
            return;
        }

        String markerId = "hf_territory_" + territoryName.toLowerCase().replace(" ", "_");
        if (territoryMarkers.values().stream().anyMatch(info -> info.territoryName.equalsIgnoreCase(territoryName)) ||
                (markerSet != null && markerSet.findAreaMarker(markerId) != null)) {
            if (configManager != null) {
                String message = configManager.getMessage("territory.define.name_exists", "&cTerritory '{name}' already exists.");
                player.sendMessage(message.replace("{name}", territoryName));
            } else {
                player.sendMessage(ChatColor.RED + "Territory '" + territoryName + "' already exists.");
            }
            definingCorners.put(player.getUniqueId(), corners);
            return;
        }

        String worldName = player.getWorld().getName();
        createOrUpdateTerritoryMarker(territoryName, faction, worldName, corners);
        saveTerritories();

        if (configManager != null) {
            String message = configManager.getMessage("territory.define.claimed", "&aTerritory '{name}' for {faction_name} claimed!");
            message = message.replace("{name}", territoryName)
                    .replace("{faction_name}", faction.getName());
            player.sendMessage(message);
        } else {
            player.sendMessage(ChatColor.GREEN + "Territory '" + territoryName + "' claimed for " + faction.getName() + "!");
        }
    }


    // --- Геттеры для команд и информации ---
    public List<String> getTerritoryNames() {
        return new ArrayList<>(territoryMarkers.values().stream()
                .map(info -> info.territoryName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList()));
    }

    public TerritoryInfo getTerritoryInfo(String territoryName) {
        if (territoryName == null) return null;
        return territoryMarkers.values().stream()
                .filter(info -> info.territoryName.equalsIgnoreCase(territoryName))
                .findFirst()
                .orElse(null);
    }

    public Map<String, TerritoryInfo> getAllTerritories() {
        // Используем имя территории как ключ для возвращаемой карты
        Map<String, TerritoryInfo> result = new HashMap<>();
        for(TerritoryInfo info : territoryMarkers.values()){
            result.put(info.territoryName, info);
        }
        return Collections.unmodifiableMap(result);
    }
}