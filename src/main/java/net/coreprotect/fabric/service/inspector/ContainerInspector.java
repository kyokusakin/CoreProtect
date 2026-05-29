package net.coreprotect.fabric.service.inspector;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.permission.CoreProtectPermissions;
import net.coreprotect.fabric.service.GiveRenderContext;
import net.coreprotect.fabric.service.LookupNetworkingService;
import net.coreprotect.fabric.service.LookupService;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.ChestType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

public final class ContainerInspector {
    private final LookupService lookupService;

    public ContainerInspector(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    public void performContainerLookup(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        String title = Phrase.build(Phrase.CONTAINER_HEADER);
        String emptyMessage = "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.SECOND);
        List<CoreProtectEventType> eventTypes = List.of(CoreProtectEventType.CONTAINER_TRANSACTION);
        BlockPos lookupPos = canonicalizeContainerPos(world, pos);
        boolean offerGive = CoreProtectPermissions.canUseGive(player.getCommandSource(), false);
        List<Text> lines = GiveRenderContext.withGive(offerGive, () -> lookupService.describeBlockHistory(world, lookupPos, 7, eventTypes, title, emptyMessage));
        for (Text line : lines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getBlockHistory(world, lookupPos, 7, eventTypes));
    }

    private BlockPos canonicalizeContainerPos(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)
            || !state.contains(Properties.CHEST_TYPE)
            || state.get(Properties.CHEST_TYPE) == ChestType.SINGLE) {
            return pos;
        }

        Direction facing = state.contains(Properties.HORIZONTAL_FACING)
            ? state.get(Properties.HORIZONTAL_FACING)
            : Direction.NORTH;
        Direction offset = state.get(Properties.CHEST_TYPE) == ChestType.LEFT
            ? facing.rotateYClockwise()
            : facing.rotateYCounterclockwise();
        BlockPos companionPos = pos.offset(offset);
        if (!world.getBlockState(companionPos).isOf(state.getBlock())) {
            return pos;
        }

        if (pos.getX() != companionPos.getX()) {
            return pos.getX() < companionPos.getX() ? pos : companionPos;
        }
        if (pos.getY() != companionPos.getY()) {
            return pos.getY() < companionPos.getY() ? pos : companionPos;
        }
        return pos.getZ() <= companionPos.getZ() ? pos : companionPos;
    }
}
