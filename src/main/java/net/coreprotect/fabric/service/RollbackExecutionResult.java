package net.coreprotect.fabric.service;

public final class RollbackExecutionResult {
    private final boolean restore;
    private final int scanned;
    private final int changed;
    private final int marked;
    private final int radius;
    private final int seconds;
    private final String actorFilter;

    public RollbackExecutionResult(boolean restore, int scanned, int changed, int marked, int radius, int seconds, String actorFilter) {
        this.restore = restore;
        this.scanned = scanned;
        this.changed = changed;
        this.marked = marked;
        this.radius = radius;
        this.seconds = seconds;
        this.actorFilter = actorFilter;
    }

    public boolean restore() {
        return restore;
    }

    public int scanned() {
        return scanned;
    }

    public int changed() {
        return changed;
    }

    public int marked() {
        return marked;
    }

    public int radius() {
        return radius;
    }

    public int seconds() {
        return seconds;
    }

    public String actorFilter() {
        return actorFilter;
    }

    public String summary() {
        String operation = restore ? "restore" : "rollback";
        String actorSummary = actorFilter == null ? "all" : actorFilter;
        String radiusSummary = radius < 0 ? "global" : Integer.toString(radius);
        return "CoreProtect " + operation + ": scanned=" + scanned + ", changed=" + changed + ", marked=" + marked + ", radius=" + radiusSummary + ", time=" + seconds + "s, actor=" + actorSummary;
    }
}
