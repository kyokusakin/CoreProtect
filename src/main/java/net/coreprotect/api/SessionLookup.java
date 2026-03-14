package net.coreprotect.api;

import net.coreprotect.CoreProtect;

import java.util.List;

public final class SessionLookup {
    public static final int ID = 0;

    private SessionLookup() {
        throw new IllegalStateException("API class");
    }

    public static List<String[]> performLookup(String user, int offset) {
        return CoreProtect.getInstance().getAPI().sessionLookup(user, offset);
    }
}
