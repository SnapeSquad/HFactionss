package org.isyateq.hfactions.util;

import org.isyateq.hfactions.HFactions;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageUtil {

    private final HFactions plugin;
    private final FileConfiguration config;
    private final String prefix;
    private final Pattern placeholderPattern = Pattern.compile("\\{([^{}]+)\\}"); // Pattern для {placeholder}

    public MessageUtil(HFactions plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.prefix = ChatColor.translateAlternateColorCodes('&', config.getString("messages.prefix", "&e[HFactions] &r"));
    }

    public String get(String key) {
        return get(key, true, new HashMap<>());
    }

    public String get(String key, boolean usePrefix) {
        return get(key, usePrefix, new HashMap<>());
    }

    public String get(String key, Map<String, String> placeholders) {
        return get(key, true, placeholders);
    }

    public String get(String key, boolean usePrefix, Map<String, String> placeholders) {
        String message = config.getString("messages." + key);
        if (message == null) {
            plugin.getLogger().warning("Missing message in config.yml: messages." + key);
            return ChatColor.RED + "Ошибка: Сообщение не найдено (" + key + ")";
        }

        message = replacePlaceholders(message, placeholders);
        message = ChatColor.translateAlternateColorCodes('&', message);

        return usePrefix ? prefix + message : message;
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, true, new HashMap<>());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        send(sender, key, true, placeholders);
    }

    public void send(CommandSender sender, String key, boolean usePrefix) {
        send(sender, key, usePrefix, new HashMap<>());
    }

    public void send(CommandSender sender, String key, boolean usePrefix, Map<String, String> placeholders) {
        if (sender != null) {
            sender.sendMessage(get(key, usePrefix, placeholders));
        }
    }

    private String replacePlaceholders(String message, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return message;
        }
        Matcher matcher = placeholderPattern.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String placeholderKey = matcher.group(1);
            String replacement = placeholders.getOrDefault(placeholderKey, matcher.group(0)); // Use original if no replacement
            // Важно: Экранируем символы '$' и '\' в замене, чтобы избежать проблем с Matcher.appendReplacement
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    // Удобные методы для создания карт плейсхолдеров
    public static Map<String, String> placeholders(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Must provide key-value pairs for placeholders.");
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}