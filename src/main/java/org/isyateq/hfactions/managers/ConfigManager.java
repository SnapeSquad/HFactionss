package org.isyateq.hfactions.managers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.util.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ConfigManager {

    private final HFactions plugin;
    private FileConfiguration config = null;
    private File configFile = null;

    private FileConfiguration factionsConfig = null;
    public File factionsConfigFile = null;

    // playerdata.yml больше не используется напрямую здесь
    // private FileConfiguration playerDataConfig = null;
    // private File playerDataConfigFile = null;

    private FileConfiguration territoriesConfig = null;
    private File territoriesConfigFile = null;

    // private FileConfiguration langConfig = null; // Для будущего lang.yml
    // private File langConfigFile = null;

    public ConfigManager(HFactions plugin) {
        this.plugin = plugin;
        // Загрузка происходит в loadConfigs()
    }

    // --- Загрузка всех конфигов ---
    public boolean loadConfigs() {
        // config.yml
        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        // Загрузка defaults из JAR, если нужно (опционально)
        loadDefaultsFromJar("config.yml", config);


        // factions.yml
        factionsConfigFile = new File(plugin.getDataFolder(), "factions.yml");
        if (!factionsConfigFile.exists()) {
            plugin.saveResource("factions.yml", false);
        }
        factionsConfig = YamlConfiguration.loadConfiguration(factionsConfigFile);
        loadDefaultsFromJar("factions.yml", factionsConfig);


        // territories.yml (создаем, если нет)
        territoriesConfigFile = new File(plugin.getDataFolder(), "territories.yml");
        if (!territoriesConfigFile.exists()) {
            try {
                // Создаем пустой файл с корневой секцией
                if (territoriesConfigFile.createNewFile()) {
                    plugin.getLogger().info("Created empty territories.yml.");
                    YamlConfiguration tempConfig = new YamlConfiguration();
                    tempConfig.createSection("territories");
                    tempConfig.save(territoriesConfigFile);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create territories.yml!", e);
            }
        }
        // Загружаем даже если только что создали (будет пустой или с секцией territories)
        territoriesConfig = YamlConfiguration.loadConfiguration(territoriesConfigFile);


        // lang.yml (в будущем)
        plugin.getLogger().info("Configurations loaded.");
        return false;
    }

    // --- Перезагрузка конфигов ---
    public void reloadConfigs() {
        plugin.getLogger().info("Reloading configurations...");
        // Перезагружаем config.yml
        if (configFile != null) {
            config = YamlConfiguration.loadConfiguration(configFile);
            loadDefaultsFromJar("config.yml", config); // Перезагрузка defaults
        } else {
            plugin.getLogger().warning("config.yml file object is null, cannot reload.");
        }

        // Перезагружаем factions.yml
        if (factionsConfigFile != null) {
            factionsConfig = YamlConfiguration.loadConfiguration(factionsConfigFile);
            loadDefaultsFromJar("factions.yml", factionsConfig);
        } else {
            plugin.getLogger().warning("factions.yml file object is null, cannot reload.");
        }

        // Перезагружаем territories.yml
        if (territoriesConfigFile != null) {
            territoriesConfig = YamlConfiguration.loadConfiguration(territoriesConfigFile);
        } else {
            plugin.getLogger().warning("territories.yml file object is null, cannot reload.");
        }

        // Перезагрузка lang.yml (в будущем)
        plugin.getLogger().info("Configurations reloaded.");
    }

    // --- Сохранение конфигов ---

    // Сохраняем factions.yml
    public void saveFactionsConfig() {
        if (factionsConfig == null || factionsConfigFile == null) {
            plugin.getLogger().severe("Cannot save factions.yml: config or file object is null.");
            return;
        }
        try {
            factionsConfig.save(factionsConfigFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config to " + factionsConfigFile, ex);
        }
    }

    // Сохраняем territories.yml (вызывается из DynmapManager)
    public void saveTerritoriesConfig() {
        if (territoriesConfig == null || territoriesConfigFile == null) {
            plugin.getLogger().severe("Cannot save territories.yml: config or file object is null.");
            return;
        }
        try {
            territoriesConfig.save(territoriesConfigFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config to " + territoriesConfigFile, ex);
        }
    }

    // --- Методы доступа к конфигурациям ---
    public FileConfiguration getConfig() {
        if (config == null) {
            plugin.getLogger().warning("getConfig() called but config is null, attempting to reload...");
            reloadConfigs(); // Попытка перезагрузить
            if (config == null) { // Если все еще null после перезагрузки
                plugin.getLogger().severe("Failed to load config.yml even after reload attempt!");
                // Возвращаем пустой конфиг, чтобы избежать NPE, но это плохой знак
                return new YamlConfiguration();
            }
        }
        return config;
    }

    public FileConfiguration getFactionsConfig() {
        if (factionsConfig == null) {
            plugin.getLogger().warning("getFactionsConfig() called but factionsConfig is null, attempting to reload...");
            reloadConfigs();
            if (factionsConfig == null) {
                plugin.getLogger().severe("Failed to load factions.yml even after reload attempt!");
                return new YamlConfiguration();
            }
        }
        return factionsConfig;
    }

    public FileConfiguration getTerritoriesConfig() {
        if (territoriesConfig == null) {
            plugin.getLogger().warning("getTerritoriesConfig() called but territoriesConfig is null, attempting to reload...");
            reloadConfigs();
            if (territoriesConfig == null) {
                plugin.getLogger().severe("Failed to load territories.yml even after reload attempt!");
                return new YamlConfiguration();
            }
        }
        return territoriesConfig;
    }
    public long getFactionSaveIntervalTicks() { return Math.max(20L * 10, getConfig().getLong("faction.auto_save_interval_seconds", 300) * 20L); }
    public String getDefaultFactionColor() { return getConfig().getString("faction.defaults.color", "#FFFFFF"); }
    public String getDefaultFactionPrefixFormat() { return getConfig().getString("faction.defaults.prefix", "[{id_upper}]"); }
    public int getDefaultFactionWarehouseSize() { return getConfig().getInt("faction.defaults.warehouse_size", 54); }

    // --- Специализированные геттеры для настроек ---

    /**
     * Проверяет, включена ли поддержка Oraxen в конфигурации.
     * Путь в config.yml: integrations.oraxen.enabled
     * @return true, если включено, иначе false.
     */
    public boolean isOraxenSupportEnabled() {
        // Получаем основной конфиг
        FileConfiguration mainConfig = getConfig();
        // Получаем значение по указанному пути, с дефолтным значением false
        // Метод getBoolean() вернет false, если путь не найден или значение не boolean
        return mainConfig.getBoolean("integrations.oraxen.enabled", false);
    }


    public boolean isDebugModeEnabled() {
        return getConfig().getBoolean("general.debug_mode", false);
    }

    // Вспомогательный метод для загрузки defaults из JAR
    private void loadDefaultsFromJar(String filename, FileConfiguration targetConfig) {
        if (targetConfig == null) return; // Проверка на null

        InputStream defConfigStream = plugin.getResource(filename);
        if (defConfigStream != null) {
            try (InputStreamReader reader = new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(reader);
                targetConfig.setDefaults(defConfig);
                // Не используем copyDefaults(true), чтобы не перезаписывать пользовательские секции,
                // которых нет в defaults. Вместо этого полагаемся на getX(path, default).
                // targetConfig.options().copyDefaults(true);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE,"Could not load default configuration from JAR: " + filename, e);
            }
        } else {
            plugin.getLogger().warning("Default configuration file not found in JAR: " + filename);
        }
    }
    // --- Faction Chat ---
    public String getFactionChatFormat() { return getConfig().getString("faction_chat.format", "&b[Фракция] {prefix} {player}&f: {message}"); }

    // --- Item Settings (Вспомогательные) ---
    private String getItemName(String path, String def) { return Utils.color(getConfig().getString(path + ".name", def)); }
    private List<String> getItemLore(String path) { return getConfig().getStringList(path + ".lore").stream().map(Utils::color).collect(Collectors.toList()); }
    private String getItemMaterial(String path, String def) { return getConfig().getString(path + ".material", def).toUpperCase(); }
    private int getItemModelData(String path) { return getConfig().getInt(path + ".custom_model_data", 0); }
    private String getItemOraxenId(String path) { return getConfig().getString(path + ".oraxen_id"); }

    // --- Тайзер ---
    public String getTaserOraxenId() { return getItemOraxenId("mechanics.taser"); }
    public String getTaserName() { return getItemName("mechanics.taser", "&eTaser"); }
    public List<String> getTaserLore() { return getItemLore("mechanics.taser"); }
    public String getTaserMaterial() { return getItemMaterial("mechanics.taser", "STICK"); }
    public int getTaserCustomModelData() { return getItemModelData("mechanics.taser"); }
    public double getTaserRange() { return Math.max(1.0, getConfig().getDouble("mechanics.taser.range", 10.0)); }
    public int getTaserCooldownSeconds() { return Math.max(0, getConfig().getInt("mechanics.taser.cooldown_seconds", 5)); }

    // --- Наручники ---
    public String getHandcuffsOraxenId() { return getItemOraxenId("mechanics.handcuffs"); }
    public String getHandcuffsName() { return getItemName("mechanics.handcuffs", "&7Handcuffs"); }
    public List<String> getHandcuffsLore() { return getItemLore("mechanics.handcuffs"); }
    public String getHandcuffsMaterial() { return getItemMaterial("mechanics.handcuffs", "IRON_INGOT"); }
    public int getHandcuffsCustomModelData() { return getItemModelData("mechanics.handcuffs"); }

    // --- Штрафы ---
    public String getProtocolOraxenId() { return getItemOraxenId("mechanics.fining.protocol_item"); }
    public String getProtocolName() { return getItemName("mechanics.fining.protocol_item", "&cProtocol"); }
    public List<String> getProtocolLore() { return getItemLore("mechanics.fining.protocol_item"); }
    public String getProtocolMaterial() { return getItemMaterial("mechanics.fining.protocol_item", "PAPER"); }
    public int getProtocolCustomModelData() { return getItemModelData("mechanics.fining.protocol_item"); }
    public boolean useProtocolItem() { return getConfig().getBoolean("mechanics.fining.use_protocol_item", true); }
    public String getProtocolFactionId() { return getConfig().getString("mechanics.fining.protocol_item_faction", "pd"); }
    public int getFineMinRank() { return getConfig().getInt("mechanics.fining.fine_min_rank", 2); }
    public double getFineMinAmount() { return Math.max(0.01, getConfig().getDouble("mechanics.fining.min_amount", 1.0)); }
    public double getFineMaxAmount() { return Math.max(getFineMinAmount(), getConfig().getDouble("mechanics.fining.max_amount", 10000.0)); }
    public int getFineCooldownTargetSeconds() { return Math.max(0, getConfig().getInt("mechanics.fining.fine_cooldown_target_seconds", 60)); }
    public String getFineRecipientType() { return getConfig().getString("mechanics.fining.fine_recipient", "player").toLowerCase(); }
    public int getFineMaxReasonLength() { return Math.max(1, getConfig().getInt("mechanics.fining.max_reason_length", 100)); }

    // --- Крафт ---
    public boolean isCraftingEnabled() { return getConfig().getBoolean("crafting.enabled", false); }
    public ConfigurationSection getCraftingRecipesSection() { return getConfig().getConfigurationSection("crafting.recipes"); }

    /**
     * Проверяет, включена ли интеграция с Dynmap в конфигурации.
     * @return true, если включена.
     */
    public boolean isDynmapEnabled() { // ***** ДОБАВЛЕН/ПРОВЕРЕН МЕТОД *****
        return getConfig().getBoolean("integrations.dynmap.enabled", true); // По умолчанию включено
    }
    public double getDynmapMarkerOpacity() { return Math.max(0.0, Math.min(1.0, getConfig().getDouble("integrations.dynmap.style.fill_opacity", 0.3))); }
    public int getDynmapMarkerWeight() { return Math.max(1, getConfig().getInt("integrations.dynmap.style.stroke_weight", 1)); }
    public double getDynmapMarkerStrokeOpacity() { return Math.max(0.0, Math.min(1.0, getConfig().getDouble("integrations.dynmap.style.stroke_opacity", 0.8))); }
    public String getDynmapMarkerFormat() { return getConfig().getString("integrations.dynmap.style.popup_format", "<b>{faction_name}</b> ({faction_id})<br/>Type: {faction_type}"); }
    public long getDynmapUpdateIntervalSeconds() { return Math.max(30, getConfig().getLong("integrations.dynmap.update_interval_seconds", 300)); }

    // --- Наркотики (Заглушки Геттеров) ---
    public boolean isDrugsEnabled() { return getConfig().getBoolean("mechanics.drugs.enabled", false); }

    // --- Квесты (Заглушки Геттеров) ---
    public boolean isQuestsEnabled() { return getConfig().getBoolean("quests.enabled", false); }

    // --- Сообщения ---
    public String getMessage(String path, String defaultMessage, String label, String s, String usage) { String msg = getConfig().getString("messages." + path, defaultMessage); return Utils.color(msg); }
    public String getMessage(String path) { return getMessage(path, "&cMissing: messages." + path, label, "{usage}", usage); }
    public List<String> getMessageList(String path) { List<String> msgs = getConfig().getStringList("messages." + path); if (msgs.isEmpty()) return Collections.singletonList(getMessage(path+".<entry>")); return msgs.stream().map(Utils::color).collect(Collectors.toList()); }

}