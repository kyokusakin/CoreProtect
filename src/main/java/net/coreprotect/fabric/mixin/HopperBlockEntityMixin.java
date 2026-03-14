package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.player.HopperPullListener;
import net.coreprotect.fabric.listener.player.HopperPushListener;
import net.coreprotect.fabric.listener.player.HopperTransactionLogger;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Unique
    private static final ThreadLocal<InsertContext> coreprotect$insertContext = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<ExtractContext> coreprotect$extractContext = new ThreadLocal<>();

    @Inject(method = "insert", at = @At("HEAD"))
    private static void coreprotect$captureInsert(World world, BlockPos pos, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
        if (!(world instanceof ServerWorld serverWorld)) {
            coreprotect$insertContext.remove();
            return;
        }

        Direction facing = world.getBlockState(pos).get(HopperBlock.FACING);
        BlockPos destinationPos = pos.offset(facing);
        Inventory destinationInventory = HopperBlockEntity.getInventoryAt(world, destinationPos);
        if (destinationInventory == null) {
            coreprotect$insertContext.remove();
            return;
        }

        coreprotect$insertContext.set(new InsertContext(
            serverWorld,
            pos.toImmutable(),
            HopperTransactionLogger.snapshotInventory(hopper),
            destinationPos.toImmutable(),
            BlockStateSerializer.describeBlock(world.getBlockState(destinationPos)),
            HopperTransactionLogger.snapshotInventory(destinationInventory)
        ));
    }

    @Inject(method = "insert", at = @At("RETURN"))
    private static void coreprotect$logInsert(World world, BlockPos pos, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
        InsertContext context = coreprotect$insertContext.get();
        coreprotect$insertContext.remove();
        if (context == null || !cir.getReturnValueZ()) {
            return;
        }

        Inventory destinationInventory = HopperBlockEntity.getInventoryAt(world, context.destinationPos());
        if (destinationInventory == null) {
            return;
        }

        HopperPushListener.logHopperPush(
            context.world(),
            context.hopperPos(),
            context.beforeHopper(),
            HopperTransactionLogger.snapshotInventory(hopper),
            context.destinationPos(),
            context.destinationType(),
            context.beforeDestination(),
            HopperTransactionLogger.snapshotInventory(destinationInventory)
        );
    }

    @Inject(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At("HEAD"))
    private static void coreprotect$captureExtract(World world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (!(world instanceof ServerWorld serverWorld) || !(hopper instanceof HopperBlockEntity hopperBlockEntity)) {
            coreprotect$extractContext.remove();
            return;
        }

        BlockPos hopperPos = hopperBlockEntity.getPos();
        BlockPos sourcePos = hopperPos.up();
        Inventory sourceInventory = HopperBlockEntity.getInventoryAt(world, sourcePos);
        List<ItemEntity> sourceItems = sourceInventory == null ? HopperBlockEntity.getInputItemEntities(world, hopper) : List.of();

        coreprotect$extractContext.set(new ExtractContext(
            serverWorld,
            hopperPos.toImmutable(),
            HopperTransactionLogger.snapshotInventory(hopperBlockEntity),
            sourceInventory != null ? sourcePos.toImmutable() : null,
            sourceInventory != null ? BlockStateSerializer.describeBlock(world.getBlockState(sourcePos)) : null,
            sourceInventory != null ? HopperTransactionLogger.snapshotInventory(sourceInventory) : Map.of(),
            sourceInventory == null ? HopperTransactionLogger.snapshotItemEntities(sourceItems) : Map.of()
        ));
    }

    @Inject(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At("RETURN"))
    private static void coreprotect$logExtract(World world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        ExtractContext context = coreprotect$extractContext.get();
        coreprotect$extractContext.remove();
        if (context == null || !cir.getReturnValueZ() || !(hopper instanceof HopperBlockEntity hopperBlockEntity)) {
            return;
        }

        Inventory sourceInventory = context.sourcePos() == null ? null : HopperBlockEntity.getInventoryAt(world, context.sourcePos());
        Map<LoggedItemData, Integer> afterSource = sourceInventory == null
            ? HopperTransactionLogger.snapshotItemEntities(HopperBlockEntity.getInputItemEntities(world, hopper))
            : HopperTransactionLogger.snapshotInventory(sourceInventory);

        HopperPullListener.logHopperPull(
            context.world(),
            context.hopperPos(),
            context.beforeHopper(),
            HopperTransactionLogger.snapshotInventory(hopperBlockEntity),
            context.sourcePos(),
            context.sourceType(),
            context.beforeSource(),
            afterSource
        );
    }

    @Unique
    private record InsertContext(
        ServerWorld world,
        BlockPos hopperPos,
        Map<LoggedItemData, Integer> beforeHopper,
        BlockPos destinationPos,
        String destinationType,
        Map<LoggedItemData, Integer> beforeDestination
    ) {
    }

    @Unique
    private record ExtractContext(
        ServerWorld world,
        BlockPos hopperPos,
        Map<LoggedItemData, Integer> beforeHopper,
        BlockPos sourcePos,
        String sourceType,
        Map<LoggedItemData, Integer> beforeSource,
        Map<LoggedItemData, Integer> beforeSourceItems
    ) {
    }
}
