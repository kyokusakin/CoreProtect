package net.coreprotect.fabric.service;

import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.log.CoreProtectEventType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RollbackServiceLifecycleTest {
    @Test
    void reportsQueuedServerThreadWork() {
        RollbackService service = new RollbackService(null, null, LoggerFactory.getLogger(getClass()));

        assertFalse(service.hasPendingWork());

        service.enqueuePreparedApply(
            List.of(new StoredEventRecord(
                1L,
                System.currentTimeMillis(),
                CoreProtectEventType.BLOCK_PLACE,
                null,
                "Alex",
                "minecraft:overworld",
                0,
                64,
                0,
                "minecraft:stone",
                "",
                false
            )),
            false,
            10,
            3600,
            "Alex",
            result -> {
            }
        );

        assertTrue(service.hasPendingWork());
    }
}
