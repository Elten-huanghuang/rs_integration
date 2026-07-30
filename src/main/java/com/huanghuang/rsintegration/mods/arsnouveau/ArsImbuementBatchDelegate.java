package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.reflection.probes.ArsNouveauReflection;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * Batch delegate for Ars Nouveau Imbuement Chamber.
 *
 * <p>The Imbuement Chamber is a single-block machine that can optionally use
 * nearby Arcane Pedestals (radius 1) for recipes that require pedestal items
 * (e.g., elemental essences). It consumes Source (a per-tile integer resource)
 * during crafting, with a soft-throttle fallback that adds 10 Source/tick when
 * nearby providers are exhausted.</p>
 *
 * <p><strong>Key lifecycle notes:</strong></p>
 * <ul>
 *   <li>Input slot 0 is both input and output — the machine overwrites it with
 *       the result when done.</li>
 *   <li>{@code m_7013_(slot, stack)} (canPlaceItem) is recipe-gated: it
 *       temporarily writes to {@code stack} field, checks {@code getRecipeNow()},
 *       and only accepts if a valid recipe forms. This makes it a natural
 *       "machine accepted input" probe, but it has side effects and must be
 *       called on the main thread.</li>
 *   <li>{@code m_8016_(slot)} (removeItem) is UNGUARDED — external hoppers/magnets
 *       can extract during crafting. RSI polls for {@code slot0 == expectedOutput}
 *       before collecting, which protects our own extraction but not against
 *       external theft.</li>
 *   <li>Minimum craft time is 100 ticks ({@code craftTicks} countdown), plus
 *       time to accumulate required Source. Timeout must account for both.</li>
 *   <li>Source scarcity is NOT a binary gate — the machine will eventually
 *       finish even with zero nearby Source (10/tick fallback). Lack of Source
 *       returns a throttle WAITING state, not FAILED.</li>
 * </ul>
 */
public final class ArsImbuementBatchDelegate extends AbstractBatchDelegate {

    // ── Instance state ────────────────────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> dimension;
    private BlockPos machinePos;
    private Recipe<?> recipe;
    private ItemStack expectedOutput;
    private int sourceCost;
    private ArsPedestalLayout pedestalLayout;
    private List<IngredientSpec> requiredMaterials;
    private final List<OwnedPedestalCatalyst> activePedestalCatalysts = new ArrayList<>();
    private boolean pedestalCatalystsInstalled;
    private boolean craftStarted;
    private long craftStartTick;

    // Without a nearby provider Ars Nouveau only adds 10 Source every 20 ticks.
    private static final int SOURCE_FALLBACK_AMOUNT = 10;
    private static final int SOURCE_FALLBACK_INTERVAL_TICKS = 20;
    private static final long CRAFT_TIMEOUT_GRACE_TICKS = 200;

