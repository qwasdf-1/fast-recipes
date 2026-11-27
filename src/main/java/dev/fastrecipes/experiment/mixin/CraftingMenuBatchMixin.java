package dev.fastrecipes.experiment.mixin;

import dev.fastrecipes.experiment.CraftingUpdateBatch;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({CraftingMenu.class, InventoryMenu.class})
abstract class CraftingMenuBatchMixin {
    @Redirect(method = "quickMoveStack",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;onTake("
                    + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V"))
    private void fastrecipes$batchResultUpdates(Slot slot, Player player, ItemStack stack) {
        CraftingUpdateBatch.take((AbstractContainerMenu) (Object) this, slot, player, stack);
    }

    @Inject(method = "slotsChanged", at = @At("HEAD"), cancellable = true)
    private void fastrecipes$deferCraftingGridChange(Container container, CallbackInfo callback) {
        if (CraftingUpdateBatch.defer((AbstractContainerMenu) (Object) this, container)) {
            callback.cancel();
        }
    }
}
