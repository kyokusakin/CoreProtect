package net.coreprotect.fabric.service;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UpdateCheckServiceLifecycleTest {
    @Test
    void closesOwnedExecutor() {
        UpdateCheckService service = new UpdateCheckService(LoggerFactory.getLogger(getClass()));

        assertFalse(service.isShutdown());

        service.close();

        assertTrue(service.isShutdown());
    }
}
