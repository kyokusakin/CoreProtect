package net.coreprotect;

public final class CoreProtect {
    private static final CoreProtect INSTANCE = new CoreProtect();

    private final CoreProtectAPI api = new CoreProtectAPI();

    private CoreProtect() {
    }

    public static CoreProtect getInstance() {
        return INSTANCE;
    }

    public CoreProtectAPI getAPI() {
        return api;
    }

    public boolean isEnabled() {
        return api.isEnabled();
    }

    public boolean isAdvancedChestsEnabled() {
        return false;
    }
}
