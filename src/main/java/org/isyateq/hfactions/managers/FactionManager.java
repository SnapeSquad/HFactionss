package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit; // Импорт для Bukkit.getScheduler()
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.util.Utils; // Импортируем наш Utils

import java.sql.Connection; // Импорты для очистки БД при удалении
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
// import java.util.stream.Collectors; // Не используется пока

public class FactionManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final PlayerManager playerManager; // Нужен для очистки данных при удалении фракции
    private final DatabaseManager databaseManager; // Нужен для очистки данных при удалении фракции
    private final Map<String, Faction> factions = new ConcurrentHashMap<>();
    private final Set<String> modifiedFactions = ConcurrentHashMap.newKeySet(); // Храним ID измененных фракций
    private int saveTask = -1; // ID задачи автосохранения

    public FactionManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.playerManager = plugin.getPlayerManager(); // Инициализируем
        this.databaseManager = plugin.getDatabaseManager(); // Инициализируем
        // НЕ вызываем loadFactions() здесь, это делается в onEnable плагина
        scheduleAutoSave(); // Запускаем автосохранение
    }

    // --- Загрузка Фракций ---
    public void loadFactions() {
        factions.clear(); // Очищаем старые данные перед загрузкой
        modifiedFactions.clear(); // Сбрасываем флаги модификации
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");

        if (factionsSection == null) {
            plugin.getLogger().warning("No 'factions' section found in factions.yml!");
            factionsConfig.createSection("factions"); // Создаем пустую секцию, если ее нет
            try {
                configManager.getFactionsConfig().save(configManager.factionsConfigFile); // Сохраняем файл с пустой секцией
            } catch (Exception e) { plugin.getLogger().log(Level.SEVERE,"Could not save factions.yml after creating empty section", e); }
            return;
        }

        int loadedCount = 0;
        for (String factionId : factionsSection.getKeys(false)) {
            ConfigurationSection data = factionsSection.getConfigurationSection(factionId);
            if (data == null) {
                plugin.getLogger().warning("Invalid configuration for faction ID: " + factionId);
                continue;
            }

            try {
                String name = data.getString("name", "Unnamed Faction");
                FactionType type = FactionType.valueOf(data.getString("type", "OTHER").toUpperCase());
                String color = data.getString("color", "#FFFFFF");
                String prefix = data.getString("prefix", "[" + factionId.toUpperCase() + "]");
                // При загрузке читаем ТЕКУЩИЙ баланс, а не initial_balance
                double balance = data.getDouble("balance", 0.0); // Читаем 'balance'
                int warehouseSize = data.getInt("warehouse_size", 54);

                // Загрузка рангов
                Map<Integer, FactionRank> ranks = loadRanks(data.getConfigurationSection("ranks"), factionId);

                // Загрузка склада (десериализация из Base64)
                String warehouseBase64 = data.getString("warehouse_contents_base64"); // Новое поле
                ItemStack[] warehouseContents = Utils.itemStackArrayFromBase64(warehouseBase64);
                // Если десериализация не удалась или размер не совпадает, склад будет пустым (логика в конструкторе Faction)

                Faction faction = new Faction(factionId, name, type, color, prefix, balance, warehouseSize, ranks, warehouseContents);
                factions.put(factionId.toLowerCase(), faction); // Храним ID в нижнем регистре
                loadedCount++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid FactionType for faction " + factionId + ": " + data.getString("type") + ". Using OTHER.");
                // Можно добавить создание фракции с типом OTHER здесь, если нужно
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load faction: " + factionId, e);
            }
        }
        plugin.getLogger().info("Loaded " + loadedCount + " factions.");
    }

    private Map<Integer, FactionRank> loadRanks(ConfigurationSection ranksSection, String factionId) {
        Map<Integer, FactionRank> ranks = new ConcurrentHashMap<>();
        if (ranksSection == null) {
            plugin.getLogger().warning("No 'ranks' section found for faction: " + factionId + ". Creating default rank 1 and 11.");
            // Создаем хотя бы ранг 1 и 11 по умолчанию
            ranks.put(1, new FactionRank(1, "Recruit", "Recruit", 0.0, new ArrayList<>()));
            ranks.put(11, new FactionRank(11, "Leader", "Leader", 0.0, getDefaultLeaderPerms()));
            return ranks;
        }

        for (String rankIdStr : ranksSection.getKeys(false)) {
            try {
                int rankId = Integer.parseInt(rankIdStr);
                ConfigurationSection rankData = ranksSection.getConfigurationSection(rankIdStr);
                if (rankData == null) continue;

                String defaultName = rankData.getString("defaultName", "Rank " + rankId);
                String displayName = rankData.getString("displayName", defaultName);
                double salary = rankData.getDouble("salary", 0.0);
                List<String> permissions = rankData.getStringList("permissions");

                ranks.put(rankId, new FactionRank(rankId, defaultName, displayName, salary, permissions));

            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid rank ID '" + rankIdStr + "' for faction " + factionId + ". Skipping.");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load rank " + rankIdStr + " for faction " + factionId, e);
            }
        }
        // Убедимся, что ранг 1 и 11 существуют
        if (!ranks.containsKey(1)) {
            plugin.getLogger().warning("Rank 1 not found for faction: " + factionId + ". Creating default rank 1.");
            ranks.put(1, new FactionRank(1, "Recruit", "Recruit", 0.0, new ArrayList<>()));
        }
        if (!ranks.containsKey(11)) {
            plugin.getLogger().warning("Rank 11 (Leader) not found for faction: " + factionId + ". Creating default rank 11.");
            ranks.put(11, new FactionRank(11, "Leader", "Leader", 0.0, getDefaultLeaderPerms()));
        }
        return ranks;
    }


    // --- Сохранение Фракций ---

    /**
     * Сохраняет ВСЕ фракции в factions.yml. Вызывается при выключении плагина.
     */
    public void saveAllFactions() {
        plugin.getLogger().info("Saving all factions data...");
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        // Очищаем старую секцию factions полностью перед записью
        factionsConfig.set("factions", null);
        // Создаем секцию заново, даже если фракций нет (чтобы файл не был совсем пустым)
        ConfigurationSection factionsSection = factionsConfig.createSection("factions");

        int savedCount = 0;
        for (Faction faction : factions.values()) {
            saveFactionData(faction, factionsSection);
            savedCount++;
        }
        configManager.saveFactionsConfig(); // Сохраняем файл factions.yml
        modifiedFactions.clear(); // Сбрасываем флаги после полного сохранения
        plugin.getLogger().info("Saved data for " + savedCount + " factions.");
        // Останавливаем задачу автосохранения при выключении
        if (saveTask != -1) {
            Bukkit.getScheduler().cancelTask(saveTask);
            saveTask = -1;
        }
    }

    /**
     * Сохраняет только измененные фракции. Вызывается автоматически по таймеру.
     */
    public synchronized void saveModifiedFactions() { // Синхронизировано на всякий случай
        if (modifiedFactions.isEmpty()) {
            return; // Нечего сохранять
        }
        // Копируем набор ID, чтобы избежать ConcurrentModificationException, если новые изменения придут во время сохранения
        Set<String> factionsToSave = new HashSet<>(modifiedFactions);
        modifiedFactions.removeAll(factionsToSave); // Очищаем флаги ДО сохранения

        plugin.getLogger().info("Auto-saving data for " + factionsToSave.size() + " modified factions...");
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");
        if (factionsSection == null) {
            factionsSection = factionsConfig.createSection("factions");
            plugin.getLogger().warning("Faction section was null during auto-save? Recreated.");
        }

        int savedCount = 0;
        boolean fileChanged = false;

        for (String factionId : factionsToSave) {
            Faction faction = factions.get(factionId); // Получаем актуальные данные
            if (faction != null) {
                saveFactionData(faction, factionsSection);
                savedCount++;
                fileChanged = true;
            } else {
                plugin.getLogger().warning("Tried to save modified faction " + factionId + " but it was not found in memory (maybe deleted?).");
                // Если фракция удалена, она должна быть удалена и из файла через deleteFaction
            }
        }

        if (fileChanged) {
            configManager.saveFactionsConfig(); // Сохраняем файл только если что-то реально изменилось
            plugin.getLogger().info("Auto-saved data for " + savedCount + " modified factions.");
        } else {
            plugin.getLogger().info("Auto-save triggered, but no actual factions needed saving.");
        }
    }

    // Метод для запуска периодического сохранения
    private void scheduleAutoSave() {
        long interval = configManager.getConfig().getLong("faction.auto_save_interval_seconds", 300) * 20L; // 5 минут по умолчанию
        if (interval <= 0) {
            plugin.getLogger().info("Faction auto-save is disabled (interval <= 0).");
            return;
        }
        if (saveTask != -1) { // Отменяем предыдущую задачу, если есть
            Bukkit.getScheduler().cancelTask(saveTask);
        }
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveModifiedFactions, interval, interval).getTaskId();
        plugin.getLogger().info("Faction auto-save scheduled every " + (interval / 20L) + " seconds.");
    }


    // Вспомогательный метод для сохранения данных одной фракции
    private void saveFactionData(Faction faction, ConfigurationSection factionsSection) {
        ConfigurationSection data = factionsSection.createSection(faction.getId());

        data.set("name", faction.getName());
        data.set("type", faction.getType().name());
        data.set("color", faction.getColor());
        data.set("prefix", faction.getPrefix());
        data.set("balance", faction.getBalance()); // Сохраняем ТЕКУЩИЙ баланс
        data.set("warehouse_size", faction.getWarehouseSize());

        // Сохранение рангов
        ConfigurationSection ranksSection = data.createSection("ranks");
        for (Map.Entry<Integer, FactionRank> entry : faction.getRanks().entrySet()) {
            ConfigurationSection rankData = ranksSection.createSection(String.valueOf(entry.getKey()));
            FactionRank rank = entry.getValue();
            rankData.set("defaultName", rank.getDefaultName());
            if (!rank.getDisplayName().equals(rank.getDefaultName())) {
                rankData.set("displayName", rank.getDisplayName());
            } else {
                rankData.set("displayName", null); // Удаляем поле
            }
            rankData.set("salary", rank.getSalary());
            rankData.set("permissions", rank.getPermissions());
        }

        // Сохранение склада
        String warehouseBase64 = Utils.itemStackArrayToBase64(faction.getWarehouseContents());
        if (warehouseBase64 != null && !warehouseBase64.isEmpty()) { // Сохраняем только если не пусто
            data.set("warehouse_contents_base64", warehouseBase64);
        } else {
            data.set("warehouse_contents_base64", null); // Удаляем поле, если склад пуст
        }
    }

    // --- Управление фракциями ---

    public void createFaction(String id, String name, FactionType type, String color, String prefix, double balance, int warehouseSize) {
        id = id.toLowerCase();
        if (factions.containsKey(id)) {
            plugin.getLogger().warning("Faction with ID " + id + " already exists.");
            return; // Не создаем дубликат
        }

        Map<Integer, FactionRank> defaultRanks = new HashMap<>();
        defaultRanks.put(1, new FactionRank(1, "Recruit", "Recruit", 0.0, new ArrayList<>()));
        defaultRanks.put(11, new FactionRank(11, "Leader", "Leader", 0.0, getDefaultLeaderPerms()));

        Faction faction = new Faction(id, name, type, color, prefix, balance, warehouseSize, defaultRanks, new ItemStack[warehouseSize]); // Создаем с пустым складом

        factions.put(id, faction);
        markFactionAsModified(id); // Отмечаем для сохранения
        saveModifiedFactions(); // Сохраняем сразу после создания, чтобы она появилась в файле
        plugin.getLogger().info("Faction " + name + " (ID: " + id + ") created.");
    }

    // Вспомогательный метод для получения дефолтных прав лидера
    private List<String> getDefaultLeaderPerms() {
        return Arrays.asList(
                "hfactions.faction.invite", "hfactions.faction.kick", "hfactions.faction.promote",
                "hfactions.faction.demote", "hfactions.faction.setrank", "hfactions.faction.manage_ranks",
                "hfactions.faction.balance.view", "hfactions.faction.deposit", "hfactions.faction.withdraw",
                "hfactions.faction.manage_balance",
                "hfactions.faction.warehouse.open", "hfactions.faction.warehouse.deposit", "hfactions.faction.warehouse.withdraw"
                // Добавьте сюда права на /hf fine, если лидер PD должен их иметь по умолчанию
                // "hfactions.pd.fine"
        );
    }

    public void deleteFaction(String id) {
        id = id.toLowerCase();
        Faction faction = factions.remove(id); // Удаляем из памяти
        if (faction != null) {
            plugin.getLogger().info("Deleting faction " + faction.getName() + " (ID: " + id + ")");
            // Очищаем данные игроков этой фракции (онлайн кэш и БД)
            playerManager.clearFactionDataFor(id); // Теперь этот метод существует в PlayerManager

            // Удаляем данные из factions.yml
            FileConfiguration factionsConfig = configManager.getFactionsConfig();
            factionsConfig.set("factions." + id, null); // Удаляем секцию
            configManager.saveFactionsConfig(); // Сохраняем файл

            modifiedFactions.remove(id); // Убираем из измененных, если был там
            plugin.getLogger().info("Faction " + faction.getName() + " (ID: " + id + ") deleted successfully.");

            // TODO: Удалить территорию Dynmap, если она привязана к этой фракции?
            // plugin.getDynmapManager().removeTerritoriesForFaction(id);
        } else {
            plugin.getLogger().warning("Faction with ID " + id + " not found for deletion.");
        }
    }

    // --- Доступ к данным ---

    public Faction getFaction(String id) {
        return id != null ? factions.get(id.toLowerCase()) : null;
    }

    public Map<String, Faction> getAllFactions() {
        return Collections.unmodifiableMap(factions); // Возвращаем неизменяемую копию
    }

    public void reloadFactions() {
        plugin.getLogger().info("Reloading factions from factions.yml...");
        saveModifiedFactions(); // Сохраняем все несохраненные изменения перед перезагрузкой
        loadFactions();
        // TODO: Пройтись по онлайн игрокам и проверить валидность их фракции/ранга после перезагрузки?
        playerManager.validatePlayerDataAfterReload(); // Нужен такой метод в PlayerManager
    }

    /**
     * Помечает фракцию как измененную, чтобы она была сохранена при автосохранении.
     * @param factionId ID фракции
     */
    public void markFactionAsModified(String factionId) {
        if (factionId != null) {
            String lowerId = factionId.toLowerCase();
            if (factions.containsKey(lowerId)) { // Убедимся, что фракция еще существует
                modifiedFactions.add(lowerId);
                plugin.getLogger().fine("Faction " + lowerId + " marked as modified.");
            }
        }
    }

    // --- Управление рангами (сохранение имени) ---

    public void updateRankDisplayName(String factionId, int rankId, String newDisplayName) {
        Faction faction = getFaction(factionId);
        if (faction != null) {
            FactionRank rank = faction.getRank(rankId);
            if (rank != null) {
                rank.setDisplayName(newDisplayName);
                // Обновляем ранг внутри объекта фракции (не обязательно, т.к. ссылка та же)
                // faction.updateRank(rank); // Метод updateRank в Faction не нужен, если меняем объект по ссылке
                markFactionAsModified(factionId); // Просто помечаем фракцию
                // Сохранение произойдет автоматически по таймеру
            }
        }
    }

    public void resetRankDisplayName(String factionId, int rankId) {
        Faction faction = getFaction(factionId);
        if (faction != null) {
            FactionRank rank = faction.getRank(rankId);
            if (rank != null) {
                rank.resetDisplayName();
                // faction.updateRank(rank); // Не нужно
                markFactionAsModified(factionId);
            }
        }
    }

    // --- Управление балансом ---
    public void setFactionBalance(String factionId, double amount) {
        Faction faction = getFaction(factionId);
        if (faction != null) {
            faction.setBalance(amount);
            markFactionAsModified(factionId);
        }
    }

    public boolean depositToFaction(String factionId, double amount) {
        Faction faction = getFaction(factionId);
        if (faction != null && amount > 0) {
            faction.deposit(amount);
            markFactionAsModified(factionId);
            return true;
        }
        return false;
    }

    public boolean withdrawFromFaction(String factionId, double amount) {
        Faction faction = getFaction(factionId);
        if (faction != null && amount > 0) {
            boolean success = faction.withdraw(amount);
            if(success) {
                markFactionAsModified(factionId);
            }
            return success;
        }
        return false;
    }
}