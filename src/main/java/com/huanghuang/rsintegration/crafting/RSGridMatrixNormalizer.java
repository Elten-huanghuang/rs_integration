package com.huanghuang.rsintegration.crafting;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

/**
 * Returns items left in the RS crafting matrix to the network before RSI
 * starts a virtual recursive craft. The matrix remains UI state; it is never
 * treated as an ExtractionLedger source.
 */
public final class RSGridMatrixNormalizer {
    private RSGridMatrixNormalizer() {}

    /**
     * @return true when the matrix is empty or was returned in full
     */
    public static boolean returnToNetwork(ServerPlayer player, @Nullable INetwork network) {
        // Vanilla RS keeps the matrix/result lifecycle compatible with RSI's
        // existing execution path. This bridge is only needed for the custom
        // transfer and menu behavior supplied by RS Crafting Stations.
        if (!ModList.get().isLoaded("rs_crafting_stations")) return true;
        if (player == null || !(player.containerMenu instanceof GridContainerMenu gridMenu)) {
            return true;
        }

        IGrid grid = gridMenu.getGrid();
        if (grid == null || grid.getGridType() != GridType.CRAFTING) return true;

        CraftingContainer matrix = grid.getCraftingMatrix();
        if (matrix == null) return true;

        // Prefer the network authenticated by the open grid. A caller-side
        // fallback may refer to a retained/selected session and must not
        // receive items from a different visible grid.
        INetwork target = grid instanceof INetworkAwareGrid awareGrid
                ? awareGrid.getNetwork() : network;
        if (target == null) {
            return isEmpty(matrix);
        }

        // Simulate every insertion first. This prevents clearing part of the
        // matrix when the network cannot accept one of the stacks.
        for (int slot = 0; slot < matrix.getContainerSize(); slot++) {
            ItemStack stack = matrix.getItem(slot);
            if (stack.isEmpty()) continue;
            ItemStack remainder = target.insertItem(stack.copy(), stack.getCount(), Action.SIMULATE);
            if (!remainder.isEmpty()) {
                return false;
            }
        }

        boolean changed = false;
        for (int slot = 0; slot < matrix.getContainerSize(); slot++) {
            ItemStack stack = matrix.getItem(slot);
            if (stack.isEmpty()) continue;

            ItemStack remainder = target.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
            matrix.setItem(slot, remainder);
            changed = true;
            if (!remainder.isEmpty()) {
                return false;
            }
        }

        if (changed) {
            grid.onCraftingMatrixChanged();
            gridMenu.broadcastChanges();
        }
        return true;
    }

    private static boolean isEmpty(CraftingContainer matrix) {
        for (int slot = 0; slot < matrix.getContainerSize(); slot++) {
            if (!matrix.getItem(slot).isEmpty()) return false;
        }
        return true;
    }
}
