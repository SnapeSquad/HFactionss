package org.isyateq.hfactions.managers;

// Bukkit API
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

// Локальные классы
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.integrations.LuckPermsIntegration;
import org.isyateq.hfactions.models.Faction;
import org.isyateq.hfactions.models.FactionRank;
import org.isyateq.hfactions.models.PendingInvite;
import org.isyateq.hfactions.util.Utils; // Нужен для форматирования сообщений

// Утилиты Java
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Управляет данными и состоянием игроков, связанными с фракциями.
 * Хранит онлайн-кэш, взаимодействует с БД, управляет правами и отображением.
 */
public final class PlayerManager { // Делаем класс final

    private final HFactions plugin;
    // Зависимости
    private final DatabaseManager databaseManager;
    private final FactionManager factionManager;
    private final ConfigManager configManager;
    private final LuckPermsIntegration luckPermsIntegration;
    private final CuffManager cuffManager;

    // --- Онлайн Кэш Данных Игроков ---
    private final Map<UUID, String> playerFactions = new ConcurrentHashMap<>(); // UUID -> Faction ID (lowercase)
    private final Map<UUID, Integer> playerRanks = new ConcurrentHashMap<>(); // UUID -> Rank ID
    private final Set<UUID> playersInFactionChat = ConcurrentHashMap.newKeySet(); // UUID игроков в фракционном чате
    private final Map<UUID, PendingInvite> pendingInvites = new ConcurrentHashMap<>(); // UUID Цели -> Объект Приглашения
    private final Map<UUID, String> adminsInFactionMode = new ConcurrentHashMap<>(); // UUID Админа -> Faction ID

    /** Конструктор */
    public PlayerManager(HFactions plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.databaseManager = plugin.getDatabaseManager();
        this.factionManager = plugin.getFactionManager();
        this.configManager = plugin.getConfigManager();
        this.luckPermsIntegration = plugin.getLuckPermsIntegration();
        this.cuffManager = plugin.getCuffManager();
        if (this.databaseManager == null || this.factionManager == null || this.configManager == null || this.luckPermsIntegration == null || this.cuffManager == null) {
            throw new IllegalStateException("PlayerManager missing critical dependencies!");
        }
    }

    // --- Загрузка / Сохранение / Очистка Данных ---

    /** Загружает данные для игрока при входе (асинхронно) */
    public void loadPlayerData(Player player) {
        Objects.requireNonNull(player, "Cannot load data for null player");
        UUID uuid = player.getUniqueId();
        plugin.getLogger().fine("Loading data for player " + player.getName() + " (" + uuid + ")");

        databaseManager.loadPlayerDataAsync(uuid, (factionId, rankId) -> {
            plugin.getLogger().fine("Data loaded callback for " + player.getName() + ": Faction=" + factionId + ", Rank=" + rankId);
            boolean needsDbUpdate = false; String finalFactionId = null; Integer finalRankId = null;
            if (factionId != null) {
                Faction faction = factionManager.getFaction(factionId);
                if (faction != null) {
                    finalFactionId = faction.getId();
                    if (rankId != null) { if (faction.getRank(rankId) != null) finalRankId = rankId; else { plugin.getLogger().warning("Player " + player.getName() + " invalid rank " + rankId + ". Resetting."); finalRankId = 1; needsDbUpdate = true; } }
                    else { plugin.getLogger().warning("Player " + player.getName() + " null rank ID. Resetting."); finalRankId = 1; needsDbUpdate = true; }
                } else { plugin.getLogger().warning("Player " + player.getName() + " non-existent faction " + factionId + ". Clearing."); needsDbUpdate = true; finalFactionId = null; finalRankId = null; }
            }
            updateCache(uuid, finalFactionId, finalRankId);
            if (needsDbUpdate) databaseManager.savePlayerDataAsync(uuid, finalFactionId, finalRankId);
            updatePlayerPermissions(player); updatePlayerDisplay(player);
        });
    }

    /** Сохраняет данные для игрока при выходе (асинхронно) */
    public void savePlayerData(Player player) { savePlayerData(player.getUniqueId()); }

    /** Сохраняет данные по UUID (асинхронно) */
    public void savePlayerData(UUID uuid) {
        Objects.requireNonNull(uuid, "Cannot save data for null UUID"); String factionId = playerFactions.get(uuid); Integer rankId = playerRanks.get(uuid);
        plugin.getLogger().fine("Saving data async for player " + uuid + ": Faction=" + factionId + ", Rank=" + rankId); databaseManager.savePlayerDataAsync(uuid, factionId, rankId); }

