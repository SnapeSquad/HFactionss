package org.isyateq.hfactions.managers;

// Bukkit API
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

// Локальные классы
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.util.Utils;

// Утилиты Java
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Управляет загрузкой, доступом и сохранением конфигурационных файлов плагина.
 */
public final class ConfigManager {

    private final HFactions plugin;
    File configFile;
    File factionsConfigFile;
    File territoriesConfigFile;
    private FileConfiguration config;
    private FileConfiguration factionsConfig;
    private FileConfiguration territoriesConfig;

    /** Конструктор */
    public ConfigManager(HFactions plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin instance cannot be null");
    }

    /** Загрузка конфигов */
    public boolean loadConfigs() {
        boolean success = true;
        try {
            plugin.getLogger().info("Loading config.yml..."); configFile = loadOrCreate("config.yml"); config = YamlConfiguration.loadConfiguration(configFile); loadDefaultsFromJar("config.yml", config);
            plugin.getLogger().info("Loading factions.yml..."); factionsConfigFile = loadOrCreate("factions.yml"); factionsConfig = YamlConfiguration.loadConfiguration(factionsConfigFile);
            plugin.getLogger().info("Loading territories.yml..."); territoriesConfigFile = loadOrCreate("territories.yml"); territoriesConfig = YamlConfiguration.loadConfiguration(territoriesConfigFile);
            plugin.getLogger().info("Configurations finished loading.");
        } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Fatal error loading configs!", e); success = false; }
        return success;
    }

