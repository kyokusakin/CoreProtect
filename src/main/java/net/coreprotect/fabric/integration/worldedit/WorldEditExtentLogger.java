package net.coreprotect.fabric.integration.worldedit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.coreprotect.fabric.log.FabricEventLogger;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedSignState;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.BlockState;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WorldEditExtentLogger extends AbstractDelegateExtent {
    private final FabricEventLogger eventLogger;
    private final Actor actor;
    private final ServerWorld world;

    public WorldEditExtentLogger(FabricEventLogger eventLogger, Actor actor, ServerWorld world, Extent extent) {
        super(extent);
        this.eventLogger = eventLogger;
        this.actor = actor;
        this.world = world;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException {
        BaseBlock oldFullBlock = getExtent().getFullBlock(position);
        com.sk89q.worldedit.world.block.BlockState oldState = oldFullBlock.toImmutableState();

        boolean changed = super.setBlock(position, block);
        if (!changed) {
            return false;
        }

        BaseBlock newFullBlock = getExtent().getFullBlock(position);
        com.sk89q.worldedit.world.block.BlockState newState = newFullBlock.toImmutableState();
        if (!oldState.equalsFuzzy(newState)) {
            logBlockChange(position, oldState, newState);
        }
        logContainerChanges(position, oldFullBlock, newFullBlock);
        logSignChanges(position, oldFullBlock, newFullBlock);
        return true;
    }

    private void logBlockChange(BlockVector3 position, com.sk89q.worldedit.world.block.BlockState oldState, com.sk89q.worldedit.world.block.BlockState newState) {
        BlockState oldNative = FabricAdapter.adapt(oldState);
        BlockState newNative = FabricAdapter.adapt(newState);
        BlockPos blockPos = FabricAdapter.toBlockPos(position);
        UUID actorUuid = actor.getUniqueId();
        String actorName = actor.getName();

        if (!oldNative.isAir()) {
            eventLogger.logBlockBreak(actorUuid, actorName, world, blockPos, oldNative);
        }
        if (!newNative.isAir()) {
            eventLogger.logBlockPlace(actorUuid, actorName, world, blockPos, newNative);
        }
    }

    private void logSignChanges(BlockVector3 position, BaseBlock oldBlock, BaseBlock newBlock) {
        if (!isSignState(newBlock.toImmutableState())) {
            return;
        }

        logSignSide(position, oldBlock, newBlock, true);
        logSignSide(position, oldBlock, newBlock, false);
    }

    private void logSignSide(BlockVector3 position, BaseBlock oldBlock, BaseBlock newBlock, boolean front) {
        LoggedSignState beforeState = readSignState(oldBlock, front);
        LoggedSignState afterState = readSignState(newBlock, front);
        if (beforeState.equals(afterState)) {
            return;
        }

        UUID actorUuid = actor.getUniqueId();
        String actorName = actor.getName();
        eventLogger.logSignChange(actorUuid, actorName, world, FabricAdapter.toBlockPos(position), afterState);
    }

    private void logContainerChanges(BlockVector3 position, BaseBlock oldBlock, BaseBlock newBlock) {
        BlockState oldNative = FabricAdapter.adapt(oldBlock.toImmutableState());
        BlockState newNative = FabricAdapter.adapt(newBlock.toImmutableState());
        BlockPos blockPos = FabricAdapter.toBlockPos(position);
        String worldKey = world.getRegistryKey().getValue().toString();
        String containerType = BlockStateSerializer.describeBlock(!newNative.isAir() ? newNative : oldNative);
        UUID actorUuid = actor.getUniqueId();
        String actorName = actor.getName();

        if (oldNative.getBlock() instanceof LecternBlock || newNative.getBlock() instanceof LecternBlock) {
            logContainerSlotChange(actorUuid == null ? null : actorUuid.toString(), actorName, worldKey, blockPos, containerType, 0, readNamedStack(toNbt(oldBlock), "Book"), readNamedStack(toNbt(newBlock), "Book"));
            return;
        }
        if (oldNative.getBlock() instanceof JukeboxBlock || newNative.getBlock() instanceof JukeboxBlock) {
            logContainerSlotChange(actorUuid == null ? null : actorUuid.toString(), actorName, worldKey, blockPos, containerType, 0, readNamedStack(toNbt(oldBlock), "RecordItem"), readNamedStack(toNbt(newBlock), "RecordItem"));
            return;
        }
        if (oldNative.getBlock() instanceof DecoratedPotBlock || newNative.getBlock() instanceof DecoratedPotBlock) {
            logContainerSlotChange(actorUuid == null ? null : actorUuid.toString(), actorName, worldKey, blockPos, containerType, 0, readNamedStack(toNbt(oldBlock), "item"), readNamedStack(toNbt(newBlock), "item"));
            return;
        }
        if (oldNative.getBlock() instanceof ChiseledBookshelfBlock || newNative.getBlock() instanceof ChiseledBookshelfBlock) {
            Map<Integer, ItemStack> before = readStackMap(toNbt(oldBlock), "Items");
            Map<Integer, ItemStack> after = readStackMap(toNbt(newBlock), "Items");
            for (int slot = 0; slot < 6; slot++) {
                logContainerSlotChange(actorUuid == null ? null : actorUuid.toString(), actorName, worldKey, blockPos, containerType, slot, before.getOrDefault(slot, ItemStack.EMPTY), after.getOrDefault(slot, ItemStack.EMPTY));
            }
        }
    }

    private void logContainerSlotChange(String actorUuid, String actorName, String worldKey, BlockPos pos, String containerType, int slotIndex, ItemStack before, ItemStack after) {
        ItemStack beforeStack = before == null ? ItemStack.EMPTY : before;
        ItemStack afterStack = after == null ? ItemStack.EMPTY : after;
        if (ItemStack.areEqual(beforeStack, afterStack)) {
            return;
        }

        eventLogger.logContainerTransaction(
            actorUuid,
            actorName,
            worldKey,
            pos,
            containerType,
            slotIndex,
            0,
            SlotActionType.PICKUP,
            beforeStack,
            afterStack,
            ItemStack.EMPTY,
            ItemStack.EMPTY
        );
    }

    private boolean isSignState(com.sk89q.worldedit.world.block.BlockState state) {
        BlockState nativeState = FabricAdapter.adapt(state);
        return nativeState.getBlock() instanceof AbstractSignBlock;
    }

    private LoggedSignState readSignState(BaseBlock block, boolean front) {
        String[] lines = new String[] { "", "", "", "" };
        DyeColor color = DyeColor.BLACK;
        boolean glowing = false;
        boolean waxed = false;
        NbtCompound nbt = toNbt(block);
        if (nbt == null) {
            return LoggedSignState.blank(front);
        }

        waxed = nbt.getBoolean("is_waxed", false);

        String sideKey = front ? "front_text" : "back_text";
        if (nbt.contains(sideKey)) {
            NbtCompound side = nbt.getCompoundOrEmpty(sideKey);
            NbtList messages = side.getListOrEmpty("messages");
            for (int index = 0; index < lines.length; index++) {
                lines[index] = decodeSignComponent(messages.getString(index, ""));
            }
            color = DyeColor.byId(side.getString("color", DyeColor.BLACK.asString()), DyeColor.BLACK);
            glowing = side.getBoolean("has_glowing_text", false);
            return LoggedSignState.of(front, color, glowing, waxed, lines);
        }

        if (!front) {
            return LoggedSignState.of(front, color, glowing, waxed, lines);
        }

        for (int index = 0; index < lines.length; index++) {
            lines[index] = decodeSignComponent(nbt.getString("Text" + (index + 1), ""));
        }
        return LoggedSignState.of(front, color, glowing, waxed, lines);
    }

    private NbtCompound toNbt(BaseBlock block) {
        if (block == null || block.getNbtReference() == null) {
            return null;
        }

        NbtElement element = NBTConverter.toNative(block.getNbtReference().getValue());
        return element instanceof NbtCompound ? (NbtCompound) element : null;
    }

    private ItemStack readNamedStack(NbtCompound nbt, String key) {
        if (nbt == null || key == null || key.isBlank() || !nbt.contains(key)) {
            return ItemStack.EMPTY;
        }
        return parseItemStack(nbt.get(key));
    }

    private Map<Integer, ItemStack> readStackMap(NbtCompound nbt, String key) {
        Map<Integer, ItemStack> stacks = new HashMap<>();
        if (nbt == null || key == null || key.isBlank()) {
            return stacks;
        }

        NbtList list = nbt.getListOrEmpty(key);
        for (int index = 0; index < list.size(); index++) {
            NbtElement element = list.get(index);
            StackWithSlot stackWithSlot = StackWithSlot.CODEC.parse(RegistryOps.of(NbtOps.INSTANCE, world.getRegistryManager()), element).result().orElse(null);
            if (stackWithSlot == null || !stackWithSlot.isValidSlot(64)) {
                continue;
            }
            stacks.put(stackWithSlot.slot(), stackWithSlot.stack());
        }
        return stacks;
    }

    private ItemStack parseItemStack(NbtElement element) {
        if (element == null) {
            return ItemStack.EMPTY;
        }
        return ItemStack.CODEC.parse(RegistryOps.of(NbtOps.INSTANCE, world.getRegistryManager()), element).result().orElse(ItemStack.EMPTY);
    }

    private String decodeSignComponent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if (!raw.startsWith("{") && !raw.startsWith("[") && !raw.startsWith("\"")) {
            return raw;
        }

        try {
            return flattenComponent(JsonParser.parseString(raw));
        }
        catch (Exception exception) {
            return raw;
        }
    }

    private String flattenComponent(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement child : element.getAsJsonArray()) {
                builder.append(flattenComponent(child));
            }
            return builder.toString();
        }

        JsonObject object = element.getAsJsonObject();
        StringBuilder builder = new StringBuilder();
        if (object.has("text")) {
            builder.append(flattenComponent(object.get("text")));
        }
        else if (object.has("translate")) {
            builder.append(flattenComponent(object.get("translate")));
        }
        if (object.has("with")) {
            builder.append(flattenComponent(object.get("with")));
        }
        if (object.has("extra")) {
            builder.append(flattenComponent(object.get("extra")));
        }
        return builder.toString();
    }
}