    /** Сохраняет данные по UUID СИНХРОННО (для onDisable) */
    public void savePlayerDataSynchronously(UUID uuid) {
        Objects.requireNonNull(uuid, "Cannot save sync data for null UUID"); String factionId = playerFactions.get(uuid); Integer rankId = playerRanks.get(uuid);
        plugin.getLogger().info("Saving data sync for player " + uuid + ": Faction=" + factionId + ", Rank=" + rankId);
        final String sql = "INSERT OR REPLACE INTO player_data (uuid, faction_id, rank_id) VALUES(?, ?, ?);"; Connection conn = null; PreparedStatement pstmt = null;
        try { conn = databaseManager.getConnection(); if (conn == null || conn.isClosed()) { plugin.getLogger().severe("DB connection unavailable for sync save " + uuid); return; } pstmt = conn.prepareStatement(sql); pstmt.setString(1, uuid.toString()); if (factionId != null) pstmt.setString(2, factionId); else pstmt.setNull(2, Types.VARCHAR); if (rankId != null) pstmt.setInt(3, rankId); else pstmt.setNull(3, Types.INTEGER); pstmt.executeUpdate();
        } catch (SQLException e) { plugin.getLogger().log(Level.SEVERE, "Error saving player data sync for " + uuid, e); } finally { if (pstmt != null) try { pstmt.close(); } catch (SQLException ignored) {} }
    }

    /** Загружает данные для всех онлайн игроков (при старте/релоаде) */
    public void loadDataForOnlinePlayers() { int count = Bukkit.getOnlinePlayers().size(); if (count > 0) { plugin.getLogger().info("Loading initial data for " + count + " online players..."); for (Player player : Bukkit.getOnlinePlayers()) { loadPlayerData(player); } } }

    /** Сохраняет данные для всех онлайн игроков СИНХРОННО (при выключении) */
    public void saveDataForOnlinePlayersSynchronously() {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers(); int count = onlinePlayers.size();
        if (count > 0) { plugin.getLogger().info("Saving data sync for " + count + " online players..."); for (Player player : onlinePlayers) if (player != null) savePlayerDataSynchronously(player.getUniqueId()); plugin.getLogger().info("Sync save complete."); }
        else { plugin.getLogger().info("No online players for sync save."); }
    }

    /** Очищает кэш игрока при выходе */
    public void clearPlayerData(Player player) { Objects.requireNonNull(player); UUID uuid = player.getUniqueId(); plugin.getLogger().fine("Clearing cache for " + player.getName()); if(adminsInFactionMode.containsKey(uuid))exitAdminMode(player,true); if(cuffManager!=null && cuffManager.isCuffed(player))cuffManager.handlePlayerQuit(player); playerFactions.remove(uuid); playerRanks.remove(uuid); playersInFactionChat.remove(uuid); pendingInvites.remove(uuid); }

    /** Проверяет кэш игроков после релоада конфигов */
    public void validatePlayerDataAfterReload() {
        plugin.getLogger().info("Validating cached player data after reload..."); List<UUID> toUpdateDb=new ArrayList<>(); Set<UUID> onlineUUIDs=new HashSet<>(playerFactions.keySet());
        for(UUID uuid:onlineUUIDs){ String cachedFid=playerFactions.get(uuid); Integer cachedRid=playerRanks.get(uuid); boolean needsDbUpd=false,needsCacheUpd=false; String finalFid=cachedFid; Integer finalRid=cachedRid; Player p=Bukkit.getPlayer(uuid); boolean isOnline=(p!=null&&p.isOnline());
            if(cachedFid!=null){Faction f=factionManager.getFaction(cachedFid); if(f==null){plugin.getLogger().warning("Player "+getPlayerNameSafe(uuid)+"'s faction "+cachedFid+" gone. Clearing."); finalFid=null;finalRid=null;needsCacheUpd=true;needsDbUpd=true;} else {finalFid=f.getId(); if(cachedRid!=null){if(f.getRank(cachedRid)==null){plugin.getLogger().warning("Player "+getPlayerNameSafe(uuid)+"'s rank "+cachedRid+" in "+finalFid+" gone. Resetting.");finalRid=1;if(!Objects.equals(cachedRid,finalRid))needsCacheUpd=true;needsDbUpd=true;}else finalRid=cachedRid;} else {plugin.getLogger().warning("Player "+getPlayerNameSafe(uuid)+" in "+finalFid+" null rank? Resetting.");finalRid=1;needsCacheUpd=true;needsDbUpd=true;}}}
            if(needsCacheUpd)updateCache(uuid,finalFid,finalRid);
            if(needsDbUpd||needsCacheUpd){ if(needsDbUpd)toUpdateDb.add(uuid); if(needsCacheUpd&&isOnline){updatePlayerPermissions(p);updatePlayerDisplay(p);p.sendMessage(ChatColor.YELLOW+"Faction data updated due to config changes.");}}}
        if(!toUpdateDb.isEmpty()){plugin.getLogger().info("Updating DB for "+toUpdateDb.size()+" players after reload.");for(UUID u:toUpdateDb)savePlayerData(u);} else {plugin.getLogger().info("Validation complete, no inconsistencies found.");}
    }

