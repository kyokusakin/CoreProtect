package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.block.BlockFertilizeListener;
import net.coreprotect.fabric.listener.block.BlockIgniteListener;
import net.coreprotect.fabric.listener.player.HopperTransactionLogger;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.BonemealFertilizeContext;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ArmorStandItem;
import net.minecraft.item.BoatItem;
import net.minecraft.item.EndCrystalItem;
import net.minecraft.item.EntityBucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MinecartItem;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
    @Unique
    private static final ThreadLocal<DispenseContext> coreprotect$dispenseContext = new ThreadLocal<>();

    @Inject(method = "dispense", at = @At("HEAD"))
    private void coreprotect$captureDispense(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
        DispenserBlockEntity dispenser = world.getBlockEntity(pos, net.minecraft.block.entity.BlockEntityType.DISPENSER).orElse(null);
        if (dispenser == null) {
            coreprotect$dispenseContext.remove();
            return;
        }

        int slot = dispenser.chooseNonEmptySlot(world.random);
        if (slot < 0) {
            coreprotect$dispenseContext.remove();
            return;
        }

        ItemStack chosenStack = dispenser.getStack(slot).copy();
        Direction facing = state.get(DispenserBlock.FACING);
        BlockPos targetPos = pos.offset(facing);
        BlockState targetState = world.getBlockState(targetPos);
        coreprotect$dispenseContext.set(new DispenseContext(
            pos.toImmutable(),
            targetPos.toImmutable(),
            HopperTransactionLogger.snapshotInventory(dispenser),
            chosenStack,
            targetState,
            coreprotect$shouldTrackPlacedEntity(chosenStack) ? coreprotect$collectNearbyEntityIds(world, targetPos) : null
        ));

        if (chosenStack.isOf(Items.BONE_MEAL)) {
            BonemealFertilizeContext.begin(world, "#dispenser", targetPos, targetState);
        }
    }

    @Inject(method = "dispense", at = @At("RETURN"))
    private void coreprotect$logDispense(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
        try {
            DispenseContext context = coreprotect$dispenseContext.get();
            if (context == null) {
                return;
            }

            DispenserBlockEntity dispenser = world.getBlockEntity(pos, net.minecraft.block.entity.BlockEntityType.DISPENSER).orElse(null);
            if (dispenser != null) {
                HopperTransactionLogger.logInventoryChange(
                    CoreProtectFabricMod.getRuntime(),
                    "#dispenser",
                    world.getRegistryKey().getValue().toString(),
                    context.dispenserPos(),
                    BlockStateSerializer.describeBlock(state),
                    context.beforeInventory(),
                    HopperTransactionLogger.snapshotInventory(dispenser)
                );
            }

            BlockState afterTargetState = world.getBlockState(context.targetPos());
            if (context.chosenStack().isOf(Items.BONE_MEAL)) {
                BlockFertilizeListener.logCompletedFertilize(BonemealFertilizeContext.end(true));
            }
            else if (context.chosenStack().isOf(Items.WATER_BUCKET) || context.chosenStack().isOf(Items.LAVA_BUCKET)) {
                String actor = context.chosenStack().isOf(Items.WATER_BUCKET) ? "#water" : "#lava";
                if (!afterTargetState.equals(context.beforeTargetState())) {
                    CoreProtectFabricMod.getRuntime().logger().logBlockPlace(null, actor, world, context.targetPos(), afterTargetState);
                    TransientLookupCache.rememberPlacedActor(world.getRegistryKey().getValue().toString(), context.targetPos(), actor, BlockStateSerializer.describeBlock(afterTargetState));
                }
            }
            else if (context.chosenStack().isOf(Items.BUCKET)) {
                String actor = null;
                if (context.beforeTargetState().getFluidState().isStill()) {
                    if (context.beforeTargetState().getFluidState().isOf(net.minecraft.fluid.Fluids.WATER)) {
                        actor = "#water";
                    }
                    else if (context.beforeTargetState().getFluidState().isOf(net.minecraft.fluid.Fluids.LAVA)) {
                        actor = "#lava";
                    }
                }
                if (actor != null && !afterTargetState.equals(context.beforeTargetState())) {
                    CoreProtectFabricMod.getRuntime().logger().logBlockBreak(null, actor, world, context.targetPos(), context.beforeTargetState());
                }
            }
            else if (context.chosenStack().isOf(Items.FLINT_AND_STEEL)) {
                BlockIgniteListener.logFireIgnite("#fire", world, context.targetPos(), context.beforeTargetState(), afterTargetState);
            }
            else if (context.chosenStack().getItem() instanceof net.minecraft.item.ProjectileItem) {
                CoreProtectFabricMod.getRuntime().logger().logItemShoot(
                    "#dispenser",
                    world.getRegistryKey().getValue().toString(),
                    context.targetPos(),
                    net.coreprotect.fabric.util.LoggedItemData.fromStack(context.chosenStack(), world.getRegistryManager()),
                    1,
                    null
                );
            }

            if (context.beforeEntityIds() != null) {
                Entity placedEntity = coreprotect$findNewEntity(world, context.targetPos(), context.beforeEntityIds());
                if (placedEntity != null) {
                    CoreProtectFabricMod.getRuntime().logger().logEntityPlace("#dispenser", world, placedEntity.getBlockPos(), placedEntity);
                }
            }
        }
        finally {
            if (coreprotect$dispenseContext.get() != null && coreprotect$dispenseContext.get().chosenStack().isOf(Items.BONE_MEAL)) {
                BonemealFertilizeContext.end(true);
            }
            coreprotect$dispenseContext.remove();
        }
    }

    @Unique
    private record DispenseContext(
        BlockPos dispenserPos,
        BlockPos targetPos,
        java.util.Map<net.coreprotect.fabric.util.LoggedItemData, Integer> beforeInventory,
        ItemStack chosenStack,
        BlockState beforeTargetState,
        Set<Integer> beforeEntityIds
    ) {
    }

    @Unique
    private static boolean coreprotect$shouldTrackPlacedEntity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        return stack.getItem() instanceof SpawnEggItem
            || stack.getItem() instanceof EntityBucketItem
            || stack.getItem() instanceof ArmorStandItem
            || stack.getItem() instanceof BoatItem
            || stack.getItem() instanceof MinecartItem
            || stack.getItem() instanceof EndCrystalItem;
    }

    @Unique
    private static Set<Integer> coreprotect$collectNearbyEntityIds(ServerWorld world, BlockPos pos) {
        Set<Integer> ids = new HashSet<>();
        for (Entity entity : world.getOtherEntities(null, Box.of(pos.toCenterPos(), 8.0D, 8.0D, 8.0D), Entity::isAlive)) {
            ids.add(entity.getId());
        }
        return ids;
    }

    @Unique
    private static Entity coreprotect$findNewEntity(ServerWorld world, BlockPos pos, Set<Integer> beforeIds) {
        return world.getOtherEntities(null, Box.of(pos.toCenterPos(), 8.0D, 8.0D, 8.0D), entity -> entity.isAlive() && !beforeIds.contains(entity.getId()))
            .stream()
            .min((left, right) -> Double.compare(left.squaredDistanceTo(pos.toCenterPos()), right.squaredDistanceTo(pos.toCenterPos())))
            .orElse(null);
    }
}
