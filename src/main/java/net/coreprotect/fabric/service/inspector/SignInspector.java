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

public final class SignInspector {
    private final LookupService lookupService;

    public SignInspector(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    public void performSignLookup(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        String title = Phrase.build(Phrase.SIGN_HEADER);
        String emptyMessage = "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FOURTH);
        List<CoreProtectEventType> eventTypes = List.of(CoreProtectEventType.SIGN_CHANGE);
        List<Text> lines = lookupService.describeBlockHistory(world, pos, 7, eventTypes, title, emptyMessage);
        for (Text line : lines) {
            player.sendMessage(line, false);
        }
        LookupNetworkingService.send(player.getCommandSource(), lookupService.getBlockHistory(world, pos, 7, eventTypes));
    }
}
