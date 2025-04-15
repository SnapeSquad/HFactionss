package org.isyateq.hfactions.managers;

import io.th0rgal.oraxen.api.OraxenItems;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.util.Utils; // Импорт Utils

import java.util.*;

public class CraftingManager {

    private final HFactions plugin;
    private final ItemManager itemManager;
    private final PlayerManager playerManager;
    private final ConfigManager configManager; // Добавляем ConfigManager

    private final Map<String, CustomCraftingRecipe> customRecipes = new HashMap<>();

    // Внутренний класс рецепта
    public static class CustomCraftingRecipe {
        final String id;
        public final ItemStack resultItem;
        final Recipe recipe; // Bukkit Recipe
        public final boolean permissionRequired;
        public final String permissionNode;
        public final List<String> allowedFactionIds;

        CustomCraftingRecipe(String id, ItemStack result, Recipe recipe, boolean permRequired, String permNode, List<String> allowedFactions) {
            this.id = id;
            this.resultItem = result;
            this.recipe = recipe;
            this.permissionRequired = permRequired;
            this.permissionNode = permNode;
            this.allowedFactionIds = (allowedFactions != null)
                    ? allowedFactions.stream().map(String::toLowerCase).toList()
                    : Collections.emptyList(); // Используем пустой список вместо null
        }
    }

    public CraftingManager(HFactions plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.playerManager = plugin.getPlayerManager();
        this.configManager = plugin.getConfigManager(); // Инициализируем
        loadRecipesFromConfig();
    }

