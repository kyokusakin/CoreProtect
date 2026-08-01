package net.coreprotect.fabric.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RollbackBlockApplyPlanTest {
    @Test
    void ordersAirBeforeStableBlocksAndPhysicsBlocks() {
        RollbackBlockApplyPlan<String, String> plan = new RollbackBlockApplyPlan<>();
        plan.add("torch", "place torch");
        plan.add("support", "place support");
        plan.add("removed", "remove block");
        Map<String, RollbackBlockApplyPlan.Phase> phases = Map.of(
            "torch", RollbackBlockApplyPlan.Phase.PHYSICS,
            "support", RollbackBlockApplyPlan.Phase.STABLE,
            "removed", RollbackBlockApplyPlan.Phase.AIR
        );

        List<String> ordered = plan.orderedGroups((position, events) -> phases.get(position)).stream()
            .flatMap(group -> group.events().stream())
            .toList();

        assertEquals(List.of("remove block", "place support", "place torch"), ordered);
    }

    @Test
    void keepsEventsForOnePositionAdjacentAndOrdered() {
        RollbackBlockApplyPlan<String, String> plan = new RollbackBlockApplyPlan<>();
        plan.add("dependent", "undo latest placement");
        plan.add("support", "restore support");
        plan.add("dependent", "restore earlier break");

        List<String> ordered = plan.orderedGroups((position, events) ->
            "support".equals(position)
                ? RollbackBlockApplyPlan.Phase.STABLE
                : RollbackBlockApplyPlan.Phase.PHYSICS
        ).stream()
            .flatMap(group -> group.events().stream())
            .toList();

        assertEquals(
            List.of("restore support", "undo latest placement", "restore earlier break"),
            ordered
        );
    }
}
