package net.coreprotect.fabric.api;

public final class CoreProtectFabric {
    private static final CoreProtectFabricAPI API = new CoreProtectFabricAPI();

    private CoreProtectFabric() {
    }

    public static CoreProtectFabricAPI getAPI() {
        return API;
    }
}
