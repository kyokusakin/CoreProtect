package net.coreprotect.fabric.service;

import net.minecraft.entity.Entity;
import net.coreprotect.fabric.permission.CoreProtectPermissions;
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

    private final LookupService lookupService;
    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastInspectAt = new ConcurrentHashMap<>();

    public InspectorService(LookupService lookupService) {
        this.lookupService = lookupService;
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

    public boolean inspectBlock(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        if (!isEnabled(player)) {
            return false;
        }
        if (!CoreProtectPermissions.canUseInspect(player, true)) {
            disable(player);
            return false;
        }

        long now = System.currentTimeMillis();
        long last = lastInspectAt.getOrDefault(player.getUuid(), 0L);
        if ((now - last) < THROTTLE_MS) {
            return true;
        }

        lastInspectAt.put(player.getUuid(), now);
        List<Text> lines = lookupService.describeBlockHistory(world, pos, 7);
        for (Text line : lines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getBlockHistory(world, pos, 7, null));
        return true;
    }

    public boolean inspectEntity(ServerPlayerEntity player, ServerWorld world, Entity entity) {
        if (!isEnabled(player)) {
            return false;
        }
        if (!CoreProtectPermissions.canUseInspect(player, true)) {
            disable(player);
            return false;
        }

        long now = System.currentTimeMillis();
        long last = lastInspectAt.getOrDefault(player.getUuid(), 0L);
        if ((now - last) < THROTTLE_MS) {
            return true;
        }

        lastInspectAt.put(player.getUuid(), now);
        List<Text> lines = lookupService.describeEntityHistory(world, entity, 7);
        for (Text line : lines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getEntityHistory(world, entity, 7, null));
        return true;
    }
}
