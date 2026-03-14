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
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.screen.LecternScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
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

import java.util.Arrays;
import java.util.Map;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Unique
    private BlockPos coreprotect$signPos;
    @Unique
    private boolean coreprotect$signFront;
    @Unique
    private String[] coreprotect$beforeSignLines;
    @Unique
    private int coreprotect$slotIndex = Integer.MIN_VALUE;
    @Unique
    private int coreprotect$slotButton;
    @Unique
    private SlotActionType coreprotect$slotActionType;
    @Unique
    private ItemStack coreprotect$beforeSlotStack = ItemStack.EMPTY;
    @Unique
    private ItemStack coreprotect$beforeCursorStack = ItemStack.EMPTY;
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforePlayerInventory = Map.of();
    @Unique
    private LecternScreenHandler coreprotect$lecternScreenHandler;
    @Unique
    private int coreprotect$lecternButtonId = Integer.MIN_VALUE;
    @Unique
    private ItemStack coreprotect$beforeLecternBook = ItemStack.EMPTY;
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeLecternInventory = Map.of();

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
            if (!Arrays.equals(packet.getText(), currentLines)) {
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

        coreprotect$slotIndex = packet.slot();
        coreprotect$slotButton = packet.button();
        coreprotect$slotActionType = packet.actionType();
        coreprotect$beforeSlotStack = coreprotect$copySlotStack(handler, coreprotect$slotIndex);
        coreprotect$beforeCursorStack = handler.getCursorStack().copy();
        coreprotect$beforePlayerInventory = ItemDeltaSnapshot.snapshotPlayerInventory(this.player);
    }

    @Inject(method = "onClickSlot", at = @At("RETURN"))
    private void coreprotect$logClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        try {
            if (coreprotect$slotActionType == null) {
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

            ItemStack afterSlotStack = coreprotect$copySlotStack(handler, coreprotect$slotIndex);
            ItemStack afterCursorStack = handler.getCursorStack().copy();
            if (ItemStack.areEqual(coreprotect$beforeSlotStack, afterSlotStack) && ItemStack.areEqual(coreprotect$beforeCursorStack, afterCursorStack)) {
                return;
            }

            CoreProtectFabricMod.logContainerTransaction(
                this.player,
                context.worldKey(),
                context.pos(),
                context.containerType(),
                coreprotect$slotIndex,
                coreprotect$slotButton,
                coreprotect$slotActionType,
                coreprotect$beforeSlotStack,
                afterSlotStack,
                coreprotect$beforeCursorStack,
                afterCursorStack
            );

            Map<LoggedItemData, Integer> afterPlayerInventory = ItemDeltaSnapshot.snapshotPlayerInventory(this.player);
            for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(coreprotect$beforePlayerInventory, afterPlayerInventory)) {
                if (delta.delta() > 0) {
                    CoreProtectFabricMod.logItemPickup(this.player, context.worldKey(), context.pos(), delta.item(), delta.delta(), context.containerType());
                }
                else {
                    CoreProtectFabricMod.logItemDrop(this.player, context.worldKey(), context.pos(), delta.item(), -delta.delta(), context.containerType());
                }
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

        coreprotect$lecternScreenHandler = lecternScreenHandler;
        coreprotect$lecternButtonId = packet.buttonId();
        coreprotect$beforeLecternBook = lecternScreenHandler.getBookItem().copy();
        coreprotect$beforeLecternInventory = ItemDeltaSnapshot.snapshotPlayerInventory(this.player);
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

            ItemStack afterLecternBook = coreprotect$lecternScreenHandler.getBookItem().copy();
            if (ItemStack.areEqual(coreprotect$beforeLecternBook, afterLecternBook)) {
                return;
            }

            PlayerTakeLecternBookListener.logTakeLecternBook(
                this.player,
                context,
                coreprotect$lecternButtonId,
                coreprotect$beforeLecternBook,
                afterLecternBook,
                coreprotect$beforeLecternInventory,
                ItemDeltaSnapshot.snapshotPlayerInventory(this.player)
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
        coreprotect$slotIndex = Integer.MIN_VALUE;
        coreprotect$slotButton = 0;
        coreprotect$slotActionType = null;
        coreprotect$beforeSlotStack = ItemStack.EMPTY;
        coreprotect$beforeCursorStack = ItemStack.EMPTY;
        coreprotect$beforePlayerInventory = Map.of();
    }

    @Unique
    private void coreprotect$clearLecternState() {
        coreprotect$lecternScreenHandler = null;
        coreprotect$lecternButtonId = Integer.MIN_VALUE;
        coreprotect$beforeLecternBook = ItemStack.EMPTY;
        coreprotect$beforeLecternInventory = Map.of();
    }
}
