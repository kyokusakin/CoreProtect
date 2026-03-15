package net.coreprotect.fabric.service;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.coreprotect.fabric.service.inspector.BlockInspector;
import net.coreprotect.fabric.service.inspector.ContainerInspector;
import net.coreprotect.fabric.service.inspector.InteractionInspector;
import net.coreprotect.fabric.service.inspector.SignInspector;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrushableBlockEntity;
import net.minecraft.block.entity.ChiseledBookshelfBlockEntity;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.Entity;
import net.coreprotect.fabric.permission.CoreProtectPermissions;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InspectorService {
    private static final long THROTTLE_MS = 200L;
    private static final int INSPECT_DISABLED = 0;
    private static final int INSPECT_THROTTLED = 1;
    private static final int INSPECT_READY = 2;
    private final LookupService lookupService;
    private final BlockInspector blockInspector;
    private final SignInspector signInspector;
    private final ContainerInspector containerInspector;
    private final InteractionInspector interactionInspector;
    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastInspectAt = new ConcurrentHashMap<>();

    public InspectorService(LookupService lookupService) {
        this.lookupService = lookupService;
        this.blockInspector = new BlockInspector(lookupService);
        this.signInspector = new SignInspector(lookupService);
        this.containerInspector = new ContainerInspector(lookupService);
        this.interactionInspector = new InteractionInspector(lookupService);
    }

    public boolean isEnabled(ServerPlayerEntity player) {
        return enabledPlayers.contains(player.getUuid());
    }

    public boolean setEnabled(ServerPlayerEntity player, boolean enabled) {
        if (enabled) {
            enabledPlayers.add(player.getUuid());
            return true;
        }

        disable(player);
        return false;
    }

    public boolean toggle(ServerPlayerEntity player) {
        if (isEnabled(player)) {
            disable(player);
            return false;
        }

        enabledPlayers.add(player.getUuid());
        return true;
    }

    public void disable(ServerPlayerEntity player) {
        enabledPlayers.remove(player.getUuid());
        lastInspectAt.remove(player.getUuid());
    }

    public boolean inspectLeftClickBlock(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        int inspectState = prepareInspect(player);
        if (inspectState == INSPECT_DISABLED) {
            return false;
        }
        if (inspectState == INSPECT_THROTTLED) {
            return true;
        }

        BlockPos normalizedPos = normalizeLeftClickPos(world, pos);
        blockInspector.performBlockLookup(player, world, normalizedPos);
        return true;
    }

    public boolean inspectRightClickBlock(ServerPlayerEntity player, ServerWorld world, BlockPos clickedPos, BlockPos inspectPos) {
        int inspectState = prepareInspect(player);
        if (inspectState == INSPECT_DISABLED) {
            return false;
        }
        if (inspectState == INSPECT_THROTTLED) {
            return true;
        }

        BlockState clickedState = world.getBlockState(clickedPos);
        BlockPos targetPos = inspectPos == null ? clickedPos : inspectPos;

        if (clickedState.getBlock() instanceof AbstractSignBlock) {
            signInspector.performSignLookup(player, world, clickedPos);
            return true;
        }

        if (isContainerTarget(world, clickedPos)) {
            containerInspector.performContainerLookup(player, world, clickedPos);
            return true;
        }

        if (isInteractionBlock(clickedState)) {
            interactionInspector.performInteractionLookup(player, world, normalizeInteractionPos(world, clickedPos, clickedState));
            return true;
        }

        blockInspector.performAirBlockLookup(player, world, targetPos);
        return true;
    }

    public boolean inspectEntity(ServerPlayerEntity player, ServerWorld world, Entity entity) {
        int inspectState = prepareInspect(player);
        if (inspectState == INSPECT_DISABLED) {
            return false;
        }
        if (inspectState == INSPECT_THROTTLED) {
            return true;
        }
        List<Text> lines = lookupService.describeEntityHistory(world, entity, 7);
        for (Text line : lines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getEntityHistory(world, entity, 7, null));

        List<Text> inventoryLines = lookupService.describeEntityInventoryHistory(world, entity, 7);
        for (Text line : inventoryLines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getEntityInventoryHistory(world, entity, 7));
        return true;
    }

    private int prepareInspect(ServerPlayerEntity player) {
        if (!isEnabled(player)) {
            return INSPECT_DISABLED;
        }
        if (!CoreProtectPermissions.canUseInspect(player, true)) {
            disable(player);
            return INSPECT_DISABLED;
        }

        long now = System.currentTimeMillis();
        long last = lastInspectAt.getOrDefault(player.getUuid(), 0L);
        if ((now - last) < THROTTLE_MS) {
            return INSPECT_THROTTLED;
        }

        lastInspectAt.put(player.getUuid(), now);
        return INSPECT_READY;
    }

    private boolean isContainerTarget(ServerWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof Inventory
            || blockEntity instanceof LecternBlockEntity
            || blockEntity instanceof JukeboxBlockEntity
            || blockEntity instanceof ChiseledBookshelfBlockEntity
            || blockEntity instanceof DecoratedPotBlockEntity
            || blockEntity instanceof BrushableBlockEntity;
    }

    private boolean isInteractionBlock(BlockState state) {
        return state.isIn(BlockTags.BUTTONS)
            || state.isIn(BlockTags.DOORS)
            || state.isIn(BlockTags.TRAPDOORS)
            || state.isIn(BlockTags.FENCE_GATES)
            || state.isIn(BlockTags.PRESSURE_PLATES)
            || state.getBlock() instanceof LeverBlock;
    }

    private BlockPos normalizeLeftClickPos(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);

        if (state.contains(Properties.BED_PART)
            && state.get(Properties.BED_PART) == BedPart.HEAD
            && state.contains(Properties.HORIZONTAL_FACING)) {
            BlockPos footPos = pos.offset(state.get(Properties.HORIZONTAL_FACING).getOpposite());
            if (world.getBlockState(footPos).isOf(state.getBlock())) {
                return footPos;
            }
        }

        if (state.contains(Properties.DOUBLE_BLOCK_HALF)
            && state.get(Properties.DOUBLE_BLOCK_HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER) {
            BlockPos lowerPos = pos.down();
            if (world.getBlockState(lowerPos).isOf(state.getBlock())) {
                return lowerPos;
            }
        }

        return pos;
    }

    private BlockPos normalizeInteractionPos(ServerWorld world, BlockPos pos, BlockState state) {
        if (!state.isIn(BlockTags.DOORS)) {
            return pos;
        }

        if (!state.contains(Properties.DOUBLE_BLOCK_HALF) || state.get(Properties.DOUBLE_BLOCK_HALF) != net.minecraft.block.enums.DoubleBlockHalf.UPPER) {
            return pos;
        }

        BlockPos lowerPos = pos.down();
        BlockState lowerState = world.getBlockState(lowerPos);
        return lowerState.isOf(state.getBlock()) ? lowerPos : pos;
    }
}
