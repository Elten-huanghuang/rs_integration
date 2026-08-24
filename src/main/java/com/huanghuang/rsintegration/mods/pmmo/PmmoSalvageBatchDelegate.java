package com.huanghuang.rsintegration.mods.pmmo;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Synchronous logical salvage anchored to the configured, bound PMMO block. */
public final class PmmoSalvageBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos boundPos;
    private PmmoSalvageRecipeWrapper recipe;
    private final List<ItemStack> results = new ArrayList<>();
    private int attempts;
    private int failedTargetAttempts;
    private boolean done;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        PmmoSalvageRecipeWrapper found = PmmoSalvageCatalog.byId(recipeId);
        BlockPos root = resolved == null ? null : PmmoSalvageStructure.resolveRoot(resolved, pos);
        if (resolved == null) {
            return PreparationResult.fatal("PMMO salvage dimension is unavailable");
        }
        if (found == null) {
            return PreparationResult.fatal("PMMO salvage recipe no longer exists: " + recipeId);
        }
        if (root == null || !PmmoSalvageStructure.isValid(resolved, root)) {
            return PreparationResult.fatal("PMMO salvage structure is not valid",
                    Component.translatable("rsi.pmmo.error.structure_invalid"));
        }
        PmmoSalvageRuntime.Eligibility eligibility = PmmoSalvageRuntime.eligibility(player, found);
        if (eligibility.state() == PmmoSalvageRuntime.EligibilityState.LEVEL_TOO_LOW) {
            return PreparationResult.fatal("PMMO salvage level requirement is not met",
                    Component.translatable("rsi.pmmo.error.level_required",
                            PmmoSalvageRuntime.requirementSummary(eligibility)));
        }
        if (!eligibility.eligible()) {
            return PreparationResult.fatal("PMMO level lookup failed",
                    Component.translatable("rsi.pmmo.error.level_lookup_failed"));
        }
        this.level = resolved;
        this.dimension = resolved.dimension();
        this.boundPos = root;
        this.recipe = found;
        this.results.clear();
        this.attempts = 0;
        this.failedTargetAttempts = 0;
        this.done = false;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        markCraftStarted();
        return PreparationResult.ready();
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return player != null && recipe != null && level != null && boundPos != null
                && PmmoSalvageStructure.isValid(level, boundPos)
                && PmmoSalvageRuntime.isTargetEligible(player, recipe);
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        return Math.max(0, remainingOperations);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : new PmmoSalvageRecipeHandler().getIngredients(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        if (!validateExecutionContext(player) || materials.size() != 1
                || materials.get(0) == null || materials.get(0).isEmpty()) return false;
        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.attempts = materials.get(0).getCount();
        try {
            PmmoSalvageRuntime.Execution execution =
                    PmmoSalvageRuntime.execute(player, recipe, attempts);
            results.addAll(execution.outputs());
            failedTargetAttempts = execution.failedTargetAttempts();
            done = failedTargetAttempts == 0;
            if (!done) phase = CraftPhase.FAILED;
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            RSIntegrationMod.LOGGER.error("[RSI-PMMO] Salvage execution failed before publication", exception);
            return false;
        }
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel ignoredLevel, BlockEntity ignoredEntity) {
        return done;
    }

    @Nonnull
    @Override
    public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        List<ItemStack> copy = results.stream().map(ItemStack::copy).toList();
        results.clear();
        return copy;
    }

    @Nonnull
    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (results.isEmpty()) return ItemStack.EMPTY;
        return results.remove(0);
    }

    @Override
    public boolean failureConsumesInputs(CraftObservation observation) {
        return observation.phase() == CraftPhase.FAILED && attempts > 0;
    }

    @Nullable
    @Override
    public Component craftFailureMessage(CraftObservation observation) {
        return Component.translatable("rsi.pmmo.error.salvage_failed",
                failedTargetAttempts, attempts, recipe == null
                        ? Component.translatable("rsi.plan.unknown_item")
                        : recipe.getResultItem(level.registryAccess()).getHoverName());
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        results.clear();
        done = false;
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
    }

    @Nullable
    @Override
    public BlockPos getMachinePos() {
        // The operation is synchronous and has no physical inventory to poll.
        return null;
    }

    @Override
    public BlockPos getOperationMachinePos(@Nonnull BlockPos ignoredBoundPos) {
        return boundPos == null ? ignoredBoundPos : boundPos;
    }
}
