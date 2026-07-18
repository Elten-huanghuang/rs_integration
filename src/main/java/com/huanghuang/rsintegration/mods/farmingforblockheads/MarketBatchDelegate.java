package com.huanghuang.rsintegration.mods.farmingforblockheads;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.reflection.probes.FarmingForBlockheadsReflection;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Virtual batch delegate for FarmingForBlockheads Market trades.
 *
 * <p>The Market has no processing inventory — trades are instant exchanges.
 * This delegate bypasses the Market block entirely: payment is extracted
 * from the RS network and the result is deposited directly.</p>
 *
 * <p>The bound Market block serves as an access gate: you must have a
 * Market bound to use its trades in auto-crafting. The physical block
 * is not interacted with during execution.</p>
 */
public final class MarketBatchDelegate extends AbstractBatchDelegate {

    // Reflection — Market API
    private static volatile boolean probed;
    private static volatile boolean available;
    private static volatile Object marketRegistryInstance;

    private MarketRecipeWrapper wrapper;
    private ServerPlayer player;
    private boolean done;
    private boolean resultInserted;

    private static void probe() {
        if (probed) return;
        probed = true;
        try {
            java.lang.reflect.Field instField = FarmingForBlockheadsReflection.marketRegistryClass.getField("INSTANCE");
            marketRegistryInstance = instField.get(null);
            available = marketRegistryInstance != null;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Market] MarketRegistry not available", e);
            available = false;
        }
    }

    // ── IBatchDelegate contract ────────────────────────────────────

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (network == null || wrapper == null) return false;

        // Extract payment from RS network.
        ItemStack cost = wrapper.costItem();
        if (!cost.isEmpty()) {
            ItemStack extracted = RSIntegrationNetwork.extractFromNetwork(
                    network, Ingredient.of(cost), cost.getCount(), player);
            if (extracted.isEmpty() || extracted.getCount() < cost.getCount()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Market] Failed to extract payment: {}x {}",
                        cost.getCount(), cost.getHoverName().getString());
                return false;
            }
        }

        // Direct path: insert result immediately.  The chain path uses the
        // ledger-overloaded tryStartSingleCraft + collectResult instead.
        ItemStack result = wrapper.getResultItem(player.serverLevel().registryAccess());
        if (!result.isEmpty()) {
            network.insertItem(result.copy(), result.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            resultInserted = true;
        }

        done = true;
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player, ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;

        if (network == null || wrapper == null) return false;

        sharedLedger.commit(network, player);
        done = true;
        return true;
    }

    // ── validateAndInit ────────────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.player = player;
        this.done = false;

        // Check that the block at pos is a Market
        ServerLevel level = player.serverLevel();
        if (pos == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Market] validateAndInit: pos is null");
            return false;
        }
        var be = level.getBlockEntity(pos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Market] validateAndInit: no block entity at {}", pos);
            return false;
        }
        if (!FarmingForBlockheadsReflection.marketBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Market] validateAndInit: not a MarketBlockEntity, got {}", be.getClass().getName());
            return false;
        }

        // Look up the recipe wrapper (created by RecipeIndex or resolveRecipe)
        probe();
        Recipe<?> recipe = resolveWrapper(recipeId);
        if (!(recipe instanceof MarketRecipeWrapper mrw)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Market] validateAndInit: recipe {} is not a MarketRecipeWrapper", recipeId);
            return false;
        }
        this.wrapper = mrw;

        // Get RS network
        network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Market] validateAndInit: no RS network for player {}", player.getGameProfile().getName());
            return false;
        }

        return true;
    }

    private static Recipe<?> resolveWrapper(ResourceLocation recipeId) {
        String path = recipeId.getPath();
        if (!path.startsWith("market/")) return null;
        String uuidStr = path.substring("market/".length());
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return resolveMarketEntry(uuid);
    }

    private static Recipe<?> resolveMarketEntry(UUID entryId) {
        probe();
        if (!available || marketRegistryInstance == null) return null;
        try {
            Class<?> registryClass = marketRegistryInstance.getClass();
            java.lang.reflect.Method getEntryById = Reflect.findMethod(registryClass,
                    "getEntryById", new Class<?>[]{UUID.class});
            if (getEntryById == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Market] getEntryById method not found");
                return null;
            }
            Object entry = getEntryById.invoke(marketRegistryInstance, entryId);
            if (entry == null) return null;
            return wrapEntry(entry);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Market] Failed to resolve entry {}", entryId, e);
            return null;
        }
    }

    /**
     * Resolve a Market entry from a recipeId with format
     * {@code farmingforblockheads:market/<uuid>}.
     */
    @Nullable
    public static Recipe<?> resolveMarketEntry(ResourceLocation recipeId) {
        String path = recipeId.getPath();
        if (!path.startsWith("market/")) return null;
        String uuidStr = path.substring("market/".length());
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return resolveMarketEntry(uuid);
    }

    /**
     * Create a MarketRecipeWrapper from an IMarketEntry via reflection.
     * Also used by GenericCraftPacket when resolving a plan preview.
     */
    @Nullable
    public static Recipe<?> wrapEntry(Object entry) {
        if (entry == null) return null;
        try {
            java.lang.reflect.Method getOutput = Reflect.findMethod(entry.getClass(),
                    "getOutputItem", new Class<?>[0]);
            java.lang.reflect.Method getCost = Reflect.findMethod(entry.getClass(),
                    "getCostItem", new Class<?>[0]);
            java.lang.reflect.Method getEntryId = Reflect.findMethod(entry.getClass(),
                    "getEntryId", new Class<?>[0]);
            if (getOutput == null || getCost == null || getEntryId == null) return null;

            ItemStack output = (ItemStack) getOutput.invoke(entry);
            ItemStack cost = (ItemStack) getCost.invoke(entry);
            UUID entryId = (UUID) getEntryId.invoke(entry);

            if (output.isEmpty() || cost.isEmpty()) return null;
            return new MarketRecipeWrapper(entryId, output, cost);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Market] Failed to wrap entry", e);
            return null;
        }
    }

    // ── Materials ──────────────────────────────────────────────────

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (wrapper == null) return null;
        ItemStack cost = wrapper.costItem();
        if (cost.isEmpty()) return null;
        return List.of(new IngredientSpec(Ingredient.of(cost), cost.getCount()));
    }

    // ── Virtual execution ──────────────────────────────────────────

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;

        if (wrapper == null || materials.isEmpty()) return false;

        // Materials already committed by chain.  Result is delivered by
        // collectResult — do NOT insert here or the chain flushes it twice.
        sharedLedger.commit(network, player);
        done = true;
        return true;
    }

    // ── Polling ────────────────────────────────────────────────────

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return done;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        done = false;
        if (resultInserted) {
            resultInserted = false;
            return ItemStack.EMPTY; // already inserted in tryStartSingleCraft (direct path)
        }
        if (wrapper != null) {
            return wrapper.getResultItem(player.serverLevel().registryAccess());
        }
        return ItemStack.EMPTY;
    }

    // ── Cleanup ────────────────────────────────────────────────────

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        done = false;
        resultInserted = false;
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        done = false;
        resultInserted = false;
        resetState();
    }

    @Override
    @Nullable
    public BlockPos getMachinePos() {
        return null;
    }

    // ── Plan-time warnings ─────────────────────────────────────────

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (!(recipe instanceof MarketRecipeWrapper mrw)) return warnings;

        ItemStack cost = mrw.costItem();
        ItemStack output = mrw.getResultItem(player.serverLevel().registryAccess());
        if (!cost.isEmpty() && !output.isEmpty()) {
            warnings.add(cost.getCount() + "x "
                    + cost.getHoverName().getString()
                    + " → " + output.getCount() + "x "
                    + output.getHoverName().getString());
        }
        return warnings;
    }
}
