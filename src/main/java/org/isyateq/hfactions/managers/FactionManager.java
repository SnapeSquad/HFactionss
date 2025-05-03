package org.isyateq.hfactions.managers;

// Bukkit API
import org.bukkit.Bukkit; // Для шедулера и OfflinePlayer
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

// Локальные классы
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.util.Utils; // Для сериализации склада

// Утилиты Java
import java.io.IOException; // Для сохранения файла
import java.util.*;
import java.util.concurrent.ConcurrentHashMap; // Потокобезопасная мапа
import java.util.logging.Level;

/**
 * Управляет фракциями: загрузка, сохранение, создание, удаление, доступ.
 * Отвечает за взаимодействие с factions.yml и автосохранение изменений.
 */
public final class FactionManager { // Делаем класс final

    private final HFactions plugin;
    private final ConfigManager configManager;
    // Зависимости от других менеджеров (инициализируются в конструкторе)
    private final PlayerManager playerManager;
    private final DatabaseManager databaseManager;
    private final DynmapManager dynmapManager; // Может быть null

    // Хранилище фракций (ID в lowercase -> Faction object)
    private final Map<String, Faction> factions = new ConcurrentHashMap<>();
    // Набор ID фракций, которые были изменены и требуют сохранения
    private final Set<String> modifiedFactions = ConcurrentHashMap.newKeySet();
    // ID задачи автосохранения (-1 если не запущена)
    private int saveTask = -1;

    /**
     * Конструктор FactionManager.
     * Получает зависимости от других менеджеров и запускает автосохранение.
     * @param plugin Экземпляр главного класса плагина.
     * @throws IllegalStateException если не удалось получить критические зависимости.
     */
    public FactionManager(HFactions plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin instance cannot be null");
        this.configManager = plugin.getConfigManager();
        this.playerManager = plugin.getPlayerManager();
        this.databaseManager = plugin.getDatabaseManager();
        this.dynmapManager = plugin.getDynmapManager(); // Может быть null

        // Проверка критических зависимостей
        if (this.configManager == null || this.playerManager == null || this.databaseManager == null) {
            throw new IllegalStateException("FactionManager could not get required dependencies (Config/Player/Database Manager)!");
        }

        scheduleAutoSave(); // Запускаем автосохранение при инициализации
    }

