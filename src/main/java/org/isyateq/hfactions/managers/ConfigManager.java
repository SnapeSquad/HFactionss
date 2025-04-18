package org.isyateq.hfactions.managers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.util.Utils; // Убедимся, что Utils импортирован для getMessage

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level; // Импортируем Level

public class ConfigManager {

    private final HFactions plugin;
    private FileConfiguration config = null;
    private File configFile = null;

    private FileConfiguration factionsConfig = null;
    private File factionsConfigFile = null;

    // PlayerData больше не управляется этим менеджером
    // private FileConfiguration playerDataConfig = null;
    // private File playerDataConfigFile = null;

    private FileConfiguration territoriesConfig = null;
    private File territoriesConfigFile = null;

    // private FileConfiguration langConfig = null; // Для будущего lang.yml
    // private File langConfigFile = null;

    public ConfigManager(HFactions plugin) {
        this.plugin = plugin;
        // Не вызываем loadConfigs здесь, это делает HFactions.onEnable()
    }

    // --- Загрузка всех конфигов ---
    public void loadConfigs() {
        // --- config.yml ---
        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.getLogger().info("config.yml not found, creating from resources..."); // Используем info()
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        plugin.getLogger().info("Loaded config.yml");

        // --- factions.yml ---
        factionsConfigFile = new File(plugin.getDataFolder(), "factions.yml");
        if (!factionsConfigFile.exists()) {
            plugin.getLogger().info("factions.yml not found, creating from resources..."); // Используем info()
            plugin.saveResource("factions.yml", false);
        }
        factionsConfig = YamlConfiguration.loadConfiguration(factionsConfigFile);
        plugin.getLogger().info("Loaded factions.yml");

        // --- territories.yml ---
        territoriesConfigFile = new File(plugin.getDataFolder(), "territories.yml");
        if (!territoriesConfigFile.exists()) {
            plugin.getLogger().info("territories.yml not found, creating empty file..."); // Используем info()
            try {
                // Создаем папку, если ее нет
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }
                if (territoriesConfigFile.createNewFile()) {
                    // Можно добавить базовую структуру
                    YamlConfiguration tempConfig = YamlConfiguration.loadConfiguration(territoriesConfigFile);
                    tempConfig.createSection("territories");
                    tempConfig.save(territoriesConfigFile);
                    plugin.getLogger().info("Created empty territories.yml.");
                }
            } catch (IOException e) {
                // Используем log() с уровнем SEVERE и исключением
                plugin.getLogger().log(Level.SEVERE, "Could not create territories.yml!", e);
            }
        }
        territoriesConfig = YamlConfiguration.loadConfiguration(territoriesConfigFile);
        plugin.getLogger().info("Loaded territories.yml");