    public void loadRecipesFromConfig() {
        customRecipes.clear();
        ConfigurationSection craftingSection = configManager.getCraftingSection(); // Используем ConfigManager
        if (craftingSection == null) {
            plugin.logInfo("No 'crafting' section found. No custom recipes loaded.");
            return;
        }

        int loadedCount = 0;
        Set<NamespacedKey> registeredKeys = new HashSet<>(); // Отслеживаем ключи для удаления старых

        for (String craftId : craftingSection.getKeys(false)) {
            ConfigurationSection recipeCfg = craftingSection.getConfigurationSection(craftId);
            if (recipeCfg == null || !recipeCfg.getBoolean("enabled", false)) continue;

            ItemStack resultItem = getResultItem(craftId);
            if (resultItem == null || resultItem.getType() == Material.AIR || resultItem.getType() == Material.BARRIER) {
                plugin.logWarning("Could not load recipe for '" + craftId + "': Result item could not be created.");
                continue;
            }

            NamespacedKey recipeKey = new NamespacedKey(plugin, "hf_" + craftId);
            registeredKeys.add(recipeKey); // Добавляем ключ в сет для последующей проверки
            Recipe bukkitRecipe = null;

            try {
                if (recipeCfg.isList("shape") && recipeCfg.isConfigurationSection("ingredients")) {
                    ShapedRecipe shapedRecipe = new ShapedRecipe(recipeKey, resultItem);
                    List<String> shapeList = recipeCfg.getStringList("shape");
                    if (shapeList.size() > 3 || shapeList.isEmpty()) throw new IllegalArgumentException("Shape must have 1-3 rows.");
                    shapedRecipe.shape(shapeList.toArray(new String[0]));

                    ConfigurationSection ingredientsSec = recipeCfg.getConfigurationSection("ingredients");
                    if (ingredientsSec == null) throw new IllegalArgumentException("Missing 'ingredients' section.");

                    for (String key : ingredientsSec.getKeys(false)) {
                        if (key.length() != 1) throw new IllegalArgumentException("Ingredient key must be single char.");
                        char keyChar = key.charAt(0);
                        String materialName = ingredientsSec.getString(key);
                        if (materialName == null) throw new IllegalArgumentException("Missing material for key '" + keyChar + "'.");

                        ItemStack ingredientStack = getIngredientStack(materialName);
                        if (ingredientStack == null || ingredientStack.getType().isAir()) {
                            throw new IllegalArgumentException("Invalid material/Oraxen ID for '" + keyChar + "': " + materialName);
                        }
                        shapedRecipe.setIngredient(keyChar, new RecipeChoice.ExactChoice(ingredientStack));
                    }
                    bukkitRecipe = shapedRecipe;
                } else if (recipeCfg.isList("shapeless_ingredients")) {
                    ShapelessRecipe shapelessRecipe = new ShapelessRecipe(recipeKey, resultItem);
                    List<String> ingredientsList = recipeCfg.getStringList("shapeless_ingredients");
                    if (ingredientsList.isEmpty()) throw new IllegalArgumentException("Shapeless ingredients list is empty.");

                    for (String materialName : ingredientsList) {
                        ItemStack ingredientStack = getIngredientStack(materialName);
                        if (ingredientStack == null || ingredientStack.getType().isAir()) {
                            throw new IllegalArgumentException("Invalid material/Oraxen ID for shapeless ingredient: " + materialName);
                        }
                        shapelessRecipe.addIngredient(new RecipeChoice.ExactChoice(ingredientStack));
                    }
                    bukkitRecipe = shapelessRecipe;
                } else {
                    plugin.logWarning("Invalid recipe format for '" + craftId + "'. Skipping.");
                    continue;
                }

                boolean permRequired = recipeCfg.getBoolean("permission_required", false);
                String permNode = recipeCfg.getString("craft_permission_node", getCraftPermissionFromResult(craftId)); // Фолбэк
                List<String> allowedFactions = recipeCfg.getStringList("allowed_factions");

                CustomCraftingRecipe customRecipe = new CustomCraftingRecipe(craftId, resultItem, bukkitRecipe, permRequired, permNode, allowedFactions);
                customRecipes.put(craftId.toLowerCase(), customRecipe); // Используем нижний регистр для ID

                // Удаляем старый рецепт с этим ключом, если есть
                if (Bukkit.getRecipe(recipeKey) != null) {
                    Bukkit.removeRecipe(recipeKey);
                }

                // Регистрируем новый
                if (Bukkit.addRecipe(bukkitRecipe)) {
                    plugin.logInfo("Loaded and registered custom recipe: " + craftId + " (Key: " + recipeKey + ")");
                    loadedCount++;
                } else {
                    plugin.logError("Failed to register recipe " + craftId + " with Bukkit! Key: " + recipeKey);
                }

            } catch (Exception e) {
                plugin.logError("Failed to load recipe for '" + craftId + "': " + e.getMessage());
                // e.printStackTrace();
            }
        }

        // Удаляем старые рецепты плагина, которые больше не в конфиге
        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
        while (recipeIterator.hasNext()) {
            Recipe recipe = recipeIterator.next();
            if (recipe instanceof Keyed && ((Keyed) recipe).getKey().getNamespace().equals(plugin.getName().toLowerCase())) {
                NamespacedKey key = ((Keyed) recipe).getKey();
                if (!registeredKeys.contains(key)) { // Если ключ не был зарегистрирован в этом цикле загрузки
                    try {
                        recipeIterator.remove(); // Удаляем старый рецепт
                        plugin.logInfo("Removed orphaned custom recipe: " + key);
                    } catch (Exception e) {
                        plugin.logError("Failed to remove orphaned recipe " + key + ": " + e.getMessage());
                    }
                }
            }
        }


        plugin.logInfo("Loaded " + loadedCount + " custom recipes.");
    }

    private String getCraftPermissionFromResult(String craftId) {
        switch (craftId.toLowerCase()) {
            case "taser": return configManager.getTaserCraftPermission();
            case "handcuffs": return configManager.getHandcuffsCraftPermission();
            case "protocol": return configManager.getProtocolCraftPermission();
            default: return null;
        }
    }

