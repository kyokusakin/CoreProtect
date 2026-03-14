package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(CraftingResultSlot.class)
public abstract class CraftingResultSlotMixin {
    @Shadow
    @Final
    private RecipeInputInventory input;

    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeInputs = Map.of();
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforePlayerInventory = Map.of();

    @Inject(method = "onTakeItem", at = @At("HEAD"))
    private void coreprotect$captureCraftingInputs(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        coreprotect$beforeInputs = ItemDeltaSnapshot.snapshotStacks(input.getHeldStacks());
        if (player instanceof ServerPlayerEntity serverPlayerEntity) {
            coreprotect$beforePlayerInventory = ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayerEntity);
        }
        else {
            coreprotect$beforePlayerInventory = Map.of();
        }
    }

    @Inject(method = "onTakeItem", at = @At("RETURN"))
    private void coreprotect$logCraftingTransaction(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayerEntity)) {
                return;
            }
            if (!(player.getEntityWorld() instanceof ServerWorld serverWorld)) {
                return;
            }

            Map<LoggedItemData, Integer> afterInputs = ItemDeltaSnapshot.snapshotStacks(input.getHeldStacks());
            Map<LoggedItemData, Integer> afterPlayerInventory = ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayerEntity);
            for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(coreprotect$beforeInputs, afterInputs)) {
                if (delta.delta() < 0) {
                    CoreProtectFabricMod.logItemDestroy(
                        serverPlayerEntity,
                        serverWorld.getRegistryKey().getValue().toString(),
                        player.getBlockPos(),
                        delta.item(),
                        -delta.delta(),
                        "crafting"
                    );
                }
            }

            if (!stack.isEmpty()) {
                LoggedItemData craftedItem = LoggedItemData.fromStack(stack);
                int createdCount = ItemDeltaSnapshot.countDelta(coreprotect$beforePlayerInventory, afterPlayerInventory, craftedItem);
                if (createdCount <= 0) {
                    createdCount = stack.getCount();
                }
                CoreProtectFabricMod.logItemCreate(
                    serverPlayerEntity,
                    serverWorld.getRegistryKey().getValue().toString(),
                    player.getBlockPos(),
                    craftedItem,
                    createdCount,
                    "crafting"
                );
            }
        }
        finally {
            coreprotect$beforeInputs = Map.of();
            coreprotect$beforePlayerInventory = Map.of();
        }
    }
}
