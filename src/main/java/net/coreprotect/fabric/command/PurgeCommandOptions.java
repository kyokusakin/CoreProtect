package net.coreprotect.fabric.command;

import java.util.List;

public final class PurgeCommandOptions {
    private final Integer seconds;
    private final String worldFilter;
    private final List<String> includeTargets;
    private final boolean optimize;

    public PurgeCommandOptions(Integer seconds, String worldFilter, List<String> includeTargets, boolean optimize) {
        this.seconds = seconds;
        this.worldFilter = worldFilter;
        this.includeTargets = includeTargets == null ? null : List.copyOf(includeTargets);
        this.optimize = optimize;
    }

    public Integer seconds() {
        return seconds;
    }

    public String worldFilter() {
        return worldFilter;
    }

    public List<String> includeTargets() {
        return includeTargets;
    }

    public boolean optimize() {
        return optimize;
    }

    public boolean isEmpty() {
        return seconds == null
            && worldFilter == null
            && (includeTargets == null || includeTargets.isEmpty())
            && !optimize;
    }
}
