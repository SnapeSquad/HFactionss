package org.isyateq.hfactions.util;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta; // Для кожаной брони
import org.bukkit.inventory.meta.PotionMeta; // Для зелий
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionData; // Для зелий

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Удобный строитель для создания ItemStack.
 */
public class ItemStackBuilder {

    private final ItemStack itemStack;
    private final ItemMeta itemMeta; // Кэшируем мету для производительности

    public ItemStackBuilder(Material material) {
        this(material, 1);
    }

    public ItemStackBuilder(Material material, int amount) {
        // Проверка на null и валидность материала
        if (material == null || material == Material.AIR) {
            // Можно выбросить исключение или использовать дефолтный материал
            // throw new IllegalArgumentException("Material cannot be null or AIR");
            this.itemStack = new ItemStack(Material.STONE, amount); // Запасной вариант
            System.err.println("ItemStackBuilder: Material was null or AIR, using STONE instead.");
        } else {
            this.itemStack = new ItemStack(material, amount);
        }
        // Получаем мету сразу
        this.itemMeta = this.itemStack.getItemMeta();
        if (this.itemMeta == null) {
            // Это может случиться для Material.AIR, но мы его обработали выше
            System.err.println("ItemStackBuilder: Could not get ItemMeta for " + this.itemStack.getType());
        }
    }

    public ItemStackBuilder(ItemStack itemStack) {
        if (itemStack == null) {
            throw new IllegalArgumentException("ItemStack cannot be null");
        }
        this.itemStack = itemStack.clone(); // Работаем с клоном
        this.itemMeta = this.itemStack.getItemMeta();
    }

    public ItemStackBuilder setAmount(int amount) {
        itemStack.setAmount(amount);
        return this;
    }

    public ItemStackBuilder setName(String name) {
        if (itemMeta != null && name != null) {
            itemMeta.setDisplayName(Utils.color(name)); // Используем Utils.color
        }
        return this;
    }

    public ItemStackBuilder setLore(List<String> lore) {
        if (itemMeta != null && lore != null) {
            itemMeta.setLore(lore.stream().map(Utils::color).collect(Collectors.toList()));
        }
        return this;
    }

    public ItemStackBuilder setLore(String... lore) {
        if (itemMeta != null && lore != null) {
            itemMeta.setLore(Arrays.stream(lore).map(Utils::color).collect(Collectors.toList()));
        }
        return this;
    }

    public ItemStackBuilder addLoreLine(String line) {
        if (itemMeta != null && line != null) {
            List<String> lore = itemMeta.hasLore() ? new ArrayList<>(itemMeta.getLore()) : new ArrayList<>();
            lore.add(Utils.color(line));
            itemMeta.setLore(lore);
        }
        return this;
    }

    public ItemStackBuilder addEnchant(Enchantment enchantment, int level, boolean ignoreLevelRestriction) {
        if (itemMeta != null && enchantment != null) {
            itemMeta.addEnchant(enchantment, level, ignoreLevelRestriction);
        }
        return this;
    }

    public ItemStackBuilder addEnchant(Enchantment enchantment, int level) {
        return addEnchant(enchantment, level, false); // По умолчанию не игнорируем ограничения
    }


    public ItemStackBuilder removeEnchant(Enchantment enchantment) {
        if (itemMeta != null && enchantment != null) {
            itemMeta.removeEnchant(enchantment);
        }
        return this;
    }

    public ItemStackBuilder addItemFlags(ItemFlag... flags) {
        if (itemMeta != null && flags != null) {
            itemMeta.addItemFlags(flags);
        }
        return this;
    }

    public ItemStackBuilder removeItemFlags(ItemFlag... flags) {
        if (itemMeta != null && flags != null) {
            itemMeta.removeItemFlags(flags);
        }
        return this;
    }

    public ItemStackBuilder setCustomModelData(int data) {
        if (itemMeta != null) {
            itemMeta.setCustomModelData(data);
        }
        return this;
    }

