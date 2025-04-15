package org.isyateq.hfactions.managers;

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
import java.util.stream.Collectors;

public class FactionManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final Map<String, Faction> factions = new ConcurrentHashMap<>();
    private final Set<String> modifiedFactions = ConcurrentHashMap.newKeySet(); // Храним ID измененных фракций

    public FactionManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        // НЕ вызываем loadFactions() здесь, это делается в onEnable плагина
    }

    // --- Загрузка Фракций ---
    public void loadFactions() {
        factions.clear(); // Очищаем старые данные перед загрузкой
        modifiedFactions.clear(); // Сбрасываем флаги модификации
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

            try {
                String name = data.getString("name", "Unnamed Faction");
                FactionType type = FactionType.valueOf(data.getString("type", "OTHER").toUpperCase());
                String color = data.getString("color", "#FFFFFF");
                String prefix = data.getString("prefix", "[" + factionId.toUpperCase() + "]");
                double balance = data.getDouble("initial_balance", 0.0); // Загружаем начальный баланс
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
                // Можно продолжить загрузку с типом OTHER или пропустить фракцию
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load faction: " + factionId, e);
            }
        }
        plugin.getLogger().info("Loaded " + loadedCount + " factions.");
    }

    private Map<Integer, FactionRank> loadRanks(ConfigurationSection ranksSection, String factionId) {
        Map<Integer, FactionRank> ranks = new ConcurrentHashMap<>();
        if (ranksSection == null) {
            plugin.getLogger().warning("No 'ranks' section found for faction: " + factionId + ". Creating default rank 1.");
            // Создаем хотя бы ранг 1 по умолчанию
            ranks.put(1, new FactionRank(1, "Rank 1", "Rank 1", 0.0, new ArrayList<>()));
            return ranks;
        }

        for (String rankIdStr : ranksSection.getKeys(false)) {
            try {
                int rankId = Integer.parseInt(rankIdStr);
                ConfigurationSection rankData = ranksSection.getConfigurationSection(rankIdStr);
                if (rankData == null) continue;

                String defaultName = rankData.getString("defaultName", "Rank " + rankId);
                // Загружаем сохраненное displayName, если оно есть
                String displayName = rankData.getString("displayName", defaultName); // По умолчанию используем defaultName
                double salary = rankData.getDouble("salary", 0.0);
                List<String> permissions = rankData.getStringList("permissions");

                ranks.put(rankId, new FactionRank(rankId, defaultName, displayName, salary, permissions));

            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid rank ID '" + rankIdStr + "' for faction " + factionId + ". Skipping.");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load rank " + rankIdStr + " for faction " + factionId, e);
            }
        }
        // Убедимся, что ранг 1 существует
        if (!ranks.containsKey(1)) {
            plugin.getLogger().warning("Rank 1 not found for faction: " + factionId + ". Creating default rank 1.");
            ranks.put(1, new FactionRank(1, "Rank 1", "Rank 1", 0.0, new ArrayList<>()));
        }
        return ranks;
    }


    // --- Сохранение Фракций ---

    /**
     * Сохраняет ВСЕ фракции в factions.yml. Вызывать осторожно (например, при выключении).
     */
    public void saveAllFactions() {
        plugin.getLogger().info("Saving all factions data...");
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        // Очищаем старую секцию factions полностью перед записью
        factionsConfig.set("factions", null);
        ConfigurationSection factionsSection = factionsConfig.createSection("factions");

        int savedCount = 0;
        for (Faction faction : factions.values()) {
            saveFactionData(faction, factionsSection);
            savedCount++;
        }
        configManager.saveFactionsConfig(); // Сохраняем файл factions.yml
        modifiedFactions.clear(); // Сбрасываем флаги после полного сохранения
        plugin.getLogger().info("Saved data for " + savedCount + " factions.");
    }

    /**
     * Сохраняет только измененные фракции. Можно вызывать периодически или при изменении.
     */
    public void saveModifiedFactions() {
        if (modifiedFactions.isEmpty()) {
            return; // Нечего сохранять
        }
        plugin.getLogger().info("Saving data for " + modifiedFactions.size() + " modified factions...");
        FileConfiguration factionsConfig = configManager.getFactionsConfig();
        ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");
        if (factionsSection == null) {
            factionsSection = factionsConfig.createSection("factions");
        }

        int savedCount = 0;
        Set<String> savedIds = new HashSet<>(); // Чтобы не очищать флаг, если сохранение не удалось

        for (String factionId : modifiedFactions) {
            Faction faction = factions.get(factionId);
            if (faction != null) {
                saveFactionData(faction, factionsSection);
                savedIds.add(factionId);
                savedCount++;
            }
        }

        if (savedCount > 0) {
            configManager.saveFactionsConfig(); // Сохраняем файл
            modifiedFactions.removeAll(savedIds); // Очищаем флаги только для успешно сохраненных
            plugin.getLogger().info("Saved data for " + savedCount + " modified factions.");
        } else {
            plugin.getLogger().info("No modified factions were actually saved (perhaps they were deleted?).");
            modifiedFactions.clear(); // Очищаем в любом случае, чтобы избежать зацикливания
        }
    }


    // Вспомогательный метод для сохранения данных одной фракции
    private void saveFactionData(Faction faction, ConfigurationSection factionsSection) {
        ConfigurationSection data = factionsSection.createSection(faction.getId());

        data.set("name", faction.getName());
        data.set("type", faction.getType().name());
        data.set("color", faction.getColor());
        data.set("prefix", faction.getPrefix());
        data.set("initial_balance", faction.getBalance()); // Сохраняем ТЕКУЩИЙ баланс как initial? Или нужно отдельное поле? Пока так.
        data.set("warehouse_size", faction.getWarehouseSize());

        // Сохранение рангов (включая измененные displayName)
        ConfigurationSection ranksSection = data.createSection("ranks");
        for (Map.Entry<Integer, FactionRank> entry : faction.getRanks().entrySet()) {
            ConfigurationSection rankData = ranksSection.createSection(String.valueOf(entry.getKey()));
            FactionRank rank = entry.getValue();
            rankData.set("defaultName", rank.getDefaultName());
            // Сохраняем displayName только если он отличается от defaultName
            if (!rank.getDisplayName().equals(rank.getDefaultName())) {
                rankData.set("displayName", rank.getDisplayName());
            } else {
                rankData.set("displayName", null); // Удаляем поле, если имя сброшено к дефолту
            }
            rankData.set("salary", rank.getSalary());
            rankData.set("permissions", rank.getPermissions());
        }

        // Сохранение склада (сериализация в Base64)
        String warehouseBase64 = Utils.itemStackArrayToBase64(faction.getWarehouseContents());
        if (warehouseBase64 != null) {
            data.set("warehouse_contents_base64", warehouseBase64); // Новое поле
        } else {
            data.set("warehouse_contents_base64", null); // Убираем поле, если склад пуст или ошибка сериализации
        }
    }


    // --- Управление фракциями ---

    public void createFaction(String id, String name, FactionType type, String color, String prefix, double balance, int warehouseSize) {
        id = id.toLowerCase();
        if (factions.containsKey(id)) {
            // Ошибка: фракция уже существует
            plugin.getLogger().warning("Faction with ID " + id + " already exists.");
            return;
        }
        // Создаем фракцию с пустыми рангами (кроме ранга 1) и складом
        Faction faction = new Faction(id, name, type, color, prefix, balance, warehouseSize);
        // Добавляем обязательный ранг 1
        faction.addRank(new FactionRank(1, "Recruit", "Recruit", 0.0, new ArrayList<>()));
        // Добавляем обязательный ранг 11 (Лидер)
        List<String> leaderPerms = Arrays.asList(
                "hfactions.faction.invite", "hfactions.faction.kick", "hfactions.faction.promote",
                "hfactions.faction.demote", "hfactions.faction.setrank", "hfactions.faction.manage_ranks",
                "hfactions.faction.balance.view", "hfactions.faction.deposit", "hfactions.faction.withdraw",
                "hfactions.faction.manage_balance", "hfactions.faction.warehouse.open",
                "hfactions.faction.warehouse.deposit", "hfactions.faction.warehouse.withdraw"
                // Добавьте специфичные права, если нужно
        );
        faction.addRank(new FactionRank(11, "Leader", "Leader", 1000.0, leaderPerms)); // Пример ЗП и прав

        factions.put(id, faction);
        markFactionAsModified(id); // Отмечаем для сохранения
        saveModifiedFactions(); // Сохраняем сразу после создания
        plugin.getLogger().info("Faction " + name + " (ID: " + id + ") created.");
    }

    public void deleteFaction(String id) {
        id = id.toLowerCase();
        Faction faction = factions.remove(id);
        if (faction != null) {
            // TODO: Что делать с игроками этой фракции? Кикнуть? Оставить без фракции?
            // Пока просто удаляем фракцию. Игроки останутся без валидной фракции при след. загрузке.
            // Лучше пройтись по PlayerManager и очистить данные игроков этой фракции.
            plugin.getPlayerManager().clearFactionDataFor(id); // Нужен такой метод в PlayerManager

            // Удаляем данные из factions.yml
            FileConfiguration factionsConfig = configManager.getFactionsConfig();
            factionsConfig.set("factions." + id, null); // Удаляем секцию
            configManager.saveFactionsConfig(); // Сохраняем файл

            modifiedFactions.remove(id); // Убираем из измененных, если был там
            plugin.getLogger().info("Faction " + faction.getName() + " (ID: " + id + ") deleted.");
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
        // Сохранить несохраненные изменения перед перезагрузкой? Опасно, т.к. файл мог измениться.
        // saveModifiedFactions(); // Раскомментировать с осторожностью
        loadFactions();
        // TODO: Проверить онлайн игроков - если их фракция изменилась/удалилась, обновить их данные?
    }

    /**
     * Помечает фракцию как измененную, чтобы она была сохранена.
     * @param factionId ID фракции
     */
    public void markFactionAsModified(String factionId) {
        if (factionId != null && factions.containsKey(factionId.toLowerCase())) {
            modifiedFactions.add(factionId.toLowerCase());
            plugin.getLogger().fine("Faction " + factionId + " marked as modified."); // Debug лог
            // Можно запланировать отложенное сохранение здесь, если не хотим сохранять при каждом чихе
            // scheduleSaveTask();
        }
    }

    // --- Управление рангами (сохранение имени) ---

    /**
     * Обновляет отображаемое имя ранга и сохраняет фракцию.
     */
    public void updateRankDisplayName(String factionId, int rankId, String newDisplayName) {
        Faction faction = getFaction(factionId);
        if (faction != null) {
            FactionRank rank = faction.getRank(rankId);
            if (rank != null) {
                rank.setDisplayName(newDisplayName);
                faction.updateRank(rank); // Используем новый метод для обновления в мапе фракции
                // Отметка как измененной происходит внутри faction.updateRank() -> markFactionAsModified()
                saveModifiedFactions(); // Сохраняем сразу (или по таймеру)
            }
        }
    }

    /**
     * Сбрасывает отображаемое имя ранга к дефолтному и сохраняет фракцию.
     */
    public void resetRankDisplayName(String factionId, int rankId) {
        Faction faction = getFaction(factionId);
        if (faction != null) {
            FactionRank rank = faction.getRank(rankId);
            if (rank != null) {
                rank.resetDisplayName();
                faction.updateRank(rank);
                saveModifiedFactions();
            }
        }
    }

    // --- Управление балансом (пример, как помечать для сохранения) ---
    public void setFactionBalance(String factionId, double amount) {
        Faction faction = getFaction(factionId);
        if (faction != null) {
            faction.setBalance(amount);
            markFactionAsModified(factionId);
            // saveModifiedFactions(); // Сохранять сразу или по таймеру/команде?
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


    // TODO: Добавить метод scheduleSaveTask() для отложенного/периодического сохранения modifiedFactions,
    // если не хотим сохранять при каждом изменении.

}