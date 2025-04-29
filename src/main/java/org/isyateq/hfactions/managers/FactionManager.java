package org.isyateq.hfactions.managers;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.util.MessageUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
// import java.util.stream.Collectors; // Не используется пока

public class FactionManager {

    private final HFactions plugin;
    private final MessageUtil messageUtil; // Утилита для сообщений
    private final InviteManager inviteManager; // Менеджер приглашений

    // ConcurrentHashMap для потокобезопасности при асинхронных операциях в будущем
    private final Map<String, Faction> factions = new ConcurrentHashMap<>(); // Ключ - имя фракции в нижнем регистре
    private final Map<UUID, String> playerFactions = new ConcurrentHashMap<>(); // Ключ - UUID игрока, Значение - имя фракции в нижнем регистре

    // Настройки из конфига
    private final int maxNameLength;
    private final int minNameLength;
    private final Pattern disallowedNameCharsPattern;
    private final int maxMembers;

    // Для сохранения/загрузки
    private final File factionsFile;
    private FileConfiguration factionsConfig;

    // Флаг для блокировки сохранения во время загрузки
    private boolean isLoading = false;
    // Флаг для подтверждения роспуска
    private final Map<UUID, String> disbandConfirmation = new HashMap<>();


    public FactionManager(HFactions plugin) {
        this.plugin = plugin;
        this.messageUtil = plugin.getMessageUtil();
        this.inviteManager = inviteManager;

        // Загрузка настроек
        this.maxNameLength = plugin.getConfig().getInt("settings.faction.max-name-length", 16);
        this.minNameLength = plugin.getConfig().getInt("settings.faction.min-name-length", 3);
        this.disallowedNameCharsPattern = Pattern.compile(plugin.getConfig().getString("settings.faction.disallowed-name-chars", "[^a-zA-Z0-9_]"));
        this.maxMembers = plugin.getConfig().getInt("settings.faction.max-members", 20);


        // Настройка файла данных
        this.factionsFile = new File(plugin.getDataFolder(), "factions.yml");
        setupFactionDataFile();
        loadFactions(); // Загрузка данных
    }

    // --- Основные операции с фракциями ---

    public boolean createFaction(Player leader, String name) {
        UUID leaderId = leader.getUniqueId();

        // Проверка, состоит ли игрок уже во фракции
        if (playerFactions.containsKey(leaderId)) {
            messageUtil.send(leader, "create.fail-in-faction");
            return false;
        }

        // Валидация имени
        if (name.length() < minNameLength || name.length() > maxNameLength) {
            messageUtil.send(leader, "create.fail-name-length", MessageUtil.placeholders(
                    "min", String.valueOf(minNameLength),
                    "max", String.valueOf(maxNameLength)
            ));
            return false;
        }
        if (disallowedNameCharsPattern.matcher(name).find()) {
            messageUtil.send(leader, "create.fail-name-chars");
            return false;
        }

        String lowerCaseName = name.toLowerCase();

        // Проверка, существует ли фракция с таким именем
        if (factions.containsKey(lowerCaseName)) {
            messageUtil.send(leader, "create.fail-exists");
            return false;
        }

        // Создание фракции
        Faction faction = new Faction(name, leaderId);
        factions.put(lowerCaseName, faction);
        playerFactions.put(leaderId, lowerCaseName);

        saveFactionsAsync(); // Асинхронное сохранение
        messageUtil.send(leader, "create.success", MessageUtil.placeholders("faction_name", faction.getName()));
        plugin.getLogger().info("Фракция '" + name + "' создана лидером " + leader.getName() + " (" + leaderId + ")");
        return true;
    }

