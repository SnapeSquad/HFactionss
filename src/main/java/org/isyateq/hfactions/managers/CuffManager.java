package org.isyateq.hfactions.managers;

// Bukkit Imports
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask; // Используем BukkitTask для хранения ссылки

// HFactions Imports
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.tasks.CuffLeashTask; // Импорт задачи
import org.isyateq.hfactions.util.Utils; // Для color и safe location
import org.isyateq.hfactions.models.FactionType;

// Java Imports
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class CuffManager {

    private final HFactions plugin;
    private final ConfigManager configManager;
    private final PlayerManager playerManager; // Нужен для проверки фракций/статусов
    private final FactionManager factionManager;

    // Игрок в наручниках (UUID) -> Тот, кто надел (UUID)
    private final Map<UUID, UUID> cuffedPlayers = new ConcurrentHashMap<>();
    // Игрок в наручниках (UUID) -> Задача "поводка"
    private final Map<UUID, BukkitTask> leashTasks = new ConcurrentHashMap<>(); // Храним BukkitTask

    // Настройки из конфига (кэшируем для производительности)
    private List<PotionEffect> cuffEffects;
    private Set<Material> blockedInteractions;
    private boolean preventDrop;
    private boolean preventInventory;
    private boolean preventAttack;
    private boolean preventCommands;
    private boolean preventGamemodeChange;
    private boolean preventTeleport; // Предотвращать ли ТП другими плагинами/командами?
    private long leashCheckInterval;
    private boolean leashEnabled;


    public CuffManager(HFactions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.playerManager = plugin.getPlayerManager();
        this.factionManager = plugin.getFactionManager();

        // Проверки менеджеров
        if (this.configManager == null) plugin.getLogger().severe("ConfigManager is null in CuffManager!");
        if (this.playerManager == null) plugin.getLogger().severe("PlayerManager is null in CuffManager!");

        loadConfigSettings(); // Загружаем настройки при инициализации
    }

    /**
     * Загружает и кэширует настройки наручников из config.yml.
     */
    public void loadConfigSettings() {
        plugin.getLogger().info("Loading handcuff settings from config...");
        if (configManager == null || configManager.getConfig() == null) {
            plugin.getLogger().severe("Cannot load handcuff settings: ConfigManager or config is null.");
            // Устанавливаем безопасные значения по умолчанию
            cuffEffects = new ArrayList<>();
            blockedInteractions = new HashSet<>();
            preventDrop = true;
            preventInventory = true;
            preventAttack = true;
            preventCommands = true;
            preventGamemodeChange = true;
            preventTeleport = true;
            leashEnabled = true;
            leashCheckInterval = 20L; // 1 секунда
            return;
        }
        FileConfiguration config = configManager.getConfig();
        String pathPrefix = "mechanics.handcuffs.";

        // Эффекты зелий
        cuffEffects = new ArrayList<>();
        ConfigurationSection effectsSection = config.getConfigurationSection(pathPrefix + "effects");
        if (effectsSection != null) {
            for (String effectName : effectsSection.getKeys(false)) {
                try {
                    PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
                    if (type != null) {
                        int amplifier = effectsSection.getInt(effectName + ".amplifier", 0);
                        // Ставим очень большую длительность, будем обновлять при проверке
                        cuffEffects.add(new PotionEffect(type, Integer.MAX_VALUE, amplifier, true, false)); // ambient=true, particles=false
                        plugin.getLogger().fine("Loaded cuff effect: " + type.getName() + " amplifier " + amplifier);
                    } else {
                        plugin.getLogger().warning("Invalid potion effect type in handcuff config: " + effectName);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error loading handcuff effect: " + effectName, e);
                }
            }
        } else {
            plugin.getLogger().info("No handcuff effects defined in config.");
        }

        // Запрещенные взаимодействия (с блоками)
        blockedInteractions = new HashSet<>();
        List<String> blockedMats = config.getStringList(pathPrefix + "blocked_interactions");
        for (String matName : blockedMats) {
            try {
                Material mat = Material.matchMaterial(matName.toUpperCase());
                if (mat != null) {
                    blockedInteractions.add(mat);
                    plugin.getLogger().fine("Added blocked interaction for handcuffs: " + mat.name());
                } else {
                    plugin.getLogger().warning("Invalid material in handcuff blocked_interactions: " + matName);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error parsing blocked interaction material: " + matName, e);
            }
        }

        // Другие запреты
        preventDrop = config.getBoolean(pathPrefix + "prevent_drop", true);
        preventInventory = config.getBoolean(pathPrefix + "prevent_inventory", true);
        preventAttack = config.getBoolean(pathPrefix + "prevent_attack", true);
        preventCommands = config.getBoolean(pathPrefix + "prevent_commands.enabled", true);
        // Список разрешенных команд (если preventCommands=true)
        // List<String> allowedCommands = config.getStringList(pathPrefix + "prevent_commands.allowed"); // Обрабатывается в PlayerCommandPreprocessEvent
        preventGamemodeChange = config.getBoolean(pathPrefix + "prevent_gamemode_change", true);
        preventTeleport = config.getBoolean(pathPrefix + "prevent_teleport", true);


        // Настройки поводка
        leashEnabled = config.getBoolean(pathPrefix + "leash.enabled", true);
        leashCheckInterval = config.getLong(pathPrefix + "leash.check_interval_ticks", 20L);
        if (leashCheckInterval <= 0) {
            plugin.getLogger().warning("Handcuff leash check interval is invalid (<=0), setting to 20.");
            leashCheckInterval = 20L;
        }
        plugin.getLogger().info("Handcuff settings loaded.");
    }


    /**
     * Надевает наручники на игрока.
     *
     * @param cuffed Игрок, на которого надевают.
     * @param cuffer Игрок, который надевает.
     */
    public void cuffPlayer(Player cuffed, Player cuffer) {
        if (cuffed == null || cuffer == null || configManager == null) return;

        // Проверка, не является ли игрок сам собой
        if (cuffed.getUniqueId().equals(cuffer.getUniqueId())) {
            cuffer.sendMessage(Utils.color(configManager.getMessage("handcuffs.cant_cuff_self")));
            return;
        }

        // Проверка, не находится ли цель уже в наручниках
        if (isCuffed(cuffed)) {
            Player currentCuffer = Bukkit.getPlayer(cuffedPlayers.get(cuffed.getUniqueId()));
            String cufferName = currentCuffer != null ? currentCuffer.getName() : "someone";
            String msg = configManager.getMessage("handcuffs.already_cuffed");
            cuffer.sendMessage(Utils.color(msg.replace("{target_name}", cuffed.getName()).replace("{cuffer_name}", cufferName)));
            return;
        }


        // Проверка правил (на своих, на гос) - если нужно
        boolean allowCuff = checkCuffRules(cuffer, cuffed);
        if (!allowCuff) {
            // Сообщение об ошибке отправляется внутри checkCuffRules
            return;
        }



        // Надеваем наручники
        cuffedPlayers.put(cuffed.getUniqueId(), cuffer.getUniqueId());
        plugin.getLogger().info(cuffer.getName() + " cuffed " + cuffed.getName());

        // Применяем эффекты
        applyCuffEffects(cuffed);

        // Запускаем задачу "поводка", если включено
        if (leashEnabled) {
            startLeashTask(cuffed, cuffer);
        }

        // Отправляем сообщения
        String targetMsg = configManager.getMessage("handcuffs.cuffed_target");
        cuffed.sendMessage(Utils.color(targetMsg.replace("{cuffer_name}", cuffer.getName())));
        String cufferMsg = configManager.getMessage("handcuffs.cuffed_cuffer");
        cuffer.sendMessage(Utils.color(cufferMsg.replace("{target_name}", cuffed.getName())));

        // Звук (если настроено)
        playSound(cuffed.getLocation(), configManager.getConfig().getString("mechanics.handcuffs.sounds.cuff"));

    }

    /**
     * Снимает наручники с игрока.
     * @param cuffed Игрок, с которого снимают.
     * @param remover Игрок, который снимает (может быть null, если снимает консоль/админ).
     * @param force Снимать ли принудительно (игнорируя проверку, кто надел)?
     * @return true, если успешно сняли, false если не был в наручниках или нет прав снять.
     */
    public boolean uncuffPlayer(Player cuffed, Player remover, boolean force) {
        if (cuffed == null) return false;
        UUID cuffedUuid = cuffed.getUniqueId();

        UUID originalCufferUuid = cuffedPlayers.get(cuffedUuid);
        if (originalCufferUuid == null) {
            // Игрок не в наручниках
            if (remover != null && configManager != null) { // Сообщаем только если есть кому сообщать
                String msg = configManager.getMessage("handcuffs.not_cuffed").replace("{target_name}", cuffed.getName());
                remover.sendMessage(Utils.color(msg));
            }
            return false;
        }

        // Проверка прав на снятие
        boolean canUncuff = false;
        if (force) {
            canUncuff = true; // Принудительное снятие
        } else if (remover != null) {
            // Снять может тот, кто надел
            if (remover.getUniqueId().equals(originalCufferUuid)) {
                canUncuff = true;
            }
            // Или админ с правом hfactions.admin.uncuff
            else if (remover.hasPermission("hfactions.admin.uncuff")) {
                canUncuff = true;
            }
            // TODO: Добавить проверку на ранг/право фракции? (Например, старший офицер может снять)
        }

        if (!canUncuff) {
            if (remover != null && configManager != null) {
                remover.sendMessage(Utils.color(configManager.getMessage("handcuffs.cant_uncuff")));
            }
            return false;
        }


        // Снимаем наручники
        cuffedPlayers.remove(cuffedUuid);
        stopLeashTask(cuffedUuid); // Останавливаем поводок
        removeCuffEffects(cuffed); // Снимаем эффекты

        String removerName = force ? "an Administrator" : remover.getName();
        plugin.getLogger().info(removerName + " uncuffed " + cuffed.getName());


        // Отправляем сообщения
        if (configManager != null) {
            String targetMsg = configManager.getMessage("handcuffs.uncuffed_target");
            cuffed.sendMessage(Utils.color(targetMsg.replace("{remover_name}", removerName)));

            if (remover != null && !force) { // Не сообщаем админу при форсе?
                String removerMsg = configManager.getMessage("handcuffs.uncuffed_remover");
                remover.sendMessage(Utils.color(removerMsg.replace("{target_name}", cuffed.getName())));
            }
        }

        // Звук снятия
        playSound(cuffed.getLocation(), configManager.getConfig().getString("mechanics.handcuffs.sounds.uncuff"));

        return true;
    }

    // --- Проверки состояния и правил ---

    public boolean isCuffed(Player player) {
        return player != null && cuffedPlayers.containsKey(player.getUniqueId());
    }
    public boolean isCuffed(UUID uuid) {
        return uuid != null && cuffedPlayers.containsKey(uuid);
    }


    /**
     * Возвращает UUID игрока, который надел наручники на данного игрока.
     * @param cuffedPlayer Игрок в наручниках.
     * @return UUID "ведущего" или null, если игрок не в наручниках.
     */
    public UUID getWhoCuffed(Player cuffedPlayer) {
        return cuffedPlayer != null ? cuffedPlayers.get(cuffedPlayer.getUniqueId()) : null;
    }
    public UUID getWhoCuffed(UUID cuffedUuid) {
        return cuffedUuid != null ? cuffedPlayers.get(cuffedUuid) : null;
    }


    /**
     * Проверяет, разрешено ли надевать наручники согласно правилам в конфиге.
     * @param cuffer Тот, кто надевает.
     * @param target Тот, на кого надевают.
     * @return true, если разрешено, false иначе.
     */
    private boolean checkCuffRules(Player cuffer, Player target) {
        if (configManager == null || configManager.getConfig() == null || playerManager == null) return true; // Разрешаем, если нет конфига
        FileConfiguration config = configManager.getConfig();
        String pathPrefix = "mechanics.handcuffs.rules.";

        boolean preventOwnFaction = config.getBoolean(pathPrefix + "prevent_on_own_faction", true);
        boolean preventSameType = config.getBoolean(pathPrefix + "prevent_on_same_type", true); // Например, STATE на STATE
        List<String> whitelistFactions = config.getStringList(pathPrefix + "whitelist_factions"); // Фракции, на которые всегда можно
        List<String> blacklistFactions = config.getStringList(pathPrefix + "blacklist_factions"); // Фракции, на которые никогда нельзя

        String cufferFactionId = playerManager.getPlayerFactionId(cuffer);
        Faction cufferFaction = cufferFactionId != null ? factionManager.getFaction(cufferFactionId) : null;
        FactionType cufferType = cufferFaction != null ? cufferFaction.getType() : null;

        String targetFactionId = playerManager.getPlayerFactionId(target);
        Faction targetFaction = targetFactionId != null ? factionManager.getFaction(targetFactionId) : null;
        FactionType targetType = targetFaction != null ? targetFaction.getType() : null;

        // 1. Вайтлист - если цель в вайтлисте, всегда можно
        if (targetFactionId != null && !whitelistFactions.isEmpty() && whitelistFactions.contains(targetFactionId)) {
            return true;
        }

        // 2. Блэклист - если цель в блэклисте, никогда нельзя
        if (targetFactionId != null && !blacklistFactions.isEmpty() && blacklistFactions.contains(targetFactionId)) {
            cuffer.sendMessage(Utils.color(configManager.getMessage("handcuffs.rule_blacklist")));
            return false;
        }

        // 3. Запрет на свою фракцию
        if (preventOwnFaction && cufferFactionId != null && cufferFactionId.equals(targetFactionId)) {
            cuffer.sendMessage(Utils.color(configManager.getMessage("handcuffs.rule_own_faction")));
            return false;
        }

        // 4. Запрет на тот же тип (если оба игрока во фракциях)
        if (preventSameType && targetType != null && cufferType == targetType) {
            // Исключение: разрешаем STATE на STATE? (Настраивается)
            boolean allowStateOnState = config.getBoolean(pathPrefix + "allow_state_on_state", true);
            if (!(cufferType == FactionType.STATE && allowStateOnState)) {
                cuffer.sendMessage(Utils.color(configManager.getMessage("handcuffs.rule_same_type")));
                return false;
            }
        }

        return true; // Все проверки пройдены
    }


    // --- Управление эффектами и ограничениями ---

    /**
     * Применяет эффекты зелий к игроку в наручниках.
     */
    public void applyCuffEffects(Player player) {
        if (player == null || !player.isOnline() || cuffEffects == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> { // Убедимся, что выполняем в основном потоке
            for (PotionEffect effect : cuffEffects) {
                // Обновляем длительность на всякий случай (хотя она и так Integer.MAX_VALUE)
                player.addPotionEffect(new PotionEffect(effect.getType(), Integer.MAX_VALUE, effect.getAmplifier(), effect.isAmbient(), effect.hasParticles()), true); // true = force
            }
        });
    }

    /**
     * Снимает эффекты зелий наручников с игрока.
     */
    public void removeCuffEffects(Player player) {
        if (player == null || !player.isOnline() || cuffEffects == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (PotionEffect effect : cuffEffects) {
                player.removePotionEffect(effect.getType());
            }
        });
    }

    /**
     * Проверяет, разрешено ли игроку в наручниках выполнять определенные действия.
     * Используется в слушателях событий.
     */
    public boolean canDropItems(Player player) { return !isCuffed(player) || !preventDrop; }
    public boolean canOpenInventory(Player player) { return !isCuffed(player) || !preventInventory; }
    public boolean canAttack(Player player) { return !isCuffed(player) || !preventAttack; }
    public boolean canUseCommand(Player player, String command) {
        if (!isCuffed(player) || !preventCommands) return true;
        // Проверяем список разрешенных команд
        if (configManager != null && configManager.getConfig() != null) {
            List<String> allowed = configManager.getConfig().getStringList("mechanics.handcuffs.prevent_commands.allowed");
            String baseCommand = command.toLowerCase().split(" ")[0]; // Берем только саму команду без аргументов
            for (String allowedCmd : allowed) {
                if (baseCommand.equals(allowedCmd.toLowerCase())) {
                    return true; // Команда в списке разрешенных
                }
            }
        }
        return false; // Команда запрещена
    }
    public boolean canChangeGamemode(Player player) { return !isCuffed(player) || !preventGamemodeChange; }
    public boolean canTeleport(Player player) { return !isCuffed(player) || !preventTeleport; }
    public boolean canInteract(Player player, Material blockMaterial) {
        if (!isCuffed(player)) return true;
        return blockedInteractions == null || !blockedInteractions.contains(blockMaterial);
    }


    // --- Управление задачей "поводка" ---

    private void startLeashTask(Player cuffed, Player cuffer) {
        if (cuffed == null || cuffer == null) return;
        UUID cuffedUuid = cuffed.getUniqueId();
        // Останавливаем старую задачу, если вдруг она еще есть
        stopLeashTask(cuffedUuid);

        CuffLeashTask leashTaskRunnable = new CuffLeashTask(plugin, cuffedUuid, cuffer.getUniqueId());
        // Запускаем задачу с интервалом из конфига
        BukkitTask task = leashTaskRunnable.runTaskTimer(plugin, leashCheckInterval, leashCheckInterval);
        leashTasks.put(cuffedUuid, task);
        plugin.getLogger().fine("Started leash task for cuffed player: " + cuffed.getName());
    }

    /**
     * Останавливает задачу "поводка" для игрока. Вызывается из самой задачи или при снятии наручников.
     */
    public void stopLeashTask(UUID cuffedUuid) {
        if (cuffedUuid == null) return;
        BukkitTask task = leashTasks.remove(cuffedUuid);
        if (task != null) {
            try {
                if (!task.isCancelled()) { // Проверяем перед отменой
                    task.cancel();
                    plugin.getLogger().fine("Stopped leash task for cuffed player: " + cuffedUuid);
                }
            } catch (Exception e) {
                // Ловим возможные ошибки при отмене задачи
                plugin.getLogger().log(Level.WARNING, "Error cancelling leash task for " + cuffedUuid, e);
            }

        }
    }

    // --- Обработка выхода игрока ---
    public void handlePlayerQuit(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        // Если игрок был в наручниках, освобождаем его
        if (cuffedPlayers.containsKey(uuid)) {
            plugin.getLogger().info("Player " + player.getName() + " quit while cuffed. Uncuffing...");
            uncuffPlayer(player, null, true); // Снимаем принудительно без уведомителя
        }

        // Если игрок кого-то вел, освобождаем ведомого
        // Проходим по всем закованным и ищем, не этот ли игрок их вел
        UUID playerUuid = player.getUniqueId();
        List<UUID> toUncuff = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : cuffedPlayers.entrySet()) {
            if (playerUuid.equals(entry.getValue())) { // Если UUID вышедшего = UUID ведущего
                toUncuff.add(entry.getKey()); // Добавляем UUID закованного в список
            }
        }

        if (!toUncuff.isEmpty()) {
            plugin.getLogger().info("Cuffer " + player.getName() + " quit. Uncuffing " + toUncuff.size() + " players...");
            for (UUID cuffedUuidToRelease : toUncuff) {
                Player cuffedPlayerToRelease = Bukkit.getPlayer(cuffedUuidToRelease);
                if (cuffedPlayerToRelease != null && cuffedPlayerToRelease.isOnline()) {
                    uncuffPlayer(cuffedPlayerToRelease, null, true); // Снимаем принудительно
                } else {
                    // Если ведомый тоже оффлайн, просто удаляем запись
                    cuffedPlayers.remove(cuffedUuidToRelease);
                    stopLeashTask(cuffedUuidToRelease);
                }
            }
        }
    }

    // --- Воспроизведение звука ---
    private void playSound(Location location, String soundName) {
        if (location == null || soundName == null || soundName.isEmpty() || location.getWorld() == null) {
            return;
        }
        try {
            // Используем строку как ключ для org.bukkit.Sound
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName.toUpperCase());
            location.getWorld().playSound(location, sound, 1.0f, 1.0f); // Громкость и питч по умолчанию
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid sound name in handcuff config: " + soundName);
        } catch (Exception e) { // Ловим другие ошибки
            plugin.getLogger().log(Level.WARNING, "Error playing sound " + soundName, e);
        }
    }

}