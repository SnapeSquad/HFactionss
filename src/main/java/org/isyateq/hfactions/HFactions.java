package org.isyateq.hfactions;

// ... другие импорты ...
import net.luckperms.api.LuckPerms;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.isyateq.hfactions.commands.FactionCommand;
import org.isyateq.hfactions.listeners.*; // Импорт всех листенеров
import org.isyateq.hfactions.managers.*; // Импорт всех менеджеров
import org.isyateq.hfactions.tasks.CuffLeashTask; // Пример импорта задачи
import org.isyateq.hfactions.tasks.PaydayTask;    // Пример импорта задачи
import org.isyateq.hfactions.integrations.*; // Импорт интеграций

import java.util.Objects;
import java.util.logging.Level;


public final class HFactions extends JavaPlugin {

    private static HFactions instance;

    // Менеджеры
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private FactionManager factionManager;
    private PlayerManager playerManager;
    private GuiManager guiManager;
    private CuffManager cuffManager;
    private ItemManager itemManager;
    private CraftingManager craftingManager;
    private DynmapManager dynmapManager; // Если Dynmap опционален, может быть null

    // Интеграции (API)
    private VaultIntegration vaultIntegration; // Может быть null
    private LuckPermsIntegration luckPermsIntegration; // Должен быть не null
    private OraxenIntegration oraxenIntegration; // Может быть null

    @Override
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();
        getLogger().info("===================================");
        getLogger().info("Enabling HFactions v" + getDescription().getVersion());

        // 1. Инициализация Базы Данных (Первым делом)
        getLogger().info("Initializing database...");
        try {
            databaseManager = new DatabaseManager(this);
            // Попытка получить соединение для создания/проверки таблицы
            databaseManager.getConnection();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize database! Disabling plugin.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }


        // 2. Загрузка Конфигурации
        getLogger().info("Loading configurations...");
        configManager = new ConfigManager(this);
        configManager.loadConfigs(); // Загружает config.yml, factions.yml, territories.yml

        // 3. Инициализация Менеджеров
        getLogger().info("Initializing managers...");
        // PlayerManager и FactionManager зависят от DB и Config
        playerManager = new PlayerManager(this);
        factionManager = new FactionManager(this);
        // Остальные менеджеры
        guiManager = new GuiManager(this);
        cuffManager = new CuffManager(this);
        itemManager = new ItemManager(this);
        craftingManager = new CraftingManager(this);
        this.dynmapManager = new DynmapManager(this);
        // DynmapManager инициализируется в setupDynmap

        // 4. Загрузка Данных Фракций (Игроки загружаются при входе)
        getLogger().info("Loading faction data...");
        factionManager.loadFactions();

        // 5. Настройка Зависимостей и Интеграций
        getLogger().info("Setting up integrations...");
        setupVault(); // Vault может быть опциональным для некоторых функций
        if (!setupLuckPerms()) { // LuckPerms обязателен
            getLogger().severe("LuckPerms integration failed! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        setupDynmap(); // Опционально
        setupOraxen(); // Опционально

        // 6. Регистрация Команд
        getLogger().info("Registering commands...");
        registerCommands();

        // 7. Регистрация Слушателей
        getLogger().info("Registering listeners...");
        registerListeners();

        // 8. Загрузка данных для уже онлайн игроков (важно после инициализации всего)
        getLogger().info("Loading data for online players...");
        playerManager.loadDataForOnlinePlayers();

        // 9. Запуск Задач
        getLogger().info("Scheduling tasks...");
        scheduleTasks();

        long endTime = System.currentTimeMillis();
        getLogger().info("HFactions has been enabled successfully! (" + (endTime - startTime) + "ms)");
        getLogger().info("===================================");
    }

    @Override
    public void onDisable() {
        long startTime = System.currentTimeMillis();
        getLogger().info("===================================");
        getLogger().info("Disabling HFactions v" + getDescription().getVersion());

        // Остановка задач (в первую очередь, чтобы не мешали сохранению)
        getLogger().info("Stopping tasks...");
        Bukkit.getScheduler().cancelTasks(this);

        // Сохранение данных для онлайн игроков (СИНХРОННО)
        getLogger().info("Saving data for online players...");
        if (playerManager != null) {
            playerManager.saveDataForOnlinePlayers();
        } else {
            getLogger().warning("PlayerManager was null during disable, player data might not be saved!");
        }

        // Сохранение измененных данных фракций (СИНХРОННО)
        getLogger().info("Saving modified faction data...");
        if (factionManager != null) {
            factionManager.saveModifiedFactions(); // Сохраняем то, что не успело автосохранение
        } else {
            getLogger().warning("FactionManager was null during disable, faction data might not be saved!");
        }

        // Сохранение территорий Dynmap (если нужно)
        if (dynmapManager != null && dynmapManager.isDynmapEnabled()) {
            getLogger().info("Saving Dynmap territories...");
            dynmapManager.saveTerritories(); // Сохраняем в territories.yml
        }

        // Закрытие соединения с БД (ВАЖНО)
        getLogger().info("Closing database connection...");
        if (databaseManager != null) {
            databaseManager.closeConnection();
        } else {
            getLogger().warning("DatabaseManager was null during disable.");
        }


        long endTime = System.currentTimeMillis();
        getLogger().info("HFactions has been disabled. (" + (endTime - startTime) + "ms)");
        getLogger().info("===================================");
        instance = null; // Очистка инстанса
    }

    private void registerCommands() {
        try {
            // Получаем команду из plugin.yml
            var pluginCommand = getCommand("hfactions"); // Используем var для краткости
            if (pluginCommand == null) {
                getLogger().severe("Command 'hfactions' NOT FOUND in plugin.yml! Commands will not work.");
                return;
            }
            // Создаем экземпляр обработчика
            FactionCommand factionCommandExecutor = new FactionCommand(this);
            // Назначаем исполнителя и автодополнителя
            pluginCommand.setExecutor(factionCommandExecutor);
            pluginCommand.setTabCompleter(factionCommandExecutor);
            getLogger().info("Command 'hfactions' registered successfully.");
        } catch (Exception e) { // Ловим любые ошибки при регистрации
            getLogger().log(Level.SEVERE, "Failed to register command 'hfactions'.", e);
        }
    }


    private void registerListeners() {
        // Регистрируем основные слушатели
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiClickListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryCloseListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerChatListener(this), this); // Для фракц. чата и ввода имени ранга

        // Слушатели механик
        getServer().getPluginManager().registerEvents(new TaserListener(this), this);
        getServer().getPluginManager().registerEvents(new HandcuffListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtocolListener(this), this); // Для штрафов через предмет

        // Слушатель крафта
        getServer().getPluginManager().registerEvents(new CraftingListener(this), this);

        getLogger().info("All listeners registered.");
    }