    public boolean disbandFaction(Player leader, boolean confirm) {
        UUID leaderId = leader.getUniqueId();
        String factionNameLower = playerFactions.get(leaderId);

        if (factionNameLower == null) {
            messageUtil.send(leader, "disband.fail-not-in-faction");
            return false;
        }

        Faction faction = factions.get(factionNameLower);
        if (faction == null) { // Должно быть невозможно, если playerFactions актуальна, но проверим
            playerFactions.remove(leaderId); // Очистка некорректной записи
            messageUtil.send(leader, "disband.fail-not-in-faction"); // Или другая ошибка
            plugin.getLogger().severe("Несоответствие данных: игрок " + leaderId + " числится во фракции " + factionNameLower + ", но фракция не найдена!");
            return false;
        }

        if (!faction.isLeader(leaderId)) {
            messageUtil.send(leader, "disband.fail-not-leader");
            return false;
        }

        // Логика подтверждения
        if (!confirm) {
            if (!disbandConfirmation.containsKey(leaderId) || !disbandConfirmation.get(leaderId).equals(factionNameLower)) {
                disbandConfirmation.put(leaderId, factionNameLower);
                // Запускаем таймер для сброса подтверждения через некоторое время (например, 30 секунд)
                Bukkit.getScheduler().runTaskLater(plugin, () -> disbandConfirmation.remove(leaderId, factionNameLower), 30 * 20L);
                messageUtil.send(leader, "disband.confirm");
                return false; // Не распускаем, ждем подтверждения
            }
            // Если подтверждение есть, удаляем его и продолжаем
            disbandConfirmation.remove(leaderId);
        } else if (!disbandConfirmation.containsKey(leaderId) || !disbandConfirmation.get(leaderId).equals(factionNameLower)) {
            // Если команда /f disband confirm введена без предварительного /f disband
            messageUtil.send(leader, "disband.fail-not-leader"); // Или другое сообщение
            return false;
        } else {
            // Подтверждение есть, удаляем его
            disbandConfirmation.remove(leaderId);
        }


        // Удаляем всех игроков фракции из playerFactions
        faction.getAllMembersIncludingLeader().forEach(memberId -> {
            playerFactions.remove(memberId);
            // Опционально: Уведомить онлайн-игроков об удалении
            Player memberPlayer = Bukkit.getPlayer(memberId);
            if (memberPlayer != null && memberPlayer.isOnline() && !memberId.equals(leaderId)) {
                messageUtil.send(memberPlayer, "disband.success", // Используем то же сообщение, но можно другое
                        MessageUtil.placeholders("faction_name", faction.getName()));
            }
        });

        // Удаляем фракцию из основной карты
        factions.remove(factionNameLower);

        saveFactionsAsync(); // Асинхронное сохранение
        messageUtil.send(leader, "disband.success", MessageUtil.placeholders("faction_name", faction.getName()));
        plugin.getLogger().info("Фракция '" + faction.getName() + "' ("+factionNameLower+") распущена лидером " + leader.getName() + " (" + leaderId + ")");
        return true;
    }

    public boolean invitePlayer(Player inviter, Player targetPlayer) {
        UUID inviterId = inviter.getUniqueId();
        UUID targetId = targetPlayer.getUniqueId();

        String factionNameLower = playerFactions.get(inviterId);
        if (factionNameLower == null) {
            messageUtil.send(inviter, "invite.fail-not-in-faction");
            return false;
        }

        Faction faction = factions.get(factionNameLower);
        if (faction == null) return false; // Ошибка данных

        // Проверка прав на приглашение
        if (!faction.isLeaderOrOfficer(inviterId) && !inviter.hasPermission("hfactionss.admin")) {
            messageUtil.send(inviter, "invite.fail-no-permission");
            return false;
        }

        // Нельзя пригласить себя
        if (inviterId.equals(targetId)) {
            messageUtil.send(inviter, "invite.fail-invite-self");
            return false;
        }

        // Проверка, состоит ли цель уже во фракции
        if (playerFactions.containsKey(targetId)) {
            messageUtil.send(inviter, "invite.fail-player-in-faction", MessageUtil.placeholders("player_name", targetPlayer.getName()));
            return false;
        }

        // Проверка лимита участников
        if (faction.getTotalSize() >= maxMembers) {
            messageUtil.send(inviter, "invite.fail-faction-full", MessageUtil.placeholders(
                    "max_members", String.valueOf(maxMembers)
            ));
            return false;
        }

        // Проверка, не приглашен ли игрок уже
        if (inviteManager.hasInvite(targetId, factionNameLower)) {
            messageUtil.send(inviter, "invite.fail-already-invited", MessageUtil.placeholders("player_name", targetPlayer.getName()));
            return false;
        }


        // Добавляем приглашение через InviteManager
        inviteManager.addInvite(targetId, factionNameLower, inviter.getName());

        messageUtil.send(inviter, "invite.success-sent", MessageUtil.placeholders(
                "faction_name", faction.getName(),
                "player_name", targetPlayer.getName()
        ));
        return true;
    }