    // --- Загрузка Фракций ---
    public void loadFactions() {
        factions.clear(); modifiedFactions.clear();
        plugin.getLogger().info("Loading factions from factions.yml...");
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        if (factionsConfig == null) { plugin.getLogger().severe("Factions configuration is null!"); return; }
        ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");
        if (factionsSection == null) { plugin.getLogger().info("No 'factions' section found."); return; }
        int loadedCount = 0;
        for (String factionId : factionsSection.getKeys(false)) {
            ConfigurationSection data = factionsSection.getConfigurationSection(factionId); if (data == null) continue;
            String lowerCaseId = factionId.toLowerCase();
            try {
                String name = data.getString("name", "Unnamed[" + lowerCaseId + "]");
                FactionType type = FactionType.fromString(data.getString("type", "OTHER"));
                String color = data.getString("color", configManager.getDefaultFactionColor()); // Используем геттер
                String prefixFormat = data.getString("prefix", configManager.getDefaultFactionPrefixFormat()); // Используем геттер
                String prefix = prefixFormat.replace("{id_upper}", lowerCaseId.toUpperCase());
                double balance = data.getDouble("balance", 0.0);
                int warehouseSize = data.getInt("warehouse_size", configManager.getDefaultFactionWarehouseSize()); // Используем геттер
                Map<Integer, FactionRank> loadedRanks = loadRanksFromConfig(data.getConfigurationSection("ranks"), lowerCaseId);
                if (loadedRanks.isEmpty()) { plugin.getLogger().severe("Faction " + lowerCaseId + " skipped: No ranks loaded."); continue; }
                String warehouseBase64 = data.getString("warehouse_contents_base64");
                ItemStack[] loadedWarehouse = Utils.itemStackArrayFromBase64(warehouseBase64);
                Faction faction = new Faction(lowerCaseId, name, type, color, prefix, balance, warehouseSize, loadedRanks, loadedWarehouse);
                factions.put(lowerCaseId, faction); loadedCount++;
            } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Failed to load faction '" + lowerCaseId + "'", e); }
        }
        plugin.getLogger().info("Successfully loaded " + loadedCount + " factions.");
    }
    private Map<Integer, FactionRank> loadRanksFromConfig(ConfigurationSection ranksSection, String factionId) {
        Map<Integer, FactionRank> ranks = new ConcurrentHashMap<>();
        if (ranksSection == null) plugin.getLogger().warning("No 'ranks' section for " + factionId + ". Creating defaults.");
        else {
            for (String rankIdStr : ranksSection.getKeys(false)) {
                try {
                    int rankId = Integer.parseInt(rankIdStr); if (rankId < 1) continue;
                    ConfigurationSection d = ranksSection.getConfigurationSection(rankIdStr); if(d==null) continue;
                    String defName = d.getString("defaultName", "Rank " + rankId); String dispName = d.getString("displayName", defName);
                    double salary = d.getDouble("salary", 0.0); List<String> perms = d.getStringList("permissions");
                    ranks.put(rankId, new FactionRank(rankId, defName, dispName, salary, perms));
                } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Error loading rank '" + rankIdStr + "' for " + factionId, e); }
            }
        }
        if (!ranks.containsKey(1)) { plugin.getLogger().warning("Rank 1 missing for " + factionId + ", adding."); ranks.put(1, new FactionRank(1, "Recruit", "Recruit", 0.0, new ArrayList<>())); }
        if (!ranks.containsKey(11)) { plugin.getLogger().warning("Rank 11 missing for "+factionId+", adding."); ranks.put(11, new FactionRank(11, "Leader", "Leader", 0.0, getDefaultLeaderPerms()));}
        return ranks;
    }

