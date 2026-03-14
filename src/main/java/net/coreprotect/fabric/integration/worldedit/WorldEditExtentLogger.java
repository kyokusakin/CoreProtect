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
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
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
        com.sk89q.worldedit.world.block.BlockState requestedState = block.toImmutableState();
        boolean signRelated = isSignState(oldState) || isSignState(requestedState);
        if (!signRelated && oldState.equalsFuzzy(requestedState)) {
            return super.setBlock(position, block);
        }

        boolean changed = super.setBlock(position, block);
        if (!changed) {
            return false;
        }

        BaseBlock newFullBlock = getExtent().getFullBlock(position);
        com.sk89q.worldedit.world.block.BlockState newState = newFullBlock.toImmutableState();
        if (!oldState.equalsFuzzy(newState)) {
            logBlockChange(position, oldState, newState);
        }
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
        String[] beforeLines = readSignLines(oldBlock, front);
        String[] afterLines = readSignLines(newBlock, front);
        if (Arrays.equals(beforeLines, afterLines)) {
            return;
        }

        UUID actorUuid = actor.getUniqueId();
        String actorName = actor.getName();
        eventLogger.logSignChange(actorUuid, actorName, world, FabricAdapter.toBlockPos(position), front, afterLines);
    }

    private boolean isSignState(com.sk89q.worldedit.world.block.BlockState state) {
        BlockState nativeState = FabricAdapter.adapt(state);
        return nativeState.getBlock() instanceof AbstractSignBlock;
    }

    private String[] readSignLines(BaseBlock block, boolean front) {
        String[] lines = new String[] { "", "", "", "" };
        NbtCompound nbt = toNbt(block);
        if (nbt == null) {
            return lines;
        }

        String sideKey = front ? "front_text" : "back_text";
        if (nbt.contains(sideKey)) {
            NbtCompound side = nbt.getCompoundOrEmpty(sideKey);
            NbtList messages = side.getListOrEmpty("messages");
            for (int index = 0; index < lines.length; index++) {
                lines[index] = decodeSignComponent(messages.getString(index, ""));
            }
            return lines;
        }

        if (!front) {
            return lines;
        }

        for (int index = 0; index < lines.length; index++) {
            lines[index] = decodeSignComponent(nbt.getString("Text" + (index + 1), ""));
        }
        return lines;
    }

    private NbtCompound toNbt(BaseBlock block) {
        if (block == null || block.getNbtReference() == null) {
            return null;
        }

        NbtElement element = NBTConverter.toNative(block.getNbtReference().getValue());
        return element instanceof NbtCompound ? (NbtCompound) element : null;
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
