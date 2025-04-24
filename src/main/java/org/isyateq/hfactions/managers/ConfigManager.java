package org.isyateq.hfactions.managers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.util.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public class ConfigManager {

    private final HFactions plugin;
    private FileConfiguration config = null;
    private File configFile = null;

    private FileConfiguration factionsConfig = null;
    private File factionsConfigFile = null;

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

    public String getMessage(String path, String s) {
        // TODO: Заменить на получение из lang.yml в будущем
        String message = getConfig().getString("messages." + path);
        // Возвращаем сообщение или заглушку, если не найдено
        return message != null ? Utils.color(message) : Utils.color("&cMessage not found: messages." + path);
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
}