package org.isyateq.hfactions;

// --- Проверь эти импорты ---

import net.luckperms.api.LuckPerms;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.isyateq.hfactions.commands.FactionCommand;
import org.isyateq.hfactions.integrations.LuckPermsIntegration;
import org.isyateq.hfactions.integrations.OraxenIntegration;
import org.isyateq.hfactions.integrations.VaultIntegration;
import org.isyateq.hfactions.listeners.*;
import org.isyateq.hfactions.managers.*;
import org.isyateq.hfactions.tasks.PaydayTask;
import org.isyateq.hfactions.util.MessageUtil;

import java.util.Objects;
import java.util.logging.Level;

public final class HFactions extends JavaPlugin {

    private static HFactions instance;

    // Менеджеры - убедись, что все объявлены и инициализированы
    private ConfigManager configManager;
    private FactionManager factionManager;
    private PlayerManager playerManager;
    private DatabaseManager databaseManager; // Из предыдущих шагов
    private GuiManager guiManager;
    private ItemManager itemManager;
    private CuffManager cuffManager;
    private CooldownManager cooldownManager; // Добавлен? Убедись, что инициализирован
    private CraftingManager craftingManager; // Добавлен? Убедись, что инициализирован
    private DynmapManager dynmapManager;     // Добавлен? Убедись, что инициализирован
    private MessageUtil messageUtil;
    private InviteManager inviteManager;

    // Интеграции
    private VaultIntegration vaultIntegration;
    private LuckPermsIntegration luckPermsIntegration;
    // private DynmapIntegration dynmapIntegration; // Заменен на DynmapManager?
    private OraxenIntegration oraxenIntegration;

    // Задачи (храним ссылки, если нужно отменять)
    private BukkitTask paydayTaskRef = null;
    // private BukkitTask cuffLeashTaskRef = null; // CuffLeashTask управляется через CuffManager

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("===================================");
        getLogger().info("Enabling HFactions v" + getDescription().getVersion());

        saveDefaultConfig(); // Создаст config.yml, если его нет
        this.messageUtil = new MessageUtil(this); // Инициализация MessageUtil ПОСЛЕ загрузки конфига

        this.inviteManager = new InviteManager(this);
        this.factionManager = new FactionManager(this);
        this.messageUtil = new MessageUtil(this);
        this.inviteManager = new InviteManager(this);
        this.factionManager = new FactionManager(this);

        // 0. Инициализация базовых менеджеров (без зависимостей от конфигов)
        cooldownManager = new CooldownManager(); // Инициализируем CooldownManager

        // 1. Инициализация Базы Данных (ПЕРЕД PlayerManager)
        getLogger().info("Initializing database...");
        databaseManager = new DatabaseManager(this);

