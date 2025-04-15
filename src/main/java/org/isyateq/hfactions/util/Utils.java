package org.isyateq.hfactions.util;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.isyateq.hfactions.HFactions;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder; // Используем встроенный в Bukkit/Spigot кодер

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Level;

public class Utils {

    // ... (остальные ваши методы в Utils) ...

    /**
     * Сериализует массив ItemStack в строку Base64.
     * @param items Массив ItemStack для сериализации.
     * @return Строка Base64 или null в случае ошибки.
     */
    public static String itemStackArrayToBase64(ItemStack[] items) {
        if (items == null) {
            return null; // Или вернуть пустую строку? Решите сами.
        }
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            // Записываем размер массива, чтобы знать, сколько читать при десериализации
            dataOutput.writeInt(items.length);

            // Записываем каждый предмет
            for (ItemStack item : items) {
                dataOutput.writeObject(item); // BukkitObjectOutputStream умеет сериализовать ItemStack
            }

            // Закрываем поток и кодируем в Base64
            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (IOException e) {
            // Логируем ошибку - важно!
            HFactions.getInstance().getLogger().log(Level.SEVERE, "Could not serialize ItemStack array to Base64", e);
            return null; // Возвращаем null при ошибке
        }
    }

    /**
     * Десериализует строку Base64 обратно в массив ItemStack.
     * @param base64Data Строка Base64.
     * @return Массив ItemStack или null, если строка пуста/некорректна или произошла ошибка.
     */
    public static ItemStack[] itemStackArrayFromBase64(String base64Data) {
        if (base64Data == null || base64Data.isEmpty()) {
            return null; // Возвращаем null для пустых данных
        }
        try {
            byte[] data = Base64Coder.decodeLines(base64Data);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            // Читаем размер массива
            int size = dataInput.readInt();
            if (size < 0) { // Проверка на невалидный размер
                dataInput.close();
                throw new IOException("Invalid array size read from Base64 data: " + size);
            }

            ItemStack[] items = new ItemStack[size];

            // Читаем каждый предмет
            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) dataInput.readObject(); // Читаем объект ItemStack
            }

            dataInput.close();
            return items;
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            // Логируем ошибку
            HFactions.getInstance().getLogger().log(Level.SEVERE, "Could not deserialize ItemStack array from Base64", e);
            // Возвращаем пустой массив нужного размера или null? Лучше null, чтобы показать ошибку.
            return null;
        }
    }

    // --- Метод для форматирования цвета (у вас он уже может быть) ---
    public static String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

}