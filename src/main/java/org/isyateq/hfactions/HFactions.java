package org.isyateq.hfactions;

// Bukkit/Paper API
import org.bukkit.Bukkit;
import org.bukkit.conversations.Conversation;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;

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
    private DatabaseManager databaseManager;
    private FactionManager factionManager;
    private PlayerManager playerManager;
    private GuiManager guiManager;
    private CuffManager cuffManager;
    private ItemManager itemManager;
    private CraftingManager craftingManager;
    private ConversationManager conversationManager;

    // Интеграции
    private VaultIntegration vaultIntegration;
    private LuckPermsIntegration luckPermsIntegration;
    private DynmapIntegration dynmapIntegration;
    private OraxenIntegration oraxenIntegration;
    private HFactions plugin;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("===================================");
        getLogger().info("Enabling HFactions v" + getDescription().getVersion());

        // 1. Инициализация Базы Данных
        getLogger().info("Initializing database...");
        databaseManager = new DatabaseManager(this);

        // 2. Загрузка Конфигурации
        getLogger().info("Loading configurations...");
        configManager = new ConfigManager(this);
        configManager.loadConfigs();

        // 3. Инициализация Менеджеров
        getLogger().info("Initializing managers...");
        factionManager = new FactionManager(this); // FactionManager первым
        playerManager = new PlayerManager(this);   // PlayerManager после FactionManager
        guiManager = new GuiManager(this);
        cuffManager = new CuffManager(this);
        itemManager = new ItemManager(this);
        craftingManager = new CraftingManager(this);
        conversationManager = new ConversationManager(this);

        // 4. Загрузка Данных (фракции, рецепты)
        getLogger().info("Loading data...");
        if (factionManager != null) factionManager.loadFactions(); else getLogger().severe("FactionManager is null!");
        if (craftingManager != null) craftingManager.loadRecipes(); else getLogger().severe("CraftingManager is null!");

        // 5. Настройка Зависимостей и Интеграций
        getLogger().info("Setting up integrations...");
        setupVault();
        setupLuckPerms();
        setupDynmap(); // Загрузит территории, если успешно
        setupOraxen();

        // 6. Регистрация Команд
        getLogger().info("Registering commands...");
        registerCommands();

        // 7. Регистрация Слушателей
        getLogger().info("Registering listeners...");
        registerListeners();

        // 8. Загрузка данных для уже онлайн игроков (если был /reload)
        getLogger().info("Loading data for online players...");
        if (playerManager != null) {
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

        // Сохранение измененных фракций (синхронно)
        getLogger().info("Saving modified faction data...");
        if (factionManager != null) {
            factionManager.saveAllFactions(); // Сохраняем все при выключении
        }

        // Сохранение территорий Dynmap
        if (dynmapIntegration != null && dynmapIntegration.getDynmapManager() != null) {
            getLogger().info("Saving Dynmap territories...");
            dynmapIntegration.getDynmapManager().saveTerritories();
        }

        // Остановка задач
        getLogger().info("Stopping tasks...");
        Bukkit.getScheduler().cancelTasks(this);

        // Закрытие соединения с БД
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
            // getCommand() может вернуть null, если команда не описана в plugin.yml
            Objects.requireNonNull(getCommand("hfactions"), "Command 'hfactions' not found in plugin.yml!")
                    .setExecutor(factionCommandExecutor);
            // Устанавливаем TabCompleter для той же команды
            Objects.requireNonNull(getCommand("hfactions"), "Command 'hfactions' not found in plugin.yml!")
                    .setTabCompleter(factionCommandExecutor);
            getLogger().info("Command /hfactions registered.");
        } catch (NullPointerException e) {
            getLogger().log(Level.SEVERE, "Failed to register command 'hfactions'. Check plugin.yml!", e);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred while registering commands.", e);
        }
    }

    // --- Регистрация Слушателей ---
    private void registerListeners() {
        try {
            PluginManager pm = getServer().getPluginManager();
            pm.registerEvents(new PlayerJoinQuitListener(this), this);
            pm.registerEvents(new GuiClickListener(this), this);
            pm.registerEvents(new PlayerChatListener(this), this);
            pm.registerEvents(new InventoryCloseListener(this), this);
            pm.registerEvents(new TaserListener(this), this);
            pm.registerEvents(new HandcuffListener(this), this);
            pm.registerEvents(new ProtocolListener(this), this);
            pm.registerEvents(new CraftingListener(this), this);
            getLogger().info("Listeners registered.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred while registering listeners.", e);
        }
    }

    // --- Методы настройки интеграций ---

    private void setupVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault not found. Economy features disabled.");
            vaultIntegration = null; // Явно ставим в null
            return;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("Vault found, but no Economy provider found! Economy features disabled.");
            vaultIntegration = null;
            return;
        }
        try {
            Economy econ = rsp.getProvider();
            this.vaultIntegration = new VaultIntegration(econ);
            getLogger().info("Vault & Economy hooked successfully!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error initializing Vault integration", e);
            vaultIntegration = null;
        }
    }

    private void setupLuckPerms() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                getLogger().severe("LuckPerms found, but API not registered! Permissions WILL NOT WORK.");
            } else {
                getLogger().severe("LuckPerms not found! Permissions WILL NOT WORK.");
            }
            luckPermsIntegration = null;
            return;
        }
        try {
            this.luckPermsIntegration = new LuckPermsIntegration(provider.getProvider());
            getLogger().info("LuckPerms hooked successfully!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error initializing LuckPerms integration", e);
            luckPermsIntegration = null;
        }
    }

    private void setupDynmap() {
        if (configManager != null && configManager.getConfig() != null &&
                configManager.getConfig().getBoolean("integrations.dynmap.enabled", true)) {
            if (getServer().getPluginManager().isPluginEnabled("dynmap")) {
                try {
                    dynmapIntegration = new DynmapIntegration(this);
                    if (dynmapIntegration.initialize()) {
                        getLogger().info("Dynmap integration enabled.");
                        if (dynmapIntegration.getDynmapManager() != null) {
                            dynmapIntegration.getDynmapManager().loadTerritories();
                        } else {
                            getLogger().warning("DynmapManager is null after DynmapIntegration initialization. Cannot load territories.");
                        }
                    } else {
                        getLogger().warning("Dynmap integration initialization failed (initialize() returned false). Territory features disabled.");
                        dynmapIntegration = null;
                    }
                } catch (Exception | NoClassDefFoundError e) {
                    getLogger().log(Level.WARNING, "Error initializing Dynmap integration. Territory features disabled.", e);
                    dynmapIntegration = null;
                }
            } else {
                getLogger().info("Dynmap integration enabled in config, but Dynmap plugin not found/enabled. Territory features unavailable.");
                dynmapIntegration = null;
            }
        } else {
            getLogger().info("Dynmap integration is disabled in config or config is unavailable.");
            dynmapIntegration = null;
        }
    }

    private void setupOraxen() {
        if (configManager != null && configManager.getConfig() != null &&
                configManager.getConfig().getBoolean("integrations.oraxen.enabled", false)) {
            if (getServer().getPluginManager().isPluginEnabled("Oraxen")) {
                try {
                    // OraxenIntegration сам проверит доступность API в конструкторе
                    this.oraxenIntegration = new OraxenIntegration(this);
                    if (this.oraxenIntegration.isEnabled()) { // Проверяем флаг после создания
                        getLogger().info("Oraxen integration enabled.");
                    } else {
                        // Лог об ошибке будет в конструкторе OraxenIntegration
                        this.oraxenIntegration = null; // Сбрасываем, если не удалось
                    }
                } catch (Exception e) { // Ловим ошибки конструктора на всякий случай
                    getLogger().log(Level.SEVERE, "Unexpected error creating OraxenIntegration", e);
                    this.oraxenIntegration = null;
                }
            } else {
                getLogger().warning("Oraxen support enabled in config, but Oraxen plugin not found/enabled.");
                this.oraxenIntegration = null;
            }
        } else {
            getLogger().info("Oraxen support is disabled in config or config is unavailable.");
            this.oraxenIntegration = null;
        }
    }

    // --- Запуск Задач ---
    private void scheduleTasks() {
        // Payday Task
        if (configManager != null && configManager.getConfig() != null &&
                configManager.getConfig().getBoolean("payday.enabled", true)) {
            long intervalMinutes = configManager.getConfig().getLong("payday.interval_minutes", 60);
            if (intervalMinutes > 0) {
                long intervalTicks = intervalMinutes * 60 * 20;
                try {
                    new PaydayTask(this).runTaskTimer(this, intervalTicks, intervalTicks);
                    getLogger().info("Payday task scheduled every " + intervalMinutes + " minutes.");
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Failed to schedule PaydayTask!", e);
                }
            } else {
                getLogger().warning("Payday interval is invalid (<= 0), task not scheduled.");
            }
        } else {
            getLogger().info("Payday task is disabled or config is unavailable.");
        }

        // Auto-save modified factions task
        if (configManager != null && configManager.getConfig() != null) {
            long saveInterval = configManager.getConfig().getLong("general.auto_save_interval_minutes", 15);
            if (saveInterval > 0 && factionManager != null) {
                long saveTicks = saveInterval * 60 * 20;
                Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    try {
                        // Проверяем FactionManager еще раз внутри задачи
                        FactionManager fm = plugin.getFactionManager();
                        if (fm != null) {
                            fm.saveModifiedFactions();
                        }
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Error during auto-save of modified factions!", e);
                    }
                }, saveTicks, saveTicks);
                getLogger().info("Scheduled auto-save for modified factions every " + saveInterval + " minutes.");
            } else if (saveInterval <= 0) {
                getLogger().info("Faction auto-save is disabled (interval <= 0).");
            }
        } else {
            getLogger().info("Faction auto-save disabled because config is unavailable.");
        }
    }


    // --- Геттеры ---
    public static HFactions getInstance() { return instance; }
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
    public ConversationManager getConversationManager() { return conversationManager; }
}