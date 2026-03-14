package net.coreprotect.fabric.util;

import java.util.ArrayDeque;
import java.util.Deque;

public final class EntityBlockChangeContext {
    private static final ThreadLocal<Deque<String>> ACTORS = ThreadLocal.withInitial(ArrayDeque::new);

    private EntityBlockChangeContext() {
    }

    public static void push(String actor) {
        if (actor == null || actor.isBlank()) {
            return;
        }
        ACTORS.get().push(actor);
    }

    public static void pop() {
        Deque<String> actors = ACTORS.get();
        if (!actors.isEmpty()) {
            actors.pop();
        }
        if (actors.isEmpty()) {
            ACTORS.remove();
        }
    }

    public static String currentActor() {
        Deque<String> actors = ACTORS.get();
        return actors.isEmpty() ? null : actors.peek();
    }

    public static boolean isActive() {
        return currentActor() != null;
    }
}
