package net.coreprotect.fabric.util;

public final class BonemealGrowthContext {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private BonemealGrowthContext() {
    }

    public static void begin() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void end() {
        int depth = Math.max(0, DEPTH.get() - 1);
        if (depth == 0) {
            DEPTH.remove();
        }
        else {
            DEPTH.set(depth);
        }
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
