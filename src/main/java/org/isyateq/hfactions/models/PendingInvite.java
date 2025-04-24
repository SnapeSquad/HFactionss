package org.isyateq.hfactions.models;

import java.util.UUID;

public class PendingInvite {

    private final UUID inviterUUID;
    private final String inviterName;
    private final String factionId;
    private final String factionName; // Храним имя для отображения
    private final long timestamp; // Время создания приглашения

    public PendingInvite(UUID inviterUUID, String inviterName, String factionId, long factionName) {
        this.inviterUUID = inviterUUID;
        this.inviterName = inviterName;
        this.factionId = factionId;
        this.factionName = factionName;
        this.timestamp = System.currentTimeMillis(); // Записываем текущее время
    }

    public UUID getInviterUUID() {
        return inviterUUID;
    }

    public String getInviterName() {
        return inviterName;
    }

    public String getFactionId() {
        return factionId;
    }

    public String getFactionName() {
        return factionName;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Проверяет, не истекло ли приглашение.
     * @param expireSeconds Время жизни приглашения в секундах.
     * @return true, если приглашение истекло, иначе false.
     */
    public boolean isExpired(long expireSeconds) {
        return (System.currentTimeMillis() - timestamp) > (expireSeconds * 1000);
    }
}