package org.isyateq.hfactions;

// Bukkit/Paper API
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager; // Import PluginManager

// Vault & LuckPerms API
import net.luckperms.api.LuckPerms;
import net.milkbowl.vault.economy.Economy;

// HFactions Components
import org.isyateq.hfactions.commands.FactionCommand;
import org.isyateq.hfactions.integrations.DynmapIntegration;
import org.isyateq.hfactions.integrations.LuckPermsIntegration;
import org.isyateq.hfactions.integrations.OraxenIntegration;
import org.isyateq.hfactions.integrations.VaultIntegration;
import org.isyateq.hfactions.listeners.*; // Импортируем все слушатели из пакета
import org.isyateq.hfactions.managers.*; // Импортируем все менеджеры
import org.isyateq.hfactions.tasks.CuffLeashTask; // Импортируем задачи, если они в tasks
import org.isyateq.hfactions.tasks.PaydayTask;

// Java Utilities
import java.util.Objects;
import java.util.logging.Level;


public final class HFactions extends JavaPlugin {

    private static HFactions instance;

    // Менеджеры
    private ConfigManager configManager;
    private DatabaseManager databaseManager; // Для данных игроков
    private FactionManager factionManager;
    private PlayerManager playerManager;
    private GuiManager guiManager;
    private CuffManager cuffManager;
    private ItemManager itemManager;
    private CraftingManager craftingManager;
    // DynmapManager теперь часть DynmapIntegration

    // Интеграции
    private VaultIntegration vaultIntegration;
    private LuckPermsIntegration luckPermsIntegration;
    private DynmapIntegration dynmapIntegration; // Главный класс для Dynmap
    private OraxenIntegration oraxenIntegration;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("===================================");
        getLogger().info("Enabling HFactions v" + getDescription().getVersion());

        // 1. Инициализация Базы Данных (ПЕРЕД загрузкой данных игрока)
        getLogger().info("Initializing database...");
        databaseManager = new DatabaseManager(this);

        // 2. Загрузка Конфигурации
        getLogger().info("Loading configurations...");
        configManager = new ConfigManager(this);
        configManager.loadConfigs(); // Загрузит config.yml, factions.yml и др.

        // 3. Инициализация Менеджеров
        getLogger().info("Initializing managers...");
        factionManager = new FactionManager(this);
        playerManager = new PlayerManager(this); // PlayerManager теперь будет использовать DatabaseManager
        guiManager = new GuiManager(this);       // Инициализация GuiManager
        cuffManager = new CuffManager(this);     // Инициализация CuffManager
        itemManager = new ItemManager(this);     // Инициализация ItemManager
        craftingManager = new CraftingManager(this); // Инициализация CraftingManager

        // 4. Загрузка Данных (фракции, рецепты)
        getLogger().info("Loading data...");
        factionManager.loadFactions();       // Загрузка фракций из factions.yml
        craftingManager.loadRecipes();      // Загрузка рецептов крафта из config.yml
        // Данные игроков загружаются при входе

        // 5. Настройка Зависимостей и Интеграций
        getLogger().info("Setting up integrations...");
        setupVault();        // <--- Проверка вызова
        setupLuckPerms();    // <--- Проверка вызова
        setupDynmap();       // <--- Проверка вызова (Загрузит территории, если успешно)
        setupOraxen();       // <--- Проверка вызова

        // 6. Регистрация Команд
        getLogger().info("Registering commands...");
        registerCommands();

        // 7. Регистрация Слушателей
        getLogger().info("Registering listeners...");
        registerListeners(); // <--- Проверка вызова

        // 8. Загрузка данных для уже онлайн игроков (если был /reload)
        getLogger().info("Loading data for online players...");
        if (playerManager != null) { // Убедимся, что менеджер инициализирован
            playerManager.loadDataForOnlinePlayers();
        } else {
            getLogger().severe("PlayerManager is null! Cannot load data for online players.");
        }


        // 9. Запуск Задач
        getLogger().info("Scheduling tasks...");
        scheduleTasks();

