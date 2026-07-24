package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch delegate for Ars Nouveau Enchanting Apparatus.
 *
 * <p>The Enchanting Apparatus is a single-block machine that requires nearby
 * Arcane Pedestals (radius 3) to hold recipe ingredients. It consumes Source
 * during crafting and has a 210-tick craft cycle tracked via {@code counter}.</p>
 *
 * <p><strong>Key lifecycle notes:</strong></p>
 * <ul>
 *   <li>Central tile holds the reagent (catalyst) in slot 0; output appears
 *       in the same slot when done.</li>
 *   <li>{@code public boolean isCrafting} indicates active crafting.</li>
 *   <li>{@code private int counter} counts from 0 to 210+ during crafting.
 *       When {@code counter > 210}, the machine settles the result and sets
 *       {@code isCrafting = false}.</li>
 *   <li><strong>CRITICAL:</strong> {@code m_8020_(int)} (getItem) returns
 *       {@code ItemStack.EMPTY} when {@code isCrafting == true}, so we CANNOT
 *       poll slot 0 to detect completion. Must observe {@code isCrafting} and
 *       {@code counter} via reflection.</li>
 *   <li>Craft is triggered by calling {@code attemptCraft(ItemStack catalyst, @Nullable Player)}.
 *       Player can be null, so RSI calls it with null.</li>
 *   <li>Pedestals must be placed in a stable order to match the recipe's
 *       {@code pedestalItems} list. We capture the layout at validation time.</li>
 * </ul>
 */
public final class ArsApparatusBatchDelegate extends AbstractBatchDelegate {

    // ── Instance state ────────────────────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> dimension;
    private BlockPos machinePos;
    private Recipe<?> recipe;
    private ItemStack expectedOutput;
    private int sourceCost;
    private ArsPedestalLayout pedestalLayout;
    private List<IngredientSpec> requiredMaterials;
    private boolean craftStarted;
    private long craftStartTick;

    // Timeout: 210 craft ticks + margin for Source accumulation
    private static final long CRAFT_TIMEOUT_TICKS = 210 + 200;

