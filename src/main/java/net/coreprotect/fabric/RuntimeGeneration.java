package net.coreprotect.fabric;

import java.util.concurrent.atomic.AtomicLong;

final class RuntimeGeneration {
    private final AtomicLong value = new AtomicLong();

    long capture() {
        return value.get();
    }

    boolean isCurrent(long generation) {
        return value.get() == generation;
    }

    void advance() {
        value.incrementAndGet();
    }
}
