package net.coreprotect.fabric.service.inspector;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.service.LookupNetworkingService;
import net.coreprotect.fabric.service.LookupService;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public final class InteractionInspector {
    private final LookupService lookupService;

    public InteractionInspector(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    public void performInteractionLookup(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        String title = Phrase.build(Phrase.INTERACTIONS_HEADER);
        String emptyMessage = "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.THIRD);
        List<CoreProtectEventType> eventTypes = List.of(CoreProtectEventType.BLOCK_USE, CoreProtectEventType.ENTITY_USE);
        List<Text> lines = lookupService.describeBlockHistory(world, pos, 7, eventTypes, title, emptyMessage);
        for (Text line : lines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getBlockHistory(world, pos, 7, eventTypes));
    }
}
