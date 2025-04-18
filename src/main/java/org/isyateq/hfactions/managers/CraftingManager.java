package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.*;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.integrations.OraxenIntegration; // Импорт для Oraxen
import org.isyateq.hfactions.util.Utils; // Для сообщений

import java.util.*;
import java.util.logging.Level;

public class CraftingManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final ItemManager itemManager; // Нужен для получения результата крафта
    private final OraxenIntegration oraxenIntegration; // Нужен для Oraxen предметов

    // Храним информацию о кастомных рецептах (не обязательно сами рецепты Bukkit)
    private final Map<String, CustomRecipeInfo> customRecipes = new HashMap<>();

    // Класс для хранения информации о нашем кастомном рецепте
    private static class CustomRecipeInfo {
        final String id; // Уникальный ID рецепта из конфига
        final Recipe bukkitRecipe; // Сам рецепт Bukkit (Shaped или Shapeless)
        final List<String> allowedFactions;
        final String requiredPermission;
        final boolean permissionRequired;
        final ItemStack resultItem; // Кэшируем результат

        CustomRecipeInfo(String id, Recipe bukkitRecipe, List<String> allowedFactions, String requiredPermission, boolean permissionRequired, ItemStack resultItem) {
            this.id = id;
            this.bukkitRecipe = bukkitRecipe;
            this.allowedFactions = allowedFactions != null ? allowedFactions : new ArrayList<>();
            this.requiredPermission = requiredPermission;
            this.permissionRequired = permissionRequired;
            this.resultItem = resultItem;
        }
    }


    public CraftingManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.itemManager = plugin.getItemManager();
        this.oraxenIntegration = plugin.getOraxenIntegration();
        // Не загружаем рецепты здесь, это делает HFactions.onEnable()
    }

    /**
     * Загружает определения кастомных рецептов из config.yml.
     */
    public void loadRecipes() {
        // Очищаем старые рецепты и удаляем их с сервера
        clearRecipes();

        ConfigurationSection recipesSection = configManager.getConfig().getConfigurationSection("crafting.recipes");
        if (!configManager.getConfig().getBoolean("crafting.enabled", false) || recipesSection == null) {
            plugin.getLogger().info("Custom crafting is disabled or no recipes found in config.yml.");
            return;
        }

        int loadedCount = 0;
        for (String recipeId : recipesSection.getKeys(false)) {
            ConfigurationSection recipeData = recipesSection.getConfigurationSection(recipeId);
            if (recipeData == null) continue;

            try {
                ItemStack resultItem = parseResultItem(recipeData.getString("result"));
                if (resultItem == null) {
                    plugin.getLogger().warning("Invalid or missing 'result' item for recipe: " + recipeId);
                    continue;
                }

                Recipe bukkitRecipe = null;
                NamespacedKey key = new NamespacedKey(plugin, "hf_" + recipeId); // Уникальный ключ для Bukkit

                // Определяем тип рецепта (форменный или бесформенный)
                if (recipeData.contains("shape") && recipeData.isList("shape")) {
                    // --- Форменный рецепт (ShapedRecipe) ---
                    ShapedRecipe shapedRecipe = new ShapedRecipe(key, resultItem);
                    List<String> shape = recipeData.getStringList("shape");
                    if (shape.size() > 3 || shape.isEmpty()) {
                        plugin.getLogger().warning("Invalid 'shape' for shaped recipe: " + recipeId + ". Must be 1-3 lines.");
                        continue;
                    }
                    shapedRecipe.shape(shape.toArray(new String[0])); // Устанавливаем форму

                    // Ингредиенты
                    ConfigurationSection ingredientsSection = recipeData.getConfigurationSection("ingredients");
                    if (ingredientsSection == null) {
                        plugin.getLogger().warning("Missing 'ingredients' section for shaped recipe: " + recipeId);
                        continue;
                    }
                    boolean ingredientsValid = true;
                    for (String ingredientKey : ingredientsSection.getKeys(false)) {
                        if (ingredientKey.length() != 1) {
                            plugin.getLogger().warning("Invalid ingredient key '" + ingredientKey + "' in recipe: " + recipeId + ". Key must be a single character.");
                            ingredientsValid = false;
                            break;
                        }
                        char keyChar = ingredientKey.charAt(0);
                        String ingredientValue = ingredientsSection.getString(ingredientKey);
                        RecipeChoice choice = parseIngredient(ingredientValue);
                        if (choice == null) {
                            plugin.getLogger().warning("Invalid ingredient value '" + ingredientValue + "' for key '" + keyChar + "' in recipe: " + recipeId);
                            ingredientsValid = false;
                            break;
                        }
                        shapedRecipe.setIngredient(keyChar, choice);
                    }
                    if (!ingredientsValid) continue; // Пропускаем рецепт, если ингредиенты невалидны
                    bukkitRecipe = shapedRecipe;

                } else if (recipeData.contains("ingredients") && recipeData.isList("ingredients")) {
                    // --- Бесформенный рецепт (ShapelessRecipe) ---
                    ShapelessRecipe shapelessRecipe = new ShapelessRecipe(key, resultItem);
                    List<String> ingredientsList = recipeData.getStringList("ingredients");
                    if (ingredientsList.isEmpty()) {
                        plugin.getLogger().warning("Empty 'ingredients' list for shapeless recipe: " + recipeId);
                        continue;
                    }
                    boolean ingredientsValid = true;
                    for (String ingredientValue : ingredientsList) {
                        RecipeChoice choice = parseIngredient(ingredientValue);
                        if (choice == null) {
                            plugin.getLogger().warning("Invalid ingredient value '" + ingredientValue + "' in shapeless recipe: " + recipeId);
                            ingredientsValid = false;
                            break;
                        }
                        shapelessRecipe.addIngredient(choice);
                    }
                    if (!ingredientsValid) continue; // Пропускаем рецепт
                    bukkitRecipe = shapelessRecipe;
                } else {
                    plugin.getLogger().warning("Invalid recipe format for: " + recipeId + ". Missing 'shape' or 'ingredients' list.");
                    continue;
                }

                // Общие параметры рецепта
                List<String> allowedFactions = recipeData.getStringList("allowed_factions");
                boolean permissionRequired = recipeData.getBoolean("permission_required", false);
                String requiredPermission = recipeData.getString("craft_permission_node", "hfactions.craft." + recipeId); // Дефолтное право

                // Сохраняем информацию о рецепте
                CustomRecipeInfo recipeInfo = new CustomRecipeInfo(recipeId, bukkitRecipe, allowedFactions, requiredPermission, permissionRequired, resultItem.clone()); // Сохраняем клон результата
                customRecipes.put(recipeId, recipeInfo);

                // Регистрируем рецепт в Bukkit
                if (Bukkit.addRecipe(bukkitRecipe)) {
                    loadedCount++;
                    plugin.getLogger().fine("Registered custom recipe: " + recipeId);
                } else {
                    plugin.getLogger().warning("Failed to register recipe " + recipeId + " with Bukkit (maybe key conflict?).");
                    customRecipes.remove(recipeId); // Удаляем из нашей мапы, если не смогли зарегистрировать
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error loading custom recipe: " + recipeId, e);
            }
        }
        plugin.getLogger().info("Loaded and registered " + loadedCount + " custom recipes.");
    }

    /**
     * Разбирает строку ингредиента (может быть ванильным Material или Oraxen ID).
     * @param value Строка ингредиента (e.g., "DIAMOND", "oraxen:my_item").
     * @return RecipeChoice или null, если не удалось разобрать.
     */
    private RecipeChoice parseIngredient(String value) {
        if (value == null || value.isEmpty()) return null;

        if (value.toLowerCase().startsWith("oraxen:") && oraxenIntegration != null && oraxenIntegration.isEnabled()) {
            String oraxenId = value.substring(7);
            ItemStack oraxenItem = oraxenIntegration.getOraxenItemById(oraxenId);
            if (oraxenItem != null) {
                return new RecipeChoice.ExactChoice(oraxenItem); // Точное совпадение для Oraxen предметов
            } else {
                plugin.getLogger().warning("Oraxen item not found for ID: " + oraxenId);
                return null;
            }
        } else {
            try {
                Material material = Material.matchMaterial(value.toUpperCase());
                if (material != null) {
                    return new RecipeChoice.MaterialChoice(material);
                } else {
                    plugin.getLogger().warning("Invalid vanilla material: " + value);
                    return null;
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Error parsing material: " + value + " - " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Разбирает строку результата крафта (может быть ванильным Material, Oraxen ID или ID спец. предмета HFactions).
     * @param value Строка результата (e.g., "DIAMOND", "oraxen:my_item", "hfactions:taser").
     * @return ItemStack или null, если не удалось разобрать.
     */
    private ItemStack parseResultItem(String value) {
        if (value == null || value.isEmpty()) return null;

        if (value.toLowerCase().startsWith("hfactions:")) {
            String hfItemId = value.substring(10).toLowerCase();
            switch (hfItemId) {
                case "taser": return itemManager.getTaserItem();
                case "handcuffs": return itemManager.getHandcuffsItem();
                case "protocol": return itemManager.getProtocolItem();
                default:
                    plugin.getLogger().warning("Unknown HFactions special item ID: " + hfItemId);
                    return null;
            }
        } else if (value.toLowerCase().startsWith("oraxen:") && oraxenIntegration != null && oraxenIntegration.isEnabled()) {
            String oraxenId = value.substring(7);
            ItemStack oraxenItem = oraxenIntegration.getOraxenItemById(oraxenId);
            if (oraxenItem == null) {
                plugin.getLogger().warning("Oraxen result item not found for ID: " + oraxenId);
            }
            return oraxenItem; // Возвращаем null, если не найден
        } else {
            try {
                Material material = Material.matchMaterial(value.toUpperCase());
                if (material != null) {
                    return new ItemStack(material);
                } else {
                    plugin.getLogger().warning("Invalid vanilla material for result: " + value);
                    return null;
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Error parsing material for result: " + value + " - " + e.getMessage());
                return null;
            }
        }
    }


    /**
     * Очищает зарегистрированные кастомные рецепты HFactions.
     * Вызывается перед перезагрузкой.
     */
    public void clearRecipes() {
        int removedCount = 0;
        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
        while (recipeIterator.hasNext()) {
            Recipe recipe = recipeIterator.next();
            // Проверяем, является ли рецепт ключом нашего плагина
            if (recipe instanceof Keyed && ((Keyed) recipe).getKey().getNamespace().equalsIgnoreCase(plugin.getName())) {
                try {
                    recipeIterator.remove(); // Удаляем рецепт с сервера
                    removedCount++;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to remove recipe: " + ((Keyed) recipe).getKey(), e);
                }
            }
        }
        customRecipes.clear(); // Очищаем нашу внутреннюю мапу
        if (removedCount > 0) {
            plugin.getLogger().info("Removed " + removedCount + " custom recipes.");
        }
    }

    /**
     * Получает информацию о кастомном рецепте по его результату.
     * @param result ItemStack, который является результатом крафта.
     * @return CustomRecipeInfo или null, если рецепт не найден.
     */
    public CustomRecipeInfo getCustomRecipeInfo(ItemStack result) {
        if (result == null) return null;
        // Ищем рецепт, у которого результат совпадает с данным (простое сравнение, может потребоваться isSimilar)
        for (CustomRecipeInfo info : customRecipes.values()) {
            if (result.isSimilar(info.resultItem)) { // Сравниваем через isSimilar
                return info;
            }
        }
        return null;
    }

    // Геттер для команды /hf listrecipes
    public Map<String, CustomRecipeInfo> getCustomRecipes() {
        return new HashMap<>(customRecipes); // Возвращаем копию
    }
}