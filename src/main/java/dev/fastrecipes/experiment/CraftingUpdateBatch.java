package dev.fastrecipes.experiment;

import com.mojang.logging.LogUtils;
import java.lang.ref.WeakReference;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

public final class CraftingUpdateBatch {
    private static final boolean ENABLED = Boolean.getBoolean("fastrecipes.batchCrafting");
    private static final boolean FABRIC_INGREDIENTS = FabricLoader.getInstance().isModLoaded("fabric-recipe-api-v1");
    private static final ThreadLocal<Batch> ACTIVE = new ThreadLocal<>();

    // The experiment only runs on the dedicated server thread.
    private static WeakReference<RecipeManager> checkedManager = new WeakReference<>(null);
    private static boolean batchRecomputations;

    private CraftingUpdateBatch() {}

    public static void take(AbstractContainerMenu menu, Slot slot, Player player, ItemStack stack) {
        if (!ENABLED) {
            slot.onTake(player, stack);
            return;
        }
        Batch previous = ACTIVE.get();
        if (previous != null) {
            ACTIVE.remove();
            try {
                previous.flush();
                slot.onTake(player, stack);
            } finally {
                ACTIVE.set(previous);
            }
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !supports(menu, slot, serverPlayer)) {
            slot.onTake(player, stack);
            return;
        }

        Batch batch = new Batch(menu, serverPlayer.connection, batchRecomputations);
        ACTIVE.set(batch);
        try {
            slot.onTake(player, stack);
        } finally {
            ACTIVE.remove();
            batch.flush();
        }
    }

    public static boolean defer(AbstractContainerMenu menu, Container container) {
        if (!ENABLED) {
            return false;
        }
        Batch batch = ACTIVE.get();
        if (batch == null || !batch.recomputations || batch.menu != menu
                || container.getClass() != TransientCraftingContainer.class) {
            return false;
        }
        batch.pending = container;
        return true;
    }

    public static void sendPreview(ServerGamePacketListenerImpl connection, Packet<?> packet,
                                   AbstractContainerMenu menu) {
        Batch batch = ENABLED ? ACTIVE.get() : null;
        // Mixed recipe sets still run every intermediate callback and advance vanilla state IDs.
        // Only the result-slot packet snapshot from this call site is coalesced.
        if (batch != null && !batch.recomputations && batch.menu == menu && batch.connection == connection
                && packet instanceof ClientboundContainerSetSlotPacket) {
            batch.preview = packet;
            return;
        }
        if (batch != null && batch.connection == connection) {
            batch.flushPreview();
        }
        connection.send(packet);
    }

    private static boolean supports(AbstractContainerMenu menu, Slot slot, ServerPlayer player) {
        if (slot.getClass() != ResultSlot.class
                || (menu.getClass() != CraftingMenu.class && menu.getClass() != InventoryMenu.class)
                || !(player.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return false;
        }
        RecipeManager manager = level.getServer().getRecipeManager();
        if (manager.getClass() != RecipeManager.class) {
            return false;
        }
        if (manager != checkedManager.get()) {
            batchRecomputations = manager.getRecipes().stream()
                    .noneMatch(holder -> holder.value() instanceof CraftingRecipe recipe && !supportsRecipe(recipe));
            checkedManager = new WeakReference<>(manager);
            if (batchRecomputations) {
                LogUtils.getLogger().info("Fast Recipes crafting update experiment initialized");
            } else {
                LogUtils.getLogger().info("Fast Recipes crafting packet batching experiment initialized");
            }
        }
        return true;
    }

    private static boolean supportsRecipe(CraftingRecipe recipe) {
        Class<?> type = recipe.getClass();
        if (!type.getPackageName().equals(CraftingRecipe.class.getPackageName())) {
            return false;
        }
        // The built-in special recipes have no serialized Fabric ingredients.
        return !FABRIC_INGREDIENTS || (type != ShapedRecipe.class && type != ShapelessRecipe.class)
                || FabricRecipePredicates.hasOnlyVanillaIngredients(recipe);
    }

    private static final class Batch {
        private final AbstractContainerMenu menu;
        private final ServerGamePacketListenerImpl connection;
        private final boolean recomputations;
        private Container pending;
        private Packet<?> preview;

        private Batch(AbstractContainerMenu menu, ServerGamePacketListenerImpl connection, boolean recomputations) {
            this.menu = menu;
            this.connection = connection;
            this.recomputations = recomputations;
        }

        private void flush() {
            if (pending != null) {
                Container container = pending;
                pending = null;
                menu.slotsChanged(container);
            }
            flushPreview();
        }

        private void flushPreview() {
            if (preview != null) {
                Packet<?> packet = preview;
                preview = null;
                if (connection.player.containerMenu == menu) {
                    connection.send(packet);
                }
            }
        }
    }
}