        // Загрузка lang.yml (в будущем)
    }

    // --- Перезагрузка конфигов ---
    public void reloadConfigs() {
        // config.yml
        if (configFile != null) {
            config = YamlConfiguration.loadConfiguration(configFile);
            plugin.getLogger().info("Reloaded config.yml");
        } else {
            plugin.getLogger().warning("Cannot reload config.yml: file object is null."); // Используем warning()
        }

        // factions.yml
        if (factionsConfigFile != null) {
            factionsConfig = YamlConfiguration.loadConfiguration(factionsConfigFile);
            plugin.getLogger().info("Reloaded factions.yml");
        } else {
            plugin.getLogger().warning("Cannot reload factions.yml: file object is null."); // Используем warning()
        }

        // territories.yml
        if (territoriesConfigFile != null) {
            territoriesConfig = YamlConfiguration.loadConfiguration(territoriesConfigFile);
            plugin.getLogger().info("Reloaded territories.yml");
        } else {
            plugin.getLogger().warning("Cannot reload territories.yml: file object is null."); // Используем warning()
        }

        // Перезагрузка lang.yml (в будущем)
        plugin.getLogger().info("Configurations reloaded.");
    }

    // --- Сохранение конфигов (только для тех, что могут меняться программно) ---

    public void saveFactionsConfig() { // Этот метод нужен FactionManager'у
        if (factionsConfig == null || factionsConfigFile == null) {
            plugin.getLogger().severe("Cannot save factions.yml: config object or file object is null."); // Используем severe()
            return;
        }
        try {
            factionsConfig.save(factionsConfigFile);
            plugin.getLogger().fine("Saved factions.yml"); // Используем fine() для менее важных сообщений
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config to " + factionsConfigFile.getName(), ex); // Используем log()
        }
    }

    public void saveTerritoriesConfig() { // Этот метод нужен DynmapManager'у
        if (territoriesConfig == null || territoriesConfigFile == null) {
            plugin.getLogger().severe("Cannot save territories.yml: config object or file object is null."); // Используем severe()
            return;
        }
        try {
            territoriesConfig.save(territoriesConfigFile);
            plugin.getLogger().fine("Saved territories.yml"); // Используем fine()
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config to " + territoriesConfigFile.getName(), ex); // Используем log()
        }
    }

    // --- Методы доступа к конфигурациям ---
    public FileConfiguration getConfig() {
        if (config == null) {
            plugin.getLogger().warning("config.yml was requested but is null. Attempting to load..."); // Используем warning()
            loadConfigs(); // Попытка перезагрузить (может помочь после /reload с ошибкой)
            if (config == null) {
                plugin.getLogger().severe("Failed to load config.yml even after reload attempt!"); // Используем severe()
                // Возвращаем пустой конфиг, чтобы избежать NPE дальше?
                // return new YamlConfiguration(); // Опасно, т.к. будут значения по умолчанию
            }
        }
        return config;
    }

    public FileConfiguration getFactionsConfig() {
        if (factionsConfig == null) {
            plugin.getLogger().warning("factions.yml was requested but is null. Attempting to load..."); // Используем warning()
            loadConfigs();
            if (factionsConfig == null) {
                plugin.getLogger().severe("Failed to load factions.yml even after reload attempt!"); // Используем severe()
            }
        }
        return factionsConfig;
    }

    public FileConfiguration getTerritoriesConfig() {
        if (territoriesConfig == null) {
            plugin.getLogger().warning("territories.yml was requested but is null. Attempting to load..."); // Используем warning()
            loadConfigs();
            if (territoriesConfig == null) {
                plugin.getLogger().severe("Failed to load territories.yml even after reload attempt!"); // Используем severe()
            }
        }
        return territoriesConfig;
    }

    // --- Методы для получения конкретных настроек ---

    public boolean isDebugModeEnabled() {
        // Получаем конфиг через геттер для проверки на null
        FileConfiguration currentConfig = getConfig();
        return currentConfig != null && currentConfig.getBoolean("general.debug_mode", false);
    }

    // Убираем isOraxenSupportEnabled, т.к. проверка теперь в OraxenIntegration.isEnabled()

    /**
     * Получает сообщение из config.yml (временно, пока нет lang.yml).
     * @param path Путь к сообщению в секции 'messages'.
     * @param defaultMessage Значение по умолчанию, если сообщение не найдено.
     * @return Отформатированное сообщение или defaultMessage.
     */
    public String getMessage(String path, String defaultMessage) {
        FileConfiguration currentConfig = getConfig();
        String rawMessage = defaultMessage; // По умолчанию
        if (currentConfig != null) {
            rawMessage = currentConfig.getString("messages." + path, defaultMessage);
        } else {
            plugin.getLogger().warning("Attempted to get message '" + path + "' but config is null."); // Используем warning()
        }
        // Используем Utils.color для форматирования
        return Utils.color(rawMessage);
    }

    /**
     * Получает сообщение из config.yml с стандартным сообщением об ошибке.
     * @param path Путь к сообщению в секции 'messages'.
     * @return Отформатированное сообщение.
     */
    public String getMessage(String path) {
        return getMessage(path, "&cMessage not found: messages." + path);
    }


    // Вспомогательный метод для загрузки defaults из JAR (если нужно)
    // Этот метод обычно не нужен, если используется saveResource()
    /*
    private void loadDefaultsFromJar(String filename, FileConfiguration targetConfig) {
        InputStream defConfigStream = plugin.getResource(filename);
        if (defConfigStream != null) {
            try (InputStreamReader reader = new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(reader);
                targetConfig.setDefaults(defConfig);
                // targetConfig.options().copyDefaults(true); // Не всегда нужно, saveResource уже копирует
            } catch (IOException e) {
                 plugin.getLogger().log(Level.SEVERE, "Could not load defaults from JAR for " + filename, e); // Используем log()
            }
        } else {
             plugin.getLogger().warning("Default config file not found in JAR: " + filename); // Используем warning()
        }
    }
    */
}