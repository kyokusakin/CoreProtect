package net.coreprotect.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeGenerationTest {
    @Test
    void invalidatesLeasesOnlyAfterAServiceGraphIsInstalled() {
        RuntimeGeneration generation = new RuntimeGeneration();
        long initial = generation.capture();

        assertTrue(generation.isCurrent(initial));

        generation.advance();

        assertFalse(generation.isCurrent(initial));
        assertTrue(generation.isCurrent(generation.capture()));
    }
}
