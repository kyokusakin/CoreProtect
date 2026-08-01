package net.coreprotect.fabric.service;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class EntityRollbackNbtPolicyTest {
    @Test
    void capsVillagerLevelAtVanillaMaximum() {
        NbtCompound villagerData = new NbtCompound();
        villagerData.putInt("level", 8);
        NbtCompound entityNbt = new NbtCompound();
        entityNbt.put("VillagerData", villagerData);

        EntityRollbackNbtPolicy.sanitize(entityNbt);

        assertEquals(5, entityNbt.getCompoundOrEmpty("VillagerData").getInt("level", 0));
    }

    @Test
    void leavesValidVillagerLevelUnchanged() {
        NbtCompound villagerData = new NbtCompound();
        villagerData.putInt("level", 4);
        NbtCompound entityNbt = new NbtCompound();
        entityNbt.put("VillagerData", villagerData);

        EntityRollbackNbtPolicy.sanitize(entityNbt);

        assertEquals(4, entityNbt.getCompoundOrEmpty("VillagerData").getInt("level", 0));
    }

    @Test
    void preservesPassiveMobVariantData() {
        NbtCompound entityNbt = new NbtCompound();
        entityNbt.putString("variant", "minecraft:temperate");
        entityNbt.putString("sound_variant", "minecraft:temperate");

        EntityRollbackNbtPolicy.sanitize(entityNbt);

        assertEquals("minecraft:temperate", entityNbt.getString("variant", ""));
        assertEquals("minecraft:temperate", entityNbt.getString("sound_variant", ""));
        assertFalse(entityNbt.contains("UUID"));
    }
}
