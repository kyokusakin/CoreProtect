package net.coreprotect.fabric.hook;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluid;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class BucketItemHooks {
    private static final ThreadLocal<BucketUseContext> BUCKET_USE_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<EntityBucketContext> ENTITY_BUCKET_CONTEXT = new ThreadLocal<>();

    private BucketItemHooks() {
    }

    public static void clear() {
        clearBucketContext();
        clearEntityContext();
    }

    public static void setBucketContext(BucketUseContext context) {
        BUCKET_USE_CONTEXT.set(context);
    }

    public static BucketUseContext consumeBucketContext() {
        BucketUseContext context = BUCKET_USE_CONTEXT.get();
        BUCKET_USE_CONTEXT.remove();
        return context;
    }

    public static void clearBucketContext() {
        BUCKET_USE_CONTEXT.remove();
    }

    public static void setEntityContext(EntityBucketContext context) {
        ENTITY_BUCKET_CONTEXT.set(context);
    }

    public static EntityBucketContext consumeEntityContext() {
        EntityBucketContext context = ENTITY_BUCKET_CONTEXT.get();
        ENTITY_BUCKET_CONTEXT.remove();
        return context;
    }

    public static void clearEntityContext() {
        ENTITY_BUCKET_CONTEXT.remove();
    }

    public record BucketUseContext(
        ServerPlayerEntity player,
        ServerWorld world,
        boolean fill,
        BlockPos pos,
        BlockPos alternatePos,
        BlockState originalState,
        Fluid fluid
    ) {
    }

    public record EntityBucketContext(ServerPlayerEntity player) {
    }
}
