package net.coreprotect.fabric.service;

import java.util.List;

public final class RollbackPreviewResult {
    private final int matched;
    private final List<PreviewService.PreviewBlockChange> blockChanges;

    public RollbackPreviewResult(int matched, List<PreviewService.PreviewBlockChange> blockChanges) {
        this.matched = matched;
        this.blockChanges = blockChanges == null ? List.of() : List.copyOf(blockChanges);
    }

    public int matched() {
        return matched;
    }

    public List<PreviewService.PreviewBlockChange> blockChanges() {
        return blockChanges;
    }
}
