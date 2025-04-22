package org.isyateq.hfactions.managers; // Или models

import org.bukkit.configuration.ConfigurationSection;
import org.isyateq.hfactions.HFactions; // Для логирования
import org.isyateq.hfactions.models.Faction;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class DynmapTerritory {

    private final String name; // Имя территории (ключ)
    private String factionId; // ID фракции-владельца
    private String worldName;
    private List<Double> cornersX;
    private List<Double> cornersZ;
    private String label; // Метка на карте
    // Дополнительные параметры стиля можно хранить здесь или брать из конфига

    public DynmapTerritory(String name, String factionId, String worldName, List<Double> cornersX, List<Double> cornersZ, String label) {
        this.name = name;
        this.factionId = factionId;
        this.worldName = worldName;
        this.cornersX = new ArrayList<>(cornersX); // Сохраняем копии
        this.cornersZ = new ArrayList<>(cornersZ);
        this.label = (label != null && !label.isEmpty()) ? label : name; // Метка по умолчанию - имя
    }

    // Геттеры
    public String getName() { return name; }
    public String getFactionId() { return factionId; }
    public String getWorldName() { return worldName; }
    // Возвращаем массивы double[] для Dynmap API
    public double[] getCornersX() { return cornersX.stream().mapToDouble(Double::doubleValue).toArray(); }
    public double[] getCornersZ() { return cornersZ.stream().mapToDouble(Double::doubleValue).toArray(); }
    public String getLabel() { return label; }

    // Сеттеры (если нужны)
    public void setFactionId(String factionId) { this.factionId = factionId; }
    public void setLabel(String label) { this.label = label; }
    // Метод для получения описания для маркера
    public String getDescription() {
        Faction faction = HFactions.getInstance().getFactionManager().getFaction(this.factionId);
        String ownerName = (faction != null) ? faction.getName() : "Unclaimed";
        // Можно добавить больше информации
        return "<div class=\"infowindow\">" +
                "<span style=\"font-weight:bold;\">" + getLabel() + "</span><br/>" + // Метка жирным
                "Owner: <span style=\"font-weight:bold;\">" + ownerName + "</span><br/>" +
                // "Type: " + (faction != null ? faction.getType() : "N/A") + "<br/>" + // Пример
                "</div>";
    }


    // Сериализация в ConfigurationSection
    public void serialize(ConfigurationSection section) {
        section.set("factionId", factionId);
        section.set("world", worldName);
        section.set("label", label);
        section.set("cornersX", cornersX);
        section.set("cornersZ", cornersZ);
    }

    // Десериализация из ConfigurationSection
    public static DynmapTerritory deserialize(String name, ConfigurationSection section) {
        if (section == null) return null;
        try {
            String factionId = section.getString("factionId");
            String world = section.getString("world");
            String label = section.getString("label", name); // Метка по умолчанию - имя
            List<Double> x = section.getDoubleList("cornersX");
            List<Double> z = section.getDoubleList("cornersZ");

            if (world == null || x == null || z == null || x.size() != z.size() || x.size() < 3) {
                HFactions.getInstance().getLogger().warning("Invalid territory data for '" + name + "': Missing world, corners, or insufficient corners.");
                return null;
            }
            // factionId может быть null для не захваченных территорий

            return new DynmapTerritory(name, factionId, world, x, z, label);
        } catch (Exception e) {
            HFactions.getInstance().getLogger().log(Level.SEVERE, "Error deserializing territory: " + name, e);
            return null;
        }
    }
}