    public boolean joinFaction(Player player, String factionNameToJoin) {
        UUID playerId = player.getUniqueId();
        String factionNameLower = factionNameToJoin.toLowerCase();

        // Проверка, состоит ли игрок уже во фракции
        if (playerFactions.containsKey(playerId)) {
            messageUtil.send(player, "join.fail-already-in-faction");
            return false;
        }

        Faction faction = factions.get(factionNameLower);
        if (faction == null) {
            messageUtil.send(player, "faction-not-found", MessageUtil.placeholders("argument", factionNameToJoin));
            return false;
        }

        // Проверка наличия приглашения
        String acceptedFactionName = inviteManager.acceptInvite(playerId, factionNameLower);
        if (acceptedFactionName == null) { // acceptInvite возвращает null, если инвайта нет или он не совпадает
            messageUtil.send(player, "join.fail-no-invite", MessageUtil.placeholders("faction_name", faction.getName()));
            return false;
        }

        // Проверка лимита участников (еще раз, на всякий случай)
        if (faction.getTotalSize() >= maxMembers) {
            messageUtil.send(player, "join.fail-faction-full", MessageUtil.placeholders("faction_name", faction.getName()));
            // Важно: Возможно, стоит вернуть инвайт обратно или уведомить пригласившего
            return false;
        }


        // Добавление игрока во фракцию
        faction.addMember(playerId);
        playerFactions.put(playerId, factionNameLower);

        saveFactionsAsync(); // Асинхронное сохранение

        messageUtil.send(player, "join.success", MessageUtil.placeholders("faction_name", faction.getName()));

        // Уведомление других участников фракции
        String joinBroadcast = messageUtil.get("join.broadcast", false, MessageUtil.placeholders("player_name", player.getName()));
        faction.getAllMembersIncludingLeader().stream()
                .filter(uuid -> !uuid.equals(playerId)) // Не отправлять себе
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull) // Только онлайн игрокам
                .forEach(member -> member.sendMessage(joinBroadcast));

