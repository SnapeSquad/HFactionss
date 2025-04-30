package org.isyateq.hfactions.models;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Представляет ранг внутри фракции.
 */
public final class FactionRank {

    private final int internalId;
    private final String defaultName;
    private String displayName;
    private double salary;
    private List<String> permissions;

    public FactionRank(int internalId, String defaultName, String displayName, double salary, List<String> permissions) {
        if (internalId < 1) throw new IllegalArgumentException("Rank ID must be >= 1");
        if (defaultName == null || defaultName.trim().isEmpty()) throw new IllegalArgumentException("Default name cannot be null/empty");
        this.internalId = internalId;
        this.defaultName = defaultName.trim();
        setDisplayName(displayName); setSalary(salary); setPermissions(permissions);
    }

    public int getInternalId() { return internalId; }
    public String getDefaultName() { return defaultName; }
    public String getDisplayName() { return displayName; }
    public double getSalary() { return salary; }
    public List<String> getPermissions() { return Collections.unmodifiableList(this.permissions); } // Неизменяемая

    public void setDisplayName(String displayName) { String t = (displayName != null) ? displayName.trim() : null; this.displayName = (t == null || t.isEmpty()) ? this.defaultName : t; }
    public void setSalary(double salary) { this.salary = Math.max(0.0, salary); }
    public void setPermissions(List<String> permissions) { if(permissions==null)this.permissions=new ArrayList<>();else this.permissions=permissions.stream().filter(p->p!=null&&!p.trim().isEmpty()).map(String::trim).distinct().collect(Collectors.toList());}
    public void resetDisplayName() { setDisplayName(null); }
    public boolean hasPermission(String permission) { return !(permission==null||permission.trim().isEmpty()) && this.permissions.contains(permission.trim()); }

    @Override public String toString() { return "FactionRank{id="+internalId+", name='"+displayName+"', salary="+salary+", perms="+permissions.size()+'}'; }
    @Override public boolean equals(Object o){if(this==o)return true;if(o==null||getClass()!=o.getClass())return false;FactionRank t=(FactionRank)o;return internalId==t.internalId&&Double.compare(t.salary,salary)==0&&defaultName.equals(t.defaultName)&&displayName.equals(t.displayName)&&new HashSet<>(permissions).equals(new HashSet<>(t.permissions));}
    @Override public int hashCode() { return Objects.hash(internalId, defaultName, displayName, salary, new HashSet<>(permissions)); }
}