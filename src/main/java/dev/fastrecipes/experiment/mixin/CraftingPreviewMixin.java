package dev.fastrecipes.experiment.mixin;

import dev.fastrecipes.experiment.CraftingUpdateBatch;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CraftingMenu.class)
abstract class CraftingPreviewMixin {
    @Redirect(method = "slotChangedCraftingGrid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send("
                    + "Lnet/minecraft/network/protocol/Packet;)V"))
    private static void fastrecipes$coalescePreview(ServerGamePacketListenerImpl connection, Packet<?> packet,
                                                    AbstractContainerMenu menu, ServerLevel level, Player player,
                                                    CraftingContainer grid, ResultContainer result,
                                                    RecipeHolder<CraftingRecipe> hint) {
        CraftingUpdateBatch.sendPreview(connection, packet, menu);
    }
}
