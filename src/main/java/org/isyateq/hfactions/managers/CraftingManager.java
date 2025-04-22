package org.isyateq.hfactions.managers;

import io.th0rgal.oraxen.api.OraxenItems; // Импорт Oraxen API (если используется)
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.recipe.CraftingBookCategory; // Импорт категории
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.util.Utils; // Для Utils.color

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class CraftingManager {

    private final HFactions plugin;
    private final PlayerManager playerManager; // Зависимость от PlayerManager
    private final ItemManager itemManager; // Нужен для получения кастомных предметов
    private final ConfigManager configManager; // Нужен для чтения конфига

    // Класс для хранения информации о кастомном рецепте (остается приватным)
    private static class CustomRecipeInfo {
        final ItemStack result;
        final List<String> allowedFactions;
        final String requiredPermission;
        final NamespacedKey key; // Ключ рецепта

        // Конструктор
        CustomRecipeInfo(NamespacedKey key, ItemStack result, List<String> allowedFactions, String requiredPermission) {
            this.key = key;
            this.result = result; // Сохраняем клон для безопасности? Не обязательно, т.к. result из конфига
            this.allowedFactions = allowedFactions != null ? List.copyOf(allowedFactions) : List.of(); // Неизменяемый список
            this.requiredPermission = requiredPermission;
        }
    }

    // Мапа для хранения информации о наших кастомных рецептах (ключ -> инфо)
    private final Map<NamespacedKey, CustomRecipeInfo> customRecipes = new HashMap<>();

    // Конструктор
    public CraftingManager(HFactions plugin) {
        this.plugin = plugin;
        // Получаем зависимости из главного класса
        this.playerManager = plugin.getPlayerManager();
        this.itemManager = plugin.getItemManager();
        this.configManager = plugin.getConfigManager();

        // Проверка на null для критических зависимостей
        if (this.playerManager == null || this.itemManager == null || this.configManager == null) {
            plugin.getLogger().severe("CraftingManager could not be initialized due to missing dependencies (PlayerManager, ItemManager, or ConfigManager). Crafting will not work.");
            // Можно было бы выбросить исключение, чтобы плагин не загрузился
            // throw new IllegalStateException("Missing dependencies for CraftingManager");
        }
    }

    // --- Загрузка рецептов из конфига ---
    public void loadRecipes() {
        customRecipes.clear(); // Очищаем старые данные
        // TODO: Удалить старые зарегистрированные рецепты из Bukkit? Это сложнее.
        // Bukkit.removeRecipe(key); - нужно итерировать и удалять

        FileConfiguration config = configManager.getConfig();
        if (!config.getBoolean("crafting.enabled", false)) {
            plugin.getLogger().info("Custom crafting is disabled in config.yml.");
            return;
        }

        ConfigurationSection recipesSection = config.getConfigurationSection("crafting.recipes");
        if (recipesSection == null) {
            plugin.getLogger().info("No 'crafting.recipes' section found in config.yml.");
            return;
        }

        int loadedCount = 0;
        for (String keyName : recipesSection.getKeys(false)) {
            ConfigurationSection recipeData = recipesSection.getConfigurationSection(keyName);
            if (recipeData == null) continue;

            NamespacedKey recipeKey = new NamespacedKey(plugin, keyName.toLowerCase()); // Ключ рецепта

            try {
                // 1. Получаем результат крафта
                String resultType = recipeData.getString("result.type", "");
                String resultItemId = recipeData.getString("result.item_id"); // Для Oraxen или кастомных ID
                int resultAmount = recipeData.getInt("result.amount", 1);
                ItemStack resultItem = null;

                if (resultItemId != null && !resultItemId.isEmpty() && configManager.isOraxenSupportEnabled() && plugin.getOraxenIntegration() != null) {
                    // Пытаемся получить предмет Oraxen
                    resultItem = plugin.getOraxenIntegration().getItemStack(resultItemId);
                    if (resultItem != null) {
                        resultItem.setAmount(resultAmount);
                    } else {
                        plugin.getLogger().warning("Oraxen item '" + resultItemId + "' not found for recipe '" + keyName + "'. Skipping recipe.");
                        continue;
                    }
                } else if (!resultType.isEmpty()) {
                    // Пытаемся получить ванильный предмет
                    try {
                        Material material = Material.valueOf(resultType.toUpperCase());
                        resultItem = new ItemStack(material, resultAmount);
                        // TODO: Добавить поддержку CustomModelData для ванильных предметов, если нужно
                        // int customModelData = recipeData.getInt("result.custom_model_data", -1);
                        // if (customModelData != -1) itemManager.applyCustomModelData(resultItem, customModelData);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid vanilla material type '" + resultType + "' for recipe '" + keyName + "'. Skipping recipe.");
                        continue;
                    }
                } else {
                    // Пытаемся получить предмет через ItemManager (тайзер, наручники и т.д.)
                    resultItem = itemManager.getManagedItem(resultItemId); // resultItemId используется как ключ (taser, handcuffs...)
                    if (resultItem != null) {
                        resultItem.setAmount(resultAmount); // Устанавливаем количество
                    } else {
                        plugin.getLogger().warning("Invalid result item_id or type for recipe '" + keyName + "'. Skipping recipe.");
                        continue;
                    }
                }

                if (resultItem == null || resultItem.getType().isAir()) {
                    plugin.getLogger().warning("Result item is null or AIR for recipe '" + keyName + "'. Skipping recipe.");
                    continue;
                }


                // 2. Получаем условия
                List<String> allowedFactions = recipeData.getStringList("allowed_factions")
                        .stream().map(String::toLowerCase).toList(); // Приводим к нижнему регистру
                String requiredPermission = recipeData.getString("craft_permission_node"); // Может быть null

                // 3. Создаем информацию о рецепте
                CustomRecipeInfo recipeInfo = new CustomRecipeInfo(recipeKey, resultItem.clone(), allowedFactions, requiredPermission); // Сохраняем клон результата

                // 4. Создаем и регистрируем сам рецепт Bukkit
                Recipe bukkitRecipe = createBukkitRecipe(recipeKey, resultItem, recipeData);
                if (bukkitRecipe != null) {
                    if (Bukkit.addRecipe(bukkitRecipe)) { // Пытаемся добавить рецепт
                        customRecipes.put(recipeKey, recipeInfo); // Сохраняем инфо только если рецепт успешно добавлен
                        loadedCount++;
                        plugin.getLogger().fine("Registered custom recipe: " + keyName);
                    } else {
                        plugin.getLogger().warning("Failed to add recipe '" + keyName + "' to Bukkit (maybe a duplicate key or invalid recipe?).");
                    }
                } else {
                    plugin.getLogger().warning("Failed to create Bukkit recipe object for key: " + keyName);
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error loading custom recipe: " + keyName, e);
            }
        }
        plugin.getLogger().info("Loaded and registered " + loadedCount + " custom recipes.");
    }

    // Вспомогательный метод для создания объекта Recipe из конфига
    private Recipe createBukkitRecipe(NamespacedKey key, ItemStack result, ConfigurationSection data) {
        String type = data.getString("recipe_type", "SHAPED").toUpperCase(); // SHAPED или SHAPELESS

        if ("SHAPED".equals(type)) {
            ShapedRecipe shapedRecipe = new ShapedRecipe(key, result);
            List<String> shape = data.getStringList("shape");
            if (shape.isEmpty() || shape.size() > 3) {
                plugin.getLogger().warning("Invalid shape for shaped recipe '" + key.getKey() + "'. Must be 1-3 strings.");
                return null;
            }
            shapedRecipe.shape(shape.toArray(new String[0])); // Устанавливаем форму

            ConfigurationSection ingredientsSection = data.getConfigurationSection("ingredients");
            if (ingredientsSection == null) {
                plugin.getLogger().warning("Missing 'ingredients' section for shaped recipe '" + key.getKey() + "'.");
                return null;
            }

            // Устанавливаем ингредиенты
            for (String ingredientKey : ingredientsSection.getKeys(false)) {
                if (ingredientKey.length() != 1) {
                    plugin.getLogger().warning("Invalid ingredient key '" + ingredientKey + "' in shaped recipe '" + key.getKey() + "'. Must be a single character.");
                    continue; // Пропускаем невалидный ключ
                }
                char keyChar = ingredientKey.charAt(0);
                String ingredientValue = ingredientsSection.getString(ingredientKey);
                if (ingredientValue == null || ingredientValue.isEmpty()) continue;

                RecipeChoice choice = parseIngredient(ingredientValue);
                if (choice != null) {
                    shapedRecipe.setIngredient(keyChar, choice);
                } else {
                    plugin.getLogger().warning("Invalid ingredient value '" + ingredientValue + "' for key '" + keyChar + "' in shaped recipe '" + key.getKey() + "'.");
                    return null; // Считаем рецепт невалидным, если ингредиент плохой
                }
            }
            // Установка категории для книги рецептов (опционально)
            try {
                String categoryStr = data.getString("category", "MISC").toUpperCase();
                CraftingBookCategory category = CraftingBookCategory.valueOf(categoryStr);
                shapedRecipe.setCategory(category);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid crafting category '" + data.getString("category") + "' for recipe " + key.getKey() + ". Using MISC.");
                shapedRecipe.setCategory(CraftingBookCategory.MISC);
            }

            return shapedRecipe;

        } else if ("SHAPELESS".equals(type)) {
            ShapelessRecipe shapelessRecipe = new ShapelessRecipe(key, result);
            List<String> ingredientsList = data.getStringList("ingredients"); // Здесь ingredients - это список строк
            if (ingredientsList.isEmpty()) {
                plugin.getLogger().warning("Missing 'ingredients' list for shapeless recipe '" + key.getKey() + "'.");
                return null;
            }

            for (String ingredientValue : ingredientsList) {
                RecipeChoice choice = parseIngredient(ingredientValue);
                if (choice != null) {
                    shapelessRecipe.addIngredient(choice);
                } else {
                    plugin.getLogger().warning("Invalid ingredient value '" + ingredientValue + "' in shapeless recipe '" + key.getKey() + "'.");
                    return null; // Считаем рецепт невалидным
                }
            }
            // Установка категории для книги рецептов (опционально)
            try {
                String categoryStr = data.getString("category", "MISC").toUpperCase();
                CraftingBookCategory category = CraftingBookCategory.valueOf(categoryStr);
                shapelessRecipe.setCategory(category);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid crafting category '" + data.getString("category") + "' for recipe " + key.getKey() + ". Using MISC.");
                shapelessRecipe.setCategory(CraftingBookCategory.MISC);
            }
            return shapelessRecipe;
        } else {
            plugin.getLogger().warning("Unknown recipe_type '" + type + "' for recipe '" + key.getKey() + "'.");
            return null;
        }
    }

    // Вспомогательный метод для парсинга строки ингредиента (может быть Material или Oraxen ID)
    private RecipeChoice parseIngredient(String ingredientString) {
        if (ingredientString == null || ingredientString.isEmpty()) return null;

        // 1. Проверяем, Oraxen ли это (если Oraxen включен)
        if (configManager.isOraxenSupportEnabled() && plugin.getOraxenIntegration() != null) {
            ItemStack oraxenItem = plugin.getOraxenIntegration().getItemStack(ingredientString);
            if (oraxenItem != null) {
                // Для Oraxen создаем RecipeChoice.ExactChoice
                return new RecipeChoice.ExactChoice(oraxenItem);
            }
            // Если не нашли Oraxen предмет, предполагаем, что это ванильный
        }

        // 2. Проверяем, ванильный ли это Material
        try {
            Material material = Material.valueOf(ingredientString.toUpperCase());
            // Для ванильных используем RecipeChoice.MaterialChoice
            return new RecipeChoice.MaterialChoice(material);
        } catch (IllegalArgumentException e) {
            // Не Oraxen и не ванильный Material
            return null;
        }
    }


    // --- Проверка возможности крафта ---

    /**
     * Проверяет, может ли игрок скрафтить данный рецепт, учитывая права и фракцию.
     * @param player Игрок, который крафтит.
     * @param recipe Рецепт для проверки.
     * @return true, если крафт разрешен, иначе false.
     */
    public boolean canCraft(Player player, Recipe recipe) {
        if (player == null || recipe == null) return false;

        // Проверяем, является ли рецепт Keyed (имеет NamespacedKey)
        if (!(recipe instanceof Keyed)) {
            return true; // Разрешаем ванильные не-Keyed рецепты (если такие есть)
        }

        NamespacedKey recipeKey = ((Keyed) recipe).getKey();

        // Проверяем, наш ли это кастомный рецепт
        CustomRecipeInfo recipeInfo = customRecipes.get(recipeKey);
        if (recipeInfo == null) {
            // Это не наш кастомный рецепт, разрешаем (ванильный или из другого плагина)
            return true;
        }

        // Это наш рецепт, проверяем условия

        // 1. Проверка прав
        String requiredPermission = recipeInfo.requiredPermission;
        boolean hasPermission = true; // Разрешено по умолчанию, если право не задано
        if (requiredPermission != null && !requiredPermission.isEmpty()) {
            hasPermission = player.hasPermission(requiredPermission);
            plugin.getLogger().fine("Checking permission " + requiredPermission + " for player " + player.getName() + " -> " + hasPermission); // Debug
        }

        // 2. Проверка фракции
        List<String> allowedFactions = recipeInfo.allowedFactions;
        boolean factionAllowed = true; // Разрешено по умолчанию, если список пуст
        if (allowedFactions != null && !allowedFactions.isEmpty()) {
            String playerFactionId = playerManager.getPlayerFactionId(player); // Получаем ID фракции игрока
            // Игрок должен быть во фракции, и ID фракции должен быть в списке разрешенных
            factionAllowed = playerFactionId != null && allowedFactions.contains(playerFactionId.toLowerCase());
            plugin.getLogger().fine("Checking faction " + playerFactionId + " against allowed " + allowedFactions + " -> " + factionAllowed); // Debug
        }

        boolean canCraftResult = hasPermission && factionAllowed;
        plugin.getLogger().fine("Can player " + player.getName() + " craft recipe " + recipeKey.getKey() + "? -> " + canCraftResult); // Debug

        return canCraftResult; // Крафт разрешен, только если оба условия выполнены
    }

    // Приватный метод для получения инфо (если вдруг понадобится внутри менеджера)
    private CustomRecipeInfo getRecipeInfoByKey(NamespacedKey key) {
        return customRecipes.get(key);
    }
}