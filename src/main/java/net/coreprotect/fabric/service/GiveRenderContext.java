package net.coreprotect.fabric.service;

import java.util.function.Supplier;

/**
 * Per-render flag that controls whether item/container lookup lines should include a clickable
 * "give" affordance. Lookups are rendered synchronously on a worker thread, so the flag is scoped
 * with {@link #withGive(boolean, Supplier)} around the render call and always cleared afterwards,
 * preventing leakage across pooled threads. Renders that never set it (API, console output) simply
 * see it disabled.
 */
public final class GiveRenderContext {
    private static final ThreadLocal<Boolean> ENABLED = new ThreadLocal<>();

    private GiveRenderContext() {
    }

    public static boolean isEnabled() {
        return Boolean.TRUE.equals(ENABLED.get());
    }

    public static <T> T withGive(boolean enabled, Supplier<T> body) {
        Boolean previous = ENABLED.get();
        ENABLED.set(enabled);
        try {
            return body.get();
        }
        finally {
            if (previous == null) {
                ENABLED.remove();
            }
            else {
                ENABLED.set(previous);
            }
        }
    }
}
