package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.PlayerUtils;
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
 *   <li>Writing the reagent through {@code setItem} invokes Ars Nouveau's
 *       {@code attemptCraft(reagent, null)} override and starts the craft.</li>
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
    private final List<BlockPos> activePedestalPositions = new ArrayList<>();
    private boolean craftStarted;
    private long craftStartTick;
    private ItemStack pendingReagent = ItemStack.EMPTY;
    private long startRetryDeadline;
    private long nextStartRetryTick;
    private int startAttempts;

    // Timeout: 210 craft ticks + margin for Source accumulation
    private static final long CRAFT_TIMEOUT_TICKS = 210 + 200;
    private static final long START_RETRY_TICKS = 20;

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
        this.pendingReagent = ItemStack.EMPTY;
        this.startRetryDeadline = 0L;
        this.nextStartRetryTick = 0L;
        this.startAttempts = 0;
        this.activePedestalPositions.clear();

        if (!level.hasChunkAt(pos)) return false;
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
    public void setTargetOutput(@Nullable ItemStack target) {
        super.setTargetOutput(target);
        if (recipe == null || !ArsDynamicApparatusRecipe.isSupported(recipe)) return;
        this.expectedOutput = ArsDynamicApparatusRecipe.validatedOutput(recipe, target);
        this.requiredMaterials = ArsDynamicApparatusRecipe.buildMaterials(recipe, target);
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                        @Nonnull List<ItemStack> materials,
                                        @Nonnull ExtractionLedger sharedLedger) {
        ServerLevel level = getLevel();
        if (level == null) {
            refundRejectedStart(player, player.serverLevel(), materials);
            return false;
        }

        BlockEntity be = level.getBlockEntity(machinePos);
        if (be == null || !(be instanceof Container container)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Machine disappeared during start");
            refundRejectedStart(player, level, materials);
            return false;
        }

        // Verify machine is idle
        if (ArsTileAccess.isApparatusCrafting(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Machine busy at start");
            refundRejectedStart(player, level, materials);
            return false;
        }

        // The crafting flag is authoritative. When it is clear, any central item is a
        // completed output or abandoned reagent and can be returned before this run.
        ItemStack centralSlot = container.getItem(0);
        if (!centralSlot.isEmpty()) {
            ItemStack recovered = container.removeItem(0, centralSlot.getCount());
            be.setChanged();
            returnExistingCentralItem(player, level, recovered);
        }

        if (materials.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] No materials provided");
            return false;
        }

        // Ars refuses to start while any scanned pedestal contains an old item.
        // Recover every stale stack before placing this operation's materials.
        if (network == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), machinePos);
        }
        int recovered = ArsPedestalRecovery.recover(
                level, pedestalLayout.pedestalPositions(), player, network);
        if (recovered > 0) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-ArsApparatus] Recovered {} occupied pedestal stack(s) into RS",
                    recovered);
        }

        // First item is reagent (catalyst), rest are pedestal items
        ItemStack reagent = ArsDynamicApparatusRecipe.isSupported(recipe)
                ? ArsDynamicApparatusRecipe.prepareMachineInput(
                        recipe, materials.get(0), expectedOutput)
                : materials.get(0);
        if (reagent.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Failed to prepare dynamic reagent");
            refundRejectedStart(player, level, materials);
            return false;
        }
        List<ItemStack> pedestalStacks = materials.subList(1, materials.size());

        // Place pedestal items first (reversible if fails)
        if (!placePedestalItems(level, pedestalStacks)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Failed to place pedestal items");
            refundRejectedStart(player, level, materials);
            return false;
        }

        // Place reagent in central slot
        container.setItem(0, reagent.copy());
        be.setChanged();

        // EnchantingApparatusTile.setItem invokes attemptCraft(reagent, null).
        // Verify that the native insertion path accepted and started the recipe.
        if (!ArsTileAccess.isApparatusCrafting(be)) {
            this.pendingReagent = reagent.copy();
            this.startRetryDeadline = level.getGameTime() + START_RETRY_TICKS;
            this.nextStartRetryTick = level.getGameTime() + 1L;
            this.startAttempts = 1;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-ArsApparatus] Native insertion did not start immediately; retrying for {} tick(s)",
                    START_RETRY_TICKS);
            return true;
        }

        markNativeCraftStarted(level);
        return true;
    }

    @Nonnull
    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!ArsTileAccess.isApparatus(be)) {
            return failObservation("Machine disappeared");
        }

        if (!craftStarted) {
            return observePendingStart(level, be);
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
                if (matchesExpectedOutput(result)) {
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

    private CraftObservation observePendingStart(ServerLevel level, BlockEntity be) {
        if (ArsTileAccess.isApparatusCrafting(be)) {
            markNativeCraftStarted(level);
            return workingObservation();
        }
        long now = level.getGameTime();
        if (retryWindowExpired(now, startRetryDeadline)) {
            return failObservation("Enchanting Apparatus rejected start after "
                    + startAttempts + " attempt(s)");
        }
        if (now < nextStartRetryTick) return workingObservation();
        if (!(be instanceof Container container) || pendingReagent.isEmpty()) {
            return failObservation("Cannot retry Enchanting Apparatus start");
        }
        ItemStack central = container.getItem(0);
        if (!central.isEmpty() && !ItemStack.isSameItemSameTags(central, pendingReagent)) {
            return failObservation("Enchanting Apparatus central slot changed before retry");
        }

        container.setItem(0, ItemStack.EMPTY);
        container.setItem(0, pendingReagent.copy());
        be.setChanged();
        startAttempts++;
        nextStartRetryTick = now + 1L;
        if (ArsTileAccess.isApparatusCrafting(be)) {
            markNativeCraftStarted(level);
        }
        return workingObservation();
    }

    private void markNativeCraftStarted(ServerLevel level) {
        this.craftStarted = true;
        this.craftStartTick = level.getGameTime();
        this.pendingReagent = ItemStack.EMPTY;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-ArsApparatus] Craft started successfully after {} attempt(s)",
                Math.max(1, startAttempts));
    }

    static boolean retryWindowExpired(long now, long deadline) {
        return now >= deadline;
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!ArsTileAccess.isApparatus(be)) return false;

        boolean isCrafting = ArsTileAccess.isApparatusCrafting(be);
        int counter = ArsTileAccess.apparatusCounter(be);

        if (!isCrafting && counter == 0 && be instanceof Container container) {
            ItemStack result = container.getItem(0);
            return matchesExpectedOutput(result);
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
            if (!usingSharedLedger && !remaining.isEmpty() && player != null) {
                player.addItem(remaining);
            }
        }

        // Clear pedestals
        ServerLevel level = resolveMachineLevel(player);
        if (level != null) {
            discardPedestalItems(level);
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
        if (ArsRecipeClassifier.TYPE_ENCHANTMENT.equals(ArsTileAccess.recipeTypeId(recipe))) {
            return ArsDynamicApparatusRecipe.canonicalEnchantmentOutput(recipe);
        }
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
        if (ArsDynamicApparatusRecipe.isSupported(recipe)) {
            ItemStack target = expectedOutput == null || expectedOutput.isEmpty()
                    ? null : expectedOutput;
            return ArsDynamicApparatusRecipe.buildMaterials(recipe, target);
        }
        Ingredient reagent = Reflect.<Ingredient>getField(recipe, "reagent").orElse(Ingredient.EMPTY);
        List<Ingredient> pedestalItems = Reflect.<List<Ingredient>>getField(recipe, "pedestalItems")
                .orElse(List.of());
        return ArsApparatusMaterials.build(reagent, pedestalItems);
    }

    private boolean matchesExpectedOutput(ItemStack result) {
        if (result == null || result.isEmpty() || expectedOutput == null || expectedOutput.isEmpty()) {
            return false;
        }
        return ArsDynamicApparatusRecipe.isSupported(recipe)
                ? ItemStack.isSameItemSameTags(result, expectedOutput)
                : ItemStack.isSameItem(result, expectedOutput);
    }

    private boolean placePedestalItems(ServerLevel level, List<ItemStack> pedestalStacks) {
        if (pedestalLayout == null) return false;

        List<BlockPos> positions = pedestalLayout.pedestalPositions();
        if (positions.size() < pedestalStacks.size()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Not enough pedestals: need {}, have {}",
                    pedestalStacks.size(), positions.size());
            return false;
        }

        activePedestalPositions.clear();
        for (BlockPos pedestalPos : positions) {
            BlockEntity pedestalBe = level.getBlockEntity(pedestalPos);
            if (pedestalBe instanceof Container pedestalContainer
                    && !pedestalContainer.getItem(0).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Pedestal at {} is occupied", pedestalPos);
                return false;
            }
        }

        for (int i = 0; i < pedestalStacks.size(); i++) {
            BlockPos pedestalPos = positions.get(i);
            BlockEntity pedestalBe = level.getBlockEntity(pedestalPos);
            if (pedestalBe instanceof Container pedestalContainer) {
                pedestalContainer.setItem(0, pedestalStacks.get(i).copy());
                pedestalBe.setChanged();
                activePedestalPositions.add(pedestalPos);
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Pedestal at {} is not a container", pedestalPos);
                discardPedestalItems(level);
                return false;
            }
        }

        return true;
    }

    private void discardPedestalItems(ServerLevel level) {
        for (BlockPos pedestalPos : List.copyOf(activePedestalPositions)) {
            BlockEntity pedestalBe = level.getBlockEntity(pedestalPos);
            if (pedestalBe instanceof Container pedestalContainer) {
                pedestalContainer.setItem(0, ItemStack.EMPTY);
                pedestalBe.setChanged();
            }
        }
        activePedestalPositions.clear();
    }

    private void refundRejectedStart(ServerPlayer player, ServerLevel level,
                                     List<ItemStack> materials) {
        if (!ownsRejectedStartRefund(usingSharedLedger)) return;
        if (network == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(
                    player, level.dimension(), machinePos);
        }
        for (ItemStack material : materials) {
            if (material == null || material.isEmpty()) continue;
            ItemStack remainder = insertIntoStorage(player, material, false);
            if (!remainder.isEmpty()) {
                PlayerUtils.safeGiveToPlayer(player, remainder, network);
            }
        }
        RSIntegrationMod.LOGGER.debug("[RSI-ArsApparatus] Refunded {} material stack(s) after rejected start",
                materials.stream().filter(stack -> stack != null && !stack.isEmpty()).count());
    }

    private void returnExistingCentralItem(ServerPlayer player, ServerLevel level,
                                           ItemStack stack) {
        if (stack.isEmpty()) return;
        if (network == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(
                    player, level.dimension(), machinePos);
        }
        ItemStack remainder = insertIntoStorage(player, stack, false);
        if (!remainder.isEmpty()) PlayerUtils.safeGiveToPlayer(player, remainder, network);
    }

    static boolean ownsRejectedStartRefund(boolean usingSharedLedger) {
        return !usingSharedLedger;
    }

    private void collectPedestalRemainders(ServerLevel level) {
        // Apparatus recipes may leave container remainder items on pedestals
        // (handled by getCraftingRemainingItem())
        for (BlockPos pedestalPos : List.copyOf(activePedestalPositions)) {
            BlockEntity pedestalBe = level.getBlockEntity(pedestalPos);
            if (pedestalBe instanceof Container pedestalContainer) {
                ItemStack stack = pedestalContainer.getItem(0);
                if (!stack.isEmpty()) {
                    // Check if this item has a crafting remainder (e.g., bucket -> empty bucket)
                    ItemStack remainder = stack.getCraftingRemainingItem();
                    if (!remainder.isEmpty()) {
                        // Store remainder for later collection
                        // For now, leave it on the pedestal - the chain will handle collection
                        RSIntegrationMod.LOGGER.debug("[RSI-ArsApparatus] Found remainder on pedestal: {}", remainder);
                    }
                }
                // Clear the pedestal
                pedestalContainer.setItem(0, ItemStack.EMPTY);
                pedestalBe.setChanged();
            }
        }
        activePedestalPositions.clear();
    }
}
