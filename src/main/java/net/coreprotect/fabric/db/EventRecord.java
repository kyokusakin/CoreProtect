package net.coreprotect.fabric.db;

import net.coreprotect.fabric.log.CoreProtectEventType;

public final class EventRecord {
    private final long timestamp;
    private final CoreProtectEventType type;
    private final String actorUuid;
    private final String actorName;
    private final String worldKey;
    private final Integer x;
    private final Integer y;
    private final Integer z;
    private final String target;
    private final String payload;

    public EventRecord(long timestamp, CoreProtectEventType type, String actorUuid, String actorName, String worldKey, Integer x, Integer y, Integer z, String target, String payload) {
        this.timestamp = timestamp;
        this.type = type;
        this.actorUuid = actorUuid;
        this.actorName = actorName;
        this.worldKey = worldKey;
        this.x = x;
        this.y = y;
        this.z = z;
        this.target = target;
        this.payload = payload;
    }

    public long timestamp() {
        return timestamp;
    }

    public CoreProtectEventType type() {
        return type;
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

    public Integer x() {
        return x;
    }

    public Integer y() {
        return y;
    }

    public Integer z() {
        return z;
    }

    public String target() {
        return target;
    }

    public String payload() {
        return payload;
    }
}
