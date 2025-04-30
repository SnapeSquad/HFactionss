package org.isyateq.hfactions.models;

import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.ConfigManager; // Импортируем для проверки debug

/**
 * Перечисление типов фракций.
 */
public enum FactionType {
    STATE, CRIMINAL, OTHER;

    public static FactionType fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            logDebug("Null/empty string to FactionType -> OTHER"); return OTHER;
        }
        try { return FactionType.valueOf(name.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { logDebug("Invalid FactionType string: '" + name + "' -> OTHER"); return OTHER; }
    }

    private static void logDebug(String message) {
        HFactions plugin = HFactions.getInstance();
        if (plugin != null) { ConfigManager cfg = plugin.getConfigManager(); if (cfg != null && cfg.isDebugModeEnabled()) plugin.getLogger().warning("[FactionType] " + message); }
        else System.err.println("WARN [HFactions?]: " + message);
    }
}