    // --- Управление Фракцией Игрока ---
    public void joinFaction(Player p, Faction f) { if(handleJoinLeavePreChecks(p,true))return;Objects.requireNonNull(f);UUID u=p.getUniqueId();String fid=f.getId();int rid=1;updateCache(u,fid,rid);databaseManager.savePlayerDataAsync(u,fid,rid);p.sendMessage(getMessage("faction.join.success","{faction_name}",f.getName()));broadcastToFaction(fid,getMessage("faction.join.broadcast","{player_name}",p.getName()));updatePlayerPermissions(p);updatePlayerDisplay(p);}
    public void leaveFaction(Player p) { String fid=getPlayerFactionId(p);if(handleJoinLeavePreChecks(p,false))return;Objects.requireNonNull(fid);UUID u=p.getUniqueId();Faction f=factionManager.getFaction(fid);String fn=f!=null?Utils.color(f.getPrefix()+" "+f.getName()):fid;databaseManager.savePlayerDataAsync(u,null,null);String pName=p.getName();p.sendMessage(getMessage("faction.leave.success","{faction_name}",fn));if(f!=null)broadcastToFaction(fid,getMessage("faction.leave.broadcast","{player_name}",pName));clearPlayerData(p);updatePlayerPermissions(p);} // clearPlayerData вызовет updateDisplay
    public void kickPlayer(Player k, Player t) { if(handleModifyPreChecks(k,t))return;UUID tUid=t.getUniqueId();String tFid=getPlayerFactionId(t);Objects.requireNonNull(tFid);Faction f=factionManager.getFaction(tFid);String fn=f!=null?Utils.color(f.getPrefix()+" "+f.getName()):tFid;String tName=t.getName();String kName=k.getName();databaseManager.savePlayerDataAsync(tUid,null,null);t.sendMessage(getMessage("faction.kick.target","{faction_name}",fn,"{kicker_name}",kName));k.sendMessage(getMessage("faction.kick.kicker","{target_name}",tName));if(f!=null)broadcastToFaction(tFid,getMessage("faction.kick.broadcast","{target_name}",tName,"{kicker_name}",kName));clearPlayerData(t);updatePlayerPermissions(t);}
    public void promotePlayer(Player p, Player t) { if(handleModifyPreChecks(p,t))return;UUID tUid=t.getUniqueId();String fid=getPlayerFactionId(t);Integer cRid=getPlayerRankId(t);Objects.requireNonNull(fid);Objects.requireNonNull(cRid);Faction f=factionManager.getFaction(fid);if(f==null)return;FactionRank lr=f.getLeaderRank();if(lr!=null&&cRid>=lr.getInternalId()){p.sendMessage(getMessage("faction.promote.is_leader"));return;}int nRid=cRid+1;FactionRank nR=f.getRank(nRid);if(nR==null){p.sendMessage(getMessage("faction.promote.no_next_rank","{rank_id}",String.valueOf(nRid)));return;}updateCache(tUid,fid,nRid);databaseManager.savePlayerDataAsync(tUid,fid,nRid);String rn=nR.getDisplayName();t.sendMessage(getMessage("faction.promote.target","{rank_name}",rn,"{promoter_name}",p.getName()));p.sendMessage(getMessage("faction.promote.promoter","{target_name}",t.getName(),"{rank_name}",rn));broadcastToFaction(fid,getMessage("faction.promote.broadcast","{target_name}",t.getName(),"{rank_name}",rn,"{promoter_name}",p.getName()));updatePlayerPermissions(t);updatePlayerDisplay(t);}
    public void demotePlayer(Player d, Player t) { if(handleModifyPreChecks(d,t))return;UUID tUid=t.getUniqueId();String fid=getPlayerFactionId(t);Integer cRid=getPlayerRankId(t);Objects.requireNonNull(fid);Objects.requireNonNull(cRid);if(cRid<=1){d.sendMessage(getMessage("faction.demote.is_lowest"));return;}int nRid=cRid-1;Faction f=factionManager.getFaction(fid);if(f==null)return;FactionRank nR=f.getRank(nRid);if(nR==null){plugin.getLogger().severe("Error demote: Rank "+nRid+" not found!");d.sendMessage(getMessage("error.internal"));return;}updateCache(tUid,fid,nRid);databaseManager.savePlayerDataAsync(tUid,fid,nRid);String rn=nR.getDisplayName();t.sendMessage(getMessage("faction.demote.target","{rank_name}",rn,"{demoter_name}",d.getName()));d.sendMessage(getMessage("faction.demote.demoter","{target_name}",t.getName(),"{rank_name}",rn));broadcastToFaction(fid,getMessage("faction.demote.broadcast","{target_name}",t.getName(),"{rank_name}",rn,"{demoter_name}",d.getName()));updatePlayerPermissions(t);updatePlayerDisplay(t);}
    public void setPlayerRank(Player s, Player t, int rId) { if(handleModifyPreChecks(s,t))return;UUID tUid=t.getUniqueId();String fid=getPlayerFactionId(t);Objects.requireNonNull(fid);Faction f=factionManager.getFaction(fid);if(f==null)return;FactionRank nR=f.getRank(rId);if(nR==null){s.sendMessage(getMessage("faction.setrank.invalid_rank","{rank_id}",String.valueOf(rId)));return;}if(!isAdminInMode(s)){FactionRank sR=getPlayerRank(s);if(sR==null||sR.getInternalId()<rId){s.sendMessage(getMessage("faction.setrank.rank_too_high"));return;}}updateCache(tUid,fid,rId);databaseManager.savePlayerDataAsync(tUid,fid,rId);String rn=nR.getDisplayName();t.sendMessage(getMessage("faction.setrank.target","{rank_name}",rn,"{setter_name}",s.getName()));s.sendMessage(getMessage("faction.setrank.setter","{target_name}",t.getName(),"{rank_name}",rn));broadcastToFaction(fid,getMessage("faction.setrank.broadcast","{target_name}",t.getName(),"{rank_name}",rn,"{setter_name}",s.getName()));updatePlayerPermissions(t);updatePlayerDisplay(t);}

