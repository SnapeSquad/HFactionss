package org.isyateq.hfactions.managers;

import java.util.UUID;

public class PendingInvite {
    private final UUID inviterUuid;
    private final String factionId;
    private final long timestamp; // Время создания в миллисекундах

    public PendingInvite(UUID inviterUuid, String factionId) {
        this.inviterUuid = inviterUuid;
        this.factionId = factionId;
        this.timestamp = System.currentTimeMillis();
    }

    public UUID getInviterUuid() {
        return inviterUuid;
    }

    public String getFactionId() {
        return factionId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    // Проверка, не истекло ли время приглашения
    public boolean isExpired(long timeoutMillis) {
        return (System.currentTimeMillis() - timestamp) > timeoutMillis;
    }
}