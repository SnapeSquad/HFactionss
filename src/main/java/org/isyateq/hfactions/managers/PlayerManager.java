package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit; // Для планировщика
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.util.Utils; // Для сериализации склада

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class FactionManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final PlayerManager playerManager; // Нужен для очистки данных при удалении фракции
    private final Map<String, Faction> factions = new ConcurrentHashMap<>();
    private final Set<String> modifiedFactions = ConcurrentHashMap.newKeySet(); // Храним ID измененных фракций
    private static final long AUTO_SAVE_DELAY_TICKS = 20L * 5; // 5 секунд задержки перед автосохранением после изменения
    private volatile boolean saveTaskScheduled = false; // Флаг, что задача сохранения уже запланирована

    public FactionManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        // PlayerManager будет получен позже, когда он понадобится, чтобы избежать циклической зависимости при инициализации
        this.playerManager = null; // Инициализируем как null
    }

    // Ленивая инициализация PlayerManager
    private PlayerManager getPlayerManager() {
        // Если PlayerManager еще не был получен, получаем его сейчас
        // Это решает проблему циклической зависимости при создании менеджеров
        if (this.playerManager == null) {
            // Возвращаем null или выбрасываем исключение, если plugin.getPlayerManager() недоступен?
            // Предполагаем, что к моменту вызова методов, требующих playerManager, он уже будет доступен
            return plugin.getPlayerManager();
        }
        return this.playerManager;
    }


    // --- Загрузка Фракций ---
    public void loadFactions() {
        factions.clear(); // Очищаем старые данные перед загрузкой
        modifiedFactions.clear(); // Сбрасываем флаги модификации

        if (configManager == null) {
            plugin.getLogger().severe("Cannot load factions: ConfigManager is null.");
            return;
        }
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        if (factionsConfig == null) {
            plugin.getLogger().severe("Cannot load factions: factions.yml config object is null.");
            return;
        }
        ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");

        if (factionsSection == null) {
            plugin.getLogger().warning("No 'factions' section found in factions.yml!");
            return;
        }

        int loadedCount = 0;
        for (String factionId : factionsSection.getKeys(false)) {
            // Приводим ID к нижнему регистру для консистентности
            String lowerCaseFactionId = factionId.toLowerCase();
            ConfigurationSection data = factionsSection.getConfigurationSection(factionId);
            if (data == null) {
                plugin.getLogger().warning("Invalid configuration section for faction ID: " + factionId);
                continue;
            }

            try {
                String name = data.getString("name", "Unnamed Faction");
                FactionType type = FactionType.OTHER; // Значение по умолчанию
                try {
                    String typeStr = data.getString("type", "OTHER").toUpperCase();
                    type = FactionType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid FactionType '" + data.getString("type") + "' for faction " + factionId + ". Using OTHER.");
                }

                String color = data.getString("color", "#FFFFFF");
                String prefix = data.getString("prefix", "[" + lowerCaseFactionId.toUpperCase() + "]");
                // Загружаем СОХРАНЕННЫЙ баланс, а не начальный
                double balance = data.getDouble("balance", 0.0); // Используем поле "balance"
                int warehouseSize = data.getInt("warehouse_size", 54);

                // Загрузка рангов
                Map<Integer, FactionRank> ranks = loadRanks(data.getConfigurationSection("ranks"), lowerCaseFactionId);

                // Загрузка склада (десериализация из Base64)
                String warehouseBase64 = data.getString("warehouse_contents_base64");
                ItemStack[] warehouseContents = Utils.itemStackArrayFromBase64(warehouseBase64);

                Faction faction = new Faction(lowerCaseFactionId, name, type, color, prefix, balance, warehouseSize, ranks, warehouseContents);
                factions.put(lowerCaseFactionId, faction);
                loadedCount++;
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
            ranks.put(1, new FactionRank(1, "Rank 1", "Rank 1", 0.0, new ArrayList<>()));
            ranks.put(11, new FactionRank(11, "Leader", "Leader", 0.0, getDefaultLeaderPerms())); // Лидер по умолчанию
            return ranks;
        }

        for (String rankIdStr : ranksSection.getKeys(false)) {
            try {
                int rankId = Integer.parseInt(rankIdStr);
                ConfigurationSection rankData = ranksSection.getConfigurationSection(rankIdStr);
                if (rankData == null) {
                    plugin.getLogger().warning("Invalid rank data for rank ID " + rankIdStr + " in faction " + factionId);
                    continue;
                }

                String defaultName = rankData.getString("defaultName", "Rank " + rankId);
                String displayName = rankData.getString("displayName", defaultName);
                double salary = rankData.getDouble("salary", 0.0);
                List<String> permissions = rankData.getStringList("permissions");
                if (permissions == null) { // getStringList возвращает пустой список, а не null, но на всякий случай
                    permissions = new ArrayList<>();
                }

                ranks.put(rankId, new FactionRank(rankId, defaultName, displayName, salary, permissions));

            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid rank ID format '" + rankIdStr + "' for faction " + factionId + ". Skipping.");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load rank " + rankIdStr + " for faction " + factionId, e);
            }
        }
        // Убедимся, что ранг 1 и 11 существуют
        if (!ranks.containsKey(1)) {
            plugin.getLogger().warning("Rank 1 not found for faction: " + factionId + ". Creating default rank 1.");
            ranks.put(1, new FactionRank(1, "Rank 1", "Rank 1", 0.0, new ArrayList<>()));
        }
        if (!ranks.containsKey(11)) {
            plugin.getLogger().warning("Rank 11 (Leader) not found for faction: " + factionId + ". Creating default leader rank.");
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
        if (configManager == null) {
            plugin.getLogger().severe("Cannot save factions: ConfigManager is null.");
            return;
        }
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        if (factionsConfig == null) {
            plugin.getLogger().severe("Cannot save factions: factions.yml config object is null.");
            return;
        }
        // Очищаем старую секцию factions полностью перед записью
        factionsConfig.set("factions", null);
        // Используем createSection чтобы гарантировать наличие секции
        ConfigurationSection factionsSection = factionsConfig.createSection("factions");

        int savedCount = 0;
        for (Faction faction : factions.values()) {
            if (saveFactionData(faction, factionsSection)) { // Проверяем результат сохранения
                savedCount++;
            }
        }
        configManager.saveFactionsConfig(); // Сохраняем файл factions.yml
        modifiedFactions.clear(); // Сбрасываем флаги после полного сохранения
        plugin.getLogger().info("Saved data for " + savedCount + " factions.");
    }

    /**
     * Сохраняет только измененные фракции. Вызывается автоматически или по команде.
     */
    public synchronized void saveModifiedFactions() { // Синхронизировано для управления флагом saveTaskScheduled
        if (modifiedFactions.isEmpty()) {
            // plugin.getLogger().fine("No modified factions to save."); // Можно раскомментировать для отладки
            return; // Нечего сохранять
        }
        plugin.getLogger().info("Saving data for " + modifiedFactions.size() + " modified factions...");

        if (configManager == null) {
            plugin.getLogger().severe("Cannot save modified factions: ConfigManager is null.");
            // Очищаем флаги, т.к. сохранить не удалось
            modifiedFactions.clear();
            saveTaskScheduled = false; // Сбрасываем флаг задачи
            return;
        }
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        if (factionsConfig == null) {
            plugin.getLogger().severe("Cannot save modified factions: factions.yml config object is null.");
            modifiedFactions.clear();
            saveTaskScheduled = false;
            return;
        }
        ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");
        if (factionsSection == null) {
            plugin.getLogger().warning("Creating 'factions' section in factions.yml during save.");
            factionsSection = factionsConfig.createSection("factions");
        }

        int savedCount = 0;
        // Создаем копию сета, чтобы избежать ConcurrentModificationException, если markFactionAsModified вызовется во время сохранения
        Set<String> factionsToSave = new HashSet<>(modifiedFactions);
        // Очищаем оригинальный сет ДО начала сохранения, чтобы новые изменения попали в следующую итерацию
        modifiedFactions.clear();


        for (String factionId : factionsToSave) {
            Faction faction = factions.get(factionId); // ID уже в lowercase
            if (faction != null) {
                if (saveFactionData(faction, factionsSection)) {
                    savedCount++;
                } else {
                    // Если сохранение не удалось, возвращаем ID в сет измененных? Спорно. Пока просто логируем.
                    plugin.getLogger().severe("Failed to save data for faction " + factionId + " during modified save.");
                }
            } else {
                plugin.getLogger().warning("Faction " + factionId + " marked as modified but not found in memory. Removing from config if exists.");
                // Удаляем секцию из конфига, если фракции больше нет в памяти
                if (factionsSection.contains(factionId)) {
                    factionsSection.set(factionId, null);
                    plugin.getLogger().info("Removed config section for non-existent faction: " + factionId);
                }
            }
        }

        if (savedCount > 0 || !factionsToSave.isEmpty()) { // Сохраняем файл, даже если удалили секции несуществующих фракций
            configManager.saveFactionsConfig(); // Сохраняем файл
            plugin.getLogger().info("Attempted to save data for " + factionsToSave.size() + " factions, successfully saved " + savedCount + ".");
        }
        // Сбрасываем флаг планировщика ПОСЛЕ завершения сохранения
        saveTaskScheduled = false;
    }


    // Вспомогательный метод для сохранения данных одной фракции
    // Возвращает true при успехе, false при ошибке
    private boolean saveFactionData(Faction faction, ConfigurationSection factionsSection) {
        if (faction == null || factionsSection == null) {
            return false;
        }
        try {
            // Используем ID фракции как ключ секции
            ConfigurationSection data = factionsSection.createSection(faction.getId());

            data.set("name", faction.getName());
            data.set("type", faction.getType().name());
            data.set("color", faction.getColor());
            data.set("prefix", faction.getPrefix());
            // Сохраняем ТЕКУЩИЙ баланс
            data.set("balance", faction.getBalance());
            data.set("warehouse_size", faction.getWarehouseSize());

            // Сохранение рангов (включая измененные displayName)
            ConfigurationSection ranksSection = data.createSection("ranks");
            if (faction.getRanks() != null) { // Проверка на null
                for (Map.Entry<Integer, FactionRank> entry : faction.getRanks().entrySet()) {
                    if (entry == null || entry.getValue() == null) continue; // Пропускаем невалидные записи
                    FactionRank rank = entry.getValue();
                    ConfigurationSection rankData = ranksSection.createSection(String.valueOf(entry.getKey()));

                    rankData.set("defaultName", rank.getDefaultName());
                    // Сохраняем displayName только если он отличается от defaultName
                    if (rank.getDisplayName() != null && !rank.getDisplayName().equals(rank.getDefaultName())) {
                        rankData.set("displayName", rank.getDisplayName());
                    } else {
                        // Убедимся, что поле удалено, если имя сброшено к дефолту или null
                        rankData.set("displayName", null);
                    }
                    rankData.set("salary", rank.getSalary());
                    rankData.set("permissions", rank.getPermissions()); // Сохраняем список прав
                }
            }

            // Сохранение склада (сериализация в Base64)
            ItemStack[] warehouseContents = faction.getWarehouseContents();
            if (warehouseContents != null) { // Проверка на null перед сериализацией
                String warehouseBase64 = Utils.itemStackArrayToBase64(warehouseContents);
                // Сохраняем строку, даже если она null (означает ошибку сериализации или пустой склад)
                data.set("warehouse_contents_base64", warehouseBase64);
            } else {
                data.set("warehouse_contents_base64", null); // Явно ставим null, если массив склада null
            }
            return true; // Успешно подготовили данные
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error preparing data for faction " + faction.getId(), e);
            return false; // Ошибка при подготовке данных
        }
    }


    // --- Управление фракциями ---

    public void createFaction(String id, String name, FactionType type, String color, String prefix, double balance, int warehouseSize) {
        if (id == null || name == null || type == null || color == null || prefix == null) {
            plugin.getLogger().severe("Cannot create faction: One or more arguments are null.");
            return;
        }
        String lowerCaseId = id.toLowerCase();
        if (factions.containsKey(lowerCaseId)) {
            plugin.getLogger().warning("Faction with ID " + lowerCaseId + " already exists.");
            return;
        }

        // Создаем фракцию
        Faction faction = new Faction(lowerCaseId, name, type, color, prefix, balance, warehouseSize);

        // Добавляем обязательные ранги
        faction.addRank(new FactionRank(1, "Recruit", "Recruit", 0.0, new ArrayList<>()));
        faction.addRank(new FactionRank(11, "Leader", "Leader", 1000.0, getDefaultLeaderPerms()));

        factions.put(lowerCaseId, faction);
        markFactionAsModified(lowerCaseId); // Отмечаем для сохранения
        // Не вызываем saveModifiedFactions() сразу, даем сработать автосохранению или команде
        plugin.getLogger().info("Faction " + name + " (ID: " + lowerCaseId + ") created.");
    }

    public boolean deleteFaction(String id) {
        if (id == null) return false;
        String lowerCaseId = id.toLowerCase();
        Faction faction = factions.remove(lowerCaseId); // Удаляем из памяти

        if (faction != null) {
            plugin.getLogger().info("Deleting faction " + faction.getName() + " (ID: " + lowerCaseId + ")");
            // Очищаем данные игроков этой фракции (если PlayerManager доступен)
            PlayerManager pm = getPlayerManager();
            if (pm != null) {
                pm.clearFactionDataFor(lowerCaseId);
            } else {
                plugin.getLogger().warning("PlayerManager not available, cannot clear player data for deleted faction " + lowerCaseId);
            }

            // Удаляем данные из factions.yml
            boolean removedFromConfig = false;
            if (configManager != null) {
                FileConfiguration factionsConfig = configManager.getFactionsConfig();
                if (factionsConfig != null && factionsConfig.contains("factions." + lowerCaseId)) {
                    factionsConfig.set("factions." + lowerCaseId, null); // Удаляем секцию
                    configManager.saveFactionsConfig(); // Сохраняем файл сразу после удаления
                    removedFromConfig = true;
                    plugin.getLogger().info("Removed faction " + lowerCaseId + " from factions.yml.");
                }
            } else {
                plugin.getLogger().severe("Cannot remove faction from config: ConfigManager is null.");
            }

            modifiedFactions.remove(lowerCaseId); // Убираем из измененных, если был там
            return true; // Успешно удалили из памяти (и, возможно, из конфига)
        } else {
            plugin.getLogger().warning("Faction with ID " + lowerCaseId + " not found for deletion.");
            return false;
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
        // Важно: При перезагрузке несохраненные изменения в памяти будут потеряны.
        // Это ожидаемое поведение для команды reload.
        // Если нужно сохранять перед reload, это должна быть отдельная команда/логика.
        loadFactions();
        // Проверка онлайн игроков не требуется, т.к. их данные загружаются при входе/выходе
        // или уже были загружены. Если фракция удалилась, они получат null при запросе.
        plugin.getLogger().info("Factions reloaded.");
    }

    /**
     * Помечает фракцию как измененную и планирует отложенное сохранение.
     */
    public void markFactionAsModified(String factionId) {
        if (factionId == null) return;
        String lowerCaseId = factionId.toLowerCase();
        if (factions.containsKey(lowerCaseId)) { // Проверяем, что такая фракция еще существует
            boolean added = modifiedFactions.add(lowerCaseId);
            if (added) { // Планируем сохранение только если ID был добавлен (т.е. еще не был помечен)
                plugin.getLogger().fine("Faction " + lowerCaseId + " marked as modified.");
                scheduleSaveTask(); // Планируем отложенное сохранение
            }
        } else {
            plugin.getLogger().warning("Attempted to mark non-existent faction " + lowerCaseId + " as modified.");
        }
    }

    /**
     * Планирует однократное отложенное сохранение измененных фракций.
     */
    private synchronized void scheduleSaveTask() { // Синхронизировано для флага
        // Планируем задачу, только если она еще не запланирована
        if (!saveTaskScheduled) {
            saveTaskScheduled = true;
            plugin.getLogger().fine("Scheduling auto-save for modified factions in " + AUTO_SAVE_DELAY_TICKS + " ticks.");
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                try {
                    saveModifiedFactions(); // Вызов синхронизированного метода сохранения
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error during scheduled faction save task!", e);
                    // Сбрасываем флаг в случае ошибки, чтобы можно было попробовать снова
                    saveTaskScheduled = false;
                }
            }, AUTO_SAVE_DELAY_TICKS);
        }
    }

    // --- Управление рангами (сохранение имени) ---

    public void updateRankDisplayName(String factionId, int rankId, String newDisplayName) {
        if (factionId == null || newDisplayName == null) return;
        Faction faction = getFaction(factionId); // ID уже будет в lowercase
        if (faction != null) {
            FactionRank rank = faction.getRank(rankId);
            if (rank != null) {
                // Устанавливаем имя только если оно реально изменилось
                if (!newDisplayName.equals(rank.getDisplayName())) {
                    rank.setDisplayName(newDisplayName);
                    faction.updateRank(rank); // Помечает фракцию как измененную внутри
                }
            } else {
                plugin.getLogger().warning("Cannot update display name: Rank " + rankId + " not found in faction " + factionId);
            }
        } else {
            plugin.getLogger().warning("Cannot update display name: Faction " + factionId + " not found.");
        }
    }

    public void resetRankDisplayName(String factionId, int rankId) {
        if (factionId == null) return;
        Faction faction = getFaction(factionId);
        if (faction != null) {
            FactionRank rank = faction.getRank(rankId);
            if (rank != null) {
                // Сбрасываем, только если имя отличается от дефолтного
                if (rank.getDisplayName() != null && !rank.getDisplayName().equals(rank.getDefaultName())) {
                    rank.resetDisplayName();
                    faction.updateRank(rank); // Помечает фракцию как измененную внутри
                }
            } else {
                plugin.getLogger().warning("Cannot reset display name: Rank " + rankId + " not found in faction " + factionId);
            }
        } else {
            plugin.getLogger().warning("Cannot reset display name: Faction " + factionId + " not found.");
        }
    }

    // --- Управление балансом ---
    public void setFactionBalance(String factionId, double amount) {
        if (factionId == null) return;
        Faction faction = getFaction(factionId);
        if (faction != null) {
            double sanitizedAmount = Math.max(0, amount); // Баланс не может быть отрицательным
            if (faction.getBalance() != sanitizedAmount) { // Изменяем и помечаем только если значение отличается
                faction.setBalance(sanitizedAmount);
                markFactionAsModified(faction.getId());
            }
        } else {
            plugin.getLogger().warning("Cannot set balance: Faction " + factionId + " not found.");
        }
    }

    public boolean depositToFaction(String factionId, double amount) {
        if (factionId == null || amount <= 0) return false;
        Faction faction = getFaction(factionId);
        if (faction != null) {
            faction.deposit(amount);
            markFactionAsModified(faction.getId());
            return true;
        }
        plugin.getLogger().warning("Cannot deposit to faction: Faction " + factionId + " not found.");
        return false;
    }

    public boolean withdrawFromFaction(String factionId, double amount) {
        if (factionId == null || amount <= 0) return false;
        Faction faction = getFaction(factionId);
        if (faction != null) {
            boolean success = faction.withdraw(amount);
            if(success) {
                markFactionAsModified(faction.getId());
            }
            return success;
        }
        plugin.getLogger().warning("Cannot withdraw from faction: Faction " + factionId + " not found.");
        return false;
    }

    // --- Вспомогательные методы ---

    private List<String> getDefaultLeaderPerms() {
        // Возвращает стандартный набор прав для лидера
        return Arrays.asList(
                "hfactions.faction.invite", "hfactions.faction.kick", "hfactions.faction.promote",
                "hfactions.faction.demote", "hfactions.faction.setrank", "hfactions.faction.manage_ranks",
                "hfactions.faction.balance.view", "hfactions.faction.deposit", "hfactions.faction.withdraw",
                "hfactions.faction.manage_balance", "hfactions.faction.warehouse.open",
                "hfactions.faction.warehouse.deposit", "hfactions.faction.warehouse.withdraw"
                // Можно добавить права на управление территориями своей фракции
                ,"hfactions.territory.manage.own"
        );
    }
}