    // --- Геттеры и Проверки ---
    public boolean isInFaction(Player p){return playerFactions.containsKey(p.getUniqueId());}
    @SuppressWarnings("unused") public boolean isInFaction(UUID u){return playerFactions.containsKey(u);}
    public String getPlayerFactionId(Player p){return playerFactions.get(p.getUniqueId());}
    public String getPlayerFactionId(UUID u){return playerFactions.get(u);}
    public Faction getPlayerFaction(Player p){String id=getPlayerFactionId(p);return id==null?null:factionManager.getFaction(id);}
    public Faction getPlayerFaction(UUID u){String id=getPlayerFactionId(u);return id==null?null:factionManager.getFaction(id);}
    public Integer getPlayerRankId(Player p){return playerRanks.get(p.getUniqueId());}
    public Integer getPlayerRankId(UUID u){return playerRanks.get(u);}
    public FactionRank getPlayerRank(Player p){return getPlayerRank(p.getUniqueId());}
    public FactionRank getPlayerRank(UUID u) {String fId=getPlayerFactionId(u);Integer rId=getPlayerRankId(u);if(fId!=null&&rId!=null){Faction f=factionManager.getFaction(fId);if(f!=null)return f.getRank(rId);}return null;}
    public List<Player> getOnlineFactionMembers(String fId){if(fId==null)return Collections.emptyList();String lfid=fId.toLowerCase();return Bukkit.getOnlinePlayers().stream().filter(p->lfid.equals(getPlayerFactionId(p))).collect(Collectors.toList());}
    @SuppressWarnings("unused") public List<String> getOnlineFactionMembersWithRank(String fId){if(fId==null)return Collections.emptyList(); String lfid=fId.toLowerCase(); return Bukkit.getOnlinePlayers().stream().filter(p->lfid.equals(getPlayerFactionId(p))).map(p->{FactionRank r=getPlayerRank(p);return p.getName()+"["+(r!=null?r.getDisplayName():"?")+"]";}).sorted().collect(Collectors.toList());}

