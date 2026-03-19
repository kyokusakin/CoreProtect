package net.coreprotect.fabric;

import net.coreprotect.fabric.command.CoreProtectCommands;
import net.coreprotect.fabric.api.CoreProtectFabric;
import net.coreprotect.fabric.api.CoreProtectFabricAPI;
import net.coreprotect.fabric.listener.channel.PluginChannelHandshakeListener;
import net.coreprotect.fabric.listener.channel.PluginChannelListener;
import net.coreprotect.fabric.listener.player.PlayerInteractEntityListener;
import net.coreprotect.fabric.listener.player.PlayerInteractUtils;
import net.coreprotect.fabric.service.ContainerSessionService;
import net.coreprotect.fabric.util.LoggedItemData;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreProtectFabricMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "coreprotect_fabric";
    public static final Logger LOGGER = LoggerFactory.getLogger("CoreProtectFabric");

    private static volatile FabricRuntime runtime;

    public static FabricRuntime getRuntime() {
        return runtime;
    }

    public static CoreProtectFabricAPI getAPI() {
        return CoreProtectFabric.getAPI();
    }

    @Override
    public void onInitializeServer() {
        runtime = new FabricRuntime(LOGGER);
        PluginChannelHandshakeListener.getInstance().initialize();
        PluginChannelListener.getInstance().initialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            CoreProtectCommands.register(dispatcher, runtime)
        );

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            FabricRuntime currentRuntime = runtime;
            if (currentRuntime == null) {
                return;
            }
            if (currentRuntime.logger() != null) {
                currentRuntime.logger().tick();
            }
            if (currentRuntime.rollback() != null) {
                currentRuntime.rollback().tick(server);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            FabricRuntime currentRuntime = runtime;
            if (currentRuntime == null) {
                return;
            }

            var eventLogger = currentRuntime.logger();
            if (eventLogger != null) {
                try {
                    eventLogger.logPlayerJoin(handler.player);
                }
                catch (RuntimeException exception) {
                    LOGGER.warn("Failed to log player join for {}", handler.player.getName().getString(), exception);
                }
            }

            var rollbackService = currentRuntime.rollback();
            if (rollbackService != null) {
                try {
                    rollbackService.applyPendingInventoryRollbacks(handler.player);
                }
                catch (RuntimeException exception) {
                    LOGGER.warn("Failed to apply pending inventory rollbacks for {}", handler.player.getName().getString(), exception);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            FabricRuntime currentRuntime = runtime;
            if (currentRuntime == null) {
                return;
            }

            var eventLogger = currentRuntime.logger();
            if (eventLogger != null) {
                eventLogger.flushInteractionAggregates(handler.player.getUuidAsString());
                eventLogger.logPlayerQuit(handler.player);
            }

            var inspectorService = currentRuntime.inspector();
            if (inspectorService != null) {
                inspectorService.disable(handler.player);
            }

            var containerSessionService = currentRuntime.containers();
            if (containerSessionService != null) {
                containerSessionService.clear(handler.player);
            }

            var previewService = currentRuntime.previews();
            if (previewService != null) {
                previewService.clear(handler.player.getUuid());
            }

            PluginChannelHandshakeListener.getInstance().unregisterPlayer(handler.player);
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity) || !(world instanceof ServerWorld)) {
                return ActionResult.PASS;
            }

            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            ServerWorld serverWorld = (ServerWorld) world;
            if (serverWorld.getBlockState(pos).isOf(Blocks.DRAGON_EGG)) {
                PlayerInteractUtils.clickedDragonEgg(serverPlayer, serverWorld, pos);
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity) || !(world instanceof ServerWorld)) {
                return ActionResult.PASS;
            }

            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            ServerWorld serverWorld = (ServerWorld) world;
            return runtime.inspector().inspectEntity(serverPlayer, serverWorld, entity) ? ActionResult.FAIL : ActionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity) {
                ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
                runtime.logger().logBlockBreak(serverPlayer, (net.minecraft.server.world.ServerWorld) world, pos, state);
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || player == null) {
                return ActionResult.PASS;
            }

            if (player instanceof ServerPlayerEntity && world instanceof ServerWorld) {
                ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
                ServerWorld serverWorld = (ServerWorld) world;
                BlockPos clickedPos = hitResult.getBlockPos();
                BlockPos inspectPos = clickedPos.offset(hitResult.getSide());
                if (player.getStackInHand(hand).getItem() instanceof BlockItem) {
                    inspectPos = new ItemPlacementContext(serverPlayer, hand, player.getStackInHand(hand), hitResult).getBlockPos();
                }

                if (runtime.inspector().inspectRightClickBlock(serverPlayer, serverWorld, clickedPos, inspectPos)) {
                    if (player.getStackInHand(hand).getItem() instanceof BlockItem) {
                        // Keep client inventory in sync when inspect mode suppresses placement.
                        serverPlayer.getInventory().markDirty();
                        serverPlayer.playerScreenHandler.syncState();
                        serverPlayer.currentScreenHandler.sendContentUpdates();
                        return ActionResult.SUCCESS_SERVER;
                    }
                    return ActionResult.FAIL;
                }
                if (serverWorld.getBlockState(clickedPos).isOf(Blocks.DRAGON_EGG)) {
                    PlayerInteractUtils.clickedDragonEgg(serverPlayer, serverWorld, clickedPos);
                }
            }

            if (player instanceof ServerPlayerEntity) {
                ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
                ServerWorld serverWorld = (ServerWorld) world;
                BlockPos pos = hitResult.getBlockPos();
                BlockState clickedState = world.getBlockState(pos);
                runtime.containers().trackPotentialAccess(serverPlayer, serverWorld, pos, clickedState);
                if (!(player.getStackInHand(hand).getItem() instanceof BlockItem)) {
                    var inspectorService = runtime.inspector();
                    if (inspectorService.shouldTrackBlockUse(serverWorld, pos, clickedState)) {
                        BlockPos loggedPos = inspectorService.normalizeTrackedBlockUsePos(serverWorld, pos, clickedState);
                        runtime.logger().logBlockUse(serverPlayer, serverWorld, loggedPos, serverWorld.getBlockState(loggedPos));
                    }
                }
            }
            return ActionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity) || !(world instanceof ServerWorld)) {
                return ActionResult.PASS;
            }

            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            ServerWorld serverWorld = (ServerWorld) world;
            if (runtime.inspector().inspectEntity(serverPlayer, serverWorld, entity)) {
                return ActionResult.FAIL;
            }

            if (hand == Hand.MAIN_HAND && !(entity instanceof ArmorStandEntity) && !(entity instanceof ItemFrameEntity)) {
                PlayerInteractEntityListener.logEntityUse(serverPlayer, serverWorld, entity.getBlockPos(), entity);
            }
            return ActionResult.PASS;
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
            runtime.logger().logChat(sender, (net.minecraft.server.world.ServerWorld) sender.getEntityWorld(), sender.getBlockPos(), message.getContent().getString())
        );

    }

    private void onServerStarted(MinecraftServer server) {
        runtime.initialize(server);
        if (runtime.logger() != null) {
            runtime.logger().logServerStart(server);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        if (runtime.logger() != null) {
            runtime.logger().flushInteractionAggregates();
            runtime.logger().logServerStop(server);
        }
        runtime.shutdown();
    }

    public static void logBlockPlace(ServerPlayerEntity player, net.minecraft.server.world.ServerWorld world, net.minecraft.util.math.BlockPos pos, net.minecraft.block.BlockState state) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logBlockPlace(player, world, pos, state);
    }

    public static void logPlayerCommand(net.minecraft.server.command.ServerCommandSource source, String command) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logCommand(source, command);
    }

    public static void logSignChange(ServerPlayerEntity player, ServerWorld world, BlockPos pos, boolean front, String[] lines) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logSignChange(player, world, pos, front, lines);
    }

    public static void logContainerTransaction(ServerPlayerEntity player, String worldKey, BlockPos pos, String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logContainerTransaction(player, worldKey, pos, containerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
    }

    public static void logContainerChange(ServerPlayerEntity player, String worldKey, BlockPos pos, String containerType, LoggedItemData item, int count, boolean added) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logContainerChange(player, worldKey, pos, containerType, item, count, added);
    }

    public static void logItemPickup(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemPickup(player, world, pos, stack);
    }

    public static void logItemPickup(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemPickup(player, worldKey, pos, itemKey, count, contextLabel);
    }

    public static void logItemPickup(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemPickup(player, worldKey, pos, item, count, contextLabel);
    }

    public static void logItemDrop(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemDrop(player, world, pos, stack);
    }

    public static void logItemDrop(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemDrop(player, worldKey, pos, itemKey, count, contextLabel);
    }

    public static void logItemDrop(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemDrop(player, worldKey, pos, item, count, contextLabel);
    }

    public static void logItemThrow(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemThrow(player, world, pos, stack);
    }

    public static void logItemThrow(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemThrow(player, worldKey, pos, itemKey, count, contextLabel);
    }

    public static void logItemThrow(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemThrow(player, worldKey, pos, item, count, contextLabel);
    }

    public static void logItemShoot(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemShoot(player, world, pos, stack);
    }

    public static void logItemShoot(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemShoot(player, worldKey, pos, itemKey, count, contextLabel);
    }

    public static void logItemShoot(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemShoot(player, worldKey, pos, item, count, contextLabel);
    }

    public static void logItemBuy(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemBuy(player, world, pos, stack);
    }

    public static void logItemBuy(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemBuy(player, worldKey, pos, itemKey, count, contextLabel);
    }

    public static void logItemBuy(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemBuy(player, worldKey, pos, item, count, contextLabel);
    }

    public static void logItemSell(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemSell(player, world, pos, stack);
    }

    public static void logItemSell(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemSell(player, worldKey, pos, itemKey, count, contextLabel);
    }

    public static void logItemSell(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemSell(player, worldKey, pos, item, count, contextLabel);
    }

    public static void logItemCreate(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemCreate(player, world, pos, stack);
    }

    public static void logItemCreate(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemCreate(player, worldKey, pos, itemKey, count, contextLabel);
    }

    public static void logItemCreate(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemCreate(player, worldKey, pos, item, count, contextLabel);
    }

    public static void logItemDestroy(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemDestroy(player, world, pos, stack);
    }

    public static void logItemDestroy(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemDestroy(player, worldKey, pos, itemKey, count, contextLabel);
    }

    public static void logItemDestroy(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logItemDestroy(player, worldKey, pos, item, count, contextLabel);
    }

    public static void logEntityPlace(ServerPlayerEntity player, ServerWorld world, BlockPos pos, Entity entity) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logEntityPlace(player, world, pos, entity);
    }

    public static void logEntityBreak(ServerWorld world, BlockPos pos, Entity entity, Entity breaker) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logEntityBreak(world, pos, entity, breaker);
    }

    public static void logEntityUse(ServerPlayerEntity player, ServerWorld world, BlockPos pos, Entity entity) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        currentRuntime.logger().logEntityUse(player, world, pos, entity);
    }

    public static ContainerSessionService.ContainerContext getContainerContext(ServerPlayerEntity player) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null || currentRuntime.containers() == null) {
            return null;
        }

        return currentRuntime.containers().getContext(player);
    }

    public static void clearContainerContext(ServerPlayerEntity player) {
        FabricRuntime currentRuntime = runtime;
        if (currentRuntime == null || currentRuntime.containers() == null) {
            return;
        }

        currentRuntime.containers().clear(player);
    }
}
