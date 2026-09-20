package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.util.ChunkUtils;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.MachineSlotOwnershipPolicy;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.PhysicalInputRecovery;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Batch delegate for vanilla machine cooking (Furnace, Blast Furnace, Smoker). */
public final class VanillaMachineBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private ItemStack pendingResult;
    private boolean craftDone;

    // FURNACE path state
    private AbstractFurnaceBlockEntity furnaceBE;
    private MachineKind kind;
    private boolean furnaceInventoryLease;
    private ItemStack baselineFurnaceFuel = ItemStack.EMPTY;
    private ItemStack suppliedFurnaceInput = ItemStack.EMPTY;
    private int suppliedFurnaceInputCount;
    private ItemStack suppliedFurnaceFuel = ItemStack.EMPTY;
    private int suppliedFurnaceFuelCount;
    /** Batch selected before reservation; never used as a completion contract. */
    private int plannedFurnaceOperations = 1;
    /** Immutable logical operation count for the currently running furnace lease. */
    private int activeFurnaceOperations = 1;

    private enum MachineKind {
        FURNACE,
        CAMPFIRE,
        VIRTUAL
    }

    // CAMPFIRE path state
    private int campfireSlot = -1;
    private Object campfireBE;
    private ItemStack suppliedCampfireInput = ItemStack.EMPTY;
    private static final java.lang.reflect.Field CAMPFIRE_ITEMS;
    private static final java.lang.reflect.Field CAMPFIRE_COOKING_PROGRESS;
    private static final java.lang.reflect.Field CAMPFIRE_COOKING_TIME;

    static {
        java.lang.reflect.Field items = null;
        java.lang.reflect.Field cookingProgress = null;
        java.lang.reflect.Field cookingTime = null;
        try {
            Class<?> cfb = Class.forName(
                    "net.minecraft.world.level.block.entity.CampfireBlockEntity");
            items = resolveCampfireField(cfb, "items", "f_59042_");
            cookingProgress = resolveCampfireField(cfb, "cookingProgress", "f_59043_");
            cookingTime = resolveCampfireField(cfb, "cookingTime", "f_59044_");
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] Campfire class probe failed", e); }
        CAMPFIRE_ITEMS = items;
        CAMPFIRE_COOKING_PROGRESS = cookingProgress;
        CAMPFIRE_COOKING_TIME = cookingTime;
    }

    private static java.lang.reflect.Field resolveCampfireField(Class<?> clazz, String official, String srg) {
        java.lang.reflect.Field field = findDeclaredField(clazz, official, srg);
        if (field == null) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Vanilla] Campfire field unavailable: {} (tried {}, {})",
                    official, official, srg);
        }
        return field;
    }

    @Nullable
    static java.lang.reflect.Field findDeclaredField(Class<?> clazz, String... candidateNames) {
        for (String name : candidateNames) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static boolean campfireFieldsAvailable() {
        return CAMPFIRE_ITEMS != null
                && CAMPFIRE_COOKING_PROGRESS != null
                && CAMPFIRE_COOKING_TIME != null;
    }

    private static final java.lang.reflect.Field LIT_TIME_FIELD = resolveLitTimeField();

    private static java.lang.reflect.Field resolveLitTimeField() {
        for (String name : new String[]{"litTime", "f_58316_"}) {
            try {
                java.lang.reflect.Field f = AbstractFurnaceBlockEntity.class.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        RSIntegrationMod.LOGGER.warn("[RSI-Vanilla] litTime field not found (no SRG match)");
        return null;
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(ServerLevel level, BlockPos pos) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        if (blockId == null) return false;
        String path = blockId.getPath();
        return path.contains("stonecutter")
                || path.contains("smithing_table")
                || path.contains("campfire");
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.player = player;
        this.myPos = pos;
        this.pendingResult = ItemStack.EMPTY;
        this.craftDone = false;
        this.furnaceBE = null;
        this.plannedFurnaceOperations = 1;
        this.activeFurnaceOperations = 1;
        resetFurnaceOwnership();

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        if (!level.hasChunkAt(pos)) return false;

        this.myLevel = level;
        this.myDim = level.dimension();

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        // CraftTweaker wraps vanilla recipes with IDs like
        //   crafttweaker:minecraft.coast_armor_trim_smithing_template
        // Recover the original key: replace first dot in path with colon.
        if (found == null && "crafttweaker".equals(recipeId.getNamespace())) {
            String path = recipeId.getPath();
            int dot = path.indexOf('.');
            if (dot > 0) {
                ResourceLocation vanillaKey = new ResourceLocation(
                        path.substring(0, dot), path.substring(dot + 1));
                found = level.getRecipeManager().byKey(vanillaKey).orElse(null);
            }
        }
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            String blockId = ForgeRegistries.BLOCKS
                    .getKey(level.getBlockState(pos).getBlock()).toString();
            // Smithing Table and Stonecutter have no BE (or BE is unloaded)
            // — always VIRTUAL. Campfire also falls back to VIRTUAL when
            // the chunk is not loaded.
            if (blockId.contains("smithing_table") || blockId.contains("campfire")
                    || blockId.contains("stonecutter")) {
                this.kind = MachineKind.VIRTUAL;
            } else if (recipe instanceof AbstractCookingRecipe
                    && CookingMachineFamily.fromRecipe(recipe).matches(
                            level.getBlockState(pos).getBlock())) {
                // VIRTUAL fallback for furnace/blast/smoker when the chunk isn't
                // fully loaded. Only accept if the block ID matches the recipe type
                // so a smithing table doesn't steal smelting recipes.
                this.kind = MachineKind.VIRTUAL;
            } else {
                // Wrong machine for this recipe — return false so AsyncChain
                // tries the next bound machine.
                return false;
            }
        } else if (be instanceof AbstractFurnaceBlockEntity fbe) {
            // Non-cooking recipes (smithing, stonecutting) can't run on a furnace,
            // but the furnace binding can still serve as a VIRTUAL proxy — the
            // craft is computed directly without machine interaction.
            if (!(recipe instanceof AbstractCookingRecipe)) {
                this.kind = MachineKind.VIRTUAL;
                return true;
            }
            CookingMachineFamily expectedFamily = CookingMachineFamily.fromRecipe(recipe);
            CookingMachineFamily actualFamily = CookingMachineFamily.fromBlock(
                    level.getBlockState(pos).getBlock());
            if (expectedFamily == CookingMachineFamily.UNKNOWN || expectedFamily != actualFamily) {
                RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] Cooking family mismatch: recipe={} expected={} actual={} at {}",
                        recipe.getId(), expectedFamily, actualFamily, myPos);
                return false;
            }
            BrickFurnaceCompat.Eligibility brickEligibility = BrickFurnaceCompat.canExecute(be, recipe);
            if (!brickEligibility.allowed()) {
                RSIntegrationMod.LOGGER.debug("[RSI-BrickFurnace] Rejected recipe {} at {}: {}",
                        recipe.getId(), myPos, brickEligibility.detail());
                player.sendSystemMessage(Component.translatable(
                        "rsi.brickfurnace.error.recipe_rejected", brickEligibility.detail()));
                return false;
            }

            // A finished manual output is drained at dispatch time. An occupied
            // input means the manual operation still owns this furnace, so the
            // chain keeps this order in its retry queue without reserving items.
            if (!fbe.getItem(0).isEmpty()) {
                return false;
            }

            this.furnaceBE = fbe;
            this.kind = MachineKind.FURNACE;
        } else if (be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity) {
            if (!(recipe instanceof CampfireCookingRecipe)) {
                this.kind = MachineKind.VIRTUAL;
                return true;
            }
            if (!campfireFieldsAvailable()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.vanilla.error.campfire_unavailable"));
                return false;
            }
            try {
                @SuppressWarnings("unchecked")
                net.minecraft.core.NonNullList<ItemStack> items =
                        (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(be);
                // Find an empty slot
                int slot = -1;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).isEmpty()) { slot = i; break; }
                }
                if (slot < 0) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.vanilla.error.campfire_full"));
                    return false;
                }
                this.campfireBE = be;
                this.campfireSlot = slot;
                this.kind = MachineKind.CAMPFIRE;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire reflection failed", e);
                return false;
            }
        } else {
            // Other BE-backed machines: Stonecutter (has BE)
            this.kind = MachineKind.VIRTUAL;
        }

        return true;
    }

    // ── Private ledger (direct path) ───────────────────────────────

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.usingSharedLedger = false;
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null) {
                this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            }
        }
        this.ledger.setStorageEndpoint(storageEndpoint());
        this.craftDone = false;

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnace(player);
        } else if (kind == MachineKind.CAMPFIRE) {
            return tryStartCampfire(player);
        } else {
            return tryStartVirtual(player);
        }
    }

    // ── Shared ledger (chain path, getRequiredMaterials returns null/empty) ─

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player, ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null) {
                this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            }
        }
        this.craftDone = false;

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnace(player);
        } else if (kind == MachineKind.CAMPFIRE) {
            return tryStartCampfire(player);
        } else {
            return tryStartVirtual(player);
        }
    }

    // ── Pre-reserved materials (chain path, getRequiredMaterials returns specs) ─

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        return recipe instanceof SmithingTransformRecipe smithing
                ? SmithingRecipeHandler.requireDemandedOutputTag(smithing, specs, targetOutput) : specs;
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.machineSlot();
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        if (!supportsInputBuffer()) {
            plannedFurnaceOperations = 1;
            return remainingOperations > 0 ? 1 : 0;
        }
        int batch = inputBufferContract().plan(remainingOperations).operations();
        plannedFurnaceOperations = Math.max(1, batch);
        return batch;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        // Planning may run again while the delegate is being admitted. Keep it
        // separate from activeFurnaceOperations, which becomes immutable only
        // when startOperation accepts the bound input buffer.
        plannedFurnaceOperations = Math.max(1, executions);
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        if (!supportsInputBuffer()) return 1;
        int workers = Math.max(1, workerCount);
        int evenShare = Math.max(1, (totalOperations + workers - 1) / workers);
        return Math.min(inputBufferOperationLimit(), evenShare);
    }

    @Override
    public int flatBatchOperationLimit(int configuredLimit) {
        return supportsInputBuffer()
                ? Math.max(Math.max(1, configuredLimit), inputBufferOperationLimit())
                : Math.max(1, configuredLimit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsInputBuffer();
    }

    @Override
    public boolean supportsInputBuffer() {
        if (kind != MachineKind.FURNACE || furnaceBE == null || recipe == null || myLevel == null) {
            return false;
        }
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(
                myLevel.getBlockState(myPos).getBlock());
        boolean brickFurnace = BrickFurnaceCompat.isBrickFurnace(furnaceBE);
        if (!supportsFurnaceBufferTarget(blockId, brickFurnace)) return false;
        return brickFurnace
                ? brickFurnaceInputBufferEnabled()
                    && BrickFurnaceCompat.canExecute(furnaceBE, recipe).allowed()
                : vanillaFurnaceInputBufferEnabled();
    }

    static boolean supportsFurnaceBufferTarget(ResourceLocation blockId, boolean brickFurnace) {
        return blockId != null && (brickFurnace
                ? "brickfurnace".equals(blockId.getNamespace())
                : "minecraft".equals(blockId.getNamespace()));
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        List<Ingredient> ingredients = recipe.getIngredients();
        ItemStack result = computeResult();
        if (ingredients.isEmpty() || ingredients.get(0).isEmpty() || result.isEmpty()) {
            return InputBufferContract.none();
        }
        ItemStack prototype = java.util.Arrays.stream(ingredients.get(0).getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
        if (prototype.isEmpty()) return InputBufferContract.none();
        return new InputBufferContract(inputBufferOperationLimit(),
                List.of(new InputBufferContract.InputSlot(
                        "legacy:material:0", 0, prototype, 1, false,
                        furnaceInputCapacity(ingredients.get(0)))),
                outputContract().ports());
    }

    @Override
    public InputBufferPlan inputBufferPlan(int requestedOperations) {
        return inputBufferContract().plan(requestedOperations);
    }

    @Override
    public boolean tryStartWithInputBuffer(@NotNull ServerPlayer player,
                                           @NotNull InputBufferPlan plan,
                                           @NotNull ExtractionLedger sharedLedger) {
        if (!supportsInputBuffer() || plan == null || !plan.enabled()
                || plan.inputs().size() != 1 || plan.inputs().get(0).slot() != 0) {
            return false;
        }
        InputBufferPlan.InputSlot input = plan.inputs().get(0);
        if (input.stack().isEmpty() || input.perOperation() != 1
                || input.stack().getCount() != plan.operations()) {
            return false;
        }
        InputBufferPlan expected = inputBufferPlan(plan.operations());
        if (!expected.enabled() || expected.operations() != plan.operations()) return false;
        this.plannedFurnaceOperations = plan.operations();
        this.activeFurnaceOperations = plan.operations();
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null) this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        }
        this.craftDone = false;
        boolean started = tryStartFurnaceWithMaterials(List.of(input.stack()));
        if (started) {
            ExpectedProduction expectedProduction = getExpectedProduction();
            RSIntegrationMod.LOGGER.info("[RSI-Vanilla] buffered furnace start recipe={} pos={} operations={} input={} expectedOutput={}",
                    recipe == null ? "<null>" : recipe.getId(), myPos,
                    activeFurnaceOperations, input.stack().getCount(),
                    expectedProduction == null ? 0 : expectedProduction.count());
        }
        return started;
    }

    private int inputBufferOperationLimit() {
        if (recipe == null || furnaceBE == null) return 1;
        List<Ingredient> ingredients = recipe.getIngredients();
        ItemStack result = computeResult();
        if (ingredients.isEmpty() || result.isEmpty()) return 1;
        int inputCapacity = furnaceInputCapacity(ingredients.get(0));
        int outputCapacity = result.getCount() <= 0 ? 1
                : Math.min(result.getMaxStackSize(), furnaceBE.getMaxStackSize()) / result.getCount();
        return safeFurnaceBufferOperations(Integer.MAX_VALUE,
                BrickFurnaceCompat.isBrickFurnace(furnaceBE)
                        ? brickFurnaceInputBufferLimit() : vanillaFurnaceInputBufferLimit(),
                inputCapacity, outputCapacity, 1);
    }

    static int furnaceOperationsFromMaterials(List<ItemStack> materials) {
        if (materials == null || materials.isEmpty()) return 1;
        ItemStack input = materials.get(0);
        return input == null || input.isEmpty() ? 1 : Math.max(1, input.getCount());
    }

    private int furnaceInputCapacity(Ingredient ingredient) {
        int machineLimit = furnaceBE == null ? 64 : furnaceBE.getMaxStackSize();
        return java.util.Arrays.stream(ingredient.getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .mapToInt(stack -> Math.min(stack.getMaxStackSize(), machineLimit))
                .min().orElse(1);
    }

    static int safeFurnaceBufferOperations(int requested, int configuredLimit,
                                           int inputCapacity, int outputOperationCapacity,
                                           int operationsPerCycle) {
        if (requested <= 0 || configuredLimit <= 0 || inputCapacity <= 0
                || outputOperationCapacity <= 0 || operationsPerCycle <= 0) return 0;
        return Math.min(requested, Math.min(configuredLimit,
                Math.min(inputCapacity, outputOperationCapacity)));
    }

    private static boolean vanillaFurnaceInputBufferEnabled() {
        try {
            return RSIntegrationConfig.ENABLE_VANILLA_FURNACE_INPUT_BUFFER.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return true;
        }
    }

    private static int vanillaFurnaceInputBufferLimit() {
        try {
            return RSIntegrationConfig.VANILLA_FURNACE_INPUT_BUFFER_LIMIT.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return 64;
        }
    }

    private static boolean brickFurnaceInputBufferEnabled() {
        try {
            return RSIntegrationConfig.ENABLE_BRICK_FURNACE_INPUT_BUFFER.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return true;
        }
    }

    private static int brickFurnaceInputBufferLimit() {
        try {
            return RSIntegrationConfig.BRICK_FURNACE_INPUT_BUFFER_LIMIT.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return 64;
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player,
                                         List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null) {
                this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            }
        }
        this.craftDone = false;
        // Keep the completion contract aligned with the physical input that is
        // actually injected.  Some legacy/compatibility dispatch paths still
        // call this method with a pre-reserved stack of inputs; resetting the
        // contract to one in that case made the first output finish a whole
        // six-item order while the remaining inputs were still cooking.
        int injectedOperations = furnaceOperationsFromMaterials(materials);
        this.plannedFurnaceOperations = Math.max(1, injectedOperations);
        this.activeFurnaceOperations = Math.max(1, injectedOperations);
        RSIntegrationMod.LOGGER.info("[RSI-Vanilla] furnace legacy start recipe={} operations={} input={}",
                recipe == null ? "<null>" : recipe.getId(), activeFurnaceOperations,
                materials.isEmpty() ? 0 : materials.get(0).getCount());

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnaceWithMaterials(materials);
        } else if (kind == MachineKind.CAMPFIRE) {
            return tryStartCampfireWithMaterials(materials);
        } else {
            // Virtual: materials already committed by chain, compute result directly
            this.pendingResult = recipe instanceof SmithingTransformRecipe smithing
                    ? SmithingRecipeHandler.assembleTransform(smithing, materials, myLevel.registryAccess())
                    : computeResult();
            if (this.pendingResult.isEmpty()) return false;
            this.craftDone = true;
            return true;
        }
    }

    // ── FURNACE path ──────────────────────────────────────────────

    private boolean tryStartFurnace(ServerPlayer player) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return false;

        // Phase 1: Reserve input in ledger (template — not yet extracted from RS)
        Ingredient input = ingredients.get(0);
        ItemStack inputTemplate = ItemStack.EMPTY;
        if (!input.isEmpty()) {
            ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;
            inputTemplate = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, input, 1, activeLedger);
            if (inputTemplate.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(input)));
                return false;
            }
        }

        // Phase 2: Commit BEFORE placing — commit failure leaves the furnace untouched
        if (!acquireFurnaceInventory()) return false;

        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", "Extraction commit failed"));
                return false;
            }
        }

        // Phase 3: Place REAL input (post-commit) on furnace
        if (!inputTemplate.isEmpty()) {
            furnaceBE.setItem(0, inputTemplate.copy());
            recordFurnaceInput(inputTemplate);
            BrickFurnaceCompat.invalidateRecipeCache(furnaceBE);
        }

        // Phase 4: Supply fuel (real extraction + placement, outside ledger)
        if (!ensureFuel(player, activeFurnaceOperations)) {
            // Discard input from furnace — abort() refunds the ledger, so
            // refunding the physical item here would double-refund.
            removeOwnedFurnaceInput(!usingSharedLedger);
            refundLeftoverFuel();
            resetFurnaceOwnership();
            player.sendSystemMessage(Component.translatable("rsi.vanilla.error.no_fuel"));
            return false;
        }

        furnaceBE.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        markCraftStarted();
        return true;
    }

    private boolean tryStartFurnaceWithMaterials(List<ItemStack> materials) {
        if (materials.isEmpty()) return false;
        if (!acquireFurnaceInventory()) return false;

        // Place pre-reserved input (chain already committed the ledger)
        furnaceBE.setItem(0, materials.get(0).copy());
        recordFurnaceInput(materials.get(0));
        BrickFurnaceCompat.invalidateRecipeCache(furnaceBE);

        // Auto-supply fuel (extracts directly from RS, outside ledger)
        if (!ensureFuel(player, activeFurnaceOperations)) {
            removeOwnedFurnaceInput(false);
            refundLeftoverFuel();
            resetFurnaceOwnership();
            player.sendSystemMessage(Component.translatable("rsi.vanilla.error.no_fuel"));
            return false;
        }

        furnaceBE.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        markCraftStarted();
        return true;
    }

    private boolean ensureFuel(ServerPlayer player, int operations) {
        int cookingTime = recipe instanceof AbstractCookingRecipe acr
                ? BrickFurnaceCompat.effectiveCookTicks(furnaceBE, acr) : 200;

        // Burn time already banked in litTime counts toward this item's cook.
        int litTime = readLitTime(furnaceBE);
        long requestedCook = (long) cookingTime * Math.max(1, operations);
        int remainingCook = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, requestedCook - litTime));
        if (remainingCook == 0) return true; // current burn already covers the whole cook

        // Existing fuel in slot 1: top up with more of the SAME type until it covers
        // the remaining cook. A single stick (burn 100) can't finish a 200-tick smelt,
        // so we must ensure enough units are present rather than trusting burnTime > 0.
        ItemStack existing = furnaceBE.getItem(1);
        if (!existing.isEmpty()) {
            int singleBurn = BrickFurnaceCompat.effectiveBurnTicks(
                    furnaceBE, existing, fuelRecipeType());
            if (singleBurn <= 0) return false; // non-fuel item blocking the slot
            int needed = VanillaFurnaceFuelPolicy.requiredAmount(remainingCook, singleBurn);
            int slotLimit = Math.min(existing.getMaxStackSize(), furnaceBE.getMaxStackSize());
            if (needed > slotLimit) return false;
            if (existing.getCount() >= needed) return true;
            if (!hasStorageAccess()) return false; // can't top up — insufficient fuel
            int topUp = needed - existing.getCount();
            ItemStack extra = extractExactFuel(existing, topUp);
            if (extra.isEmpty()) return false;
            ItemStack merged = existing.copy();
            merged.grow(extra.getCount());
            furnaceBE.setItem(1, merged);
            recordFurnaceFuel(extra);
            return true;
        }

        // Slot empty: apply the shared deterministic policy (configured fuels
        // first, then other safe fuels, then the best safe partial coverage).
        if (!hasStorageAccess()) return false;
        List<ItemStack> candidates = new ArrayList<>();
        var endpoint = storageEndpoint();
        if (endpoint == null) return false;
        var snapshot = endpoint.snapshot(player).snapshot().orElse(null);
        if (snapshot == null) return false;
        for (var entry : snapshot.items()) {
            candidates.add(entry.stack());
        }
        VanillaFurnaceFuelPolicy.Selection selection = VanillaFurnaceFuelPolicy.select(
                candidates, RSIntegrationConfig.VANILLA_FURNACE_FUEL_PRIORITY.get(),
                remainingCook,
                stack -> BrickFurnaceCompat.effectiveBurnTicks(furnaceBE, stack, fuelRecipeType()));
        return selection != null && !selection.partial()
                && supplyFuel(player, selection.fuel(), selection.amount());
    }

    private static int readLitTime(@Nullable AbstractFurnaceBlockEntity furnace) {
        if (furnace == null || LIT_TIME_FIELD == null) return 0;
        try {
            return Math.max(0, LIT_TIME_FIELD.getInt(furnace));
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] litTime probe failed", e);
            return 0;
        }
    }

    private boolean refillFuelWhileWorking(AbstractFurnaceBlockEntity current) {
        if (player == null || current == null || current.getItem(0).isEmpty()
                || readLitTime(current) > 0) return true;
        furnaceBE = current;
        int remainingOperations = Math.max(1, current.getItem(0).getCount());
        boolean ready = ensureFuel(player, remainingOperations);
        if (ready) {
            current.setChanged();
            if (myLevel != null && myPos != null) {
                myLevel.sendBlockUpdated(myPos, myLevel.getBlockState(myPos),
                        myLevel.getBlockState(myPos), 3);
            }
        } else {
            warnOnce("fuel-empty", "[RSI-Vanilla] Furnace fuel exhausted while inputs remain at {}", myPos);
        }
        return ready;
    }

    private RecipeType<?> fuelRecipeType() {
        return switch (CookingMachineFamily.fromRecipe(recipe)) {
            case BLAST_FURNACE -> RecipeType.BLASTING;
            case SMOKER -> RecipeType.SMOKING;
            default -> RecipeType.SMELTING;
        };
    }

    /** Extract {@code amount} of {@code fuelType} from RS into the fuel slot. */
    private boolean supplyFuel(ServerPlayer player, ItemStack fuelType, int amount) {
        ItemStack extracted = extractExactFuel(fuelType, amount);
        if (extracted.isEmpty()) return false;
        furnaceBE.setItem(1, extracted.copy());
        recordFurnaceFuel(extracted);
        player.displayClientMessage(
                Component.translatable("rsi.vanilla.info.fuel_supplied", extracted.getCount()), true);
        return true;
    }

    /** Extract exactly the requested count, refunding a concurrent partial result. */
    private ItemStack extractExactFuel(ItemStack fuelType, int amount) {
        ItemStack extracted = extractExactFromStorage(player, fuelType.copyWithCount(1), amount, false);
        if (extracted.getCount() == amount) return extracted;
        if (!extracted.isEmpty()) {
            insertIntoStorage(player, extracted, false);
        }
        return ItemStack.EMPTY;
    }

    /** Refund any whole (unburned) fuel items left in slot 1 back to RS. */
    private void refundLeftoverFuel() {
        if (furnaceBE == null || !furnaceInventoryLease) return;
        ItemStack fuel = furnaceBE.getItem(1);
        if (fuel.isEmpty() || BrickFurnaceCompat.effectiveBurnTicks(
                furnaceBE, fuel, fuelRecipeType()) <= 0) return;
        int refundable = MachineSlotOwnershipPolicy.removableAddedCount(
                baselineFurnaceFuel, suppliedFurnaceFuel, suppliedFurnaceFuelCount, fuel);
        if (refundable <= 0) return;
        ItemStack refund = fuel.copyWithCount(refundable);
        ItemStack retained = fuel.copy();
        retained.shrink(refundable);
        furnaceBE.setItem(1, retained.isEmpty() ? ItemStack.EMPTY : retained);
        furnaceBE.setChanged();
        refundToRSNetwork(refund);
        suppliedFurnaceFuelCount = Math.max(0, suppliedFurnaceFuelCount - refundable);
    }

    // ── VIRTUAL path ──────────────────────────────────────────────

    private boolean acquireFurnaceInventory() {
        if (furnaceBE == null || furnaceInventoryLease || !furnaceBE.getItem(0).isEmpty()) {
            return false;
        }

        // Publish the completed manual job before leasing the empty lane.
        ItemStack priorOutput = furnaceBE.getItem(2).copy();
        if (!priorOutput.isEmpty()) {
            furnaceBE.setItem(2, ItemStack.EMPTY);
            furnaceBE.setChanged();
            refundToRSNetwork(priorOutput);
        }
        if (!furnaceBE.getItem(2).isEmpty()) return false;

        baselineFurnaceFuel = furnaceBE.getItem(1).copy();
        suppliedFurnaceInput = ItemStack.EMPTY;
        suppliedFurnaceInputCount = 0;
        suppliedFurnaceFuel = ItemStack.EMPTY;
        suppliedFurnaceFuelCount = 0;
        furnaceInventoryLease = true;
        return true;
    }

    private void recordFurnaceInput(ItemStack input) {
        if (!furnaceInventoryLease || input.isEmpty()) return;
        suppliedFurnaceInput = input.copyWithCount(1);
        suppliedFurnaceInputCount += input.getCount();
    }

    private void recordFurnaceFuel(ItemStack fuel) {
        if (!furnaceInventoryLease || fuel.isEmpty()) return;
        if (!suppliedFurnaceFuel.isEmpty()
                && !ItemStack.isSameItemSameTags(suppliedFurnaceFuel, fuel)) {
            throw new IllegalStateException("Furnace fuel ownership type changed");
        }
        suppliedFurnaceFuel = fuel.copyWithCount(1);
        suppliedFurnaceFuelCount += fuel.getCount();
    }

    private ItemStack removeOwnedFurnaceInput(boolean refund) {
        if (furnaceBE == null || !furnaceInventoryLease) return ItemStack.EMPTY;
        ItemStack current = furnaceBE.getItem(0);
        int removable = MachineSlotOwnershipPolicy.removableAddedCount(
                ItemStack.EMPTY, suppliedFurnaceInput, suppliedFurnaceInputCount, current);
        if (removable <= 0) return ItemStack.EMPTY;
        ItemStack removed = current.copyWithCount(removable);
        ItemStack retained = current.copy();
        retained.shrink(removable);
        furnaceBE.setItem(0, retained.isEmpty() ? ItemStack.EMPTY : retained);
        suppliedFurnaceInputCount = Math.max(0, suppliedFurnaceInputCount - removable);
        if (refund) refundToRSNetwork(removed);
        return removed;
    }

    private void resetFurnaceOwnership() {
        furnaceInventoryLease = false;
        baselineFurnaceFuel = ItemStack.EMPTY;
        suppliedFurnaceInput = ItemStack.EMPTY;
        suppliedFurnaceInputCount = 0;
        suppliedFurnaceFuel = ItemStack.EMPTY;
        suppliedFurnaceFuelCount = 0;
    }

    private boolean matchesFurnaceOutput(ItemStack output) {
        ExpectedProduction expected = getExpectedProduction();
        return expected != null && IBatchDelegate.matchesProducedItem(output, expected.item());
    }

    private boolean tryStartVirtual(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        this.pendingResult = computeResult();
        if (this.pendingResult.isEmpty()) return false;

        ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;

        // Reserve all ingredients
        List<ItemStack> extracted = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), activeLedger);
            if (stack.isEmpty()) {
                if (!usingSharedLedger) {
                    ledger.rollback(player);
                }
                return false;
            }
            extracted.add(stack);
        }

        if (recipe instanceof SmithingTransformRecipe smithing) {
            this.pendingResult = SmithingRecipeHandler.assembleTransform(
                    smithing, extracted, myLevel.registryAccess());
            if (this.pendingResult.isEmpty()) {
                if (!usingSharedLedger) ledger.rollback(player);
                return false;
            }
        }

        // Commit private ledger
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                return false;
            }
        }

        this.craftDone = true;
        return true;
    }

    private ItemStack computeResult() {
        try {
            if (recipe instanceof CampfireCookingRecipe) {
                return com.huanghuang.rsintegration.recipe.CampfireRecipeSupport.resolveOutput(
                        recipe, myLevel.registryAccess());
            }
            return recipe.getResultItem(myLevel.registryAccess()).copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Vanilla] computeResult failed for {}",
                    recipe.getId(), e);
        }
        return ItemStack.EMPTY;
    }

    // ── CAMPFIRE path ──────────────────────────────────────────────

    private void ensureCampfireLit() {
        net.minecraft.world.level.block.state.BlockState state = myLevel.getBlockState(myPos);
        if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
            myLevel.setBlock(myPos, state.setValue(BlockStateProperties.LIT, true), 3);
        }
    }

    private boolean tryStartCampfire(ServerPlayer player) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return false;

        Ingredient input = ingredients.get(0);
        ItemStack inputTemplate = ItemStack.EMPTY;
        if (!input.isEmpty()) {
            ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;
            inputTemplate = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, input, 1, activeLedger);
            if (inputTemplate.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(input)));
                return false;
            }
        }

        // Commit BEFORE placing — commit failure leaves campfire untouched
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", "Extraction commit failed"));
                return false;
            }
        }

        // Place REAL item (post-commit) on campfire
        if (!inputTemplate.isEmpty()) {
            int cookTime = recipe instanceof CampfireCookingRecipe ccr ? ccr.getCookingTime() : 600;
            try {
                @SuppressWarnings("unchecked")
                net.minecraft.core.NonNullList<ItemStack> items =
                        (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
                items.set(campfireSlot, inputTemplate.copy());
                suppliedCampfireInput = inputTemplate.copy();
                int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(campfireBE);
                prog[campfireSlot] = 0;
                int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(campfireBE);
                times[campfireSlot] = cookTime;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire placement failed", e);
                return false;
            }
        }

        ensureCampfireLit();
        if (campfireBE instanceof BlockEntity be) be.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        campfireForceLoad(true);
        return true;
    }

    private boolean tryStartCampfireWithMaterials(List<ItemStack> materials) {
        if (materials.isEmpty()) return false;

        int cookTime = recipe instanceof CampfireCookingRecipe ccr ? ccr.getCookingTime() : 600;
        try {
            @SuppressWarnings("unchecked")
            net.minecraft.core.NonNullList<ItemStack> items =
                    (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
            items.set(campfireSlot, materials.get(0).copy());
            suppliedCampfireInput = materials.get(0).copy();
            int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(campfireBE);
            prog[campfireSlot] = 0;
            int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(campfireBE);
            times[campfireSlot] = cookTime;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire placement failed", e);
            return false;
        }

        ensureCampfireLit();
        if (campfireBE instanceof BlockEntity be) be.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        campfireForceLoad(true);
        return true;
    }

    private boolean isCampfireComplete() {
        // Vanilla CampfireBlockEntity.cookTick increments cookingProgress
        // each tick; when it reaches cookingTime the result is spawned as an
        // ItemEntity and the slot is cleared.  Detect completion by the slot
        // being empty.
        try {
            @SuppressWarnings("unchecked")
            net.minecraft.core.NonNullList<ItemStack> items =
                    (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
            return items.get(campfireSlot).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private ItemStack collectCampfireResult() {
        campfireForceLoad(false);

        // Vanilla spawned the result as an ItemEntity above the campfire.
        // Capture it for full NBT fidelity.
        if (myLevel != null && myLevel.isLoaded(myPos)) {
            List<net.minecraft.world.entity.item.ItemEntity> entities =
                    myLevel.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(myPos).inflate(2.0));
            for (net.minecraft.world.entity.item.ItemEntity entity : entities) {
                if (!entity.isRemoved()) {
                    ItemStack stack = entity.getItem();
                    if (!stack.isEmpty()) {
                        entity.discard();
                        return stack.copy();
                    }
                }
            }
        }

        // Never fabricate a result when the real world entity is absent. The
        // operation capture is authoritative and prevents refund + output
        // duplication when another collector races this scan.
        return ItemStack.EMPTY;
    }

    private ItemStack clearCampfireSlot(boolean refundToRS) {
        try {
            @SuppressWarnings("unchecked")
            net.minecraft.core.NonNullList<ItemStack> items =
                    (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
            ItemStack slotItem = items.get(campfireSlot);
            if (!slotItem.isEmpty()
                    && !PhysicalInputRecovery.recoveredExpected(
                    slotItem, suppliedCampfireInput)) {
                return ItemStack.EMPTY;
            }
            ItemStack recovered = slotItem.isEmpty() ? ItemStack.EMPTY
                    : slotItem.copyWithCount(suppliedCampfireInput.getCount());
            ItemStack retained = slotItem.copy();
            retained.shrink(recovered.getCount());
            items.set(campfireSlot, retained.isEmpty() ? ItemStack.EMPTY : retained);
            if (!recovered.isEmpty() && refundToRS) refundToRSNetwork(recovered.copy());
            int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(campfireBE);
            prog[campfireSlot] = 0;
            int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(campfireBE);
            times[campfireSlot] = 0;
            return recovered;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire clear failed", e);
            return ItemStack.EMPTY;
        }
    }

    // ── polling / collection ──────────────────────────────────────

    @NotNull
    @Override
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level, @NotNull BlockEntity be) {
        if (kind == MachineKind.FURNACE && be instanceof AbstractFurnaceBlockEntity current) {
            // A machine can consume its initial fuel earlier than the estimate
            // (augments and server-side recipe modifiers are common causes).
            // Keep a queued input from silently stalling when the furnace goes
            // dark between ticks.
            ItemStack output = current.getItem(2);
            if (output.isEmpty() || output.getCount() < output.getMaxStackSize()) {
                refillFuelWhileWorking(current);
            }
            if (!output.isEmpty()) {
                if (!matchesFurnaceOutput(output)) {
                    // The operation cannot have consumed RSI inputs when the
                    // output lane was occupied before dispatch. Mark this as
                    // a pre-start failure so the shared ledger is refunded.
                    return failObservation("furnace output slot contains another item");
                }
                ExpectedProduction expected = getExpectedProduction();
                if (expected != null && output.getCount() >= expected.count()) {
                    return doneObservation();
                }
                // A buffered furnace can expose a partial output stack before
                // consuming its remaining owned inputs.
                if (!current.getItem(0).isEmpty()) return workingObservation();
                return doneObservation();
            }
        }
        if (kind == MachineKind.FURNACE && BrickFurnaceCompat.isBrickFurnace(be)) {
            BrickFurnaceCompat.Eligibility eligibility = BrickFurnaceCompat.canExecute(be, recipe);
            if (!eligibility.allowed()) return failObservation(eligibility.detail());
            // Very fast configurations can finish between dispatch and the first
            // observation. Once output exists, an empty input and recipe cache are
            // normal completion state, not evidence that the recipe was rejected.
            if (matchesFurnaceOutput(furnaceBE.getItem(2))) return doneObservation();
            AbstractCookingRecipe actual = BrickFurnaceCompat.resolvedRecipe(be);
            if (actual == null) return failObservation("Brick Furnace did not accept the input recipe");
            if (!actual.getId().equals(recipe.getId())) {
                return failObservation("Brick Furnace resolved a different recipe: " + actual.getId());
            }
            if (phase == CraftPhase.WAITING_FOR_START) return workingObservation();
            if (isMachineCraftFinished(level, be)) return doneObservation();
            return workingObservation();
        }
        return super.observeMachineCraft(level, be);
    }

    @Override
    protected CraftObservation observeMissingMachineCraft(ServerLevel level, BlockPos pos) {
        // Stonecutters and smithing tables are intentionally executed virtually and
        // have no BlockEntity. Their result is ready as soon as the pre-reserved
        // materials have been committed and computeResult() succeeds.
        if (kind == MachineKind.VIRTUAL) {
            return craftDone ? doneObservation() : workingObservation();
        }
        return super.observeMissingMachineCraft(level, pos);
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (kind == MachineKind.VIRTUAL) return craftDone;
        if (kind == MachineKind.CAMPFIRE) return isCampfireComplete();

        if (furnaceBE == null) return true;

        ItemStack result = furnaceBE.getItem(2);
        // Once AbstractBatchDelegate has observed WORKING, consumed input is
        // sufficient proof of completion even if automation already took output.
        ExpectedProduction expected = getExpectedProduction();
        // For a buffered batch, an empty input slot only means the furnace has
        // consumed its queue; it does not prove that all outputs have been
        // produced (automation may remove each result immediately). Completion
        // must be based on the accumulated output count.
        if (expected != null && matchesFurnaceOutput(result)
                && result.getCount() >= expected.count()) return true;
        return activeFurnaceOperations <= 1 && furnaceBE.getItem(0).isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (kind == MachineKind.VIRTUAL) {
            ItemStack r = pendingResult.copy();
            pendingResult = ItemStack.EMPTY;
            craftDone = false;
            return r;
        }
        if (kind == MachineKind.CAMPFIRE) {
            return collectCampfireResult();
        }

        if (furnaceBE == null) return ItemStack.EMPTY;

        ItemStack visible = furnaceBE.getItem(2);
        if (!matchesFurnaceOutput(visible)) return ItemStack.EMPTY;
        ExpectedProduction expected = getExpectedProduction();
        int amount = expected == null ? visible.getCount()
                : Math.min(visible.getCount(), expected.count());
        ItemStack result = visible.copyWithCount(amount);
        ItemStack retained = visible.copy();
        retained.shrink(amount);
        furnaceBE.setItem(2, retained.isEmpty() ? ItemStack.EMPTY : retained);
        furnaceBE.setChanged();
        return result;
    }

    // ── lifecycle ─────────────────────────────────────────────────

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        List<ItemStack> recoveredInputs = new ArrayList<>();
        if (kind == MachineKind.FURNACE && furnaceBE != null) {
            // Refund the machine's physical input only when nothing else will.
            //
            // `player == null` alone is NOT a safe signal: terminate() forces
            // `online = null` under SILENT_REFUND, and that policy still has
            // refundLedger = true. Refunding here as well would return the same
            // material twice. The ledger holding this input is the deciding
            // factor, so defer to it whenever it is the shared chain ledger.
            boolean refundToRS = !usingSharedLedger;
            ItemStack recovered = removeOwnedFurnaceInput(refundToRS);
            if (!recovered.isEmpty()) recoveredInputs.add(recovered);
            // Fuel is outside the ledger, always refund unburned fuel
            refundLeftoverFuel();
            resetFurnaceOwnership();
            furnaceBE.setChanged();
        }
        if (kind == MachineKind.CAMPFIRE && campfireBE != null) {
            campfireForceLoad(false);
            ItemStack recovered = clearCampfireSlot(!usingSharedLedger);
            if (!recovered.isEmpty()) recoveredInputs.add(recovered);
            suppliedCampfireInput = ItemStack.EMPTY;
        }

        if (kind != MachineKind.VIRTUAL) recordFailureRecoveredInputs(recoveredInputs);

        // Rollback uncommitted private ledger
        if (ledger != null && !ledger.isCommitted()) {
            ledger.rollback(player);
        }

        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        resetState();
    }

    @Override
    protected boolean isFailureRefundSafe() {
        return kind == MachineKind.VIRTUAL;
    }

    @Override
    public boolean failureConsumesInputs(CraftObservation observation) {
        // An occupied furnace output is rejected before dispatch and therefore
        // must follow the normal refund path, even if the lease was classified
        // as in-flight by the asynchronous coordinator.
        return observation != null
                && observation.phase() == CraftPhase.FAILED
                && observation.detail() != null
                && !observation.detail().contains("output slot contains another item");
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        resetFurnaceOwnership();
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        campfireForceLoad(false);
        if (kind == MachineKind.FURNACE) {
            refundLeftoverFuel();
            resetFurnaceOwnership();
        }
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        if (kind != MachineKind.FURNACE) return null;
        ItemStack result = computeResult();
        if (result.isEmpty()) return null;
        long expected = (long) result.getCount() * Math.max(1, activeFurnaceOperations);
        return new ExpectedProduction(result, (int) Math.min(Integer.MAX_VALUE, expected));
    }

    @Override
    public OutputContract outputContract() {
        ItemStack result = computeResult();
        if (result.isEmpty() || kind == null) return OutputContract.none();
        return switch (kind) {
            case FURNACE -> vanillaPrimaryOutputContract(result, OutputContract.Source.SLOT, 2);
            case CAMPFIRE -> vanillaPrimaryOutputContract(result, OutputContract.Source.WORLD, null);
            case VIRTUAL -> vanillaPrimaryOutputContract(result, OutputContract.Source.VIRTUAL, null);
        };
    }

    @Override
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(ServerPlayer player) {
        OutputContract contract = outputContract();
        if (contract.ports().size() != 1) return List.of();
        ItemStack result = collectResult(player);
        if (result.isEmpty()) return List.of();
        OutputContract.Port port = contract.ports().get(0);
        return List.of(new OutputAccounting.CollectedOutput(port.portId(), port.source(), result));
    }

    static OutputContract vanillaPrimaryOutputContract(ItemStack result,
                                                       OutputContract.Source source,
                                                       @Nullable Integer physicalPort) {
        if (result == null || result.isEmpty()) return OutputContract.none();
        return new OutputContract(List.of(new OutputContract.Port(
                "vanilla:primary", physicalPort, result, result.getCount(),
                InputBufferPlan.OutputPort.Kind.PRIMARY, source)));
    }

    @Nullable
    @Override
    public ItemStack getExpectedOutput() {
        return kind == MachineKind.CAMPFIRE ? computeResult() : null;
    }

    @Nullable
    @Override
    public net.minecraft.world.phys.AABB getOutputCaptureRegion() {
        return kind == MachineKind.CAMPFIRE && myPos != null
                ? new net.minecraft.world.phys.AABB(myPos).inflate(2.0) : null;
    }

    // ── helpers ───────────────────────────────────────────────────

    private void campfireForceLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }

    private void refundToRSNetwork(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }
}
