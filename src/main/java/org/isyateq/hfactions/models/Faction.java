package org.isyateq.hfactions.models;

import org.bukkit.inventory.ItemStack;
import org.isyateq.hfactions.HFactions;
import org.isyateq.hfactions.managers.FactionManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Faction {

    private final String id;
    private final Map<Integer, FactionRank> ranks = new ConcurrentHashMap<>();
    private String name;
    private FactionType type;
    private String color;
    private String prefix;
    private volatile double balance;
    private int warehouseSize;
    private ItemStack[] warehouseContents;

    // Два конструктора (новый и для загрузки) из финальной версии

    public Faction(String id, String name, FactionType type, String color, String prefix, double initialBalance, int warehouseSize, Map<Integer, FactionRank> initialRanks) {
        this.id = Objects.requireNonNull(id).toLowerCase(); this.name = Objects.requireNonNull(name); this.type = Objects.requireNonNull(type); this.color = Objects.requireNonNull(color); this.prefix = Objects.requireNonNull(prefix); this.balance = Math.max(0.0, initialBalance);
        if (warehouseSize < 9) throw new IllegalArgumentException("WH size < 9"); this.warehouseSize = warehouseSize; this.warehouseContents = new ItemStack[this.warehouseSize]; Objects.requireNonNull(initialRanks); this.ranks.putAll(initialRanks);
        if (!this.ranks.containsKey(1)) { this.ranks.put(1, new FactionRank(1, "Default Rank 1", "Default Rank 1", 0.0, new ArrayList<>())); logWarning("Faction " + id + " created missing rank 1."); }
    }
    public Faction(String id, String name, FactionType type, String color, String prefix, double balance, int warehouseSize, Map<Integer, FactionRank> loadedRanks, ItemStack[] loadedWarehouse) {
        this.id = Objects.requireNonNull(id); this.name = Objects.requireNonNull(name); this.type = Objects.requireNonNull(type); this.color = Objects.requireNonNull(color); this.prefix = Objects.requireNonNull(prefix); this.balance = Math.max(0.0, balance);
        if (warehouseSize < 9) throw new IllegalArgumentException("Loaded WH size < 9"); this.warehouseSize = warehouseSize; Objects.requireNonNull(loadedRanks); this.ranks.putAll(loadedRanks);
        if (!this.ranks.containsKey(1)) { this.ranks.put(1, new FactionRank(1, "Loaded Rank 1", "Loaded Rank 1", 0.0, new ArrayList<>())); logWarning("Faction " + id + " loaded missing rank 1."); }
        if (loadedWarehouse != null && loadedWarehouse.length == this.warehouseSize) { this.warehouseContents = loadedWarehouse; } else { this.warehouseContents = new ItemStack[this.warehouseSize]; if (loadedWarehouse != null) logWarning("WH size mismatch loaded " + id); }
    }

    // Геттеры
    public String getId() { return id; } public String getName() { return name; } public FactionType getType() { return type; } public String getColor() { return color; } public String getPrefix() { return prefix; } public double getBalance() { return balance; } public int getWarehouseSize() { return warehouseSize; } public ItemStack[] getWarehouseContents() { return this.warehouseContents != null ? this.warehouseContents.clone() : new ItemStack[this.warehouseSize]; } public FactionRank getRank(int rankId) { return ranks.get(rankId); } public Map<Integer, FactionRank> getRanks() { return Collections.unmodifiableMap(ranks); } public FactionRank getLeaderRank() { FactionRank lr = ranks.get(11); return (lr!=null)?lr:ranks.values().stream().max(Comparator.comparingInt(FactionRank::getInternalId)).orElse(null); }

    // Сеттеры и методы управления (ПУБЛИЧНЫЕ)
    public void setName(String name) { if(isNewValue(name,this.name)&&name!=null&&!name.isEmpty()){ this.name = name; markModified(); } }
    public void setType(FactionType type) { if (isNewValue(type, this.type)) { this.type = type; markModified(); } }
    public void setColor(String color) { if(color!=null&&color.matches("^#[a-fA-F0-9]{6}")&&isNewValue(color,this.color)){this.color=color;markModified();}else if(color!=null&&!color.matches("^#[a-fA-F0-9]{6}$")){logWarning("Invalid HEX color '"+color+"' for "+id);}}
    public void setPrefix(String prefix) { if (isNewValue(prefix, this.prefix)) { this.prefix = prefix; markModified(); } }
    public void setBalance(double balance) { double newBalance = Math.max(0.0, balance); if (newBalance != this.balance) { this.balance = newBalance; markModified(); } }
    public void deposit(double amount) { if (amount > 0) this.balance += amount; } // markModified в FactionManager
    public boolean withdraw(double amount) { if (amount > 0 && this.balance >= amount) { this.balance -= amount; return true; } return false; } // markModified в FactionManager
    public void updateRank(FactionRank rank) { if (rank != null && ranks.containsKey(rank.getInternalId())) { ranks.put(rank.getInternalId(), rank); markModified(); } }
    public void addRank(FactionRank rank) { if(rank != null) { ranks.put(rank.getInternalId(), rank); markModified(); } }
    public FactionRank removeRank(int rankId) { if (rankId == 1 || ranks.size() <= 1) { logWarning("Cannot remove rank " + rankId); return null; } FactionRank removed = ranks.remove(rankId); if (removed != null) markModified(); return removed; }
    public void setWarehouseContents(ItemStack[] newContents) { ItemStack[] cs;int s=this.warehouseSize;if(newContents!=null&&newContents.length==s){cs=newContents.clone();}else{cs=new ItemStack[s];if(newContents!=null)logWarning("WH size mismatch "+id);} if(!Arrays.equals(this.warehouseContents,cs)){this.warehouseContents=cs;markModified();logFine("WH updated "+id);}}

    // Приватные вспомогательные
    private <T> boolean isNewValue(T n,T o){return n!=null&&!n.equals(o);}
    private void markModified(){HFactions p=HFactions.getInstance();if(p!=null){FactionManager fm=p.getFactionManager();if(fm!=null)fm.markFactionAsModified(this.id);else System.err.println("CRITICAL: FactionManager null");}else System.err.println("Warning: Plugin instance null");}
    private void logWarning(String m){HFactions p =HFactions.getInstance();if(p!=null)p.getLogger().warning(m);else System.err.println("WARN:"+m);}
    private void logFine(String m){HFactions p=HFactions.getInstance();if(p!=null&&p.getConfigManager()!=null&&p.getConfigManager().isDebugModeEnabled())p.getLogger().info("[DEBUG]"+m);}

    @Override public boolean equals(Object o) { if (this==o) return true; if(o==null||getClass()!=o.getClass()) return false; return id.equals(((Faction)o).id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return "Faction{id='"+id+"', name='"+name+"'}"; }
}