    // --- Сохранение Фракций ---
    public synchronized void saveAllFactions() {
        stopAutoSaveTask(); plugin.getLogger().info("Performing final save of all faction data...");
        FileConfiguration conf = configManager.getFactionsConfig(); if (conf == null) {plugin.getLogger().severe("Cannot save factions, config is null!"); return;}
        conf.set("factions", null); ConfigurationSection sect = conf.createSection("factions"); int cnt = 0;
        if (!factions.isEmpty()) { for (Faction f : factions.values()) { try { saveSingleFactionData(f, sect); cnt++; } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Failed save faction " + f.getId(), e); } } }
        configManager.saveFactionsConfig(); modifiedFactions.clear();
        plugin.getLogger().info("Final save complete. Saved data for " + cnt + " factions.");
    }
    public synchronized void saveModifiedFactions() {
        if (modifiedFactions.isEmpty()) return;
        Set<String> toSave = new HashSet<>(modifiedFactions); modifiedFactions.removeAll(toSave);
        plugin.getLogger().info("Auto-saving data for " + toSave.size() + " modified factions...");
        FileConfiguration conf = configManager.getFactionsConfig(); if (conf == null) {plugin.getLogger().severe("Cannot auto-save factions, config is null!"); modifiedFactions.addAll(toSave); return; }
        ConfigurationSection sect = conf.getConfigurationSection("factions"); if (sect == null) sect = conf.createSection("factions");
        int saved = 0; boolean changed = false;
        for (String id : toSave) { Faction f = factions.get(id); if (f != null) { try { saveSingleFactionData(f, sect); saved++; changed = true; } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Failed auto-save "+id, e); } } else { if (sect.contains(id)) { sect.set(id, null); changed = true; plugin.getLogger().info("Removed orphaned faction "+id); } } }
        if (changed) { configManager.saveFactionsConfig(); plugin.getLogger().info("Auto-save complete. Saved " + saved + "."); }
    }
    private void scheduleAutoSave() {
        stopAutoSaveTask();
        // ***** ИСПОЛЬЗУЕМ ГЕТТЕР *****
        long interval = configManager.getFactionSaveIntervalTicks();
        if (interval <= 0) { plugin.getLogger().info("Faction auto-save disabled."); return; }
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveModifiedFactions, interval, interval).getTaskId();
        plugin.getLogger().info("Faction auto-save scheduled every " + (interval / 20L) + " seconds.");
    }
    private void stopAutoSaveTask() { if (saveTask != -1) { try { if (Bukkit.getScheduler().isCurrentlyRunning(saveTask)||Bukkit.getScheduler().isQueued(saveTask)) Bukkit.getScheduler().cancelTask(saveTask); } catch (Exception ignored) {} finally { saveTask = -1; plugin.getLogger().info("Faction auto-save task stopped."); } } }
    private void saveSingleFactionData(Faction f, ConfigurationSection s) { ConfigurationSection d=s.createSection(f.getId()); d.set("name",f.getName());d.set("type",f.getType().name());d.set("color",f.getColor());d.set("prefix",f.getPrefix());d.set("balance",f.getBalance());d.set("warehouse_size",f.getWarehouseSize());ConfigurationSection rS=d.createSection("ranks");for(Map.Entry<Integer,FactionRank>e:f.getRanks().entrySet()){ConfigurationSection rD=rS.createSection(String.valueOf(e.getKey()));FactionRank r=e.getValue();rD.set("defaultName",r.getDefaultName());if(!Objects.equals(r.getDisplayName(),r.getDefaultName()))rD.set("displayName",r.getDisplayName());else rD.set("displayName",null);rD.set("salary",r.getSalary());rD.set("permissions",r.getPermissions());}String whb64=Utils.itemStackArrayToBase64(f.getWarehouseContents());d.set("warehouse_contents_base64",whb64);}

    // --- Управление Фракциями ---
    public boolean createFaction(String id, String name, FactionType type, String color, String prefix, double balance, int warehouseSize) {
        Objects.requireNonNull(id); String lowerId=id.toLowerCase(); if(factions.containsKey(lowerId)){plugin.getLogger().warning("Faction ID '"+lowerId+"' exists.");return false;}
        Map<Integer, FactionRank> iR=new HashMap<>();iR.put(1,new FactionRank(1,"Recruit","Recruit",0.0,new ArrayList<>()));iR.put(11,new FactionRank(11,"Leader","Leader",0.0,getDefaultLeaderPerms()));
        Faction nF=new Faction(lowerId,name,type,color,prefix,balance,warehouseSize,iR); factions.put(lowerId,nF); markFactionAsModified(lowerId); saveModifiedFactions(); plugin.getLogger().info("Faction '"+name+"' (ID: "+lowerId+") created.");
        // ***** ИСПОЛЬЗУЕМ ГЕТТЕР *****
        if(dynmapManager != null && dynmapManager.isDynmapApiAvailable()){ plugin.getLogger().info("Need to implement Dynmap update for new faction"); /* dynmapManager.updateMapForFaction(nF); ? */ } return true;
    }
    public boolean deleteFaction(String id) {
        Objects.requireNonNull(id); String lowerId=id.toLowerCase(); Faction removedFaction=factions.remove(lowerId);
        if(removedFaction!=null){
            plugin.getLogger().info("Deleting faction '"+removedFaction.getName()+"'...");
            if (playerManager != null) playerManager.clearFactionDataFor(lowerId); else plugin.getLogger().warning("PlayerManager null during delete!");
            FileConfiguration fc=configManager.getFactionsConfig();ConfigurationSection fs=fc.getConfigurationSection("factions");boolean ch=false;if(fs!=null&&fs.contains(lowerId)){fs.set(lowerId,null);ch=true;}if(ch)configManager.saveFactionsConfig();
            modifiedFactions.remove(lowerId);
            // ***** ИСПОЛЬЗУЕМ ГЕТТЕР *****
            if (dynmapManager != null && dynmapManager.isDynmapApiAvailable()) {
                dynmapManager.removeTerritoriesForFaction(lowerId);
            }
            plugin.getLogger().info("Faction '"+removedFaction.getName()+"' deleted."); return true;
        } else { plugin.getLogger().warning("Faction ID '"+lowerId+"' not found."); return false; }
    }

    // --- Доступ к Данным ---
    public Faction getFaction(String id) { return id!=null ? factions.get(id.toLowerCase()) : null; }
    public Map<String, Faction> getAllFactions() { return Collections.unmodifiableMap(factions); }
    public void reloadFactions() {
        plugin.getLogger().info("Reloading faction data..."); loadFactions();
        if (playerManager != null) {
            playerManager.validatePlayerDataAfterReload(); // Вызываем существующий метод
        } else { plugin.getLogger().warning("PlayerManager null during reload!"); }
    }

    // --- Управление Рангами ---
    public void updateRankDisplayName(String factionId, int rankId, String newDisplayName) { Faction f=getFaction(factionId);if(f!=null){FactionRank r=f.getRank(rankId);if(r!=null){r.setDisplayName(newDisplayName);f.updateRank(r);plugin.getLogger().fine("Rank "+rankId+" name updated for "+factionId);}}}
    public void resetRankDisplayName(String factionId, int rankId) { updateRankDisplayName(factionId, rankId, null); }

    // --- Управление Балансом ---
    public void setFactionBalance(String factionId, double amount) { Faction f=getFaction(factionId);if(f!=null)f.setBalance(amount);}
    public boolean depositToFaction(String factionId, double amount) { if(amount<=0)return false; Faction f=getFaction(factionId);if(f!=null){f.deposit(amount);markFactionAsModified(factionId);return true;}return false;}
    public boolean withdrawFromFaction(String factionId, double amount) { if(amount<=0)return false; Faction f=getFaction(factionId);if(f!=null){boolean s=f.withdraw(amount);if(s)markFactionAsModified(factionId);return s;}return false;}

    // --- Управление Модификациями ---
    public void markFactionAsModified(String factionId) { if(factionId!=null){String lId=factionId.toLowerCase();if(factions.containsKey(lId)){if(modifiedFactions.add(lId)) plugin.getLogger().fine("Faction "+lId+" marked modified.");} else {plugin.getLogger().warning("Tried mark non-existent faction "+lId);}}}

    // --- Вспомогательные ---
    private List<String> getDefaultLeaderPerms() { return Arrays.asList("hfactions.faction.invite","hfactions.faction.kick","hfactions.faction.promote","hfactions.faction.demote","hfactions.faction.setrank","hfactions.faction.manage_ranks","hfactions.faction.balance.view","hfactions.faction.deposit","hfactions.faction.withdraw","hfactions.faction.manage_balance","hfactions.faction.warehouse.open","hfactions.faction.warehouse.deposit","hfactions.faction.warehouse.withdraw","hfactions.territory.manage.own"); }

    public synchronized  void saveFactionsSync() {
        plugin.getLogger().info("Performing synchronous save of all faction data...");
        stopAutoSaveTask();

        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        if (factionsConfig != null) {
            plugin.getLogger().severe("Cannot save factions synchronously, config is null!");
            return;
        }
        factionsConfig.set("factions", null);
        ConfigurationSection factionsSection = factionsConfig.createSection("factions");

        int savedCount = 0;
        if (!factions.isEmpty()) {
            // Итерируемся по текущим фракциям в памяти
            for (Faction faction : factions.values()) {
                try {
                    // Сохраняем данные каждой фракции
                    saveSingleFactionData(faction, factionsSection);
                    savedCount++;
                } catch (Exception e) {
                    // Логируем ошибку, но продолжаем сохранять остальные
                    plugin.getLogger().log(Level.SEVERE, "Failed to prepare data for synchronous save for faction " + faction.getId(), e);
                }
            }
        }
        try {
            factionsConfig.save(configManager.factionsConfigFile); // Используем прямой доступ к файлу
            modifiedFactions.clear(); // Очищаем флаги модификации после успешного сохранения
            plugin.getLogger().info("Synchronous save complete. Saved data for " + savedCount + " factions to factions.yml.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "CRITICAL ERROR during synchronous save of factions.yml!", e);
        }
    }
} // Конец класса FactionManager