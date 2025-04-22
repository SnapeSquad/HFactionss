package org.isyateq.hfactions.models;

import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions; // Для логирования

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class Faction {
    private final String id; // lowercase
    private String name;
    private FactionType type;
    private String color; // HEX
    private String prefix; // С кодами &
    private double balance;
    private final Map<Integer, FactionRank> ranks = new ConcurrentHashMap<>();
    private int warehouseSize;
    private ItemStack[] warehouseContents;

    // Конструктор для FactionManager при загрузке
    public Faction(String id, String name, FactionType type, String color, String prefix, double balance, int warehouseSize, Map<Integer, FactionRank> ranks, ItemStack[] warehouseContents) {
        this.id = id.toLowerCase(); // Убедимся, что ID в нижнем регистре
        this.name = name;
        this.type = type;
        this.color = color;
        this.prefix = prefix;
        this.balance = balance;
        this.warehouseSize = warehouseSize;
        if (ranks != null) {
            this.ranks.putAll(ranks);
        }
        // Устанавливаем склад, проверяя размер
        this.warehouseContents = new ItemStack[this.warehouseSize]; // Инициализируем пустым
        if (warehouseContents != null) {
            if (warehouseContents.length == this.warehouseSize) {
                this.warehouseContents = warehouseContents;
            } else {
                HFactions.getInstance().getLogger().warning("Warehouse size mismatch for faction " + this.id + " during loading. Expected: " + this.warehouseSize + ", Got: " + warehouseContents.length + ". Warehouse initialized empty.");
            }
        }
    }

    // --- Методы доступа и управления ---

    public String getId() { return id; }
    public String getName() { return name; }
    public FactionType getType() { return type; }
    public String getColor() { return color; }
    public String getPrefix() { return prefix; }
    public double getBalance() { return balance; }
    public int getWarehouseSize() { return warehouseSize; }
    // Возвращаем КОПИЮ склада, чтобы избежать прямого изменения массива извне
    public ItemStack[] getWarehouseContents() {
        return Arrays.copyOf(warehouseContents, warehouseContents.length);
    }

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
        // Возвращаем неизменяемую копию для безопасности
        return Collections.unmodifiableMap(ranks);
    }

    // Добавление или обновление ранга
    public void addOrUpdateRank(FactionRank rank) {
        if (rank != null) {
            ranks.put(rank.getInternalId(), rank);
        }
    }

    public FactionRank getLeaderRank() {
        // Ранг 11 считается лидерским по ТЗ
        FactionRank leader = ranks.get(11);
        if (leader == null) {
            // Если ранг 11 не найден, возвращаем ранг с максимальным ID
            return ranks.values().stream()
                    .max(Comparator.comparingInt(FactionRank::getInternalId))
                    .orElse(null);
        }
        return leader;
    }

    /**
     * Устанавливает содержимое склада. Проверяет соответствие размера.
     * Принимает массив и СОХРАНЯЕТ ЕГО КОПИЮ.
     * @param contents Новый массив предметов.
     */
    public void setWarehouseContents(ItemStack[] contents) {
        if (contents != null) {
            if (contents.length == this.warehouseSize) {
                // Сохраняем копию массива
                this.warehouseContents = Arrays.copyOf(contents, contents.length);
            } else {
                HFactions.getInstance().getLogger().log(Level.WARNING, "Attempted to set warehouse contents with incorrect size for faction " + id + ". Expected: " + warehouseSize + ", Got: " + contents.length + ". Operation ignored.");
                // Не меняем склад, если размер не совпадает
            }
        } else {
            // Если передан null, создаем пустой массив
            this.warehouseContents = new ItemStack[this.warehouseSize];
        }
        // Помечаем фракцию как измененную после изменения склада
        HFactions.getInstance().getFactionManager().markFactionAsModified(this.id);
    }
}