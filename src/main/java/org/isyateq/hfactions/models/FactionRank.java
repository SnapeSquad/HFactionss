package org.isyateq.hfactions.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FactionRank {
    private final int internalId;        // Внутренний ID (1-11)
    private final String defaultName;    // Имя по умолчанию (из конфига)
    private String displayName;          // Отображаемое имя (может меняться лидером)
    private double salary;               // Зарплата
    private List<String> permissions;    // Дополнительные права этого ранга

    public FactionRank(int internalId, String defaultName, String displayName, double salary, List<String> permissions) {
        this.internalId = internalId;
        this.defaultName = defaultName;
        // Если displayName не указан или пуст, используем defaultName
        this.displayName = (displayName != null && !displayName.isEmpty()) ? displayName : defaultName;
        this.salary = salary;
        // Гарантируем, что список permissions не null
        this.permissions = Objects.requireNonNullElseGet(permissions, ArrayList::new);
    }

    // --- Getters ---
    public int getInternalId() {
        return internalId;
    }

    public String getDefaultName() {
        return defaultName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getSalary() {
        return salary;
    }

    public List<String> getPermissions() {
        // Возвращаем копию, чтобы предотвратить прямое изменение извне? Или оригинал? Пока оригинал.
        return permissions;
    }

    // --- Setters (для изменяемых полей) ---
    public void setDisplayName(String displayName) {
        // При установке пустого имени возвращаем к дефолтному
        this.displayName = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : this.defaultName;
    }

    public void setSalary(double salary) {
        // Зарплата не может быть отрицательной
        this.salary = Math.max(0, salary);
    }

    public void setPermissions(List<String> permissions) {
        // Гарантируем, что новый список не null
        this.permissions = Objects.requireNonNullElseGet(permissions, ArrayList::new);
    }

    // --- Стандартные методы ---
    @Override
    public String toString() {
        return "FactionRank{" +
                "id=" + internalId +
                ", name='" + displayName + '\'' +
                ", salary=" + salary +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FactionRank that = (FactionRank) o;
        // Сравниваем все поля
        return internalId == that.internalId &&
                Double.compare(that.salary, salary) == 0 &&
                Objects.equals(defaultName, that.defaultName) &&
                Objects.equals(displayName, that.displayName) &&
                Objects.equals(permissions, that.permissions);
    }

    @Override
    public int hashCode() {
        // Генерируем хеш-код на основе всех полей
        return Objects.hash(internalId, defaultName, displayName, salary, permissions);
    }

    public void resetDisplayName() {
        this.displayName = this.defaultName;
    }
}