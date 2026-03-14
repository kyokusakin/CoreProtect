package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlayerManager.class, priority = 900)
public abstract class PlayerManagerChatMixin {
    @Unique
    private static final ThreadLocal<CanceledChatContext> coreprotect$pendingChat = new ThreadLocal<>();

    @Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V", at = @At("HEAD"))
    private void coreprotect$captureChat(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params, CallbackInfo ci) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || sender == null) {
            coreprotect$pendingChat.remove();
            return;
        }
        if (!runtime.config((ServerWorld) sender.getEntityWorld()).logCancelledChat()) {
            coreprotect$pendingChat.remove();
            return;
        }
        coreprotect$pendingChat.set(new CanceledChatContext(sender, message.getContent().getString()));
    }

    @Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V", at = @At("TAIL"))
    private void coreprotect$clearSuccessfulChat(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params, CallbackInfo ci) {
        coreprotect$pendingChat.remove();
    }

    @Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V", at = @At("RETURN"))
    private void coreprotect$logCanceledChat(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params, CallbackInfo ci) {
        CanceledChatContext context = coreprotect$pendingChat.get();
        coreprotect$pendingChat.remove();
        if (context == null) {
            return;
        }

        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null) {
            return;
        }

        runtime.logger().logChat(context.player(), (ServerWorld) context.player().getEntityWorld(), context.player().getBlockPos(), context.message());
    }

    @Unique
    private record CanceledChatContext(ServerPlayerEntity player, String message) {
    }
}
