package net.coreprotect.api.result;

import net.coreprotect.fabric.api.CoreProtectFabricAPI;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.log.CoreProtectEventType;

public class ParseResult {
    private final CoreProtectFabricAPI.ParseResult delegate;

    public ParseResult(String[] data) {
        this.delegate = new CoreProtectFabricAPI.ParseResult(data);
    }

    public ParseResult(StoredEventRecord record) {
        this.delegate = new CoreProtectFabricAPI.ParseResult(record);
    }

    public int getActionId() {
        return delegate.getActionId();
    }

    public String getActionString() {
        return delegate.getActionString();
    }

    @Deprecated
    public int getData() {
        return delegate.getData();
    }

    public String getPlayer() {
        return delegate.getPlayer();
    }

    @Deprecated
    public int getTime() {
        return delegate.getTime();
    }

    public long getTimestamp() {
        return delegate.getTimestamp();
    }

    public String getType() {
        return delegate.getType();
    }

    public String getTypeKey() {
        return delegate.getType();
    }

    public String getBlockData() {
        return delegate.getBlockData();
    }

    public String getBlockDataString() {
        return delegate.getBlockData();
    }

    public int getX() {
        return delegate.getX();
    }

    public int getY() {
        return delegate.getY();
    }

    public int getZ() {
        return delegate.getZ();
    }

    public boolean isRolledBack() {
        return delegate.isRolledBack();
    }

    public String worldName() {
        return delegate.worldName();
    }

    public String getWorldKey() {
        return delegate.worldName();
    }

    public boolean hasPosition() {
        return delegate.hasPosition();
    }

    public String getTarget() {
        return delegate.getTarget();
    }

    public String getPayload() {
        return delegate.getPayload();
    }

    public CoreProtectEventType getEventType() {
        return delegate.getEventType();
    }

    public String[] raw() {
        return delegate.raw();
    }

    public StoredEventRecord record() {
        return delegate.record();
    }
}