        return true;
    }

    public boolean leaveFaction(Player player) {
        UUID playerId = player.getUniqueId();
        String factionNameLower = playerFactions.get(playerId);

        if (factionNameLower == null) {
            messageUtil.send(player, "leave.fail-not-in-faction");
            return false;
        }

        Faction faction = factions.get(factionNameLower);
        if (faction == null) return false; // Ошибка данных

        // Лидер не может покинуть фракцию
        if (faction.isLeader(playerId)) {
            messageUtil.send(player, "leave.fail-leader");
            return false;
        }

        // Удаление игрока
        if (faction.isOfficer(playerId)) {
            faction.removeOfficer(playerId);
        } else {
            faction.removeMember(playerId);
        }
        playerFactions.remove(playerId);

        saveFactionsAsync(); // Асинхронное сохранение

        messageUtil.send(player, "leave.success", MessageUtil.placeholders("faction_name", faction.getName()));

        // Уведомление других участников
        String leaveBroadcast = messageUtil.get("leave.broadcast", false, MessageUtil.placeholders("player_name", player.getName()));
        faction.getAllMembersIncludingLeader().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .forEach(member -> member.sendMessage(leaveBroadcast));

        // Удаляем все ожидающие приглашения для этого игрока
        inviteManager.removeInvite(playerId);

        return true;
    }

    public boolean kickPlayer(Player kicker, OfflinePlayer targetPlayer) {
        UUID kickerId = kicker.getUniqueId();
        UUID targetId = targetPlayer.getUniqueId();

        String factionNameLower = playerFactions.get(kickerId);
        if (factionNameLower == null) {
            messageUtil.send(kicker, "kick.fail-not-in-faction");
            return false;
        }

        Faction faction = factions.get(factionNameLower);
        if (faction == null) return false;

        // Проверка прав на кик
        boolean isKickerLeader = faction.isLeader(kickerId);
        boolean isKickerOfficer = faction.isOfficer(kickerId);

        if (!isKickerLeader && !isKickerOfficer && !kicker.hasPermission("hfactionss.admin")) {
            messageUtil.send(kicker, "kick.fail-no-permission");
            return false;
        }

        // Проверка, состоит ли цель в этой фракции
        if (!factionNameLower.equals(playerFactions.get(targetId))) {
            messageUtil.send(kicker, "kick.fail-target-not-in-faction", MessageUtil.placeholders("target_name", targetPlayer.getName()));
            return false;
        }

        // Нельзя кикнуть себя
        if (kickerId.equals(targetId)) {
            messageUtil.send(kicker, "kick.fail-cannot-kick-self");
            return false;
        }

        // Нельзя кикнуть лидера
        if (faction.isLeader(targetId)) {
            messageUtil.send(kicker, "kick.fail-cannot-kick-leader");
            return false;
        }

        // Офицер не может кикнуть другого офицера
        if (isKickerOfficer && faction.isOfficer(targetId)) {
            messageUtil.send(kicker, "kick.fail-cannot-kick-officer");
            return false;
        }

        // Исключение игрока
        boolean removed = faction.removeOfficer(targetId) || faction.removeMember(targetId);
        if (removed) {
            playerFactions.remove(targetId);
            saveFactionsAsync();

            messageUtil.send(kicker, "kick.success", MessageUtil.placeholders("target_name", targetPlayer.getName()));

            // Уведомление исключенного игрока, если он онлайн
            Player onlineTarget = targetPlayer.getPlayer();
            if (onlineTarget != null && onlineTarget.isOnline()) {
                messageUtil.send(onlineTarget, "kick.success-target", MessageUtil.placeholders(
                        "faction_name", faction.getName(),
                        "kicker_name", kicker.getName()
                ));
            }
            // Удаляем все ожидающие приглашения для кикнутого игрока
            inviteManager.removeInvite(targetId);

            return true;
        }
        return false; // Если по какой-то причине не удалось удалить
    }

    public boolean promotePlayer(Player leader, OfflinePlayer targetPlayer) {
        UUID leaderId = leader.getUniqueId();
        UUID targetId = targetPlayer.getUniqueId();

        String factionNameLower = playerFactions.get(leaderId);
        if (factionNameLower == null) {
            messageUtil.send(leader, "promote.fail-not-in-faction");
            return false;
        }

        Faction faction = factions.get(factionNameLower);
        if (faction == null) return false;

        // Только лидер может повышать
        if (!faction.isLeader(leaderId) && !leader.hasPermission("hfactionss.admin")) {
            messageUtil.send(leader, "promote.fail-not-leader");
            return false;
        }

        // Нельзя повысить себя
        if (leaderId.equals(targetId)) {
            messageUtil.send(leader, "promote.fail-promote-self");
            return false;
        }

        // Цель должна быть обычным участником
        if (!faction.isMember(targetId)) {
            if (faction.isOfficer(targetId)) {
                messageUtil.send(leader, "promote.fail-target-already-officer", MessageUtil.placeholders("target_name", targetPlayer.getName()));
            } else {
                messageUtil.send(leader, "promote.fail-target-not-member", MessageUtil.placeholders("target_name", targetPlayer.getName()));
            }
            return false;
        }

        // Повышение
        faction.removeMember(targetId);
        faction.addOfficer(targetId);
        saveFactionsAsync();

        messageUtil.send(leader, "promote.success", MessageUtil.placeholders("target_name", targetPlayer.getName()));

        // Уведомление цели, если онлайн
        Player onlineTarget = targetPlayer.getPlayer();
        if (onlineTarget != null && onlineTarget.isOnline()) {
            messageUtil.send(onlineTarget, "promote.success-target", MessageUtil.placeholders("faction_name", faction.getName()));
        }
        return true;
    }

    public boolean demotePlayer(Player leader, OfflinePlayer targetPlayer) {
        UUID leaderId = leader.getUniqueId();
        UUID targetId = targetPlayer.getUniqueId();

        String factionNameLower = playerFactions.get(leaderId);
        if (factionNameLower == null) {
            messageUtil.send(leader, "demote.fail-not-in-faction");
            return false;
        }

        Faction faction = factions.get(factionNameLower);
        if (faction == null) return false;

        // Только лидер может понижать
        if (!faction.isLeader(leaderId) && !leader.hasPermission("hfactionss.admin")) {
            messageUtil.send(leader, "demote.fail-not-leader");
            return false;
        }

        // Нельзя понизить себя
        if (leaderId.equals(targetId)) {
            messageUtil.send(leader, "demote.fail-demote-self");
            return false;
        }

        // Цель должна быть офицером
        if (!faction.isOfficer(targetId)) {
            messageUtil.send(leader, "demote.fail-target-not-officer", MessageUtil.placeholders("target_name", targetPlayer.getName()));
            return false;
        }

        // Понижение
        faction.removeOfficer(targetId);
        faction.addMember(targetId);
        saveFactionsAsync();

        messageUtil.send(leader, "demote.success", MessageUtil.placeholders("target_name", targetPlayer.getName()));

        // Уведомление цели, если онлайн
        Player onlineTarget = targetPlayer.getPlayer();
        if (onlineTarget != null && onlineTarget.isOnline()) {
            messageUtil.send(onlineTarget, "demote.success-target", MessageUtil.placeholders("faction_name", faction.getName()));
        }
        return true;
    }


    // --- Получение информации ---

    public Faction getFaction(String name) {
        return factions.get(name.toLowerCase());
    }

    public Faction getPlayerFaction(UUID playerId) {
        String factionNameLower = playerFactions.get(playerId);
        return (factionNameLower != null) ? factions.get(factionNameLower) : null;
    }

    public Collection<Faction> getAllFactions() {
        return Collections.unmodifiableCollection(factions.values()); // Возвращаем неизменяемую коллекцию
    }

    public boolean isPlayerInFaction(UUID playerId) {
        return playerFactions.containsKey(playerId);
    }


    // --- Сохранение и загрузка данных ---

    private void setupFactionDataFile() {
        if (!factionsFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs(); // Создаем папку, если нужно
                factionsFile.createNewFile();
                plugin.getLogger().info("Создан файл данных factions.yml.");
            } catch (IOException e) {
                plugin.getLogger().severe("Не удалось создать файл factions.yml!");
                e.printStackTrace();
            }
        }
        factionsConfig = YamlConfiguration.loadConfiguration(factionsFile);
    }

    // Асинхронная обертка для сохранения
    public void saveFactionsAsync() {
        if (isLoading) return; // Не сохраняем во время загрузки

        // Создаем копии данных для безопасной передачи в асинхронный поток
        final Map<String, Faction> factionsCopy = new HashMap<>(this.factions);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            saveFactionsToFile(factionsCopy);
        });
    }

    // Синхронное сохранение (вызывать только при выключении плагина)
    public void saveFactionsSync() {
        if (isLoading) return;
        saveFactionsToFile(this.factions); // Сохраняем текущие данные
    }

    // Метод, выполняющий непосредственное сохранение в файл (может быть вызван синхронно или асинхронно)
    private synchronized void saveFactionsToFile(Map<String, Faction> factionsToSave) {
        // Используем временный файл для атомарности
        File tempFile = new File(plugin.getDataFolder(), "factions.yml.tmp");
        File backupFile = new File(plugin.getDataFolder(), "factions.yml.bak");

        YamlConfiguration tempConfig = new YamlConfiguration(); // Создаем новую конфигурацию для записи

        // Сохраняем каждую фракцию
        for (Map.Entry<String, Faction> entry : factionsToSave.entrySet()) {
            String factionKey = "factions." + entry.getKey(); // ключ = имя в нижнем регистре
            Faction faction = entry.getValue();

            tempConfig.set(factionKey + ".name", faction.getName()); // Оригинальное имя
            tempConfig.set(factionKey + ".leader", faction.getLeader().toString());
            // Сохраняем UUID как строки
            tempConfig.set(factionKey + ".officers", faction.getOfficers().stream().map(UUID::toString).collect(Collectors.toList()));
            tempConfig.set(factionKey + ".members", faction.getMembers().stream().map(UUID::toString).collect(Collectors.toList()));
            // Добавь сюда сохранение других данных фракции, если они появятся (баланс, дом, и т.д.)
            // tempConfig.set(factionKey + ".balance", faction.getBalance());
        }

        try {
            // Записываем во временный файл
            tempConfig.save(tempFile);

            // Переименовываем старый файл в бэкап (если он существует)
            if (factionsFile.exists()) {
                if (backupFile.exists()) {
                    backupFile.delete(); // Удаляем старый бэкап
                }
                if (!factionsFile.renameTo(backupFile)) {
                    plugin.getLogger().severe("Не удалось создать бэкап файла factions.yml!");
                    // Продолжаем попытку сохранить основной файл
                }
            }

            // Переименовываем временный файл в основной
            if (!tempFile.renameTo(factionsFile)) {
                plugin.getLogger().severe("Не удалось переименовать временный файл в factions.yml!");
                // Попытка восстановить из бэкапа? (более сложная логика)
            }

            // plugin.getLogger().info("Данные фракций сохранены."); // Не спамим в консоль при асинхронном сохранении

        } catch (IOException e) {
            plugin.getLogger().severe("Критическая ошибка при сохранении данных фракций в factions.yml!");
            e.printStackTrace();
            // Удаляем временный файл, если он остался
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }


    public void loadFactions() {
        isLoading = true; // Блокируем сохранение во время загрузки
        try {
            if (!factionsFile.exists()) {
                plugin.getLogger().info("Файл factions.yml не найден. Загрузка не требуется.");
                return;
            }

            factionsConfig = YamlConfiguration.loadConfiguration(factionsFile); // Перезагружаем из файла

            factions.clear();
            playerFactions.clear();

            ConfigurationSection factionsSection = factionsConfig.getConfigurationSection("factions");
            if (factionsSection == null) {
                plugin.getLogger().info("Секция 'factions' не найдена в factions.yml. Нет данных для загрузки.");
                return;
            }

            int loadedCount = 0;
            for (String factionKey : factionsSection.getKeys(false)) { // factionKey - это имя в нижнем регистре
                String path = "factions." + factionKey;
                try {
                    String originalName = factionsConfig.getString(path + ".name");
                    UUID leaderUUID = UUID.fromString(factionsConfig.getString(path + ".leader"));

                    Set<UUID> officers = factionsConfig.getStringList(path + ".officers").stream()
                            .map(UUID::fromString)
                            .collect(Collectors.toSet());
                    Set<UUID> members = factionsConfig.getStringList(path + ".members").stream()
                            .map(UUID::fromString)
                            .collect(Collectors.toSet());

                    Faction faction = new Faction(originalName, leaderUUID);
                    officers.forEach(faction::forceAddOfficer);
                    members.forEach(faction::forceAddMember);

                    // Загрузка других данных (если есть)
                    // faction.setBalance(factionsConfig.getDouble(path + ".balance", 0.0));

                    factions.put(factionKey, faction); // Ключ = имя в нижнем регистре

                    // Заполняем карту playerFactions
                    playerFactions.put(leaderUUID, factionKey);
                    officers.forEach(uuid -> playerFactions.put(uuid, factionKey));
                    members.forEach(uuid -> playerFactions.put(uuid, factionKey));
                    loadedCount++;
                } catch (Exception e) {
                    plugin.getLogger().severe("Ошибка загрузки данных для фракции с ключом '" + factionKey + "'. Пропускаем.");
                    e.printStackTrace();
                }
            }
            plugin.getLogger().info("Загружено " + loadedCount + " фракций.");

        } catch (Exception e) {
            plugin.getLogger().severe("Критическая ошибка при загрузке данных фракций из factions.yml!");
            e.printStackTrace();
        } finally {
            isLoading = false; // Снимаем блокировку сохранения
        }
    }
    // Получение ника игрока (с проверкой на null)
    public String getPlayerName(UUID uuid) {
        if (uuid == null) return "Неизвестно";
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player != null ? player.getName() : uuid.toString().substring(0, 8); // Возвращаем ник или часть UUID
    }
}