package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.player.PlayerTakeLecternBookListener;
import net.coreprotect.fabric.service.ContainerSessionService;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.item.ItemStack;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.screen.LecternScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Shadow
    public abstract void updateSequence(int sequence);

    @Unique
    private BlockPos coreprotect$signPos;
    @Unique
    private boolean coreprotect$signFront;
    @Unique
    private String[] coreprotect$beforeSignLines;
    @Unique
    private List<ItemStack> coreprotect$beforeHandlerSlots = List.of();
    @Unique
    private LecternScreenHandler coreprotect$lecternScreenHandler;
    @Unique
    private int coreprotect$lecternButtonId = Integer.MIN_VALUE;
    @Unique
    private ItemStack coreprotect$beforeLecternBook = ItemStack.EMPTY;

    @Inject(
        method = "onPlayerAction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;processBlockBreakingAction(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket$Action;Lnet/minecraft/util/math/Direction;II)V"
        ),
        cancellable = true
    )
    private void coreprotect$handleInspectorBlockAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (!(this.player.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        var runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || runtime.inspector() == null || !runtime.inspector().isEnabled(this.player)) {
            return;
        }

        PlayerActionC2SPacket.Action action = packet.getAction();
        if (action != PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
            && action != PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
            && action != PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK) {
            return;
        }

        BlockPos pos = packet.getPos();
        if (action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
            runtime.inspector().inspectLeftClickBlock(this.player, serverWorld, pos);
        }

        this.player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, serverWorld.getBlockState(pos)));
        this.updateSequence(packet.getSequence());
        ci.cancel();
    }

    @Inject(method = "onUpdateSign", at = @At("HEAD"))
    private void coreprotect$captureSignBefore(UpdateSignC2SPacket packet, CallbackInfo ci) {
        coreprotect$signPos = packet.getPos().toImmutable();
        coreprotect$signFront = packet.isFront();
        coreprotect$beforeSignLines = null;

        BlockEntity blockEntity = ((ServerWorld) this.player.getEntityWorld()).getBlockEntity(packet.getPos());
        if (blockEntity instanceof SignBlockEntity) {
            SignBlockEntity signBlockEntity = (SignBlockEntity) blockEntity;
            coreprotect$beforeSignLines = coreprotect$readSignLines(signBlockEntity, packet.isFront());
        }
    }

    @Inject(method = "onUpdateSign", at = @At("RETURN"))
    private void coreprotect$logSignUpdate(UpdateSignC2SPacket packet, CallbackInfo ci) {
        try {
            if (coreprotect$signPos == null || coreprotect$beforeSignLines == null) {
                return;
            }

            BlockEntity blockEntity = ((ServerWorld) this.player.getEntityWorld()).getBlockEntity(coreprotect$signPos);
            if (!(blockEntity instanceof SignBlockEntity)) {
                return;
            }

            SignBlockEntity signBlockEntity = (SignBlockEntity) blockEntity;
            String[] currentLines = coreprotect$readSignLines(signBlockEntity, coreprotect$signFront);
            if (Arrays.equals(coreprotect$beforeSignLines, currentLines)) {
                return;
            }

            CoreProtectFabricMod.logSignChange(this.player, (ServerWorld) this.player.getEntityWorld(), coreprotect$signPos, coreprotect$signFront, currentLines);
        }
        finally {
            coreprotect$signPos = null;
            coreprotect$beforeSignLines = null;
        }
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"))
    private void coreprotect$captureClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        coreprotect$clearClickState();

        ScreenHandler handler = this.player.currentScreenHandler;
        if (handler == this.player.playerScreenHandler) {
            return;
        }

        ContainerSessionService.ContainerContext context = CoreProtectFabricMod.getContainerContext(this.player);
        if (context == null) {
            return;
        }

        if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(context.worldKey()).itemTransactions()) {
            return;
        }

        coreprotect$beforeHandlerSlots = coreprotect$copyAllSlotStacks(handler);
    }

    @Inject(method = "onClickSlot", at = @At("RETURN"))
    private void coreprotect$logClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        try {
            if (coreprotect$beforeHandlerSlots.isEmpty()) {
                return;
            }

            ScreenHandler handler = this.player.currentScreenHandler;
            if (handler == this.player.playerScreenHandler) {
                return;
            }

            ContainerSessionService.ContainerContext context = CoreProtectFabricMod.getContainerContext(this.player);
            if (context == null) {
                return;
            }
            if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(context.worldKey()).itemTransactions()) {
                return;
            }

            Map<LoggedItemData, Integer> beforeContainer = coreprotect$snapshotContainerInventory(coreprotect$beforeHandlerSlots, handler);
            Map<LoggedItemData, Integer> afterContainer = coreprotect$snapshotContainerInventory(coreprotect$copyAllSlotStacks(handler), handler);
            for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(beforeContainer, afterContainer)) {
                CoreProtectFabricMod.logContainerChange(
                    this.player,
                    context.worldKey(),
                    context.pos(),
                    context.containerType(),
                    delta.item(),
                    Math.abs(delta.delta()),
                    delta.delta() > 0
                );
            }
        }
        finally {
            coreprotect$clearClickState();
        }
    }

    @Inject(method = "onButtonClick", at = @At("HEAD"))
    private void coreprotect$captureButtonClick(ButtonClickC2SPacket packet, CallbackInfo ci) {
        coreprotect$clearLecternState();

        if (!(this.player.currentScreenHandler instanceof LecternScreenHandler lecternScreenHandler)) {
            return;
        }

        if (packet.buttonId() != LecternScreenHandler.TAKE_BOOK_BUTTON_ID) {
            return;
        }

        ContainerSessionService.ContainerContext context = CoreProtectFabricMod.getContainerContext(this.player);
        if (context == null) {
            return;
        }

        if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(context.worldKey()).itemTransactions()) {
            return;
        }

        coreprotect$lecternScreenHandler = lecternScreenHandler;
        coreprotect$lecternButtonId = packet.buttonId();
        coreprotect$beforeLecternBook = lecternScreenHandler.getBookItem().copy();
    }

    @Inject(method = "onButtonClick", at = @At("RETURN"))
    private void coreprotect$logButtonClick(ButtonClickC2SPacket packet, CallbackInfo ci) {
        try {
            if (coreprotect$lecternScreenHandler == null || coreprotect$beforeLecternBook.isEmpty()) {
                return;
            }

            ContainerSessionService.ContainerContext context = CoreProtectFabricMod.getContainerContext(this.player);
            if (context == null) {
                return;
            }
            if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(context.worldKey()).itemTransactions()) {
                return;
            }

            ItemStack afterLecternBook = coreprotect$lecternScreenHandler.getBookItem().copy();
            if (ItemStack.areEqual(coreprotect$beforeLecternBook, afterLecternBook)) {
                return;
            }

            PlayerTakeLecternBookListener.logTakeLecternBook(
                this.player,
                context,
                coreprotect$lecternButtonId,
                coreprotect$beforeLecternBook,
                afterLecternBook
            );
        }
        finally {
            coreprotect$clearLecternState();
        }
    }

    @Inject(method = "onCloseHandledScreen", at = @At("RETURN"))
    private void coreprotect$clearContainerContext(CloseHandledScreenC2SPacket packet, CallbackInfo ci) {
        CoreProtectFabricMod.clearContainerContext(this.player);
    }

    @Unique
    private String[] coreprotect$readSignLines(SignBlockEntity signBlockEntity, boolean front) {
        String[] lines = new String[4];
        for (int index = 0; index < lines.length; index++) {
            lines[index] = signBlockEntity.getText(front).getMessage(index, false).getString();
        }
        return lines;
    }

    @Unique
    private ItemStack coreprotect$copySlotStack(ScreenHandler handler, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= handler.slots.size()) {
            return ItemStack.EMPTY;
        }
        return handler.getSlot(slotIndex).getStack().copy();
    }

    @Unique
    private void coreprotect$clearClickState() {
        coreprotect$beforeHandlerSlots = List.of();
    }

    @Unique
    private List<ItemStack> coreprotect$copyAllSlotStacks(ScreenHandler handler) {
        List<ItemStack> snapshot = new ArrayList<>(handler.slots.size());
        for (int index = 0; index < handler.slots.size(); index++) {
            snapshot.add(coreprotect$copySlotStack(handler, index));
        }
        return snapshot;
    }

    @Unique
    private boolean coreprotect$isContainerSlot(ScreenHandler handler, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= handler.slots.size()) {
            return false;
        }
        return handler.getSlot(slotIndex).inventory != this.player.getInventory();
    }

    @Unique
    private Map<LoggedItemData, Integer> coreprotect$snapshotContainerInventory(List<ItemStack> slots, ScreenHandler handler) {
        List<ItemStack> containerStacks = new ArrayList<>();
        int slotCount = Math.min(slots.size(), handler.slots.size());
        for (int index = 0; index < slotCount; index++) {
            if (coreprotect$isContainerSlot(handler, index)) {
                containerStacks.add(slots.get(index));
            }
        }
        return ItemDeltaSnapshot.snapshotStacks(containerStacks, ((ServerWorld) this.player.getEntityWorld()).getRegistryManager());
    }

    @Unique
    private void coreprotect$clearLecternState() {
        coreprotect$lecternScreenHandler = null;
        coreprotect$lecternButtonId = Integer.MIN_VALUE;
        coreprotect$beforeLecternBook = ItemStack.EMPTY;
    }
}
