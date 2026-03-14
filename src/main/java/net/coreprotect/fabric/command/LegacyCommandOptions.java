package net.coreprotect.fabric.command;

import net.coreprotect.fabric.log.CoreProtectEventType;

import java.util.List;

public final class LegacyCommandOptions {
    private final Integer minimumSeconds;
    private final Integer seconds;
    private final Integer radius;
    private final Integer radiusX;
    private final Integer radiusY;
    private final Integer radiusZ;
    private final String coordinates;
    private final String worldFilter;
    private final boolean globalScope;
    private final Integer limit;
    private final List<String> actorNames;
    private final List<String> excludeActorNames;
    private final List<CoreProtectEventType> actionFilter;
    private final List<String> includeTargets;
    private final List<String> excludeTargets;
    private final boolean preview;
    private final boolean previewCancel;
    private final boolean count;
    private final boolean silent;
    private final boolean verbose;

    public LegacyCommandOptions(
        Integer minimumSeconds,
        Integer seconds,
        Integer radius,
        Integer radiusX,
        Integer radiusY,
        Integer radiusZ,
        String coordinates,
        String worldFilter,
        boolean globalScope,
        Integer limit,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets,
        boolean preview,
        boolean previewCancel,
        boolean count,
        boolean silent,
        boolean verbose
    ) {
        this.minimumSeconds = minimumSeconds;
        this.seconds = seconds;
        this.radius = radius;
        this.radiusX = radiusX;
        this.radiusY = radiusY;
        this.radiusZ = radiusZ;
        this.coordinates = coordinates;
        this.worldFilter = worldFilter;
        this.globalScope = globalScope;
        this.limit = limit;
        this.actorNames = actorNames;
        this.excludeActorNames = excludeActorNames;
        this.actionFilter = actionFilter;
        this.includeTargets = includeTargets;
        this.excludeTargets = excludeTargets;
        this.preview = preview;
        this.previewCancel = previewCancel;
        this.count = count;
        this.silent = silent;
        this.verbose = verbose;
    }

    public Integer minimumSeconds() {
        return minimumSeconds;
    }

    public Integer seconds() {
        return seconds;
    }

    public Integer radius() {
        return radius;
    }

    public Integer radiusX() {
        return radiusX;
    }

    public Integer radiusY() {
        return radiusY;
    }

    public Integer radiusZ() {
        return radiusZ;
    }

    public String coordinates() {
        return coordinates;
    }

    public String worldFilter() {
        return worldFilter;
    }

    public boolean globalScope() {
        return globalScope;
    }

    public Integer limit() {
        return limit;
    }

    public List<String> actorNames() {
        return actorNames;
    }

    public List<String> excludeActorNames() {
        return excludeActorNames;
    }

    public List<CoreProtectEventType> actionFilter() {
        return actionFilter;
    }

    public List<String> includeTargets() {
        return includeTargets;
    }

    public List<String> excludeTargets() {
        return excludeTargets;
    }

    public boolean preview() {
        return preview;
    }

    public boolean previewCancel() {
        return previewCancel;
    }

    public boolean count() {
        return count;
    }

    public boolean silent() {
        return silent;
    }

    public boolean verbose() {
        return verbose;
    }

    public boolean isEmpty() {
        return minimumSeconds == null
            && seconds == null
            && radius == null
            && radiusX == null
            && radiusY == null
            && radiusZ == null
            && coordinates == null
            && worldFilter == null
            && !globalScope
            && limit == null
            && (actorNames == null || actorNames.isEmpty())
            && (excludeActorNames == null || excludeActorNames.isEmpty())
            && (actionFilter == null || actionFilter.isEmpty())
            && (includeTargets == null || includeTargets.isEmpty())
            && (excludeTargets == null || excludeTargets.isEmpty())
            && !preview
            && !previewCancel
            && !count
            && !silent
            && !verbose;
    }
}
