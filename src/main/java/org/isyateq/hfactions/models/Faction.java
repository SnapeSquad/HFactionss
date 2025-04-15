package org.isyateq.hfactions.models;

import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.FactionType;
import org.isyateq.hfactions.HFactions;
// Убираем импорты ConfigurationSerializable
// import org.bukkit.configuration.serialization.ConfigurationSerializable;
// import org.bukkit.configuration.serialization.SerializableAs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
// import java.util.stream.Collectors; // Пока не нужен

// @SerializableAs("HFactions_Faction") // Убираем аннотацию
public class Faction { // Убираем "implements ConfigurationSerializable"
    private final String id;
    private String name;
    private FactionType type;
    private String color;
    private String prefix;
    private double balance;
    private final Map<Integer, FactionRank> ranks = new ConcurrentHashMap<>();
    private int warehouseSize;
    private ItemStack[] warehouseContents; // Массив остается для работы в памяти

    // Конструктор для создания новой фракции (без склада)
    public Faction(String id, String name, FactionType type, String color, String prefix, double initialBalance, int warehouseSize) {
        this.id = id.toLowerCase();
        this.name = name;
        this.type = type;
        this.color = color;
        this.prefix = prefix;
        this.balance = initialBalance;
        this.warehouseSize = warehouseSize;
        this.warehouseContents = new ItemStack[warehouseSize]; // Инициализация пустым массивом
    }

    // Конструктор, используемый FactionManager при загрузке из конфига
    public Faction(String id, String name, FactionType type, String color, String prefix, double balance, int warehouseSize, Map<Integer, FactionRank> ranks, ItemStack[] warehouseContents) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.color = color;
        this.prefix = prefix;
        this.balance = balance;
        this.warehouseSize = warehouseSize;
        this.ranks.putAll(ranks); // Копируем ранги
        // Устанавливаем склад, если он был успешно десериализован, иначе пустой
        this.warehouseContents = (warehouseContents != null && warehouseContents.length == warehouseSize) ? warehouseContents : new ItemStack[warehouseSize];
        if (warehouseContents != null && warehouseContents.length != warehouseSize) {
            System.err.println("Warning: Warehouse size mismatch for faction " + id + " during loading. Expected: " + warehouseSize + ", Got: " + warehouseContents.length + ". Warehouse cleared.");
        }
    }


    // УДАЛЯЕМ старый конструктор десериализации и метод serialize()
    /*
    public Faction(Map<String, Object> map) { ... } // УДАЛИТЬ
    @Override
    public Map<String, Object> serialize() { ... } // УДАЛИТЬ
    */


    // --- Методы доступа и управления (остаются как были) ---

    public String getId() { return id; }
    public String getName() { return name; }
    public FactionType getType() { return type; }
    public String getColor() { return color; }
    public String getPrefix() { return prefix; }
    public double getBalance() { return balance; }
    public int getWarehouseSize() { return warehouseSize; }
    public ItemStack[] getWarehouseContents() { return warehouseContents; } // Осторожно, возвращает ссылку!

    public void setName(String name) { this.name = name; }
    public void setType(FactionType type) { this.type = type; }
    public void setColor(String color) { this.color = color; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public void setBalance(double balance) { this.balance = Math.max(0, balance); }
    public void deposit(double amount) { if (amount > 0) this.balance += amount; }
    public boolean withdraw(double amount) {
        if (amount > 0 && this.balance >= amount) {
            this.balance -= amount;
            return true;
        }
        return false;
    }

    public FactionRank getRank(int rankId) {
        return ranks.get(rankId);
    }

    public Map<Integer, FactionRank> getRanks() {
        return new ConcurrentHashMap<>(ranks); // Возвращаем копию
    }

    public void addRank(FactionRank rank) {
        if (rank != null) {
            ranks.put(rank.getInternalId(), rank);
        }
    }

    public FactionRank getLeaderRank() {
        return ranks.values().stream()
                .max((r1, r2) -> Integer.compare(r1.getInternalId(), r2.getInternalId()))
                .orElse(null);
    }

    /**
     * Устанавливает содержимое склада. Проверяет соответствие размера.
     * @param contents Новый массив предметов.
     */
    public void setWarehouseContents(ItemStack[] contents) {
        if (contents != null && contents.length == this.warehouseSize) {
            this.warehouseContents = contents;
        } else if (contents != null) {
            // Если размер не совпадает, создаем пустой склад и логируем ошибку
            this.warehouseContents = new ItemStack[this.warehouseSize];
            HFactions.getInstance().getLogger().log(Level.WARNING, "Attempted to set warehouse contents with incorrect size for faction " + id + ". Expected: " + warehouseSize + ", Got: " + contents.length + ". Warehouse cleared.");
        } else {
            // Если передан null, создаем пустой массив
            this.warehouseContents = new ItemStack[this.warehouseSize];
        }
    }

    /**
     * Обновляет ранг (например, при изменении имени)
     * @param rank Обновленный объект ранга
     */
    public void updateRank(FactionRank rank) {
        if (rank != null && ranks.containsKey(rank.getInternalId())) {
            ranks.put(rank.getInternalId(), rank);
            // Отмечаем фракцию как измененную для сохранения (реализуем в FactionManager)
            HFactions.getInstance().getFactionManager().markFactionAsModified(this.id);
        }
    }
}