    // --- Фракционный Чат ---
    public boolean isInFactionChat(Player p){return playersInFactionChat.contains(p.getUniqueId());}
    public void toggleFactionChat(Player p){UUID u=p.getUniqueId();if(!isInFaction(p)){p.sendMessage(getMessage("faction_chat.not_in_faction"));return;}if(playersInFactionChat.remove(u))p.sendMessage(getMessage("faction_chat.disabled"));else{playersInFactionChat.add(u);p.sendMessage(getMessage("faction_chat.enabled"));}}
    public void broadcastToFaction(String fId, String msg){Objects.requireNonNull(fId);if(msg==null||msg.isEmpty())return;List<Player>mems=getOnlineFactionMembers(fId);if(!mems.isEmpty()){plugin.getLogger().fine("Broadcast to "+fId+": "+msg);String fm=Utils.color(msg);for(Player m:mems)m.sendMessage(fm);}}

    // --- Приглашения ---
    public void addInvite(Player t, PendingInvite i){Objects.requireNonNull(t);Objects.requireNonNull(i);removeInvite(t);pendingInvites.put(t.getUniqueId(),i);long tk=configManager.getInviteExpireTicks();if(tk<=0)return;Bukkit.getScheduler().runTaskLater(plugin,()->{PendingInvite cI=pendingInvites.get(t.getUniqueId());if(i.equals(cI)){removeInvite(t);if(t.isOnline()){t.sendMessage(getMessage("faction.invite.expired","{inviter_name}",i.getInviterName()));if(t.getOpenInventory().getTitle().contains("Faction Invite"))t.closeInventory();}}},tk);}
    public PendingInvite getInvite(Player t){return pendingInvites.get(t.getUniqueId());}
    public void removeInvite(Player t){pendingInvites.remove(t.getUniqueId());}

    // --- Админский Режим ---
    public boolean isAdminInMode(Player a){return adminsInFactionMode.containsKey(a.getUniqueId());}
    public String getAdminModeFactionId(Player a){return adminsInFactionMode.get(a.getUniqueId());}
    public void enterAdminMode(Player a,Faction f){if(handleAdminModePreChecks(a,true,f))return;UUID u=a.getUniqueId();CompletableFuture<Boolean> fu=luckPermsIntegration.setAdminMode(a,true);fu.whenComplete((s,e)->{if(s!=null&&s){adminsInFactionMode.put(u,f.getId());if(a.isOnline()){a.sendMessage(getMessage("admin_mode.entered","{faction_name}",f.getName()));a.sendMessage(getMessage("admin_mode.info","{faction_id}",f.getId()));}Bukkit.getScheduler().runTask(plugin,()->updatePlayerDisplay(a));}else{if(a.isOnline())a.sendMessage(getMessage("admin_mode.error_perms"));if(e!=null)plugin.getLogger().log(Level.SEVERE,"Error setting admin perms",e);}});}
    public void exitAdminMode(Player a,boolean s){if(handleAdminModePreChecks(a,false,null))return;UUID u=a.getUniqueId();String fId=adminsInFactionMode.remove(u);CompletableFuture<Boolean> fu=luckPermsIntegration.setAdminMode(a,false);fu.whenComplete((sc,e)->{boolean lS=e==null&&sc!=null&≻if(!lS&&!s&&a.isOnline())a.sendMessage(getMessage("admin_mode.warn_perms"));if(!s&&a.isOnline())a.sendMessage(getMessage("admin_mode.exited","{faction_id}",Objects.toString(fId,"N/A")));Bukkit.getScheduler().runTask(plugin,()->updatePlayerDisplay(a));});updatePlayerDisplay(a);} // Обновляем дисплей сразу