    private ItemStack getIngredientStack(String identifier) {
        // Убрали oraxenEnabled проверку, полагаемся на конфиг предмета
        if (identifier.contains(":") && Bukkit.getPluginManager().isPluginEnabled("Oraxen")) { // Проверка на ':' и активность Oraxen
            try {
                if (OraxenItems.exists(identifier)) {
                    return OraxenItems.getItemById(identifier).build();
                } else {
                    plugin.logWarning("Oraxen ingredient ID '" + identifier + "' not found.");
                    return null;
                }
            } catch (NoClassDefFoundError | Exception e) { // Ловим ошибки, если Oraxen API недоступно
                plugin.logWarning("Failed to interact with Oraxen API for ingredient: " + identifier);
                return null;
            }
        } else {
            try {
                Material mat = Material.matchMaterial(identifier.toUpperCase());
                if (mat != null && mat != Material.AIR) {
                    return new ItemStack(mat);
                } else {
                    plugin.logWarning("Invalid vanilla material name for ingredient: " + identifier);
                    return null;
                }
            } catch (IllegalArgumentException e) {
                plugin.logWarning("Invalid vanilla material name for ingredient: " + identifier);
                return null;
            }
        }
    }

    private ItemStack getResultItem(String craftId) {
        switch (craftId.toLowerCase()) {
            case "taser": return itemManager.getTaserItem();
            case "handcuffs": return itemManager.getHandcuffsItem();
            case "protocol": return itemManager.getProtocolItem();
            default:
                plugin.logWarning("Cannot determine result item for unknown craft ID: " + craftId);
                return null;
        }
    }

    public boolean canCraft(Player player, Recipe recipe) {
        CustomCraftingRecipe customRecipe = findCustomRecipe(recipe);
        if (customRecipe == null) return true; // Не наш рецепт

        // Проверка прав
        if (customRecipe.permissionRequired) {
            if (customRecipe.permissionNode == null || customRecipe.permissionNode.isEmpty()) {
                plugin.logWarning("Recipe '" + customRecipe.id + "' requires permission, but permission_node is not defined!");
                Utils.msg(player, configManager.getErrorColor() + "Ошибка конфигурации крафта."); // TODO: lang
                return false;
            }
            if (!player.hasPermission(customRecipe.permissionNode)) {
                Utils.msg(player, configManager.getErrorColor() + "У вас нет прав для создания этого предмета."); // TODO: lang
                return false;
            }
        }

        // Проверка фракции
        if (!customRecipe.allowedFactionIds.isEmpty()) {
            Faction playerFaction = playerManager.getPlayerFaction(player.getUniqueId());
            if (playerFaction == null || !customRecipe.allowedFactionIds.contains(playerFaction.getId())) {
                String allowedStr = String.join(", ", customRecipe.allowedFactionIds).toUpperCase();
                Utils.msg(player, configManager.getErrorColor() + "Создание этого предмета доступно только для фракций: " + allowedStr); // TODO: lang
                return false;
            }
        }
        return true;
    }

    private CustomCraftingRecipe findCustomRecipe(Recipe bukkitRecipe) {
        if (bukkitRecipe == null || !(bukkitRecipe instanceof Keyed)) return null;
        NamespacedKey key = ((Keyed) bukkitRecipe).getKey();
        // Ищем по ключу, который мы сами задали
        if (key.getNamespace().equals(plugin.getName().toLowerCase()) && key.getKey().startsWith("hf_")) {
            String craftId = key.getKey().substring(3); // Убираем "hf_"
            return customRecipes.get(craftId.toLowerCase());
        }
        // Фолбэк на сравнение результата (менее надежно)
        // ItemStack result = bukkitRecipe.getResult();
        // if (result == null || result.getType() == Material.AIR) return null;
        // for (CustomCraftingRecipe cr : customRecipes.values()) { ... }
        return null;
    }

    public Map<String, CustomCraftingRecipe> getCustomRecipes() {
        return Collections.unmodifiableMap(customRecipes);
    }
}