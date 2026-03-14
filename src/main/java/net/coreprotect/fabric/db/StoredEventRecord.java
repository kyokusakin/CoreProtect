package net.coreprotect.fabric.db;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.minecraft.util.math.BlockPos;

public final class StoredEventRecord {
    private final long id;
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
    private final boolean rolledBack;

    public StoredEventRecord(long id, long timestamp, CoreProtectEventType type, String actorUuid, String actorName, String worldKey, Integer x, Integer y, Integer z, String target, String payload, boolean rolledBack) {
        this.id = id;
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
        this.rolledBack = rolledBack;
    }

    public long id() {
        return id;
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

    public boolean rolledBack() {
        return rolledBack;
    }

    public boolean hasPosition() {
        return x != null && y != null && z != null;
    }

    public BlockPos blockPos() {
        if (!hasPosition()) {
            throw new IllegalStateException("Stored event has no block position");
        }

        return new BlockPos(x, y, z);
    }
}
