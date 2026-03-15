package net.coreprotect;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class CoreProtect {
    private static final CoreProtect INSTANCE = new CoreProtect();
    private static final String[] ADVANCED_CHESTS_MOD_IDS = { "advancedchests", "advanced_chests" };

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
        FabricLoader loader = FabricLoader.getInstance();
        for (String modId : ADVANCED_CHESTS_MOD_IDS) {
            if (loader.isModLoaded(modId)) {
                return true;
            }
        }

        for (ModContainer mod : loader.getAllMods()) {
            String modName = mod.getMetadata().getName();
            if (modName != null && "advancedchests".equalsIgnoreCase(modName.replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }
}
