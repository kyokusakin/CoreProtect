package net.coreprotect.fabric.service;

import net.minecraft.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RollbackBlockUpdatePolicyTest {
    @Test
    void disablesNeighborUpdatesWhenSettingAir() {
        int flags = RollbackBlockUpdatePolicy.updateFlags(true);

        assertEquals(0, flags & Block.NOTIFY_NEIGHBORS);
    }

    @Test
    void retainsNeighborUpdatesWhenSettingSolidBlocks() {
        int flags = RollbackBlockUpdatePolicy.updateFlags(false);

        assertEquals(Block.NOTIFY_NEIGHBORS, flags & Block.NOTIFY_NEIGHBORS);
    }

    @Test
    void stagesStableBlocksWithoutNeighborUpdates() {
        int flags = RollbackBlockUpdatePolicy.updateFlags(false, false);

        assertEquals(0, flags & Block.NOTIFY_NEIGHBORS);
    }

    @Test
    void appliesPhysicsDependentBlocksWithNeighborUpdates() {
        int flags = RollbackBlockUpdatePolicy.updateFlags(false, true);

        assertEquals(Block.NOTIFY_NEIGHBORS, flags & Block.NOTIFY_NEIGHBORS);
    }

    @Test
    void assignsAirStableAndPhysicsPhases() {
        assertEquals(
            RollbackBlockApplyPlan.Phase.AIR,
            RollbackBlockUpdatePolicy.phase(true, true)
        );
        assertEquals(
            RollbackBlockApplyPlan.Phase.STABLE,
            RollbackBlockUpdatePolicy.phase(false, false)
        );
        assertEquals(
            RollbackBlockApplyPlan.Phase.PHYSICS,
            RollbackBlockUpdatePolicy.phase(false, true)
        );
    }

    @Test
    void classifiesFullSupportBlocksAsStable() {
        assertFalse(RollbackBlockUpdatePolicy.requiresPhysics(false, false, true, false));
    }

    @Test
    void classifiesAttachedAndStatefulBlocksForThePhysicsPass() {
        assertTrue(RollbackBlockUpdatePolicy.requiresPhysics(false, false, false, false));
        assertTrue(RollbackBlockUpdatePolicy.requiresPhysics(false, true, true, false));
        assertTrue(RollbackBlockUpdatePolicy.requiresPhysics(false, false, true, true));
        assertFalse(RollbackBlockUpdatePolicy.requiresPhysics(true, true, false, true));
    }
}
