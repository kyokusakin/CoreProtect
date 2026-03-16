package net.coreprotect.fabric.integration.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;

public final class WorldEditIntegration implements AutoCloseable {
    private final FabricRuntime runtime;
    private final Logger logger;

    private WorldEditIntegration(FabricRuntime runtime, Logger logger) {
        this.runtime = runtime;
        this.logger = logger;
    }

    public static AutoCloseable register(FabricRuntime runtime, Logger logger) {
        WorldEditIntegration integration = new WorldEditIntegration(runtime, logger);
        WorldEdit.getInstance().getEventBus().register(integration);
        return integration;
    }

    @Subscribe
    public void onEditSessionEvent(EditSessionEvent event) {
        if (event.getActor() == null || event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        if (event.getExtent() instanceof WorldEditExtentLogger) {
            return;
        }

        net.minecraft.world.World world = FabricAdapter.adapt(event.getWorld());
        if (!(world instanceof ServerWorld)) {
            return;
        }

        try {
            Extent wrapped = new WorldEditExtentLogger(runtime.logger(), event.getActor(), (ServerWorld) world, event.getExtent());
            event.setExtent(wrapped);
        }
        catch (Exception exception) {
            logger.warn("CoreProtect Fabric failed to wrap a WorldEdit extent", exception);
        }
    }

    @Override
    public void close() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }
}
