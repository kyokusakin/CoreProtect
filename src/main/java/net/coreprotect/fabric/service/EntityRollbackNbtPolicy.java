package net.coreprotect.fabric.service;

import net.coreprotect.fabric.log.FabricEventLogger;
import net.minecraft.nbt.NbtCompound;

final class EntityRollbackNbtPolicy {
    private static final int MAX_VILLAGER_LEVEL = 5;

    private EntityRollbackNbtPolicy() {
    }

    static void sanitize(NbtCompound entityNbt) {
        entityNbt.remove("UUID");
        entityNbt.remove("Pos");
        entityNbt.remove("Motion");
        entityNbt.remove("Rotation");
        entityNbt.remove("Passengers");
        FabricEventLogger.retainStableBrainMemories(entityNbt);
        entityNbt.remove("Fire");
        entityNbt.remove("HasVisualFire");
        capVillagerLevel(entityNbt);
    }

    private static void capVillagerLevel(NbtCompound entityNbt) {
        entityNbt.getCompound("VillagerData").ifPresent(villagerData -> {
            int level = villagerData.getInt("level", 1);
            if (level > MAX_VILLAGER_LEVEL) {
                villagerData.putInt("level", MAX_VILLAGER_LEVEL);
            }
        });
    }
}