        getLogger().info("HFactions has been enabled successfully!");
        getLogger().info("===================================");
    }

    @Override
    public void onDisable() {
        getLogger().info("===================================");
        getLogger().info("Disabling HFactions v" + getDescription().getVersion());

        // Сохранение данных для онлайн игроков (синхронно)
        getLogger().info("Saving data for online players...");
        if (playerManager != null) {
            playerManager.saveDataForOnlinePlayers();
        }

        // Сохранение измененных фракций (склады, ранги, балансы)
        getLogger().info("Saving modified faction data...");
        if (factionManager != null) {
            // Используем синхронное сохранение ВСЕХ фракций при выключении для надежности
            factionManager.saveAllFactions();
        }

        // Сохранение территорий Dynmap (если нужно)
        if (dynmapIntegration != null && dynmapIntegration.getDynmapManager() != null) {
            getLogger().info("Saving Dynmap territories...");
            dynmapIntegration.getDynmapManager().saveTerritories(); // Сохраняем территории
        }

        // Остановка задач
        getLogger().info("Stopping tasks...");
        Bukkit.getScheduler().cancelTasks(this);

        // Закрытие соединения с БД (ВАЖНО)
        getLogger().info("Closing database connection...");
        if (databaseManager != null) {
            databaseManager.closeConnection();
        }

        getLogger().info("HFactions has been disabled.");
        getLogger().info("===================================");
        instance = null;
    }

    // --- Регистрация Команд ---
    private void registerCommands() {
        try {
            FactionCommand factionCommandExecutor = new FactionCommand(this);
            Objects.requireNonNull(getCommand("hfactions"), "Command 'hfactions' not found in plugin.yml")
                    .setExecutor(factionCommandExecutor);
            Objects.requireNonNull(getCommand("hfactions"), "Command 'hfactions' not found in plugin.yml")
                    .setTabCompleter(factionCommandExecutor); // Регистрируем TabCompleter
            // Если будут другие основные команды, регистрируем их тут
        } catch (NullPointerException e) {
            getLogger().log(Level.SEVERE, "Failed to register command 'hfactions'. Is it defined in plugin.yml?", e);
        }
    }

    // --- Регистрация Слушателей ---
    private void registerListeners() { // <--- Определение метода
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinQuitListener(this), this);
        pm.registerEvents(new GuiClickListener(this), this);
        pm.registerEvents(new PlayerChatListener(this), this);
        pm.registerEvents(new InventoryCloseListener(this), this);
        pm.registerEvents(new TaserListener(this), this);
        pm.registerEvents(new HandcuffListener(this), this);
        pm.registerEvents(new ProtocolListener(this), this);
        pm.registerEvents(new CraftingListener(this), this);
        // Добавьте другие слушатели, если они есть
        getLogger().info("Listeners registered.");
    }

    // --- Методы настройки интеграций ---

    private boolean setupVault() { // <--- Определение метода
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found! Economy features will be disabled.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("Vault found, but no Economy provider found! Economy features will be disabled.");
            return false;
        }
        Economy econ = rsp.getProvider();
        this.vaultIntegration = new VaultIntegration(econ);
        getLogger().info("Vault & Economy hooked successfully!");
        return true;
    }

    private boolean setupLuckPerms() { // <--- Определение метода
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            // Проверяем, установлен ли плагин, но API не зарегистрирован (маловероятно, но возможно)
            if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                getLogger().severe("LuckPerms found, but failed to get API instance! Permissions and admin mode WILL NOT WORK correctly.");
            } else {
                getLogger().severe("LuckPerms not found! Permissions and admin mode WILL NOT WORK correctly.");
            }
            return false;
        }
        this.luckPermsIntegration = new LuckPermsIntegration(provider.getProvider());
        getLogger().info("LuckPerms hooked successfully!");
        return true;
    }

    private void setupDynmap() { // <--- Определение метода
        if (getServer().getPluginManager().isPluginEnabled("dynmap")) {
            // Проверяем, включена ли интеграция в конфиге
            if (configManager != null && configManager.getConfig().getBoolean("integrations.dynmap.enabled", true)) {
                try {
                    dynmapIntegration = new DynmapIntegration(this); // Инициализируем основной класс интеграции
                    if (dynmapIntegration.initialize()) { // Вызываем его метод инициализации
                        getLogger().info("Dynmap integration enabled.");
                        // Загружаем территории после успешной инициализации
                        if (dynmapIntegration.getDynmapManager() != null) {
                            dynmapIntegration.getDynmapManager().loadTerritories();
                        } else {
                            getLogger().warning("Dynmap integration initialized, but DynmapManager is null. Cannot load territories.");
                        }
                    } else {
                        getLogger().warning("Dynmap found, but failed to initialize integration (initialize() returned false). Territory features disabled.");
                        dynmapIntegration = null; // Сбрасываем, если инициализация не удалась
                    }
                } catch (Exception | NoClassDefFoundError e) {
                    getLogger().log(Level.WARNING, "Error initializing Dynmap integration. Territory features disabled.", e);
                    dynmapIntegration = null;
                }
            } else {
                getLogger().info("Dynmap integration is disabled in config.");
                dynmapIntegration = null;
            }
        } else {
            getLogger().info("Dynmap not found or disabled. Territory features will be unavailable.");
            dynmapIntegration = null;
        }
    }

    private void setupOraxen() { // <--- Определение метода
        // Проверяем конфиг ПЕРЕД проверкой плагина
        if (configManager != null && configManager.getConfig().getBoolean("integrations.oraxen.enabled", false)) {
            if (getServer().getPluginManager().isPluginEnabled("Oraxen")) {
                try {
                    this.oraxenIntegration = new OraxenIntegration(this); // Инициализация обертки
                    getLogger().info("Oraxen integration enabled.");
                } catch (Exception | NoClassDefFoundError e) {
                    getLogger().log(Level.WARNING, "Error initializing Oraxen integration. Oraxen support disabled.", e);
                    this.oraxenIntegration = null;
                }
            } else {
                getLogger().warning("Oraxen support is enabled in config, but Oraxen plugin not found or disabled.");
                this.oraxenIntegration = null;
            }
        } else {
            getLogger().info("Oraxen support is disabled in config.");
            this.oraxenIntegration = null;
        }
    }

    // --- Запуск Задач ---
    private void scheduleTasks() {
        // Payday Task
        if (configManager.getConfig().getBoolean("payday.enabled", true)) {
            long intervalTicks = configManager.getConfig().getLong("payday.interval_minutes", 60) * 60 * 20;
            if (intervalTicks > 0) {
                // Используем try-catch на случай ошибок в конструкторе задачи
                try {
                    new PaydayTask(this).runTaskTimer(this, intervalTicks, intervalTicks);
                    getLogger().info("Payday task scheduled every " + configManager.getConfig().getLong("payday.interval_minutes", 60) + " minutes.");
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Failed to schedule PaydayTask!", e);
                }
            } else {
                getLogger().warning("Payday interval is invalid (<= 0), task not scheduled.");
            }
        } else {
            getLogger().info("Payday task is disabled in config.");
        }

        // Auto-save modified factions task (периодическое сохранение)
        long saveInterval = configManager.getConfig().getLong("general.auto_save_interval_minutes", 15);
        if (saveInterval > 0 && factionManager != null) {
            long saveTicks = saveInterval * 60 * 20;
            // Запускаем асинхронно, чтобы не блокировать основной поток
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    plugin.getFactionManager().saveModifiedFactions(); // Сохраняем только измененные
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Error during auto-save of modified factions!", e);
                }
            }, saveTicks, saveTicks); // Запускаем через N минут и повторяем каждые N минут
            getLogger().info("Scheduled auto-save for modified factions every " + saveInterval + " minutes.");
        } else if (saveInterval <= 0) {
            getLogger().info("Faction auto-save is disabled (interval <= 0).");
        }

        // Cuff Leash Task (запускается по необходимости CuffManager'ом)
        // Не нужно глобально запускать здесь, CuffManager сам запустит/остановит для конкретных игроков
    }


    // --- Геттеры ---
    public static HFactions getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public FactionManager getFactionManager() { return factionManager; }
    public PlayerManager getPlayerManager() { return playerManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public CuffManager getCuffManager() { return cuffManager; }
    public ItemManager getItemManager() { return itemManager; }
    public CraftingManager getCraftingManager() { return craftingManager; }
    public VaultIntegration getVaultIntegration() { return vaultIntegration; }
    public LuckPermsIntegration getLuckPermsIntegration() { return luckPermsIntegration; }
    public DynmapIntegration getDynmapIntegration() { return dynmapIntegration; }
    public OraxenIntegration getOraxenIntegration() { return oraxenIntegration; }
}