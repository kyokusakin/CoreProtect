package net.coreprotect.fabric.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

public final class BlockStateSerializer {
    private BlockStateSerializer() {
    }

    public static String describeBlock(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    public static String serialize(BlockState state) {
        return NbtHelper.fromBlockState(state).toString();
    }

    public static BlockState deserialize(ServerWorld world, String target, String payload, Logger logger) {
        if (payload != null && !payload.isBlank()) {
            try {
                NbtCompound nbt = StringNbtReader.readCompound(payload);
                return NbtHelper.toBlockState(world.createCommandRegistryWrapper(RegistryKeys.BLOCK), nbt);
            }
            catch (Exception exception) {
                logger.debug("Unable to deserialize block state payload {}", payload, exception);
            }
        }

        if (target != null && !target.isBlank()) {
            Identifier identifier = Identifier.tryParse(target);
            if (identifier != null && Registries.BLOCK.containsId(identifier)) {
                return Registries.BLOCK.get(identifier).getDefaultState();
            }
        }

        return null;
    }

    public static BlockState air() {
        return Blocks.AIR.getDefaultState();
    }
}