    /**
     * Перезагрузка конфигов
     */
    public void reloadConfigs() {
        plugin.getLogger().info("Reloading configurations...");
        FactionManager fm = plugin.getFactionManager(); if (fm != null) fm.saveModifiedFactions();
        try {
            config = reloadSingleConfig(configFile, "config.yml", true);
            factionsConfig = reloadSingleConfig(factionsConfigFile, "factions.yml", false);
            territoriesConfig = reloadSingleConfig(territoriesConfigFile, "territories.yml", false);
            if (fm != null) fm.reloadFactions();
            CraftingManager cm = plugin.getCraftingManager(); if (cm != null) { if (isCraftingEnabled()) cm.loadRecipes(); else cm.clearRecipes(); }
            DynmapManager dm = plugin.getDynmapManager(); if (dm != null) { if (isDynmapEnabled() && dm.isDynmapApiAvailable()) { dm.reloadTerritories(); } else if (!isDynmapEnabled() && dm.isDynmapApiAvailable()){ dm.clearAllMarkers(); } }
            plugin.getLogger().info("Configurations reloaded successfully.");
        } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Error reloading configs!", e);
        }
    }

    /** Перезагрузка одного файла */
    private FileConfiguration reloadSingleConfig(File currentFile, String filename, boolean loadDefaults) {
        File fileToLoad = currentFile != null ? currentFile : new File(plugin.getDataFolder(), filename); if (!fileToLoad.exists()) fileToLoad = loadOrCreate(filename);
        FileConfiguration loadedConfig = YamlConfiguration.loadConfiguration(fileToLoad); if (loadDefaults) loadDefaultsFromJar(filename, loadedConfig);
        switch (filename) {
            case "config.yml" -> configFile = fileToLoad;
            case "factions.yml" -> factionsConfigFile = fileToLoad;
            case "territories.yml" -> territoriesConfigFile = fileToLoad;
        }
        return loadedConfig;
    }

    // --- Методы Сохранения ---
    public void saveFactionsConfig() { saveConfig(factionsConfig, factionsConfigFile, "factions.yml"); }
    public void saveTerritoriesConfig() { saveConfig(territoriesConfig, territoriesConfigFile, "territories.yml"); }
    private void saveConfig(FileConfiguration cfg, File file, String fileName) { if(cfg==null||file==null)return;try{cfg.save(file);}catch(IOException ex){plugin.getLogger().log(Level.SEVERE,"Could not save "+fileName, ex);}}

    // --- Геттеры Конфигов ---
    public FileConfiguration getConfig() { if(config==null)loadConfigs(); return config;}
    public FileConfiguration getFactionsConfig() { if(factionsConfig==null)loadConfigs(); return factionsConfig;}
    public FileConfiguration getTerritoriesConfig() { if(territoriesConfig==null)loadConfigs(); return territoriesConfig;}

    // --- Вспомогательные Загрузки ---
    private File loadOrCreate(String filename) { File file=new File(plugin.getDataFolder(),filename);if(!file.exists()){if(!plugin.getDataFolder().exists())plugin.getDataFolder().mkdirs();try{plugin.saveResource(filename,false);}catch(IllegalArgumentException e){/*ok*/}catch(Exception e){plugin.getLogger().log(Level.SEVERE,"Could not save resource "+filename,e);}}return file;}
    private void loadDefaultsFromJar(String filename, FileConfiguration targetConfig) { try(InputStream s=plugin.getResource(filename)){if(s!=null){try(InputStreamReader r=new InputStreamReader(s,StandardCharsets.UTF_8)){YamlConfiguration d=YamlConfiguration.loadConfiguration(r);boolean changed=!targetConfig.getKeys(false).containsAll(d.getKeys(false));targetConfig.setDefaults(d);targetConfig.options().copyDefaults(true);if(changed){File f=new File(plugin.getDataFolder(),filename);saveConfig(targetConfig,f,filename);}}}else{plugin.getLogger().warning("Default "+filename+" not found.");}}catch(IOException e){plugin.getLogger().log(Level.SEVERE,"Error loading defaults "+filename,e);}}

    // ==================================================
    // --- ГЕТТЕРЫ НАСТРОЕК из config.yml ---
    // ==================================================
    public boolean isDebugModeEnabled() { return getConfig().getBoolean("general.debug_mode", false); }
    public String getLanguage() { return getConfig().getString("general.language", "en"); }
    public long getInviteExpireTicks() { return getConfig().getLong("faction.invite_expire_seconds", 60) * 20L; }
    public long getFactionSaveIntervalTicks() { return Math.max(200L, getConfig().getLong("faction.auto_save_interval_seconds", 300) * 20L); }
    public String getDefaultFactionColor() { return getConfig().getString("faction.defaults.color", "#FFFFFF"); }
    public String getDefaultFactionPrefixFormat() { return getConfig().getString("faction.defaults.prefix", "[{id_upper}]"); }
    public double getDefaultFactionBalance() { return getConfig().getDouble("faction.defaults.balance", 0.0); }
    public int getDefaultFactionWarehouseSize() { return getConfig().getInt("faction.defaults.warehouse_size", 54); }
    public boolean isPaydayEnabled() { return getConfig().getBoolean("payday.enabled", true); }
    public long getPaydayIntervalTicks() { return Math.max(1200L, getConfig().getLong("payday.interval_minutes", 60) * 60 * 20L); }
    public boolean isPaydayRequireOnline() { return getConfig().getBoolean("payday.require_online", true); }
    public int getPaydayMinPlaytimeMinutes() { return getConfig().getInt("payday.min_playtime_minutes", 0); }
    public String getFactionChatFormat() { return getConfig().getString("faction_chat.format", "&b[Фракция] {prefix} {player}&f: {message}"); }
    private String getItemName(String path, String def) { return Utils.color(getConfig().getString(path + ".name", def)); }
    private List<String> getItemLore(String path) { return getConfig().getStringList(path + ".lore").stream().map(Utils::color).collect(Collectors.toList()); }
    private String getItemMaterial(String path, String def) { return getConfig().getString(path + ".material", def).toUpperCase(); }
    private int getItemModelData(String path) { return getConfig().getInt(path + ".custom_model_data", 0); }
    private String getItemOraxenId(String path) { return getConfig().getString(path + ".oraxen_id"); }
    public String getTaserOraxenId() { return getItemOraxenId("mechanics.taser"); } public String getTaserName() { return getItemName("mechanics.taser", "&eTaser"); } public List<String> getTaserLore() { return getItemLore("mechanics.taser"); } public String getTaserMaterial() { return getItemMaterial("mechanics.taser", "STICK"); } public int getTaserCustomModelData() { return getItemModelData("mechanics.taser"); } public double getTaserRange() { return Math.max(1.0, getConfig().getDouble("mechanics.taser.range", 10.0)); } public int getTaserCooldownSeconds() { return Math.max(0, getConfig().getInt("mechanics.taser.cooldown_seconds", 5)); } public boolean canTaseSameFaction() { return getConfig().getBoolean("mechanics.taser.can_tase_same_faction", false); } public boolean canTaseOtherState() { return getConfig().getBoolean("mechanics.taser.can_tase_other_state", true); } public List<? extends ConfigurationSection> getTaserEffects() { ConfigurationSection cs = getConfig().getConfigurationSection("mechanics.taser.effects"); return (cs == null) ? Collections.emptyList() : cs.getKeys(false).stream().map(cs::getConfigurationSection).filter(Objects::nonNull).collect(Collectors.toList()); } public String getTaserSound() { return getConfig().getString("mechanics.taser.sound"); } public String getTaserParticle() { return getConfig().getString("mechanics.taser.particle"); }
    public String getHandcuffsOraxenId() { return getItemOraxenId("mechanics.handcuffs"); } public String getHandcuffsName() { return getItemName("mechanics.handcuffs", "&7Handcuffs"); } public List<String> getHandcuffsLore() { return getItemLore("mechanics.handcuffs"); } public String getHandcuffsMaterial() { return getItemMaterial("mechanics.handcuffs", "IRON_INGOT"); } public int getHandcuffsCustomModelData() { return getItemModelData("mechanics.handcuffs"); } public List<? extends ConfigurationSection> getHandcuffsEffects() { ConfigurationSection cs = getConfig().getConfigurationSection("mechanics.handcuffs.effects"); return (cs == null) ? Collections.emptyList() : cs.getKeys(false).stream().map(cs::getConfigurationSection).filter(Objects::nonNull).collect(Collectors.toList()); } public boolean canCuffSameFaction() { return getConfig().getBoolean("mechanics.handcuffs.can_cuff_same_faction", false); } public boolean canCuffOtherState() { return getConfig().getBoolean("mechanics.handcuffs.can_cuff_other_state", true); } public boolean preventHandcuffInteraction() { return getConfig().getBoolean("mechanics.handcuffs.prevent_interaction.enabled", true); } public Set<String> getPreventedCommands() { return getConfig().getStringList("mechanics.handcuffs.prevent_interaction.prevented_commands").stream().map(String::toLowerCase).map(cmd -> cmd.startsWith("/") ? cmd.substring(1) : cmd).collect(Collectors.toSet()); } public boolean preventHandcuffDamage() { return getConfig().getBoolean("mechanics.handcuffs.prevent_interaction.prevent_damage", true); } public boolean preventHandcuffItemDrop() { return getConfig().getBoolean("mechanics.handcuffs.prevent_interaction.prevent_item_drop", true); } public boolean preventHandcuffInventoryClick() { return getConfig().getBoolean("mechanics.handcuffs.prevent_interaction.prevent_inventory_click", true); } public boolean isLeashEnabled() { return getConfig().getBoolean("mechanics.handcuffs.leash.enabled", true); } public double getLeashDistance() { return Math.max(1.0, getConfig().getDouble("mechanics.handcuffs.leash.distance", 10.0)); } public long getLeashCheckIntervalTicks() { return Math.max(10L, getConfig().getLong("mechanics.handcuffs.leash.check_interval_ticks", 20L)); } public String getHandcuffSoundApply() { return getConfig().getString("mechanics.handcuffs.sound_apply"); } public String getHandcuffSoundRemove() { return getConfig().getString("mechanics.handcuffs.sound_remove"); }
    public String getProtocolOraxenId() { return getItemOraxenId("mechanics.fining.protocol_item"); } public String getProtocolName() { return getItemName("mechanics.fining.protocol_item", "&cProtocol"); } public List<String> getProtocolLore() { return getItemLore("mechanics.fining.protocol_item"); } public String getProtocolMaterial() { return getItemMaterial("mechanics.fining.protocol_item", "PAPER"); } public int getProtocolCustomModelData() { return getItemModelData("mechanics.fining.protocol_item"); } public boolean useProtocolItem() { return getConfig().getBoolean("mechanics.fining.use_protocol_item", true); } public String getProtocolFactionId() { return getConfig().getString("mechanics.fining.protocol_item_faction", "pd"); } public int getFineMinRank() { return getConfig().getInt("mechanics.fining.fine_min_rank", 2); } public double getFineMinAmount() { return Math.max(0.01, getConfig().getDouble("mechanics.fining.min_amount", 1.0)); } public double getFineMaxAmount() { return Math.max(getFineMinAmount(), getConfig().getDouble("mechanics.fining.max_amount", 10000.0)); } public int getFineCooldownTargetSeconds() { return Math.max(0, getConfig().getInt("mechanics.fining.fine_cooldown_target_seconds", 60)); } public String getFineRecipientType() { return getConfig().getString("mechanics.fining.fine_recipient", "player").toLowerCase(); } public int getFineMaxReasonLength() { return Math.max(1, getConfig().getInt("mechanics.fining.max_reason_length", 100)); }
    public boolean isCraftingEnabled() { return getConfig().getBoolean("crafting.enabled", false); } public ConfigurationSection getCraftingRecipesSection() { return getConfig().getConfigurationSection("crafting.recipes"); }
    public boolean isOraxenSupportEnabled() { return getConfig().getBoolean("integrations.oraxen.enabled", false); } public boolean isDynmapEnabled() { return getConfig().getBoolean("integrations.dynmap.enabled", true); } public double getDynmapMarkerOpacity() { return Math.max(0.0, Math.min(1.0, getConfig().getDouble("integrations.dynmap.style.fill_opacity", 0.3))); } public int getDynmapMarkerWeight() { return Math.max(1, getConfig().getInt("integrations.dynmap.style.stroke_weight", 1)); } public double getDynmapMarkerStrokeOpacity() { return Math.max(0.0, Math.min(1.0, getConfig().getDouble("integrations.dynmap.style.stroke_opacity", 0.8))); } public String getDynmapMarkerFormat() { return getConfig().getString("integrations.dynmap.style.popup_format", "<b>{faction_name}</b> ({faction_id})<br/>Type: {faction_type}"); } public long getDynmapUpdateIntervalSeconds() { return Math.max(30, getConfig().getLong("integrations.dynmap.update_interval_seconds", 300)); }
    public boolean isDrugsEnabled() { return getConfig().getBoolean("mechanics.drugs.enabled", false); } public boolean isQuestsEnabled() { return getConfig().getBoolean("quests.enabled", false); }

    // --- Сообщения ---
    /**
     * Получает форматированное сообщение из конфига.
     * @param path Путь к сообщению (начиная с 'messages.').
     * @param defaultMessage Значение по умолчанию.
     * @return Отформатированное сообщение или отформатированное значение по умолчанию.
     */
    public String getMessage(String path, String defaultMessage) {
        String message = getConfig().getString("messages." + path, defaultMessage);
        // Проверка на null перед форматированием
        return Utils.color(message != null ? message : defaultMessage);
    }
    /**
     * Получает форматированное сообщение из конфига, используя ключ как дефолтное, если не найдено.
     * @param path Путь к сообщению.
     * @return Отформатированное сообщение.
     */
    public String getMessage(String path) {
        // Убраны лишние аргументы label и usage
        return getMessage(path, "&cMissing message: messages." + path);
    }
    /**
     * Получает форматированный список сообщений.
     * @param path Путь к списку.
     * @return Список отформатированных строк.
     */
    public List<String> getMessageList(String path) {
        List<String> messages = getConfig().getStringList("messages." + path);
        if (messages.isEmpty()) {
            // Возвращаем список с одним сообщением об ошибке
            return Collections.singletonList(getMessage(path + ".<entry>", "&cMissing message list: messages." + path));
        }
        return messages.stream().map(Utils::color).collect(Collectors.toList());
    }

} // Конец класса ConfigManager