    public ItemStackBuilder setUnbreakable(boolean unbreakable) {
        if (itemMeta != null) {
            itemMeta.setUnbreakable(unbreakable);
        }
        return this;
    }

    /**
     * Добавляет или обновляет данные в PersistentDataContainer предмета.
     * @param key Ключ (NamespacedKey)
     * @param type Тип данных (e.g., PersistentDataType.STRING)
     * @param value Значение
     * @return this
     */
    public <T, Z> ItemStackBuilder setPersistentData(NamespacedKey key, PersistentDataType<T, Z> type, Z value) {
        if (itemMeta != null && key != null && type != null && value != null) {
            itemMeta.getPersistentDataContainer().set(key, type, value);
        }
        return this;
    }

    /**
     * Устанавливает LocalizedName для предмета (невидимое имя для идентификации).
     * @param name Локализованное имя.
     * @return this
     */
    public ItemStackBuilder setLocalizedName(String name) {
        if (itemMeta != null && name != null) {
            itemMeta.setLocalizedName(Utils.color(name)); // Можно тоже с цветом, хоть и невидимо
        }
        return this;
    }


    // --- Специфичные методы для определенных типов меты ---

    /**
     * Устанавливает цвет для кожаной брони.
     * @param color Цвет Bukkit.
     * @return this
     */
    public ItemStackBuilder setLeatherArmorColor(Color color) {
        if (itemMeta instanceof LeatherArmorMeta && color != null) {
            ((LeatherArmorMeta) itemMeta).setColor(color);
        } else if (!(itemMeta instanceof LeatherArmorMeta)) {
            System.err.println("ItemStackBuilder: Tried to set leather color on non-leather armor: " + itemStack.getType());
        }
        return this;
    }

    /**
     * Устанавливает основной эффект зелья.
     * @param potionData Данные зелья (тип, уровень, длительность).
     * @return this
     */
    public ItemStackBuilder setPotionData(PotionData potionData) {
        if (itemMeta instanceof PotionMeta && potionData != null) {
            ((PotionMeta) itemMeta).setBasePotionData(potionData);
        } else if (!(itemMeta instanceof PotionMeta)) {
            System.err.println("ItemStackBuilder: Tried to set potion data on non-potion item: " + itemStack.getType());
        }
        return this;
    }

    /**
     * Добавляет кастомный эффект зелья.
     * @param effect Эффект зелья.
     * @param overwrite Перезаписывать ли существующий эффект того же типа.
     * @return this
     */
    public ItemStackBuilder addCustomPotionEffect(org.bukkit.potion.PotionEffect effect, boolean overwrite) {
        if (itemMeta instanceof PotionMeta && effect != null) {
            ((PotionMeta) itemMeta).addCustomEffect(effect, overwrite);
        } else if (!(itemMeta instanceof PotionMeta)) {
            System.err.println("ItemStackBuilder: Tried to add custom potion effect on non-potion item: " + itemStack.getType());
        }
        return this;
    }

    /**
     * Устанавливает цвет зелья (для отображения).
     * @param color Цвет Bukkit.
     * @return this
     */
    public ItemStackBuilder setPotionColor(Color color) {
        if (itemMeta instanceof PotionMeta && color != null) {
            ((PotionMeta) itemMeta).setColor(color);
        } else if (!(itemMeta instanceof PotionMeta)) {
            System.err.println("ItemStackBuilder: Tried to set potion color on non-potion item: " + itemStack.getType());
        }
        return this;
    }


    /**
     * Завершает построение и возвращает готовый ItemStack.
     * @return Готовый ItemStack.
     */
    public ItemStack build() {
        // Применяем накопленную мету к стаку перед возвратом
        if (itemMeta != null) {
            itemStack.setItemMeta(itemMeta);
        }
        return itemStack;
    }
}