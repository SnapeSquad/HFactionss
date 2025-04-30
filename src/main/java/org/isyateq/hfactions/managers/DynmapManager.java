package org.isyateq.hfactions.managers;

// Bukkit API
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;

// Dynmap API
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

// Локальные классы
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank; // Для проверки лидера
import org.isyateq.hfactions.util.Utils;

// Утилиты Java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Управляет интеграцией с плагином Dynmap.
 * Отвечает за маркеры территорий и команды /hf territory.
 */
public final class DynmapManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final FactionManager factionManager;
    private final PlayerManager playerManager;

    private DynmapAPI dynmapApi = null;
    private MarkerAPI markerApi = null;
    private MarkerSet markerSet = null;

    private static final String MARKER_SET_ID = "hfactions.territories";
    private static final String MARKER_SET_LABEL = "HFactions Territories";

    private final Map<String, AreaMarker> areaMarkers = new ConcurrentHashMap<>();
    private final Map<String, TerritoryData> territoryDataMap = new ConcurrentHashMap<>();
    private boolean dynmapAvailable = false;

    // Для команды /hf territory define
    private final Map<UUID, List<Location>> definingCorners = new HashMap<>();
    private final Map<UUID, String> definingZoneName = new HashMap<>();

    /** Конструктор */
    public DynmapManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.factionManager = plugin.getFactionManager();
        this.playerManager = plugin.getPlayerManager();
        if (this.configManager == null || this.factionManager == null || this.playerManager == null) {
            throw new IllegalStateException("DynmapManager requires Config, Faction, and Player Managers!");
        }
    }

    /** Инициализация */
    public boolean initialize() { /* Код как в предыдущем полном ответе */ return false; }
    /** Доступно ли API */
    public boolean isDynmapApiAvailable() { /* Код как в предыдущем полном ответе */ return false; }

    // --- Загрузка, Рендер, Перезагрузка ---
    public void loadAndRenderTerritories() { if (!isDynmapApiAvailable()) return; loadTerritoriesFromConfig(); renderAllTerritories(); }
    public void reloadTerritories() { if (!isDynmapApiAvailable()) return; clearAllMarkers(); loadTerritoriesFromConfig(); renderAllTerritories(); }
    private void loadTerritoriesFromConfig() {
        territoryDataMap.clear();
        FileConfiguration terrConfig = configManager.getTerritoriesConfig();
        ConfigurationSection territoriesSection = terrConfig.getConfigurationSection("territories");
        if (territoriesSection == null) return;
        int count = 0;
        for (String zoneName : territoriesSection.getKeys(false)) {
            ConfigurationSection data = territoriesSection.getConfigurationSection(zoneName); if(data==null) continue;
            String factionId = data.getString("factionId"); String worldName = data.getString("world");
            List<Double> xCoords=data.getDoubleList("x"); List<Double> zCoords=data.getDoubleList("z");
            if(factionId==null||worldName==null||xCoords.isEmpty()||zCoords.isEmpty()||xCoords.size()!=zCoords.size()){plugin.getLogger().warning("Invalid data for territory '"+zoneName+"'. Skipping.");continue;}
            if(Bukkit.getWorld(worldName)==null){plugin.getLogger().warning("World '"+worldName+"' for territory '"+zoneName+"' not found. Skipping.");continue;}
            Faction faction=factionManager.getFaction(factionId);if(faction==null && factionId != null)plugin.getLogger().warning("Faction '"+factionId+"' for territory '"+zoneName+"' not found.");
            territoryDataMap.put(zoneName.toLowerCase(), new TerritoryData(factionId,worldName,xCoords,zCoords)); count++;
        }
        plugin.getLogger().info("Loaded "+count+" territory definitions.");
    }
    private void renderAllTerritories() {
        if (!isDynmapApiAvailable()) return;
        // Не очищаем маркеры здесь, это делает reloadTerritories/loadAndRenderTerritories
        int count = 0;
        for (Map.Entry<String, TerritoryData> entry : territoryDataMap.entrySet()) {
            if (createOrUpdateAreaMarker(entry.getKey(), entry.getValue())) count++;
        }
        plugin.getLogger().info("Rendered " + count + " territory markers.");
    }
    private boolean createOrUpdateAreaMarker(String zoneName, TerritoryData data) {
        if (!isDynmapApiAvailable()) return false;
        AreaMarker marker = areaMarkers.get(zoneName.toLowerCase());
        String markerId = zoneName.toLowerCase();
        double[] x = data.xCoords.stream().mapToDouble(Double::doubleValue).toArray();
        double[] z = data.zCoords.stream().mapToDouble(Double::doubleValue).toArray();
        if (x.length < 3) { plugin.getLogger().warning("Territory '" + zoneName + "' has < 3 corners."); return false; }

        if (marker == null) {
            marker = markerSet.createAreaMarker(markerId, zoneName, false, data.worldName, x, z, false);
            if (marker == null) { plugin.getLogger().severe("Failed create DMarker: " + zoneName); return false; }
            areaMarkers.put(markerId, marker); plugin.getLogger().fine("Created DMarker: " + markerId);
        } else { marker.setCornerLocations(x, z); plugin.getLogger().fine("Updated DMarker: " + markerId); }

        Faction faction = (data.factionId != null) ? factionManager.getFaction(data.factionId) : null;
        int color = 0x0000FF; if (faction != null && faction.getColor() != null) { try { color = Integer.parseInt(faction.getColor().substring(1), 16); } catch (Exception e) { color = 0x0000FF; } }
        double fo = configManager.getDynmapMarkerOpacity(); int sw = configManager.getDynmapMarkerWeight(); double so = configManager.getDynmapMarkerStrokeOpacity(); String desc = formatPopupDescription(zoneName, faction);
        marker.setLineStyle(sw, so, color); marker.setFillStyle(fo, color); marker.setDescription(desc); marker.setLabel(zoneName);
        return true;
    }
    private String formatPopupDescription(String zoneName, Faction faction) {
        String format=configManager.getDynmapMarkerFormat(); format = format.replace("{zone_name}", Utils.escapeHtml(zoneName));
        if(faction!=null){ format=format.replace("{faction_name}",Utils.escapeHtml(faction.getName()));format=format.replace("{faction_id}",Utils.escapeHtml(faction.getId()));format=format.replace("{faction_type}",faction.getType().name()); }
        else{ format=format.replace("{faction_name}","Unclaimed");format=format.replace("{faction_id}","N/A");format=format.replace("{faction_type}","N/A"); }
        return format;
    }
    public void clearAllMarkers() {
        if (!isDynmapApiAvailable()) return;
        Set<String> markerIds = new HashSet<>(areaMarkers.keySet()); for(String id : markerIds){ AreaMarker m = areaMarkers.remove(id); if(m!=null) m.deleteMarker(); }
        if (markerSet != null) markerSet.getAreaMarkers().forEach(AreaMarker::deleteMarker); plugin.getLogger().info("Cleared all HFactions Dynmap markers.");
        areaMarkers.clear();
    }
    public void removeTerritoriesForFaction(String factionId) {
        if (!isDynmapApiAvailable() || factionId == null) return; String lowerId = factionId.toLowerCase(); plugin.getLogger().info("Removing DMarkers for faction: " + lowerId); List<String> zonesToRemove = territoryDataMap.entrySet().stream().filter(e -> lowerId.equals(e.getValue().factionId)).map(Map.Entry::getKey).collect(Collectors.toList()); if (zonesToRemove.isEmpty()) return; int removedCount = 0; FileConfiguration conf = configManager.getTerritoriesConfig(); boolean changed = false; for (String zoneName : zonesToRemove) { territoryDataMap.remove(zoneName); AreaMarker marker = areaMarkers.remove(zoneName); if (marker != null) { marker.deleteMarker(); removedCount++; } String path = "territories." + zoneName; if(conf.contains(path)) { conf.set(path, null); changed = true; } } if(changed) configManager.saveTerritoriesConfig(); plugin.getLogger().info("Removed " + removedCount + " DMarkers for faction " + lowerId);
    }

    // --- Геттеры ---
    public Set<String> getTerritoryZoneNames() { return Collections.unmodifiableSet(territoryDataMap.keySet()); }
    public TerritoryData getTerritoryData(String zoneName) { return (zoneName != null) ? territoryDataMap.get(zoneName.toLowerCase()) : null; }
    public AreaMarker getAreaMarker(String zoneName) { return (zoneName != null && isDynmapApiAvailable()) ? areaMarkers.get(zoneName.toLowerCase()) : null; }
    public String getTerritoryFactionId(String zoneName) { TerritoryData d = getTerritoryData(zoneName); return (d != null) ? d.factionId : null; }
    public Map<String, TerritoryData> getAllTerritoryData() { return Collections.unmodifiableMap(territoryDataMap); }
    public String getZoneNameAt(Location loc) { if(loc==null||loc.getWorld()==null) return null; String w=loc.getWorld().getName();double x=loc.getX(),z=loc.getZ(); for(Map.Entry<String,TerritoryData>e:territoryDataMap.entrySet()){TerritoryData d=e.getValue();if(w.equals(d.worldName)&&isInside(x,z,d.xCoords,d.zCoords))return e.getKey();}return null;}
    private boolean isInside(double x, double z, List<Double> xP, List<Double> zP) { int n=xP.size();if(n<3)return false;boolean inside=false;for(int i=0,j=n-1;i<n;j=i++){double xi=xP.get(i),zi=zP.get(i),xj=xP.get(j),zj=zP.get(j);boolean intersect=((zi>z)!=(zj>z))&&(x<(xj-xi)*(z-zi)/(zj-zi)+xi);if(intersect)inside=!inside;}return inside;}

    // --- Обработка Команд /hf territory ... ---
    public void handleTerritoryCommand(CommandSender sender, String label, String[] args) {
        if (!isDynmapApiAvailable() && args.length > 1 && !"help".equalsIgnoreCase(args[1])) { sender.sendMessage(configManager.getMessage("dynmap.error.disabled")); return; }
        if (args.length < 2 || "help".equalsIgnoreCase(args[1])) { sendTerritoryHelp(sender, label); return; }
        String subCmd = args[1].toLowerCase(); Player player = (sender instanceof Player) ? (Player) sender : null;
        boolean isAdmin = sender.hasPermission("hfactions.admin.territory"); boolean canManageOwn = sender.hasPermission("hfactions.territory.manage.own"); boolean canList = sender.hasPermission("hfactions.territory.list") || isAdmin;
        switch (subCmd) {
            case "list": if (!canList) { sendNoPermission(sender); return; } listTerritories(sender, args); break;
            case "define": if (player == null) { sendPlayerOnly(sender); return; } if (!isAdmin && !canManageOwn) { sendNoPermission(sender); return; } defineTerritoryStart(player, args, label); break;
            case "corner": if (player == null) { sendPlayerOnly(sender); return; } if (!isAdmin && !canManageOwn) { sendNoPermission(sender); return; } addTerritoryCorner(player); break;
            case "clear": if (player == null) { sendPlayerOnly(sender); return; } if (!isAdmin && !canManageOwn) { sendNoPermission(sender); return; } clearTerritoryCorners(player); break;
            case "claim": if (player == null) { sendPlayerOnly(sender); return; } if (!isAdmin && !canManageOwn) { sendNoPermission(sender); return; } claimTerritory(player, args, label, isAdmin); break;
            case "delete": if (!isAdmin && !(canManageOwn && player != null)) { sendNoPermission(sender); return; } deleteTerritory(sender, args, label, isAdmin, canManageOwn); break;
            case "map": if (!isAdmin) { sendNoPermission(sender); return; } forceMapUpdate(sender); break;
            case "reload": if (!isAdmin) { sendNoPermission(sender); return; } reloadTerritories(); sender.sendMessage(configManager.getMessage("dynmap.reload_success")); break;
            default: sender.sendMessage(configManager.getMessage("error.unknown_subcommand", "{subcommand}", subCmd)); sendTerritoryHelp(sender, label); break;
        }
    }
    private void sendTerritoryHelp(CommandSender sender, String label) { /* ... Полная реализация помощи ... */ }
    private void listTerritories(CommandSender sender, String[] args) { /* ... Полная реализация списка ... */ }

    // --- Логика Define / Corner / Clear / Claim / Delete ---
    private void defineTerritoryStart(Player player, String[] args, String label) {
        if (args.length < 3) { sendUsage(player, label, "territory define <ZoneName>"); return; }
        String zoneName = args[2];
        if (zoneName.length() < 3 || zoneName.length() > 32 || !zoneName.matches("^[a-zA-Z0-9_-]+$")) { // Валидация имени
            player.sendMessage(configManager.getMessage("dynmap.error.invalid_zone_name")); return;
        }
        String lowerZoneName = zoneName.toLowerCase();
        if (territoryDataMap.containsKey(lowerZoneName)) { player.sendMessage(configManager.getMessage("dynmap.error.zone_exists", "{zone}", zoneName)); return; }
        definingCorners.put(player.getUniqueId(), new ArrayList<>()); // Начинаем новый список углов
        definingZoneName.put(player.getUniqueId(), zoneName); // Сохраняем имя
        player.sendMessage(configManager.getMessage("dynmap.define.started", "{zone}", zoneName));
        player.sendMessage(configManager.getMessage("dynmap.define.add_corners"));
    }
    private void addTerritoryCorner(Player player) {
        List<Location> corners = definingCorners.get(player.getUniqueId());
        String zoneName = definingZoneName.get(player.getUniqueId());
        if (corners == null || zoneName == null) { player.sendMessage(configManager.getMessage("dynmap.error.not_defining")); return; }
        Location corner = player.getLocation();
        // Проверка, что все точки в одном мире
        if (!corners.isEmpty() && !Objects.equals(corners.get(0).getWorld(), corner.getWorld())) { player.sendMessage(configManager.getMessage("dynmap.error.different_worlds")); return; }
        corners.add(corner);
        player.sendMessage(configManager.getMessage("dynmap.define.corner_added", "{count}", String.valueOf(corners.size()), "{x}", String.format("%.1f",corner.getX()), "{z}", String.format("%.1f", corner.getZ())));
    }
    private void clearTerritoryCorners(Player player) {
        if (definingCorners.remove(player.getUniqueId()) != null) {
            definingZoneName.remove(player.getUniqueId());
            player.sendMessage(configManager.getMessage("dynmap.define.cleared"));
        } else { player.sendMessage(configManager.getMessage("dynmap.error.not_defining")); }
    }
    private void claimTerritory(Player player, String[] args, String label, boolean isAdmin) {
        UUID playerId = player.getUniqueId();
        String zoneName = definingZoneName.get(playerId);
        List<Location> corners = definingCorners.get(playerId);
        if (zoneName == null || corners == null || corners.size() < 3) { player.sendMessage(configManager.getMessage("dynmap.error.not_enough_corners")); return; }

        String claimedFactionId = null;
        if (args.length > 2 && isAdmin) { // Админ может указать ID фракции
            String providedFactionId = args[2].toLowerCase();
            if (factionManager.getFaction(providedFactionId) != null) { claimedFactionId = providedFactionId; }
            else { player.sendMessage(configManager.getMessage("faction.error.not_found", "{id}", args[2])); return; }
        } else { // Игрок клеймит для своей фракции
            claimedFactionId = playerManager.getPlayerFactionId(playerId);
            if (playerManager.isAdminInMode(player)) claimedFactionId = playerManager.getAdminModeFactionId(player); // Админ режим
            if (claimedFactionId == null) { player.sendMessage(configManager.getMessage("dynmap.error.claim_no_faction")); return; }
            // Проверка, может ли игрок клеймить (является ли лидером? Настройка?)
            Faction pFaction = factionManager.getFaction(claimedFactionId);
            if (!isAdmin && (pFaction == null || !pFaction.getLeaderRank().equals(playerManager.getPlayerRank(player)))) {
                player.sendMessage(configManager.getMessage("dynmap.error.claim_not_leader")); return;
            }
        }

        // Сохраняем данные
        String worldName = corners.get(0).getWorld().getName();
        List<Double> xCoords = corners.stream().map(Location::getX).collect(Collectors.toList());
        List<Double> zCoords = corners.stream().map(Location::getZ).collect(Collectors.toList());
        saveTerritoryData(zoneName.toLowerCase(), claimedFactionId, worldName, xCoords, zCoords);

        // Очищаем временные данные игрока
        definingCorners.remove(playerId); definingZoneName.remove(playerId);

        // Обновляем маркер на карте
        TerritoryData newData = territoryDataMap.get(zoneName.toLowerCase());
        if (newData != null) createOrUpdateAreaMarker(zoneName.toLowerCase(), newData);

        player.sendMessage(configManager.getMessage("dynmap.claim.success", "{zone}", zoneName, "{faction_id}", claimedFactionId != null ? claimedFactionId : "Unclaimed"));
    }
    private void deleteTerritory(CommandSender sender, String[] args, String label, boolean isAdmin, boolean canManageOwn) {
        if (args.length < 3) { sendUsage(sender, label, "territory delete <ZoneName>"); return; }
        String zoneName = args[2]; String lowerZoneName = zoneName.toLowerCase();
        TerritoryData data = territoryDataMap.get(lowerZoneName);
        if (data == null) { sender.sendMessage(configManager.getMessage("dynmap.error.zone_not_found"), "{zone}", zoneName); return; }

        // Проверка прав на удаление
        if (!isAdmin) {
            if (!(sender instanceof Player player)) { sendPlayerOnly(sender); return; } // Только игрок может удалять свою
            if (!canManageOwn) { sendNoPermission(sender); return; } // Нужно право manage.own
            String playerFactionId = playerManager.getPlayerFactionId(player);
            if(playerManager.isAdminInMode(player)) playerFactionId = playerManager.getAdminModeFactionId(player);
            // Игрок должен быть лидером (или иметь высокий ранг) И фракция зоны должна совпадать с его
            if (!Objects.equals(data.factionId, playerFactionId)) { player.sendMessage(configManager.getMessage("dynmap.error.delete_not_own")); return; }
            Faction faction = factionManager.getFaction(playerFactionId);
            if (faction == null || !Objects.equals(playerManager.getPlayerRank(player), faction.getLeaderRank())) { // Проверяем, что он лидер
                player.sendMessage(configManager.getMessage("dynmap.error.delete_not_leader")); return;
            }
        } // Админ может удалять любую зону

        // Удаление
        territoryDataMap.remove(lowerZoneName);
        AreaMarker marker = areaMarkers.remove(lowerZoneName); if (marker != null) marker.deleteMarker();
        FileConfiguration terrConfig = configManager.getTerritoriesConfig(); terrConfig.set("territories." + lowerZoneName, null); configManager.saveTerritoriesConfig(); // Сохраняем удаление

        sender.sendMessage(configManager.getMessage("dynmap.delete.success"), "{zone}", zoneName);
    }
    private void forceMapUpdate(CommandSender sender) { if(!isDynmapApiAvailable())return; dynmapApi.triggerRenderOfVolume(null, 0, 0, 0, 0, 0); /* TODO: Уточнить API */ sender.sendMessage(configManager.getMessage("dynmap.map_update_triggered")); }

    // --- Сохранение Данных Территории ---
    private void saveTerritoryData(String zoneNameLower, String factionId, String world, List<Double> x, List<Double> z) {
        FileConfiguration terrConfig = configManager.getTerritoriesConfig();
        String path = "territories." + zoneNameLower;
        terrConfig.set(path + ".factionId", factionId); // Может быть null
        terrConfig.set(path + ".world", world);
        terrConfig.set(path + ".x", x);
        terrConfig.set(path + ".z", z);
        configManager.saveTerritoriesConfig(); // Сохраняем немедленно
        // Обновляем кэш в памяти
        territoryDataMap.put(zoneNameLower, new TerritoryData(factionId, world, x, z));
    }

    // --- Вспомогательные сообщения команд ---
    private void sendUsage(CommandSender sender, String label, String usage) { sender.sendMessage(configManager.getMessage("error.usage", "{label}", label, "{usage}", usage)); }
    private void sendNoPermission(CommandSender sender) { sender.sendMessage(configManager.getMessage("error.no_permission")); }
    private void sendPlayerOnly(CommandSender sender) { sender.sendMessage(configManager.getMessage("error.player_only")); }

    // --- Внутренний Класс ---
    private static final class TerritoryData { final String factionId; final String worldName; final List<Double> xCoords; final List<Double> zCoords; TerritoryData(String fId,String w,List<Double>x,List<Double>z){this.factionId=(fId!=null&&!fId.isEmpty())?fId.toLowerCase():null;this.worldName=Objects.requireNonNull(w);this.xCoords=(x!=null)?List.copyOf(x):List.of();this.zCoords=(z!=null)?List.copyOf(z):List.of();} }

} // Конец класса DynmapManager