    private record OwnedPedestalCatalyst(BlockPos position, int count) {}

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
            return PreparationResult.retry("Imbuement Chamber chunk not loaded");
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !ArsTileAccess.isImbuement(be)) {
            return PreparationResult.fatal("Bound machine is not an Imbuement Chamber");
        }

        Recipe<?> candidate = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (candidate == null) {
            return PreparationResult.fatal("Recipe not found: " + recipeId);
        }

        if (!ArsTileAccess.isImbuementRecipe(candidate)) {
            return PreparationResult.fatal("Recipe is not an Imbuement recipe");
        }

        // Check if machine is idle (slot 0 empty or can be overwritten)
        if (be instanceof Container container) {
            ItemStack currentSlot = container.getItem(0);
            if (!currentSlot.isEmpty()) {
                // Machine has something in the slot — check if it's leftover output
                ItemStack recipeOutput = getRecipeOutput(candidate);
                if (!ItemStack.isSameItemSameTags(currentSlot, recipeOutput)) {
                    return PreparationResult.retry("Imbuement Chamber slot occupied");
                }
            }
        }

        return validateAndInit(player, recipeId, dim, pos)
                ? PreparationResult.ready()
                : PreparationResult.retry("Imbuement Chamber validation failed");
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
        this.activePedestalCatalysts.clear();
        this.pedestalCatalystsInstalled = false;

        if (!level.hasChunkAt(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !ArsTileAccess.isImbuement(be)) {
            return false;
        }

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null || !ArsTileAccess.isImbuementRecipe(foundRecipe)) {
            return false;
        }

        this.recipe = foundRecipe;
        this.expectedOutput = getRecipeOutput(foundRecipe);
        this.sourceCost = getSourceCost(foundRecipe);

        // Capture pedestal layout
        this.pedestalLayout = ArsPedestalLayout.capture(level, pos);

        // Build required materials list
        this.requiredMaterials = buildMaterialsList(foundRecipe);

        List<Ingredient> pedestalItems = getPedestalIngredients(foundRecipe);
        int availablePedestals = pedestalLayout != null ? pedestalLayout.pedestalCount() : 0;
        if (!ArsImbuementMaterials.hasPedestalCapacity(pedestalItems, availablePedestals)) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-ArsImbuement] Rejecting machine with insufficient pedestals: recipe={}, need={}, have={}",
                    recipeId, ArsImbuementMaterials.pedestalItemCount(pedestalItems),
                    availablePedestals);
            return false;
        }

        RSIntegrationMod.LOGGER.debug("[RSI-ArsImbuement] Validated: recipe={}, output={}, source={}, pedestals={}",
                recipeId, expectedOutput, sourceCost, pedestalLayout != null ? pedestalLayout.pedestalCount() : 0);

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
        if (level == null) {
            refundRejectedStart(player, player.serverLevel(), materials);
            return false;
        }

        BlockEntity be = level.getBlockEntity(machinePos);
        if (be == null || !(be instanceof Container container)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] Machine disappeared during start");
            refundRejectedStart(player, level, materials);
            return false;
        }

        // Verify slot 0 is empty
        if (!container.getItem(0).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] Slot 0 occupied at start");
            refundRejectedStart(player, level, materials);
            return false;
        }

        // Place materials: first item is input (slot 0), rest are pedestal items
        if (materials.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] No materials provided");
            return false;
        }

        ItemStack inputStack = materials.get(0);
        List<ItemStack> pedestalStacks = materials.subList(1, materials.size());

        // Place pedestal items first (if any)
        if (!pedestalStacks.isEmpty() && pedestalLayout == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] Required pedestals are unavailable at start");
            refundRejectedStart(player, level, materials);
            return false;
        }
        if (!pedestalStacks.isEmpty()) {
            if (!placePedestalItems(level, pedestalStacks)) {
                RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] Failed to place pedestal items");
                refundRejectedStart(player, level, materials);
                return false;
            }
        }

        // Place input item in slot 0 — this triggers the recipe check via canPlaceItem
        container.setItem(0, inputStack.copy());
        be.setChanged();

        // Verify recipe is active (the tile's own recipe matching)
        if (!isRecipeActive(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] Recipe did not activate after placement");
            // Try to clean up
            container.setItem(0, ItemStack.EMPTY);
            discardPedestalCatalysts(level);
            refundRejectedStart(player, level, materials);
            return false;
        }

        this.craftStarted = true;
        this.craftStartTick = level.getGameTime();

        RSIntegrationMod.LOGGER.debug("[RSI-ArsImbuement] Craft started successfully");
        return true;
    }

    @Nonnull
    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!ArsTileAccess.isImbuement(be)) {
            return failObservation("Machine disappeared");
        }

        if (!(be instanceof Container container)) {
            return failObservation("Machine is not a container");
        }

        // Read current slot 0
        ItemStack currentStack = container.getItem(0);

        // Check if output matches expected
        if (!currentStack.isEmpty() && ItemStack.isSameItem(currentStack, expectedOutput)) {
            // Verify it's different from input (craft completed)
            int craftTicks = ArsTileAccess.imbuementCraftTicks(be);
            if (craftTicks <= 0 || craftTicks >= ArsTileAccess.IMBUEMENT_CRAFT_TICKS) {
                // craftTicks at 0 means done, >= 100 means not started or reset
                return doneObservation();
            }
        }

        // Ars Nouveau's no-provider fallback is intentionally slow. A 500 Source
        // recipe takes up to 1000 ticks to charge, while craftTicks counts down in
        // parallel. Derive the hard limit from the recipe instead of rejecting it
        // at the old fixed 300-tick boundary.
        long elapsed = level.getGameTime() - craftStartTick;
        if (elapsed > timeoutTicksForSourceCost(sourceCost)) {
            return failObservation("Craft timeout");
        }

        // Check if slot is empty (stolen by external hopper/magnet)
        if (currentStack.isEmpty()) {
            return failObservation("Input stolen from machine");
        }

        // Check Source availability (informational, not blocking)
        int currentSource = ArsTileAccess.getSource(be);
        if (currentSource < sourceCost && currentSource >= 0) {
            return workingObservation();
        }

        return workingObservation();
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!ArsTileAccess.isImbuement(be) || !(be instanceof Container container)) {
            return false;
        }

        ItemStack currentStack = container.getItem(0);
        if (!currentStack.isEmpty() && ItemStack.isSameItem(currentStack, expectedOutput)) {
            int craftTicks = ArsTileAccess.imbuementCraftTicks(be);
            return craftTicks <= 0 || craftTicks >= ArsTileAccess.IMBUEMENT_CRAFT_TICKS;
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

        ItemStack result = container.removeItem(0, 64);
        be.setChanged();

        // Clear pedestal items if any
        RSIntegrationMod.LOGGER.debug("[RSI-ArsImbuement] Collected result: {}", result);
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        RSIntegrationMod.LOGGER.debug("[RSI-ArsImbuement] Clearing machine state");

        // Try to recover items if craft didn't start or failed early
        if (be instanceof Container container) {
            ItemStack remaining = container.removeItem(0, 64);
            if (!usingSharedLedger && !remaining.isEmpty() && player != null) {
                player.addItem(remaining);
            }
        }

        ServerLevel level = resolveMachineLevel(player);
        if (level != null) {
            discardPedestalCatalysts(level);
        }
    }

    @Override
    public void onBatchFinished(@Nonnull ServerPlayer player) {
        RSIntegrationMod.LOGGER.debug("[RSI-ArsImbuement] Batch finished successfully");
        // Reusable pedestal catalysts are returned by releaseReusableMaterials.
    }

    @Override
    public void releaseReusableMaterials(@Nonnull ServerPlayer player) {
        ServerLevel level = getLevel();
        if (level == null) return;

        int recovered = 0;
        for (OwnedPedestalCatalyst owned : List.copyOf(activePedestalCatalysts)) {
            BlockEntity pedestalBe = level.getBlockEntity(owned.position());
            if (!(pedestalBe instanceof Container pedestalContainer)) continue;
            ItemStack catalyst = pedestalContainer.removeItem(0, owned.count());
            pedestalBe.setChanged();
            if (catalyst.isEmpty()) continue;
            recovered += catalyst.getCount();
            returnCatalyst(player, level, catalyst);
        }
        activePedestalCatalysts.clear();
        pedestalCatalystsInstalled = false;
        RSIntegrationMod.LOGGER.debug("[RSI-ArsImbuement] Recovered {} pedestal catalyst item(s)", recovered);
    }

    @Nonnull
    @Override
    public BlockPos getMachinePos() {
        return machinePos;
    }

    @Nullable
    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // Imbuement Chamber: one operation per machine, pedestals are support blocks
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
        // ImbuementRecipe.getResultItem() returns EMPTY, must read `output` field
        return Reflect.<ItemStack>getField(recipe, "output")
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
    }

    private int getSourceCost(Recipe<?> recipe) {
        return Reflect.<Integer>getField(recipe, "source")
                .orElse(0);
    }

    private List<IngredientSpec> buildMaterialsList(Recipe<?> recipe) {
        Ingredient input = Reflect.<Ingredient>getField(recipe, "input").orElse(Ingredient.EMPTY);
        return ArsImbuementMaterials.build(input, getPedestalIngredients(recipe));
    }

    private List<Ingredient> getPedestalIngredients(Recipe<?> recipe) {
        return Reflect.<List<Ingredient>>getField(recipe, "pedestalItems").orElse(List.of());
    }

    private boolean placePedestalItems(ServerLevel level, List<ItemStack> pedestalStacks) {
        if (pedestalLayout == null) return false;

        List<BlockPos> positions = pedestalLayout.pedestalPositions();
        if (positions.size() < pedestalStacks.size()) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] Not enough pedestals: need {}, have {}",
                    pedestalStacks.size(), positions.size());
            return false;
        }

        if (pedestalCatalystsInstalled) {
            for (int i = 0; i < pedestalStacks.size(); i++) {
                BlockEntity pedestalBe = level.getBlockEntity(positions.get(i));
                if (!(pedestalBe instanceof Container pedestalContainer)
                        || !ItemStack.isSameItemSameTags(
                        pedestalContainer.getItem(0), pedestalStacks.get(i))) {
                    return false;
                }
            }
            return true;
        }

        activePedestalCatalysts.clear();
        for (int i = 0; i < pedestalStacks.size(); i++) {
            BlockPos pedestalPos = positions.get(i);
            ItemStack required = pedestalStacks.get(i);
            BlockEntity pedestalBe = level.getBlockEntity(pedestalPos);
            if (pedestalBe instanceof Container pedestalContainer) {
                ItemStack installed = pedestalContainer.getItem(0);
                if (!installed.isEmpty()
                        && (!ItemStack.isSameItemSameTags(installed, required)
                        || installed.getCount() < required.getCount())) {
                    RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] Pedestal at {} is occupied", pedestalPos);
                    discardPedestalCatalysts(level);
                    return false;
                }
                if (installed.isEmpty()) {
                    pedestalContainer.setItem(0, required.copy());
                    pedestalBe.setChanged();
                }
                activePedestalCatalysts.add(new OwnedPedestalCatalyst(
                        pedestalPos, required.getCount()));
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-ArsImbuement] Pedestal at {} is not a container", pedestalPos);
                discardPedestalCatalysts(level);
                return false;
            }
        }

        pedestalCatalystsInstalled = true;
        return true;
    }

    private void discardPedestalCatalysts(ServerLevel level) {
        for (OwnedPedestalCatalyst owned : List.copyOf(activePedestalCatalysts)) {
            BlockEntity pedestalBe = level.getBlockEntity(owned.position());
            if (pedestalBe instanceof Container pedestalContainer) {
                pedestalContainer.removeItem(0, owned.count());
                pedestalBe.setChanged();
            }
        }
        activePedestalCatalysts.clear();
        pedestalCatalystsInstalled = false;
    }

    static long timeoutTicksForSourceCost(int sourceCost) {
        long sourceTicks = ((Math.max(0, sourceCost) + SOURCE_FALLBACK_AMOUNT - 1L)
                / SOURCE_FALLBACK_AMOUNT) * SOURCE_FALLBACK_INTERVAL_TICKS;
        return Math.max(ArsTileAccess.IMBUEMENT_CRAFT_TICKS, sourceTicks)
                + CRAFT_TIMEOUT_GRACE_TICKS;
    }

    private void refundRejectedStart(ServerPlayer player, ServerLevel level,
                                     List<ItemStack> materials) {
        if (network == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(
                    player, level.dimension(), machinePos);
        }
        for (ItemStack material : materials) {
            if (material == null || material.isEmpty()) continue;
            ItemStack remainder = material.copy();
            if (network != null) {
                remainder = network.insertItem(remainder, remainder.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
            if (!remainder.isEmpty()) {
                PlayerUtils.safeGiveToPlayer(player, remainder, network);
            }
        }
        RSIntegrationMod.LOGGER.debug(
                "[RSI-ArsImbuement] Refunded {} material stack(s) after rejected start",
                materials.stream().filter(stack -> stack != null && !stack.isEmpty()).count());
    }

    private void returnCatalyst(ServerPlayer player, ServerLevel level, ItemStack catalyst) {
        if (network == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), machinePos);
        }
        ItemStack remainder = catalyst.copy();
        if (network != null) {
            remainder = network.insertItem(remainder, remainder.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        }
        if (!remainder.isEmpty()) {
            PlayerUtils.safeGiveToPlayer(player, remainder, network);
        }
    }

    private boolean isRecipeActive(BlockEntity be) {
        // Check if the machine has recognized a valid recipe
        // ImbuementTile has a `getRecipeNow()` method that returns the current recipe
        return Reflect.invoke(be, "getRecipeNow").isPresent();
    }
}