    // --- Вспомогательные Методы ---
    private void updateCache(UUID u,String fId,Integer rId){if(fId!=null){playerFactions.put(u,fId.toLowerCase());if(rId!=null)playerRanks.put(u,rId);else playerRanks.remove(u);}else{playerFactions.remove(u);playerRanks.remove(u);}}
    private void updatePlayerPermissions(Player p){if(luckPermsIntegration!=null)luckPermsIntegration.updatePlayerFactionPermissions(p,getPlayerFaction(p),getPlayerRank(p));}
    private void updatePlayerDisplay(Player p){if(p!=null&&p.isOnline()){if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))plugin.getLogger().fine("PAPI found, display relies on Expansion.");else plugin.getLogger().fine("PAPI not found.");}}
    private String getPlayerNameSafe(UUID u){Player p=Bukkit.getPlayer(u);if(p!=null)return p.getName();OfflinePlayer op=Bukkit.getOfflinePlayer(u);return op.getName()!=null?op.getName():u.toString().substring(0,8);}
    private String getMessage(String path,String def,String... repl){String msg=configManager.getMessage(path,def);if(repl.length>0&&repl.length%2==0)for(int i=0;i<repl.length;i+=2)msg=msg.replace(repl[i],Objects.toString(repl[i+1],""));return msg;}
    private String getMessage(String path){return getMessage(path,"&cMissing:"+path);}
    private boolean handleJoinLeavePreChecks(Player p,boolean checkIn){if(checkIn&&isInFaction(p)){p.sendMessage(getMessage("faction.error.already_in"));return true;}if(!checkIn&&!isInFaction(p)){p.sendMessage(getMessage("faction.error.not_in"));return true;}return false;}
    private boolean handleModifyPreChecks(Player a,Player t){if(a==t){a.sendMessage(getMessage("faction.error.self_modify"));return true;}String tFid=getPlayerFactionId(t);if(tFid==null){a.sendMessage(getMessage("faction.error.target_not_in","{target_name}",t.getName()));return true;}String aFid=getPlayerFactionId(a);String amFid=getAdminModeFactionId(a);String effFid=amFid!=null?amFid:aFid;if(!tFid.equals(effFid)){a.sendMessage(getMessage("faction.error.target_not_in_yours","{target_name}",t.getName()));return true;}return false;}
    private boolean handleAdminModePreChecks(Player a,boolean e,Faction tF){boolean iA=isAdminInMode(a); if(e&&iA){a.sendMessage(getMessage("admin_mode.error.already_in","{faction_id}",adminsInFactionMode.get(a.getUniqueId())));return true;} if(!e&&!iA){if(a.hasPermission("hfactions.admin.adminmode"))a.sendMessage(getMessage("admin_mode.error_not_in_simple"));else a.sendMessage(getMessage("error.no_permission")); return true;} if(e&&!a.hasPermission("hfactions.admin.adminmode")){a.sendMessage(getMessage("error.no_permission"));return true;} if(e&&tF==null){a.sendMessage(getMessage("faction.error.not_found_generic"));return true;} return false;}
    public void clearFactionDataFor(String fId){Objects.requireNonNull(fId);String lfid=fId.toLowerCase();plugin.getLogger().info("Clearing data for "+lfid);List<UUID>toClearC=new ArrayList<>();for(Map.Entry<UUID,String>e:playerFactions.entrySet())if(lfid.equals(e.getValue()))toClearC.add(e.getKey());plugin.getLogger().info("Clearing cache for "+toClearC.size()+" members of "+lfid);for(UUID u:toClearC){updateCache(u,null,null);Player p=Bukkit.getPlayer(u);if(p!=null){p.sendMessage(getMessage("faction.disbanded","{faction_id}",lfid));updatePlayerPermissions(p);updatePlayerDisplay(p);}}Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{final String sql="UPDATE player_data SET faction_id=NULL,rank_id=NULL WHERE faction_id=? COLLATE NOCASE;";int rows;Connection c=null;PreparedStatement ps=null;try{c=databaseManager.getConnection();if(c==null||c.isClosed()){plugin.getLogger().severe("DB conn unavailable in clearFactionData!");return;}ps=c.prepareStatement(sql);ps.setString(1,lfid);rows=ps.executeUpdate();plugin.getLogger().info("Cleared DB for "+rows+" players of "+lfid);}catch(SQLException ex){plugin.getLogger().log(Level.SEVERE,"Could not clear DB for "+lfid,ex);}finally{if(ps!=null)try{ps.close();}catch(SQLException ignored){}};});}

} // Конец класса PlayerManager