        // 2. Загрузка Конфигурации
        getLogger().info("Loading configurations...");
        configManager = new ConfigManager(this);
        // loadConfigs должен существовать и вызываться
        if (!configManager.loadConfigs()) { // Добавим проверку результата
            getLogger().severe("Failed to load configurations! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Инициализация Интеграций (ДО менеджеров, которые их используют)
        getLogger().info("Setting up integrations...");
        setupVault();/* Vault не обязателен, просто выводим предупреждение */
        if (!setupLuckPerms()) { // LuckPerms обязателен
            getLogger().severe("LuckPerms integration failed! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Oraxen опционален
        setupOraxen();
        // Dynmap опционален - Инициализация DynmapManager'а
        setupDynmapManager(); // Новый метод для DynmapManager

        // 4. Инициализация Остальных Менеджеров
        getLogger().info("Initializing managers...");
        itemManager = new ItemManager(this); // Зависит от ConfigManager, OraxenIntegration
        playerManager = new PlayerManager(this); // Зависит от DB, Faction, Config, LP?
        factionManager = new FactionManager(this); // Зависит от Config, Player, DB
        guiManager = new GuiManager(this);         // Зависит от Player, Faction
        cuffManager = new CuffManager(this);       // Зависит от Config, Player, Cooldown?
        craftingManager = new CraftingManager(this); // Зависит от Config, ItemManager?
        // DynmapManager инициализирован в setupDynmapManager

        // 5. Загрузка Данных (Фракции, Крафты)
        getLogger().info("Loading data...");
        factionManager.loadFactions();
        craftingManager.loadRecipes(); // Загрузка рецептов

        // 6. Регистрация Команд
        getLogger().info("Registering commands...");
        registerCommands();

        // 7. Регистрация Слушателей
        getLogger().info("Registering listeners...");
        registerListeners();

        // 8. Загрузка данных для онлайн игроков (если /reload)
        getLogger().info("Loading data for online players...");
        playerManager.loadDataForOnlinePlayers();

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

        // Остановка задач
        getLogger().info("Stopping tasks...");
        if (paydayTaskRef != null && !paydayTaskRef.isCancelled()) {
            paydayTaskRef.cancel();
        }

        // Отмена других задач, если они есть и хранятся ссылки
        Bukkit.getScheduler().cancelTasks(this); // Отменяет все задачи плагина

        // Сохранение данных (фракции, игроки)
        getLogger().info("Saving data...");
        if (factionManager != null) {
            getLogger().info("Сохранение данных фракций...");
            factionManager.saveFactionsSync();
            getLogger().info("Данные фракций сохранены.");
        }
        if (playerManager != null) {
            // Сохраняем данные онлайн игроков синхронно!
            playerManager.saveDataForOnlinePlayersSynchronously(); // Нужен синхронный метод
        }
        // Сохранение территорий Dynmap?
        if (dynmapManager != null) {
            dynmapManager.isDynmapEnabled();
        }// dynmapManager.saveTerritories(); // Если есть метод для сохранения

        // Закрытие соединения с БД
        getLogger().info("Closing database connection...");
        if (databaseManager != null) {
            databaseManager.closeConnection();
        }

        getLogger().info("HFactions has been disabled.");
        getLogger().info("===================================");
        instance = null;
    }

    private void registerCommands() {
        try {
            // Убедись, что команда называется 'hfactions' в plugin.yml
            Objects.requireNonNull(getCommand("hfactions"), "Command 'hfactions' not found in plugin.yml")
                    .setExecutor(new FactionCommand(this));
            // TabCompleter теперь устанавливается в конструкторе FactionCommand или отдельно:
            Objects.requireNonNull(getCommand("hfactions")).setTabCompleter(new FactionCommand(this));
        } catch (NullPointerException e) {
            getLogger().log(Level.SEVERE, "Failed to register command 'hfactions'. Is it defined in plugin.yml?", e);
        } catch (Exception e) { // Ловим другие возможные ошибки
            getLogger().log(Level.SEVERE, "An unexpected error occurred while registering commands", e);
        }
    }

    private void registerListeners() {
        // Регистрируем слушателей - убедись, что классы существуют и конструкторы верны
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerChatListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiClickListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryCloseListener(this), this);
        getServer().getPluginManager().registerEvents(new TaserListener(this), this);
        getServer().getPluginManager().registerEvents(new HandcuffListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtocolListener(this), this);
        getServer().getPluginManager().registerEvents(new CraftingListener(this), this);
        // Добавь другие слушатели, если они есть
    }

    private void scheduleTasks() {
        // Payday Task
        if (getConfig().getBoolean("settings.payday.enabled", false)) { // Используем метод из ConfigManager
            long interval = getConfig().getLong("settings.payday.interval-ticks", 72000L);
            if (interval > 0) {
                // Создаем экземпляр задачи и запускаем таймер
                PaydayTask paydayTask = new PaydayTask(this);
                paydayTaskRef = paydayTask.runTaskTimer(this, interval, interval); // Сохраняем ссылку
                getLogger().info("Payday task scheduled every " + (interval / 20L) + " seconds.");
            } else {
                getLogger().warning("Payday interval is invalid ("+ interval +" ticks), task not scheduled.");
            }
        } else {
            getLogger().info("Payday task is disabled in config.");
        }

        // Задача автосохранения фракций запускается в конструкторе FactionManager
    }

    // --- Настройка интеграций ---

    private void setupVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found! Economy features might be limited or disabled.");
            return; // Не критично для запуска, но функционал пострадает
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("Vault found, but no Economy provider found! Economy features will be disabled.");
            return; // Не критично
        }
        Economy econ = rsp.getProvider();
        this.vaultIntegration = new VaultIntegration(econ);
        getLogger().info("Vault & Economy hooked successfully!");
    }

