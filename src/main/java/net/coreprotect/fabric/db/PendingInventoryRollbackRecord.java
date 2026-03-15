package net.coreprotect.fabric.db;

public final class PendingInventoryRollbackRecord {
    private final long id;
    private final long eventId;
    private final String actorUuid;
    private final String actorName;
    private final String worldKey;
    private final boolean restore;
    private final boolean addItems;
    private final String target;
    private final String payload;
    private final String status;
    private final int attempts;
    private final String lastError;
    private final long createdAt;
    private final long updatedAt;

    public PendingInventoryRollbackRecord(
        long id,
        long eventId,
        String actorUuid,
        String actorName,
        String worldKey,
        boolean restore,
        boolean addItems,
        String target,
        String payload,
        String status,
        int attempts,
        String lastError,
        long createdAt,
        long updatedAt
    ) {
        this.id = id;
        this.eventId = eventId;
        this.actorUuid = actorUuid;
        this.actorName = actorName;
        this.worldKey = worldKey;
        this.restore = restore;
        this.addItems = addItems;
        this.target = target;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long id() {
        return id;
    }

    public long eventId() {
        return eventId;
    }

    public String actorUuid() {
        return actorUuid;
    }

    public String actorName() {
        return actorName;
    }

    public String worldKey() {
        return worldKey;
    }

    public boolean restore() {
        return restore;
    }

    public boolean addItems() {
        return addItems;
    }

    public String target() {
        return target;
    }

    public String payload() {
        return payload;
    }

    public String status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }
}
