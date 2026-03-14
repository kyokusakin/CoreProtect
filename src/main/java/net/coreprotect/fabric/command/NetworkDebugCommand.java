package net.coreprotect.fabric.command;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.channel.PluginChannelListener;
import net.coreprotect.language.Phrase;
import net.minecraft.server.command.ServerCommandSource;

public final class NetworkDebugCommand {
    private NetworkDebugCommand() {
    }

    protected static int runCommand(ServerCommandSource source, boolean permission, String[] args) {
        if (!permission || CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config().networkDebug()) {
            source.sendFeedback(() -> CoreProtectText.prefixed(Phrase.build(Phrase.NO_PERMISSION)), false);
            return 0;
        }

        try {
            PluginChannelListener.getInstance().sendTest(source, args.length == 2 ? args[1] : "");
            return 1;
        }
        catch (Exception exception) {
            CoreProtectFabricMod.LOGGER.error("CoreProtect networking debug command failed", exception);
            return 0;
        }
    }
}