    private boolean setupLuckPerms() {
        // LuckPerms КРИТИЧЕН для прав
        if (getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().severe("LuckPerms not found! Permissions and admin mode WILL NOT WORK.");
            return false; // Критично
        }
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            getLogger().severe("LuckPerms found, but failed to get API instance! Permissions and admin mode WILL NOT WORK.");
            return false; // Критично
        }
        try {
            this.luckPermsIntegration = new LuckPermsIntegration(provider.getProvider());
            getLogger().info("LuckPerms hooked successfully!");
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error initializing LuckPerms integration!", e);
            return false; // Критично, если инициализация не удалась
        }
    }

    // Используем DynmapManager вместо DynmapIntegration
    private void setupDynmapManager() {
        if (!getConfig().getBoolean("settings.integrations.dynmap.enabled", false)) { // Проверяем конфиг
            getLogger().info("Dynmap integration disabled in config.");
            this.dynmapManager = null;
            return;
        }
        if (getServer().getPluginManager().isPluginEnabled("dynmap")) {
            try {
                this.dynmapManager = new DynmapManager(this);
                if (this.dynmapManager.initialize()) { // Метод initialize в DynmapManager
                    getLogger().info("Dynmap integration enabled via DynmapManager.");
                } else {
                    getLogger().warning("Dynmap found, but failed to initialize DynmapManager. Territory features disabled.");
                    this.dynmapManager = null;
                }
            } catch (Exception | NoClassDefFoundError e) { // Ловим ошибки инициализации
                getLogger().log(Level.WARNING, "Error initializing DynmapManager. Territory features disabled.", e);
                this.dynmapManager = null;
            }
        } else {
            getLogger().info("Dynmap not found or disabled. Territory features will be unavailable.");
            this.dynmapManager = null;
        }
    }

    private void setupOraxen() {
        if (!configManager.isOraxenSupportEnabled()) { // Проверяем конфиг
            getLogger().info("Oraxen support disabled in config.");
            this.oraxenIntegration = null;
            return;
        }
        if (getServer().getPluginManager().isPluginEnabled("Oraxen")) {
            try {
                // Простая обертка, не требует сложной инициализации здесь
                this.oraxenIntegration = new OraxenIntegration(this);
                getLogger().info("Oraxen integration enabled.");
            } catch (Exception | NoClassDefFoundError e) { // Ловим возможные ошибки при доступе к API Oraxen
                getLogger().log(Level.WARNING, "Error initializing Oraxen integration. Oraxen support disabled.", e);
                this.oraxenIntegration = null;
            }
        } else {
            getLogger().warning("Oraxen support is enabled in config, but Oraxen plugin not found or disabled.");
            this.oraxenIntegration = null;
        }
    }

    // --- Геттеры ---
    public static HFactions getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public FactionManager getFactionManager() { return factionManager; }
    public PlayerManager getPlayerManager() { return playerManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public ItemManager getItemManager() { return itemManager; }
    public CuffManager getCuffManager() { return cuffManager; }
    public CooldownManager getCooldownManager() { return cooldownManager; }
    public CraftingManager getCraftingManager() { return craftingManager; }
    public DynmapManager getDynmapManager() { return dynmapManager; }
    public VaultIntegration getVaultIntegration() { return vaultIntegration; }
    public LuckPermsIntegration getLuckPermsIntegration() { return luckPermsIntegration; }
    public OraxenIntegration getOraxenIntegration() { return oraxenIntegration; }
}