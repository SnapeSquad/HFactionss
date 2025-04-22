package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.util.Utils; // Импортируем наш Utils

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class FactionManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final PlayerManager playerManager; // Нужен для очистки данных при удалении фракции
    private final Map<String, Faction> factions = new ConcurrentHashMap<>();
    private final Set<String> modifiedFactions = ConcurrentHashMap.newKeySet();

    public FactionManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.playerManager = plugin.getPlayerManager(); // Инициализируем
    }

    // --- Загрузка Фракций ---
    public void loadFactions() {
        factions.clear();
        modifiedFactions.clear();
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");

        if (factionsSection == null) {
            plugin.getLogger().warning("No 'factions' section found in factions.yml!");
            return;
        }

        int loadedCount = 0;
        for (String factionId : factionsSection.getKeys(false)) {
            ConfigurationSection data = factionsSection.getConfigurationSection(factionId);
            if (data == null) {
                plugin.getLogger().warning("Invalid configuration for faction ID: " + factionId);
                continue;
            }

            String currentFactionIdLower = factionId.toLowerCase(); // Работаем с lowercase ID

            try {
                String name = data.getString("name", "Unnamed Faction");
                FactionType type = FactionType.valueOf(data.getString("type", "OTHER").toUpperCase());
                String color = data.getString("color", "#FFFFFF");
                String prefix = data.getString("prefix", "[" + currentFactionIdLower.toUpperCase() + "]");
                // Баланс теперь сохраняется и загружается ТЕКУЩИЙ, не только initial
                double balance = data.getDouble("balance", 0.0); // Используем поле balance
                int warehouseSize = data.getInt("warehouse_size", 54);

                Map<Integer, FactionRank> ranks = loadRanks(data.getConfigurationSection("ranks"), currentFactionIdLower);

                String warehouseBase64 = data.getString("warehouse_contents_base64");
                ItemStack[] warehouseContents = Utils.itemStackArrayFromBase64(warehouseBase64);


                Faction faction = new Faction(currentFactionIdLower, name, type, color, prefix, balance, warehouseSize, ranks, warehouseContents);
                factions.put(currentFactionIdLower, faction);
                loadedCount++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid FactionType for faction " + currentFactionIdLower + ": " + data.getString("type") + ". Using OTHER.");
                // Создаем фракцию с типом OTHER, чтобы избежать полной ошибки загрузки
                try {
                    String name = data.getString("name", "Unnamed Faction");
                    String color = data.getString("color", "#FFFFFF");
                    String prefix = data.getString("prefix", "[" + currentFactionIdLower.toUpperCase() + "]");
                    double balance = data.getDouble("balance", 0.0);
                    int warehouseSize = data.getInt("warehouse_size", 54);
                    Map<Integer, FactionRank> ranks = loadRanks(data.getConfigurationSection("ranks"), currentFactionIdLower);
                    String warehouseBase64 = data.getString("warehouse_contents_base64");
                    ItemStack[] warehouseContents = Utils.itemStackArrayFromBase64(warehouseBase64);

                    Faction faction = new Faction(currentFactionIdLower, name, FactionType.OTHER, color, prefix, balance, warehouseSize, ranks, warehouseContents);
                    factions.put(currentFactionIdLower, faction);
                    loadedCount++; // Все равно считаем загруженной
                } catch (Exception innerE) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to load faction even with FactionType.OTHER: " + currentFactionIdLower, innerE);
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load faction: " + currentFactionIdLower, e);
            }
        }
        plugin.getLogger().info("Loaded " + loadedCount + " factions.");
    }

    private Map<Integer, FactionRank> loadRanks(ConfigurationSection ranksSection, String factionId) {
        Map<Integer, FactionRank> ranks = new ConcurrentHashMap<>();
        if (ranksSection == null) {
            plugin.getLogger().warning("No 'ranks' section found for faction: " + factionId + ". Creating default rank 1 and 11.");
            ranks.put(1, new FactionRank(1, "Rank 1", null, 0.0, new ArrayList<>())); // displayName null initially
            // Добавляем Лидера по умолчанию
            List<String> leaderPerms = getDefaultLeaderPermissions();
            ranks.put(11, new FactionRank(11, "Leader", null, 1000.0, leaderPerms)); // displayName null initially
            return ranks;
        }

        boolean rank1Exists = false;
        boolean rank11Exists = false;

        for (String rankIdStr : ranksSection.getKeys(false)) {
            try {
                int rankId = Integer.parseInt(rankIdStr);
                ConfigurationSection rankData = ranksSection.getConfigurationSection(rankIdStr);
                if (rankData == null) continue;

                String defaultName = rankData.getString("defaultName", "Rank " + rankId);
                // Загружаем displayName, если он есть и не пустой
                String displayName = rankData.getString("displayName");
                if (displayName != null && displayName.isEmpty()) displayName = null; // Считаем пустую строку как отсутствие кастомного имени

                double salary = rankData.getDouble("salary", 0.0);
                List<String> permissions = rankData.getStringList("permissions");

                ranks.put(rankId, new FactionRank(rankId, defaultName, displayName, salary, permissions));
                if (rankId == 1) rank1Exists = true;
                if (rankId == 11) rank11Exists = true;

            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid rank ID '" + rankIdStr + "' for faction " + factionId + ". Skipping.");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load rank " + rankIdStr + " for faction " + factionId, e);
            }
        }
        // Убедимся, что ранги 1 и 11 существуют
        if (!rank1Exists) {
            plugin.getLogger().warning("Rank 1 not found for faction: " + factionId + ". Creating default rank 1.");
            ranks.put(1, new FactionRank(1, "Rank 1", null, 0.0, new ArrayList<>()));
        }
        if (!rank11Exists) {
            plugin.getLogger().warning("Rank 11 (Leader) not found for faction: " + factionId + ". Creating default Leader rank.");
            List<String> leaderPerms = getDefaultLeaderPermissions();
            ranks.put(11, new FactionRank(11, "Leader", null, 1000.0, leaderPerms));
        }
        return ranks;
    }

    // Вспомогательный метод для получения дефолтных прав лидера
    private List<String> getDefaultLeaderPermissions() {
        return Arrays.asList(
                "hfactions.faction.invite", "hfactions.faction.kick", "hfactions.faction.promote",
                "hfactions.faction.demote", "hfactions.faction.setrank", "hfactions.faction.manage_ranks",
                "hfactions.faction.balance.view", "hfactions.faction.deposit", "hfactions.faction.withdraw",
                "hfactions.faction.manage_balance", "hfactions.faction.warehouse.open",
                "hfactions.faction.warehouse.deposit", "hfactions.faction.warehouse.withdraw"
                // Добавьте специфичные права фракции, если нужно, например hfactions.pd.fine для лидера PD
        );
    }


    // --- Сохранение Фракций ---

    public void saveAllFactions() {
        plugin.getLogger().info("Saving all factions data...");
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        factionsConfig.set("factions", null); // Очищаем старую секцию
        ConfigurationSection factionsSection = factionsConfig.createSection("factions");

        int savedCount = 0;
        for (Faction faction : factions.values()) {
            saveFactionData(faction, factionsSection);
            savedCount++;
        }
        configManager.saveFactionsConfig();
        modifiedFactions.clear();
        plugin.getLogger().info("Saved data for " + savedCount + " factions.");
    }

    public void saveModifiedFactions() {
        if (modifiedFactions.isEmpty()) return;

        plugin.getLogger().info("Saving data for " + modifiedFactions.size() + " modified factions...");
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");
        if (factionsSection == null) {
            factionsSection = factionsConfig.createSection("factions");
        }

        int savedCount = 0;
        Set<String> savedIds = new HashSet<>();

        // Создаем копию Set для итерации, чтобы избежать ConcurrentModificationException, если markAsModified вызовется во время сохранения
        Set<String> factionsToSave = new HashSet<>(modifiedFactions);

        for (String factionId : factionsToSave) {
            Faction faction = factions.get(factionId);
            if (faction != null) {
                try { // Обертка для безопасности
                    saveFactionData(faction, factionsSection);
                    savedIds.add(factionId);
                    savedCount++;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save data for modified faction: " + factionId, e);
                }
            } else {
                // Если фракции уже нет в мапе, но она была помечена как измененная (например, удалена перед сохранением)
                savedIds.add(factionId); // Считаем обработанной, чтобы убрать из modifiedFactions
                plugin.getLogger().warning("Tried to save modified faction " + factionId + ", but it was not found in memory.");
            }
        }

        if (savedCount > 0) {
            configManager.saveFactionsConfig();
            plugin.getLogger().info("Saved data for " + savedCount + " modified factions.");
        }
        modifiedFactions.removeAll(savedIds); // Очищаем флаги только для обработанных
        if (!modifiedFactions.isEmpty()) {
            plugin.getLogger().warning("Some factions were marked modified during the save process and were not saved in this cycle.");
        }
    }

    private void saveFactionData(Faction faction, ConfigurationSection factionsSection) {
        // Используем ID фракции (lowercase) как ключ секции
        ConfigurationSection data = factionsSection.createSection(faction.getId());

        data.set("name", faction.getName());
        data.set("type", faction.getType().name());
        data.set("color", faction.getColor());
        data.set("prefix", faction.getPrefix());
        // Сохраняем ТЕКУЩИЙ баланс
        data.set("balance", faction.getBalance());
        data.set("warehouse_size", faction.getWarehouseSize());

        ConfigurationSection ranksSection = data.createSection("ranks");
        for (Map.Entry<Integer, FactionRank> entry : faction.getRanks().entrySet()) {
            ConfigurationSection rankData = ranksSection.createSection(String.valueOf(entry.getKey()));
            FactionRank rank = entry.getValue();
            rankData.set("defaultName", rank.getDefaultName());
            // Сохраняем displayName только если он задан и не совпадает с defaultName
            if (rank.getDisplayName() != null && !rank.getDisplayName().equals(rank.getDefaultName())) {
                rankData.set("displayName", rank.getDisplayName());
            } else {
                // Иначе не сохраняем поле displayName или ставим null, чтобы оно удалилось при сохранении YAML
                rankData.set("displayName", null);
            }
            rankData.set("salary", rank.getSalary());
            rankData.set("permissions", rank.getPermissions());
        }

        String warehouseBase64 = Utils.itemStackArrayToBase64(faction.getWarehouseContents());
        // Сохраняем даже если null или пусто (чтобы очистить старые данные, если склад опустел)
        data.set("warehouse_contents_base64", warehouseBase64);
    }

    // --- Управление фракциями ---

    public boolean createFaction(String id, String name, FactionType type, String color, String prefix, double balance, int warehouseSize) {
        String idLower = id.toLowerCase();
        if (factions.containsKey(idLower)) {
            plugin.getLogger().warning("Faction with ID " + idLower + " already exists.");
            return false;
        }

        Map<Integer, FactionRank> defaultRanks = new HashMap<>();
        defaultRanks.put(1, new FactionRank(1, "Recruit", null, 0.0, new ArrayList<>()));
        defaultRanks.put(11, new FactionRank(11, "Leader", null, 1000.0, getDefaultLeaderPermissions()));

        Faction faction = new Faction(idLower, name, type, color, prefix, balance, warehouseSize, defaultRanks, null); // Склад null при создании

        factions.put(idLower, faction);
        markFactionAsModified(idLower);
        saveModifiedFactions(); // Сохраняем сразу
        plugin.getLogger().info("Faction " + name + " (ID: " + idLower + ") created.");
        return true;
    }

    public boolean deleteFaction(String id) {
        String idLower = id.toLowerCase();
        Faction removedFaction = factions.remove(idLower);

        if (removedFaction != null) {
            plugin.getLogger().info("Deleting faction " + removedFaction.getName() + " (ID: " + idLower + ")");

            // Очищаем данные игроков этой фракции (синхронно, так как это админ команда)
            playerManager.clearFactionDataFor(idLower);

            // Удаляем данные из factions.yml
            FileConfiguration factionsConfig = configManager.getFactionsConfig();
            factionsConfig.set("factions." + idLower, null);
            configManager.saveFactionsConfig();

            modifiedFactions.remove(idLower); // Убираем из измененных
            plugin.getLogger().info("Faction " + removedFaction.getName() + " deleted successfully.");
            return true;
        } else {
            plugin.getLogger().warning("Faction with ID " + idLower + " not found for deletion.");
            return false;
        }
    }

    // --- Доступ к данным ---

    public Faction getFaction(String id) {
        return id != null ? factions.get(id.toLowerCase()) : null;
    }

    public Map<String, Faction> getAllFactions() {
        return Collections.unmodifiableMap(factions);
    }

    public void reloadFactions() {
        plugin.getLogger().info("Reloading factions from factions.yml...");
        // Сначала сохраняем все несохраненные изменения, чтобы не потерять их
        saveModifiedFactions();
        // Затем перезагружаем из файла
        loadFactions();
        // TODO: Проверить онлайн игроков и обновить их данные, если их фракция изменилась/удалилась
        playerManager.validatePlayerDataForAllOnline(); // Нужен такой метод в PlayerManager
    }

    public void markFactionAsModified(String factionId) {
        if (factionId != null && factions.containsKey(factionId.toLowerCase())) {
            modifiedFactions.add(factionId.toLowerCase());
            plugin.getLogger().fine("Faction " + factionId + " marked as modified.");
            // Можно запланировать отложенное сохранение, если нужно
            // scheduleSaveTask();
        }
    }


    // --- Управление рангами (сохранение имени) ---

    public void updateRankDisplayName(String factionId, int rankId, String newDisplayName) {
        Faction faction = getFaction(factionId);
        if (faction != null) {
            FactionRank rank = faction.getRank(rankId);
            if (rank != null) {
                // Устанавливаем null если строка пустая, иначе само имя
                rank.setDisplayName( (newDisplayName != null && !newDisplayName.trim().isEmpty()) ? newDisplayName.trim() : null );
                // Не нужно вызывать faction.updateRank, так как мы меняем объект Rank напрямую
                markFactionAsModified(faction.getId());
                // saveModifiedFactions(); // Сохранять сразу или нет? Пока нет. Сохранится при закрытии склада или по таймеру/команде.
            }
        }
    }

    public void resetRankDisplayName(String factionId, int rankId) {
        Faction faction = getFaction(factionId);
        if (faction != null) {
            FactionRank rank = faction.getRank(rankId);
            if (rank != null) {
                rank.resetDisplayName(); // Метод в FactionRank устанавливает displayName = defaultName
                rank.setDisplayName(null); // Сбрасываем кастомное имя на null
                markFactionAsModified(faction.getId());
                // saveModifiedFactions(); // Не сохраняем сразу
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
            if (success) {
                markFactionAsModified(factionId);
            }
            return success;
        }
        return false;
    }

    // Можно добавить таймер автосохранения
    public void startAutoSaveTask(long intervalTicks) {
        if (intervalTicks <= 0) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveModifiedFactions, intervalTicks, intervalTicks);
        plugin.getLogger().info("Faction auto-save task scheduled every " + (intervalTicks / 20.0) + " seconds.");
    }
}