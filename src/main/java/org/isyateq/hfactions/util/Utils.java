package org.isyateq.hfactions.util;

import org.bukkit.ChatColor; // Импорт ChatColor
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.isyateq.hfactions.HFactions; // Импорт HFactions для доступа к логгеру
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Level;

public class Utils {

    /**
     * Сериализует массив ItemStack в строку Base64.
     * @param items Массив ItemStack для сериализации.
     * @return Строка Base64 или null в случае ошибки или пустого массива.
     */
    public static String itemStackArrayToBase64(ItemStack[] items) {
        if (items == null) { // Считаем null массив как пустой для сохранения
            return null;
        }
        // Проверка, действительно ли есть что сохранять (не все null)
        boolean hasItems = false;
        for (ItemStack item : items) {
            if (item != null) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems && items.length > 0) { // Если массив не пустой, но все элементы null
            return null; // Не сохраняем строку для полностью пустого склада
        }


        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {

            dataOutput.writeInt(items.length); // Записываем размер

            for (ItemStack item : items) {
                dataOutput.writeObject(item); // Сериализуем (null тоже запишется)
            }

            // dataOutput закроется автоматически try-with-resources
            return Base64Coder.encodeLines(outputStream.toByteArray());

        } catch (IOException e) {
            HFactions.getInstance().getLogger().log(Level.SEVERE, "Could not serialize ItemStack array to Base64", e);
            return null;
        } catch (Exception e) { // Ловим другие возможные ошибки сериализации
            HFactions.getInstance().getLogger().log(Level.SEVERE, "Unexpected error serializing ItemStack array", e);
            return null;
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
            if (data.length == 0) {
                return null; // Ошибка декодирования
            }

            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
                 BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {

                int size = dataInput.readInt();
                if (size < 0) {
                    throw new IOException("Invalid array size read from Base64 data: " + size);
                }
                // Проверка на слишком большой размер (защита от OOM)
                if (size > 10000) { // Условный лимит, настройте по необходимости
                    throw new IOException("Attempted to deserialize excessively large ItemStack array: " + size);
                }


                ItemStack[] items = new ItemStack[size];

                for (int i = 0; i < size; i++) {
                    // Читаем объект, он может быть null
                    Object obj = dataInput.readObject();
                    if (obj != null && !(obj instanceof ItemStack)) {
                        // Логируем предупреждение, если объект не ItemStack, но не прерываем весь процесс
                        HFactions.getInstance().getLogger().warning("Non-ItemStack object found during deserialization at index " + i + ": " + obj.getClass().getName() + ". Skipping.");
                        items[i] = null; // Записываем null в массив
                    } else {
                        items[i] = (ItemStack) obj;
                    }
                }
                // dataInput закроется автоматически

                return items;
            } // Конец try-with-resources для потоков ввода

        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            HFactions.getInstance().getLogger().log(Level.SEVERE, "Could not deserialize ItemStack array from Base64", e);
            return null;
        } catch (Exception e) { // Ловим другие возможные ошибки
            HFactions.getInstance().getLogger().log(Level.SEVERE, "Unexpected error deserializing ItemStack array", e);
            return null;
        }
    }

    // --- Метод для форматирования цвета ---
    public static String color(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    // Другие утилиты...
}