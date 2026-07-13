package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Classifies an RS JEI "+" grid transfer by whether its ingredient layout forms
 * a vanilla crafting-table recipe.
 * <p>
 * The check runs against the JEI-provided candidate stacks (which carry the
 * recipe's exact NBT and are independent of network availability), not the
 * post-transfer crafting matrix. RS fills that matrix with network-extracted
 * items whose NBT may not satisfy NBT-sensitive ingredients (e.g. SlashBlade),
 * which would misclassify a valid crafting recipe and spill its materials out
 * of the grid.
 */
public final class GridTransferClassifier {

    private GridTransferClassifier() {}

    private static final int GRID_SIZE = 9;
    private static final int GRID_WIDTH = 3;
    private static final int GRID_HEIGHT = 3;

    /**
     * @param recipe RS {@code GridTransferMessage.recipe}: per-slot candidate
     *               stacks, indexed by 3x3 grid slot (entries may be null).
     * @return {@code true} if the layout matches a crafting-table recipe — keep
     *         it in the grid. On {@code null} input or any error, returns
     *         {@code true} so craftable materials are never spilled out.
     */
    public static boolean isCraftingRecipe(ItemStack[][] recipe, Player player) {
        if (recipe == null) return true;
        try {
            // Headless container: getRecipeFor only reads item contents, and the
            // dummy menu's slotsChanged is a no-op, so no real menu is needed.
            AbstractContainerMenu dummyMenu = new AbstractContainerMenu(null, -1) {
                @Override
                public ItemStack quickMoveStack(Player p, int index) {
                    return ItemStack.EMPTY;
                }

                @Override
                public boolean stillValid(Player p) {
                    return true;
                }
            };
            TransientCraftingContainer matrix =
                    new TransientCraftingContainer(dummyMenu, GRID_WIDTH, GRID_HEIGHT);

            int limit = Math.min(GRID_SIZE, recipe.length);
            for (int i = 0; i < limit; i++) {
                ItemStack[] candidates = recipe[i];
                if (candidates != null && candidates.length > 0
                        && candidates[0] != null && !candidates[0].isEmpty()) {
                    matrix.setItem(i, candidates[0].copy());
                }
            }

            return player.level().getRecipeManager()
                    .getRecipeFor(RecipeType.CRAFTING, matrix, player.level())
                    .isPresent();
        } catch (Throwable t) {
            return true;
        }
    }
}