    private void scheduleTasks() {
        // Payday Task
        if (configManager.getConfig().getBoolean("payday.enabled", true)) {
            long intervalMinutes = configManager.getConfig().getLong("payday.interval_minutes", 60);
            if (intervalMinutes > 0) {
                long intervalTicks = intervalMinutes * 60 * 20;
                new PaydayTask(this).runTaskTimer(this, intervalTicks, intervalTicks);
                getLogger().info("Payday task scheduled every " + intervalMinutes + " minutes.");
            } else {
                getLogger().warning("Payday interval is invalid (" + intervalMinutes + "), task not scheduled.");
            }
        } else {
            getLogger().info("Payday task is disabled in config.");
        }

        // Cuff Leash Task (запускается при надевании наручников из CuffManager)
        // new CuffLeashTask(this).runTaskTimer(this, 20L, 20L); // Запускать по таймеру НЕ НУЖНО!

        // Faction Auto-Save Task
        long autoSaveIntervalSeconds = configManager.getConfig().getLong("factions.auto_save_interval_seconds", 300); // 5 минут по умолчанию
        if (autoSaveIntervalSeconds > 0) {
            factionManager.startAutoSaveTask(autoSaveIntervalSeconds * 20L);
        } else {
            getLogger().info("Faction auto-save is disabled (interval <= 0).");
        }

    }

    // --- Методы настройки интеграций ---

    private boolean setupVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found! Economy features (payday, fines, deposit/withdraw) will be disabled.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("Vault found, but no Economy provider found! Economy features will be disabled.");
            return false;
        }
        Economy econ = rsp.getProvider();
        if (econ == null) {
            getLogger().warning("Vault Economy provider is null! Economy features will be disabled.");
            return false;
        }
        this.vaultIntegration = new VaultIntegration(econ);
        getLogger().info("Vault & Economy hooked successfully!");
        return true;
    }

    private boolean setupLuckPerms() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        // Проверяем и наличие плагина, и сервис провайдера
        if (provider == null || getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().severe("LuckPerms API not found! Permissions and admin mode WILL NOT WORK correctly.");
            // Не отключаем плагин здесь, но функционал будет сломан
            return false;
        }
        try {
            this.luckPermsIntegration = new LuckPermsIntegration(provider.getProvider());
            getLogger().info("LuckPerms hooked successfully!");
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error initializing LuckPerms integration!", e);
            return false;
        }
    }

    private void setupDynmap() {
        // Проверяем и настройку в конфиге, и наличие плагина
        if (configManager.getConfig().getBoolean("integrations.dynmap.enabled", false)
                && getServer().getPluginManager().isPluginEnabled("dynmap")) {
            try {
                this.dynmapManager = new DynmapManager(this); // Создаем менеджер
                if (this.dynmapManager.initialize()) { // Пытаемся инициализировать
                    getLogger().info("Dynmap integration enabled.");
                    // Загружаем территории после успешной инициализации
                    this.dynmapManager.loadTerritories();
                    this.dynmapManager.renderAllTerritories(); // Отображаем на карте
                } else {
                    getLogger().warning("Dynmap found, but failed to initialize integration. Territory features disabled.");
                    this.dynmapManager = null; // Сбрасываем, если не удалось
                }
            } catch (Exception | NoClassDefFoundError e) { // Ловим ошибки инициализации
                getLogger().log(Level.WARNING, "Error initializing Dynmap integration. Territory features disabled.", e);
                this.dynmapManager = null;
            }
        } else {
            getLogger().info("Dynmap integration is disabled (in config or plugin not found).");
            this.dynmapManager = null;
        }
    }

    private void setupOraxen() {
        if (configManager.isOraxenSupportEnabled()) { // Используем метод из ConfigManager
            if (getServer().getPluginManager().isPluginEnabled("Oraxen")) {
                try {
                    this.oraxenIntegration = new OraxenIntegration(this);
                    // Проверка доступности API Oraxen (если есть статический метод)
                    // OraxenItems.isLoaded(); // Пример
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
    public DynmapManager getDynmapManager() { return dynmapManager; } // Может быть null

    public VaultIntegration getVaultIntegration() { return vaultIntegration; } // Может быть null
    public LuckPermsIntegration getLuckPermsIntegration() { return luckPermsIntegration; } // Не должен быть null после onEnable
    public OraxenIntegration getOraxenIntegration() { return oraxenIntegration; } // Может быть null

}