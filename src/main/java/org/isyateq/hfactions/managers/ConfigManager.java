package org.isyateq.hfactions.managers;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.isyateq.hfactions.HFactions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects; // Добавлен импорт Objects

public class ConfigManager {

    private final HFactions plugin;
    private FileConfiguration config = null;
    private File configFile = null;
    private FileConfiguration langConfig = null;
    private File langFile = null;

    public ConfigManager(HFactions plugin) {
        this.plugin = plugin;
    }

    public void loadConfigs() {
        reloadConfig();
        reloadLangConfig(); // Загружаем lang.yml
        plugin.logInfo("Configuration files loaded/reloaded.");
    }

    // --- Базовое управление config.yml ---
    public FileConfiguration getConfig() { if (config == null) reloadConfig(); return config; }
    public void reloadConfig() {
        if (configFile == null) configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) plugin.saveResource("config.yml", false);
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultConfigStream = plugin.getResource("config.yml");
        if (defaultConfigStream != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8)));
            config.options().copyDefaults(true); // Копируем дефолты, если их нет
        }
    }
    public void saveConfig() { if (config == null || configFile == null) return; try { getConfig().save(configFile); } catch (IOException ex) { plugin.logError("Could not save config to " + configFile); ex.printStackTrace(); } }

    // --- Базовое управление lang.yml ---
    public FileConfiguration getLangConfig() { if (langConfig == null) reloadLangConfig(); return langConfig; }
    public void reloadLangConfig() {
        if (langFile == null) langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) plugin.saveResource("lang.yml", false);
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        InputStream defaultLangStream = plugin.getResource("lang.yml");
        if (defaultLangStream != null) {
            langConfig.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaultLangStream, StandardCharsets.UTF_8)));
            langConfig.options().copyDefaults(true); // Копируем дефолты, если их нет
        }
    }
    public void saveLangConfig() { if (langConfig == null || langFile == null) return; try { getLangConfig().save(langFile); } catch (IOException ex) { plugin.logError("Could not save lang to " + langFile); ex.printStackTrace(); } }

    // --- Получение сообщений из lang.yml ---

    /**
     * Получает сообщение из lang.yml по ключу, заменяя плейсхолдеры.
     * @param key Ключ сообщения (например, "error-no-permission")
     * @param replacements Карта замен (ключ плейсхолдера без скобок, значение)
     * @param defaultValue Значение по умолчанию, если ключ не найден
     * @return Отформатированное сообщение
     */
    public String getLangMessage(String key, @Nullable Map<String, String> replacements, @NotNull String defaultValue) {
        String message = getLangConfig().getString(key, defaultValue);
        // Дополнительная проверка, если в lang.yml ключ есть, но значение пустое
        if (message == null || message.isEmpty()) {
            message = defaultValue;
        }
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                // Заменяем плейсхолдеры {placeholder}
                // Используем Objects.toString для защиты от null в значении замены
                message = message.replace("{" + entry.getKey() + "}", Objects.toString(entry.getValue(), ""));
            }
        }
        // Заменяем стандартные цветовые коды Bukkit
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Получает сообщение из lang.yml без замен плейсхолдеров.
     * @param key Ключ сообщения
     * @param defaultValue Значение по умолчанию
     * @return Отформатированное сообщение
     */
    public String getLangMessage(String key, @NotNull String defaultValue) {
        return getLangMessage(key, null, defaultValue);
    }

    /**
     * Получает сообщение с одним плейсхолдером.
     * @param key Ключ сообщения
     * @param placeholderKey Ключ плейсхолдера (без скобок)
     * @param placeholderValue Значение для замены
     * @param defaultValue Значение по умолчанию
     * @return Отформатированное сообщение
     */
    public String getLangMessage(String key, String placeholderKey, String placeholderValue, @NotNull String defaultValue) {
        // Используем Map.of для создания неизменяемой карты
        return getLangMessage(key, Map.of(placeholderKey, placeholderValue), defaultValue);
    }

    // --- Геттеры для настроек (как раньше, без изменений) ---
    public String getErrorColor() { return "&c"; }
    public String getSuccessColor() { return "&a"; }
    public String getPrimaryColor() { return "&b"; }
    public String getSecondaryColor() { return "&7"; }
    public String getHighlightColor() { return "&e"; }
    public String getYellowColor() { return "&e"; } // Добавили для единообразия
    // ... (все остальные геттеры для config.yml) ...
    public boolean isFactionChatEnabled() { return getConfig().getBoolean("faction.chat.enabled", true); }
    public List<String> getFactionChatAliases() { return getConfig().getStringList("faction.chat.toggle-command-aliases"); }
    public String getFactionChatFormat() { return getConfig().getString("faction.chat.format", "&f{prefix} &7[{rank}] {player}: &f{message}"); }
    public long getInviteTimeoutSeconds() { return getConfig().getLong("faction.invite.timeout-seconds", 60); }
    public boolean isWarehouseEnabled() { return getConfig().getBoolean("faction.warehouse.enabled", true); }
    public String getWarehouseTitleFormat() { return getConfig().getString("faction.warehouse.gui_title_format", "&1Склад: &9{faction_name} &7(Стр. {page}/{max_pages})"); }
    public String getWarehouseOpenPermission() { return getConfig().getString("faction.warehouse.open_permission", "hfactions.faction.warehouse.open"); }
    public String getWarehouseDepositPermission() { return "hfactions.faction.warehouse.deposit"; }
    public String getWarehouseWithdrawPermission() { return "hfactions.faction.warehouse.withdraw"; }
    public boolean isTaserEnabled() { return getConfig().getBoolean("special_items.taser.enabled", true); }
    public boolean useOraxenForTaser() { return getConfig().getBoolean("special_items.taser.oraxen_integration", false); }
    public String getOraxenTaserId() { return getConfig().getString("special_items.taser.oraxen_item_id", "taser_gun"); }
    public String getTaserMaterial() { return getConfig().getString("special_items.taser.item_material", "CROSSBOW"); }
    public int getTaserModelData() { return getConfig().getInt("special_items.taser.item_custom_model_data", 0); }
    public String getTaserName() { return getConfig().getString("special_items.taser.item_name", "&eПолицейский Тайзер"); }
    public List<String> getTaserLore() { return getConfig().getStringList("special_items.taser.item_lore"); }
    public double getTaserRange() { return getConfig().getDouble("special_items.taser.range", 15.0); }
    public long getTaserCooldownSeconds() { return getConfig().getLong("special_items.taser.cooldown_seconds", 5); }
    public List<String> getTaserHitEffects() { return getConfig().getStringList("special_items.taser.hit_effects"); }
    public String getTaserCraftPermission() { return getConfig().getString("special_items.taser.craft_permission_node"); }
    public boolean allowTaserFriendlyFire() { return getConfig().getBoolean("special_items.taser.allow_friendly_fire", false); }
    public boolean allowTaserStateOnStateFire() { return getConfig().getBoolean("special_items.taser.allow_state_on_state_fire", false); }
    public String getTaserSoundFire() { return getConfig().getString("special_items.taser.sound_fire"); }
    public String getTaserSoundHitEntity() { return getConfig().getString("special_items.taser.sound_hit_entity"); }
    public String getTaserSoundHitBlock() { return getConfig().getString("special_items.taser.sound_hit_block"); }
    public String getTaserParticleHitEntity() { return getConfig().getString("special_items.taser.particle_hit_entity"); }
    public boolean isHandcuffsEnabled() { return getConfig().getBoolean("special_items.handcuffs.enabled", true); }
    public boolean useOraxenForHandcuffs() { return getConfig().getBoolean("special_items.handcuffs.oraxen_integration", false); }
    public String getOraxenHandcuffsId() { return getConfig().getString("special_items.handcuffs.oraxen_item_id", "handcuffs"); }
    public String getHandcuffsMaterial() { return getConfig().getString("special_items.handcuffs.item_material", "SHEARS"); }
    public int getHandcuffsModelData() { return getConfig().getInt("special_items.handcuffs.item_custom_model_data", 0); }
    public String getHandcuffsName() { return getConfig().getString("special_items.handcuffs.item_name", "&7Наручники"); }
    public List<String> getHandcuffsLore() { return getConfig().getStringList("special_items.handcuffs.item_lore"); }
    public double getHandcuffsMaxLeashDistance() { return getConfig().getDouble("special_items.handcuffs.max_distance_leash", 10.0); }
    public long getHandcuffsCheckIntervalTicks() { return getConfig().getLong("special_items.handcuffs.check_interval_ticks", 10); }
    public List<String> getHandcuffsCuffedEffects() { return getConfig().getStringList("special_items.handcuffs.cuffed_effects"); }
    public boolean disableCuffedItemDrop() { return getConfig().getBoolean("special_items.handcuffs.disable_item_drop", true); }
    public boolean disableCuffedItemPickup() { return getConfig().getBoolean("special_items.handcuffs.disable_item_pickup", true); }
    public boolean disableCuffedBlockBreak() { return getConfig().getBoolean("special_items.handcuffs.disable_block_break", true); }
    public boolean disableCuffedBlockPlace() { return getConfig().getBoolean("special_items.handcuffs.disable_block_place", true); }
    public boolean disableCuffedInventoryOpen() { return getConfig().getBoolean("special_items.handcuffs.disable_inventory_open", true); }
    public boolean disableCuffedItemSwitch() { return getConfig().getBoolean("special_items.handcuffs.disable_item_switch", false); }
    public boolean disableCuffedInteraction() { return getConfig().getBoolean("special_items.handcuffs.disable_interaction", true); }
    public String getHandcuffsCraftPermission() { return getConfig().getString("special_items.handcuffs.craft_permission_node"); }
    public boolean allowHandcuffsFriendlyCuff() { return getConfig().getBoolean("special_items.handcuffs.allow_friendly_cuff", false); }
    public boolean allowHandcuffsStateOnStateCuff() { return getConfig().getBoolean("special_items.handcuffs.allow_state_on_state_cuff", false); }
    public String getHandcuffsSoundCuff() { return getConfig().getString("special_items.handcuffs.sound_cuff"); }
    public String getHandcuffsSoundUncuff() { return getConfig().getString("special_items.handcuffs.sound_uncuff"); }
    public String getHandcuffsSoundLeashTeleport() { return getConfig().getString("special_items.handcuffs.sound_leash_teleport"); }
    public boolean isProtocolEnabled() { return getConfig().getBoolean("special_items.protocol.enabled", true); }
    public boolean useOraxenForProtocol() { return getConfig().getBoolean("special_items.protocol.oraxen_integration", false); }
    public String getOraxenProtocolId() { return getConfig().getString("special_items.protocol.oraxen_item_id", "protocol_book"); }
    public String getProtocolMaterial() { return getConfig().getString("special_items.protocol.item_material", "WRITABLE_BOOK"); }
    public int getProtocolModelData() { return getConfig().getInt("special_items.protocol.item_custom_model_data", 0); }
    public String getProtocolName() { return getConfig().getString("special_items.protocol.item_name", "&bПротокол о Нарушении"); }
    public List<String> getProtocolLore() { return getConfig().getStringList("special_items.protocol.item_lore"); }
    public String getProtocolCraftPermission() { return getConfig().getString("special_items.protocol.craft_permission_node"); }
    public boolean isFiningEnabled() { return getConfig().getBoolean("fining.enabled", true); }
    public boolean useProtocolItemForFines() { return getConfig().getBoolean("fining.use_protocol_item", true); }
    public List<String> getFineCommandAliases() { return getConfig().getStringList("fining.command_aliases"); }
    public String getFineAllowedFactionId() { return getConfig().getString("fining.allowed_faction", "pd").toLowerCase(); }
    public String getFinePermissionNode() { return getConfig().getString("fining.permission_node", "hfactions.pd.fine"); }
    public int getFineMinRankId() { return getConfig().getInt("fining.min_rank_id", 0); }
    public long getFineGlobalCooldownSeconds() { return getConfig().getLong("fining.global_cooldown_seconds", 15); }
    public long getFineTargetCooldownMinutes() { return getConfig().getLong("fining.target_cooldown_minutes", 5); }
    public double getFineMinAmount() { return getConfig().getDouble("fining.min_amount", 100.0); }
    public double getFineMaxAmount() { return getConfig().getDouble("fining.max_amount", 10000.0); }
    public String getFineMoneyDestination() { return getConfig().getString("fining.money_destination", "FACTION").toUpperCase(); }
    public String getFineGovernmentFactionId() { return getConfig().getString("fining.government_faction_id", "gov").toLowerCase(); }
    public boolean logFines() { return getConfig().getBoolean("fining.log_fines", true); }
    public String getFineLogFormat() { return getConfig().getString("fining.log_format", "[FINE] Officer: {officer}, Target: {target}, Amount: {amount}, Reason: {reason}"); }
    public boolean isDynmapEnabled() { return getConfig().getBoolean("dynmap.enabled", true); }
    public long getDynmapUpdateIntervalSeconds() { return getConfig().getLong("dynmap.update_interval_seconds", 300); }
    public String getDynmapMarkerSetId() { return getConfig().getString("dynmap.marker_set_id", "hfactions_territories"); }
    public String getDynmapMarkerSetLabel() { return getConfig().getString("dynmap.marker_set_label", "Фракции (HFactions)"); }
    public ConfigurationSection getDynmapDefaultStyleSection() { return getConfig().getConfigurationSection("dynmap.default_style"); }
    public String getDynmapDefaultPopupContent() { return getConfig().getString("dynmap.default_style.popup_content", "<b>{faction_name}</b><br>Zone: {zone_name}"); }
    public ConfigurationSection getCraftingSection() { return getConfig().getConfigurationSection("crafting"); }

    public String getLangMessage(String s) { return ChatColor.translateAlternateColorCodes('&', s);}
}