    // ── IBatchDelegate implementation ─────────────────────────────────────────

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player,
                                     @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim,
                                     @Nonnull BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            return PreparationResult.fatal("Dimension unavailable");
        }

        if (!level.isLoaded(pos)) {
            return PreparationResult.retry("Enchanting Apparatus chunk not loaded");
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !ArsTileAccess.isApparatus(be)) {
            return PreparationResult.fatal("Bound machine is not an Enchanting Apparatus");
        }

        Recipe<?> candidate = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (candidate == null) {
            return PreparationResult.fatal("Recipe not found: " + recipeId);
        }

        if (!ArsTileAccess.isApparatusRecipe(candidate)) {
            return PreparationResult.fatal("Recipe is not an Apparatus recipe");
        }

        // Check if machine is idle
        if (ArsTileAccess.isApparatusCrafting(be)) {
            return PreparationResult.retry("Enchanting Apparatus is busy");
        }

        // Check pedestals are available
        List<BlockPos> pedestals = ArsTileAccess.pedestalPositions(be);
        if (pedestals.isEmpty()) {
            return PreparationResult.fatal("No pedestals found around Apparatus");
        }

        return validateAndInit(player, recipeId, dim, pos)
                ? PreparationResult.ready()
                : PreparationResult.retry("Apparatus validation failed");
    }

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player,
                                   @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim,
                                   @Nonnull BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) return false;

        this.player = player;
        this.dimension = level.dimension();
        this.machinePos = pos;
        this.craftStarted = false;
        this.craftStartTick = 0;

        ChunkUtils.loadChunk(level, pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !ArsTileAccess.isApparatus(be)) {
            return false;
        }

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null || !ArsTileAccess.isApparatusRecipe(foundRecipe)) {
            return false;
        }

        this.recipe = foundRecipe;
        this.expectedOutput = getRecipeOutput(foundRecipe);
        this.sourceCost = getSourceCost(foundRecipe);

        // Capture pedestal layout (radius 3)
        this.pedestalLayout = ArsPedestalLayout.capture(level, pos);
        if (this.pedestalLayout == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Failed to capture pedestal layout");
            return false;
        }

        // Build required materials list
        this.requiredMaterials = buildMaterialsList(foundRecipe);

        RSIntegrationMod.LOGGER.debug("[RSI-ArsApparatus] Validated: recipe={}, output={}, source={}, pedestals={}",
                recipeId, expectedOutput, sourceCost, pedestalLayout.pedestalCount());

        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return requiredMaterials;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                        @Nonnull List<ItemStack> materials,
                                        @Nonnull ExtractionLedger sharedLedger) {
        ServerLevel level = getLevel();
        if (level == null) return false;

        BlockEntity be = level.getBlockEntity(machinePos);
        if (be == null || !(be instanceof Container container)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Machine disappeared during start");
            return false;
        }

        // Verify machine is idle
        if (ArsTileAccess.isApparatusCrafting(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Machine busy at start");
            return false;
        }

        // Verify central slot is empty
        ItemStack centralSlot = container.getItem(0);
        if (!centralSlot.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Central slot occupied");
            return false;
        }

        if (materials.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] No materials provided");
            return false;
        }

        // First item is reagent (catalyst), rest are pedestal items
        ItemStack reagent = materials.get(0);
        List<ItemStack> pedestalStacks = materials.subList(1, materials.size());

        // Place pedestal items first (reversible if fails)
        if (!placePedestalItems(level, pedestalStacks)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Failed to place pedestal items");
            return false;
        }

        // Place reagent in central slot
        container.setItem(0, reagent.copy());
        be.setChanged();

        // Call attemptCraft(catalyst, null) via reflection
        boolean craftStarted = Reflect.invoke(be, "attemptCraft",
                new Class<?>[]{ItemStack.class, net.minecraft.world.entity.player.Player.class},
                reagent.copy(), null).isPresent();

        if (!craftStarted) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] attemptCraft returned false");
            // Clean up
            container.setItem(0, ItemStack.EMPTY);
            clearPedestalItems(level);
            return false;
        }

        // Verify isCrafting flag is now true
        if (!ArsTileAccess.isApparatusCrafting(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] isCrafting not set after attemptCraft");
            container.setItem(0, ItemStack.EMPTY);
            clearPedestalItems(level);
            return false;
        }

        this.craftStarted = true;
        this.craftStartTick = level.getGameTime();

        RSIntegrationMod.LOGGER.debug("[RSI-ArsApparatus] Craft started successfully");
        return true;
    }

    @Nonnull
    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!ArsTileAccess.isApparatus(be)) {
            return failObservation("Machine disappeared");
        }

        // Check timeout
        long elapsed = level.getGameTime() - craftStartTick;
        if (elapsed > CRAFT_TIMEOUT_TICKS) {
            return failObservation("Craft timeout");
        }

        // Read crafting state
        boolean isCrafting = ArsTileAccess.isApparatusCrafting(be);
        int counter = ArsTileAccess.apparatusCounter(be);

        if (counter < 0) {
            // Reflection failed
            return failObservation("Cannot read craft state");
        }

        // Crafting cycle: counter goes from 0 to 210+, then isCrafting becomes false
        if (isCrafting && counter <= ArsTileAccess.APPARATUS_CRAFT_LENGTH) {
            // Still crafting
            int progress = (counter * 100) / ArsTileAccess.APPARATUS_CRAFT_LENGTH;
            return workingObservation();
        }

        if (!isCrafting && counter == 0) {
            // Craft completed and reset
            // Verify output is present (but can't read slot directly during isCrafting)
            if (be instanceof Container container) {
                ItemStack result = container.getItem(0);
                if (!result.isEmpty() && ItemStack.isSameItem(result, expectedOutput)) {
                    return doneObservation();
                } else {
                    return failObservation("Expected output not found after craft");
                }
            }
            return doneObservation();
        }

        // Intermediate state: counter > 210 but isCrafting still true (settling)
        return workingObservation();
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!ArsTileAccess.isApparatus(be)) return false;

        boolean isCrafting = ArsTileAccess.isApparatusCrafting(be);
        int counter = ArsTileAccess.apparatusCounter(be);

        if (!isCrafting && counter == 0 && be instanceof Container container) {
            ItemStack result = container.getItem(0);
            return !result.isEmpty() && ItemStack.isSameItem(result, expectedOutput);
        }

        return false;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        // This delegate uses tryStartWithMaterials for chain integration
        // Single-craft mode is not supported for Ars Nouveau
        return false;
    }

    @Nonnull
    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        ServerLevel level = getLevel();
        if (level == null) return ItemStack.EMPTY;

        BlockEntity be = level.getBlockEntity(machinePos);
        if (be == null || !(be instanceof Container container)) {
            return ItemStack.EMPTY;
        }

        // Remove result from central slot
        ItemStack result = container.removeItem(0, 64);
        be.setChanged();

        // Collect any remaining items from pedestals (container remainders)
        collectPedestalRemainders(level);

        RSIntegrationMod.LOGGER.debug("[RSI-ArsApparatus] Collected result: {}", result);
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        RSIntegrationMod.LOGGER.debug("[RSI-ArsApparatus] Clearing machine state");

        // Try to recover items
        if (be instanceof Container container) {
            ItemStack remaining = container.removeItem(0, 64);
            if (!remaining.isEmpty() && player != null) {
                player.addItem(remaining);
            }
        }

        // Clear pedestals
        ServerLevel level = resolveMachineLevel(player);
        if (level != null) {
            clearPedestalItems(level);
        }
    }

    @Override
    public void onBatchFinished(@Nonnull ServerPlayer player) {
        RSIntegrationMod.LOGGER.debug("[RSI-ArsApparatus] Batch finished successfully");
        // Cleanup done in collectResult
    }

    @Nonnull
    @Override
    public BlockPos getMachinePos() {
        return machinePos;
    }

    @Nullable
    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // Apparatus: one operation per machine, pedestals are support blocks
        List<BlockPos> supportOffsets = new ArrayList<>();
        if (pedestalLayout != null) {
            for (BlockPos pedestalPos : pedestalLayout.pedestalPositions()) {
                supportOffsets.add(pedestalPos.subtract(machinePos));
            }
        }

        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.MACHINE_LOCAL,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                supportOffsets
        );
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    @Nullable
    private ServerLevel getLevel() {
        if (player == null || dimension == null) return null;
        return player.server.getLevel(dimension);
    }

    private ItemStack getRecipeOutput(Recipe<?> recipe) {
        // EnchantingApparatusRecipe has a `result` field
        return Reflect.<ItemStack>getField(recipe, "result")
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
    }

    private int getSourceCost(Recipe<?> recipe) {
        return Reflect.<Integer>getField(recipe, "sourceCost")
                .orElse(0);
    }

    private List<IngredientSpec> buildMaterialsList(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        // Reagent (catalyst in central slot)
        Reflect.<Ingredient>getField(recipe, "reagent").ifPresent(ing -> {
            if (!ing.isEmpty()) {
                specs.add(new IngredientSpec(ing, 1, DemandRole.CATALYST));
            }
        });

        // Pedestal items
        Reflect.<List<Ingredient>>getField(recipe, "pedestalItems").ifPresent(pedestalList -> {
            if (pedestalList != null) {
                for (Ingredient ing : pedestalList) {
                    if (!ing.isEmpty()) {
                        specs.add(new IngredientSpec(ing, 1, DemandRole.CONSUMED));
                    }
                }
            }
        });

        return specs;
    }

    private boolean placePedestalItems(ServerLevel level, List<ItemStack> pedestalStacks) {
        if (pedestalLayout == null) return false;

        List<BlockPos> positions = pedestalLayout.pedestalPositions();
        if (positions.size() < pedestalStacks.size()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Not enough pedestals: need {}, have {}",
                    pedestalStacks.size(), positions.size());
            return false;
        }

        for (int i = 0; i < pedestalStacks.size(); i++) {
            BlockPos pedestalPos = positions.get(i);
            BlockEntity pedestalBe = level.getBlockEntity(pedestalPos);
            if (pedestalBe instanceof Container pedestalContainer) {
                pedestalContainer.setItem(0, pedestalStacks.get(i).copy());
                pedestalBe.setChanged();
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Pedestal at {} is not a container", pedestalPos);
                return false;
            }
        }

        return true;
    }

    private void clearPedestalItems(ServerLevel level) {
        if (pedestalLayout == null) return;

        for (BlockPos pedestalPos : pedestalLayout.pedestalPositions()) {
            BlockEntity pedestalBe = level.getBlockEntity(pedestalPos);
            if (pedestalBe instanceof Container pedestalContainer) {
                pedestalContainer.setItem(0, ItemStack.EMPTY);
                pedestalBe.setChanged();
            }
        }
    }

    private void collectPedestalRemainders(ServerLevel level) {
        if (pedestalLayout == null) return;

        // Apparatus recipes may leave container remainder items on pedestals
        // (handled by getCraftingRemainingItem())
        // For now, we clear them. If remainders need to be collected, add logic here.
        clearPedestalItems(level);
    }
}
