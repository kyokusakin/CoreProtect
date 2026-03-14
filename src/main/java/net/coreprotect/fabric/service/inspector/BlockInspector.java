package net.coreprotect.fabric.service.inspector;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.service.LookupNetworkingService;
import net.coreprotect.fabric.service.LookupService;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;

import java.util.List;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;

public final class BlockInspector {
    private static final List<CoreProtectEventType> LEFT_CLICK_EVENT_TYPES = List.of(
        CoreProtectEventType.BLOCK_BREAK,
        CoreProtectEventType.BLOCK_PLACE,
        CoreProtectEventType.BLOCK_USE,
        CoreProtectEventType.ENTITY_PLACE,
        CoreProtectEventType.ENTITY_BREAK,
        CoreProtectEventType.ENTITY_USE,
        CoreProtectEventType.ENTITY_KILL,
        CoreProtectEventType.SIGN_CHANGE
    );

    private final LookupService lookupService;

    public BlockInspector(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    public void performBlockLookup(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        String title = Phrase.build(Phrase.LOOKUP_HEADER, "CoreProtect");
        String emptyMessage = "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FIRST);
        List<Text> lines = lookupService.describeBlockHistory(world, pos, 7, LEFT_CLICK_EVENT_TYPES, title, emptyMessage);
        for (Text line : lines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getBlockHistory(world, pos, 7, LEFT_CLICK_EVENT_TYPES));
    }

    public void performAirBlockLookup(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        List<Text> lines = lookupService.describeBlockHistory(world, pos, 7);
        for (Text line : lines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getBlockHistory(world, pos, 7, null));
    }
}
