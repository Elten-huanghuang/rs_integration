package com.huanghuang.rsintegration.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

public interface IBatchDelegate {

    boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                            @Nullable ResourceLocation dim, BlockPos pos);

    /**
     * Check machine is idle, extract materials for one craft, place them, and start.
     * @return true if the craft was successfully started
     */
    boolean tryStartSingleCraft(ServerPlayer player);

    /**
     * Variant that uses a shared ledger. The delegate should reserve from
     * {@code sharedLedger} instead of creating its own, and must NOT commit
     * (the chain commits once at the end). Default impl falls back to
     * creating a private ledger for backward compat.
     */
    default boolean tryStartSingleCraft(ServerPlayer player, ExtractionLedger sharedLedger) {
        return tryStartSingleCraft(player);
    }

    /**
     * Return the ingredient specs for the recipe loaded by {@link #validateAndInit}.
     * Used by {@code AsyncCraftChain} to pre-reserve all multi-block materials
     * from a shared ledger before placing them in the machine.
     *
     * @return ingredient list in the order expected by {@link #tryStartWithMaterials},
     *         or null if the delegate handles extraction on its own
     */
    @Nullable
    default List<IngredientSpec> getRequiredMaterials() {
        return null;
    }

    /**
     * Accept pre-reserved materials from the chain and start the craft.
     * Materials are in the order returned by {@link #getRequiredMaterials()}.
     * The delegate must place each stack in the correct machine slot and then
     * start the craft — it must NOT extract from RS or commit the ledger.
     *
     * @param player       the player who initiated the craft
     * @param materials    pre-reserved ItemStacks matching the spec order
     * @param sharedLedger the chain's master ledger (for refund reference only,
     *                     do NOT commit)
     * @return true if all materials were placed and the craft was started
     */
    default boolean tryStartWithMaterials(ServerPlayer player,
                                          List<ItemStack> materials,
                                          ExtractionLedger sharedLedger) {
        return false;
    }

    /**
     * Poll machine state to detect craft completion.
     * Called every tick while in WAITING state.
     */
    boolean isCraftComplete(ServerLevel level);

    /**
     * Collect the result item from the machine after craft completes.
     * @return the result ItemStack, or ItemStack.EMPTY if not yet available
     */
    ItemStack collectResult(ServerPlayer player);

    /** Cleanup and refund on batch failure. */
    void onBatchFailed(ServerPlayer player, String reason);

    /** Cleanup on successful batch completion. */
    void onBatchFinished(ServerPlayer player);

    /** The machine position this delegate is operating on. */
    BlockPos getMachinePos();
}
