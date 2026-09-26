package com.huanghuang.rsintegration.mods.crockpot;
import java.lang.reflect.Field;

import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.MachineSlotOwnershipPolicy;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.CrockPotReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/** Batch delegate for Crock Pot cooking recipes (all pot levels). */
public final class CrockPotBatchDelegate extends AbstractBatchDelegate {

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // Each pot owns its input/output slots; the chain reserves one recipe
        // operation per worker before any physical insertion occurs.
        return BatchConcurrencyCapabilities.machineSlot();
    }

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;
    private int potLevel;        // block's actual pot level (input slot count)
    private int recipeMinLevel;  // recipe's minimum required pot level
    private ItemStack suppliedFuelType = ItemStack.EMPTY;
    private int suppliedFuelCount;
    private ItemStack baselineFuel = ItemStack.EMPTY;
    private ItemStack[] suppliedInputTypes = new ItemStack[0];
    private int[] suppliedInputCounts = new int[0];
    private boolean inventoryLease;

    // Food-value category constraint state
    private boolean hasCatConstraints;
    private final float[] catMins = new float[CrockPotRecipeHandler.CAT_COUNT];
    private final float[] catMaxs = new float[CrockPotRecipeHandler.CAT_COUNT];

    // Reflection handle for the pot's private item handler — loaded once
    private static volatile boolean itemHandlerProbed;
    private static volatile Field itemHandlerField;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myLevel = level;
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        }

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.suppliedFuelType = ItemStack.EMPTY;
        this.suppliedFuelCount = 0;
        this.recipeMinLevel = CrockPotRecipeHandler.getPotLevel(found);
        this.potLevel = getBlockPotLevel(level, pos);
        if (potLevel <= 0) this.potLevel = recipeMinLevel;
        resetInventoryOwnership();

        if (potLevel < recipeMinLevel) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.crockpot.error.pot_level_too_low", recipeMinLevel, potLevel));
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Block potLevel={} < recipeMinLevel={} for {}",
                    potLevel, recipeMinLevel, recipeId);
            return false;
        }

        BlockEntity be = level.getBlockEntity(pos);
        IItemHandler inventory = be != null && CrockPotReflection.crockPotBEClass.isInstance(be)
                ? getItemHandler(be) : null;
        if (inventory == null || inventory.getSlots() < potLevel + 2) return false;
        for (int slot = 0; slot < potLevel; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) return false;
        }

        // Parse category constraints
        this.hasCatConstraints = CrockPotRecipeHandler.hasCategoryConstraints(found);
        if (hasCatConstraints) {
            float[][] constraints = CrockPotRecipeHandler.parseCategoryConstraints(found);
            System.arraycopy(constraints[0], 0, catMins, 0, CrockPotRecipeHandler.CAT_COUNT);
            System.arraycopy(constraints[1], 0, catMaxs, 0, CrockPotRecipeHandler.CAT_COUNT);
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] validateAndInit OK: recipe={} blockPotLevel={} recipeMinLevel={} hasCatConstraints={}",
                recipeId, potLevel, recipeMinLevel, hasCatConstraints);
        return true;
    }

    public boolean usesPlannedCategoryMaterials() {
        return hasCatConstraints;
    }

    private static Ingredient concreteIngredient(ItemStack stack) {
        ItemStack one = stack.copyWithCount(1);
        return one.hasTag() ? StrictNBTIngredient.of(one) : Ingredient.of(one);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (hasCatConstraints) {
            return storageEndpoint() != null
                    ? buildCategoryPlanIngredients(recipe, storageEndpoint(), player, myLevel, myPos)
                    : buildCategoryPlanIngredients(recipe, network, myLevel, myPos);
        }

        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        }
        if (!hasStorageAccess()) return false;

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            ledger.setStorageEndpoint(storageEndpoint());
            List<ItemStack> materials = new ArrayList<>();

            if (hasCatConstraints) {
                // Category recipes: resolve the requirement tree into a concrete
                // placement plan via the SAME DNF term selection the plan preview
                // uses, so both agree on which OR branch to satisfy and which items
                // to place. Reserving fixed ingredients through ensureMaterialAvailable
                // keeps auto-craft/inventory fallback; filler is always network-sourced.
                CategoryPlan plan = storageEndpoint() != null
                        ? resolveCategoryPlan(recipe, storageEndpoint(), player, myLevel, potLevel)
                        : resolveCategoryPlan(recipe, network, myLevel, potLevel);
                if (plan == null) {
                    player.sendSystemMessage(Component.translatable("rsi.crockpot.error.food_values"));
                    String blocked = buildBlockedCategoriesMessage();
                    if (!blocked.isEmpty()) {
                        player.sendSystemMessage(Component.literal(blocked));
                    }
                    return false; // ledger auto-rollback via close()
                }
                for (IngredientSpec spec : plan.fixed()) {
                    if (spec.isEmpty()) continue;
                    ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                            player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                    if (reserved.isEmpty()) return false; // ledger auto-rollback via close()
                    materials.add(reserved.copy());
                }
                for (ItemStack want : plan.filler()) {
                    ItemStack reserved = storageEndpoint() != null
                            ? ledger.reserveFromEndpoint(Ingredient.of(want.getItem()), 1,
                            storageEndpoint(), player)
                            : ledger.reserveFromNetwork(Ingredient.of(want.getItem()), 1, network);
                    if (reserved.isEmpty()) return false; // network changed, auto-rollback
                    materials.add(reserved.copy());
                }
            } else {
                // Non-category recipes: fixed ingredients (padded with config filler).
                List<IngredientSpec> specs = getIngredients();
                if (specs != null) {
                    for (IngredientSpec spec : specs) {
                        if (spec.isEmpty()) continue;
                        ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                                player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                        if (reserved.isEmpty()) return false; // ledger auto-rollback via close()
                        materials.add(reserved.copy());
                    }
                }
            }

            // All reservations done — commit (performs actual extraction)
            if (!ledger.commit(network, player)) return false;

            this.usingSharedLedger = false;
            if (!tryStartWithMaterialsImpl(player, materials, ledger, false)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        insertIntoStorage(player, mat, false);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        return tryStartWithMaterialsImpl(player, materials, sharedLedger, true);
    }

    private boolean tryStartWithMaterialsImpl(ServerPlayer player, List<ItemStack> materials,
                                              ExtractionLedger sharedLedger,
                                              boolean shared) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = shared;
        this.craftDone = false;

        if (!myLevel.hasChunkAt(myPos)) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] BlockEntity missing at {}", myPos);
            return false;
        }
        if (!CrockPotReflection.crockPotBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Wrong BE type: {}", be.getClass().getName());
            return false;
        }

        IItemHandler itemHandler = getItemHandler(be);
        int expectedSlots = potLevel + 2; // input + fuel + output
        if (itemHandler == null || itemHandler.getSlots() < expectedSlots) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Item handler too small: {} < {}",
                    itemHandler != null ? itemHandler.getSlots() : 0, expectedSlots);
            return false;
        }
        if (!hasStorageAccess() || !acquireInventory(itemHandler, be)) return false;

        int requestedSlots = materials.stream()
                .filter(stack -> !stack.isEmpty())
                .mapToInt(ItemStack::getCount)
                .sum();
        if (requestedSlots > potLevel) {
            resetInventoryOwnership();
            return false;
        }
        int simulatedSlot = 0;
        for (ItemStack material : materials) {
            for (int i = 0; i < material.getCount(); i++) {
                ItemStack single = material.copyWithCount(1);
                if (!itemHandler.insertItem(simulatedSlot++, single, true).isEmpty()) {
                    resetInventoryOwnership();
                    return false;
                }
            }
        }

        forceChunkLoad(true);

        // Place materials into input slots — each item occupies one slot
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            int count = mat.getCount();
            for (int i = 0; i < count; i++) {
                if (slot >= potLevel) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Too many items for potLevel={}", potLevel);
                    break;
                }
                ItemStack single = mat.copyWithCount(1);
                ItemStack remainder = itemHandler.insertItem(slot, single, false);
                if (!remainder.isEmpty()) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Failed to insert into slot {}: {}", slot,
                            remainder);
                    return rollbackRejectedStart(itemHandler, be);
                }
                suppliedInputTypes[slot] = single.copyWithCount(1);
                suppliedInputCounts[slot]++;
                slot++;
            }
        }
        be.setChanged();

        // Fuel is outside the material ledger. Fill the slot so short-burning
        // fallback fuels cannot strand an otherwise valid asynchronous craft.
        boolean fuelReady = topUpFuel(itemHandler);
        if (!fuelReady && !isBurning(be)) {
            player.sendSystemMessage(Component.translatable("rsi.crockpot.no_fuel"));
            return rollbackRejectedStart(itemHandler, be);
        }
        be.setChanged();
        markCraftStarted();

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] Materials inserted, cooking should start next tick");
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!CrockPotReflection.crockPotBEClass.isInstance(be))
            return false;

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null) return false;

        int outputSlot = potLevel + 1;
        ItemStack output = itemHandler.getStackInSlot(outputSlot);
        if (output.isEmpty()) return false;

        ExpectedProduction expected = getExpectedProduction();
        return expected != null
                && ItemStack.isSameItem(output, expected.item())
                && output.getCount() >= expected.count();
    }

    @NotNull
    @Override
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level, @NotNull BlockEntity be) {
        IItemHandler observedHandler = getItemHandler(be);
        if (observedHandler != null) {
            ItemStack visibleOutput = observedHandler.getStackInSlot(potLevel + 1);
            if (!visibleOutput.isEmpty() && !isExpectedOutput(visibleOutput)) {
                return failObservation("Crock Pot output slot contains another item");
            }
        }
        if (isMachineCraftFinished(level, be)) return doneObservation();

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null || itemHandler.getSlots() < potLevel + 2) {
            return failObservation("Crock Pot item handler unavailable");
        }
        if (!isBurning(be)) {
            if (topUpFuel(itemHandler)) {
                be.setChanged();
            } else {
                warnOnce("fuel-empty", "[RSI-Batch-CrockPot] Waiting for fuel at {}", myPos);
            }
        }
        return workingObservation();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack visible = itemHandler.getStackInSlot(potLevel + 1);
        if (!isExpectedOutput(visible)) return ItemStack.EMPTY;
        ExpectedProduction expected = getExpectedProduction();
        int amount = expected == null ? visible.getCount()
                : Math.min(visible.getCount(), expected.count());
        ItemStack result = itemHandler.extractItem(potLevel + 1, amount, false);
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        recordFailureRecoveredInputs(clearMachineSlotsAndRefund(true));
        forceChunkLoad(false);
        craftDone = false;
        resetInventoryOwnership();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        forceChunkLoad(false);
        clearMachineSlotsAndRefund(false);
        craftDone = false;
        resetInventoryOwnership();
        resetState();
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = recipe == null || myLevel == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
        return result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
    }

    /** Build a message listing categories whose max=0 block all available items. */
    private String buildBlockedCategoriesMessage() {
        String[] names = {"MEAT", "MONSTER", "FISH", "EGG", "FRUIT",
                "VEGGIE", "DAIRY", "SWEETENER", "FROZEN", "INEDIBLE"};
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
            if (catMaxs[c] <= 0.001f) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(names[c]).append("=0");
            }
        }
        if (sb.length() == 0) return "";
        return "§7Blocked (max=0): " + sb;
    }

    /**
     * Plan-build helper for pure-category Crock Pot recipes, which carry no fixed ingredient list —
     * a match is decided by the summed food values of the pot's contents. Runs the same food-value
     * selection the batch delegate uses at craft time so the plan preview shows exactly the items the
     * craft will place. Returns null if the network cannot satisfy the recipe's category constraints.
     */
    @Nullable
    public static List<IngredientSpec> buildCategoryPlanIngredients(Recipe<?> recipe,
                                                                    @Nullable INetwork network, Level level,
                                                                    @Nullable BlockPos pos) {
        int blockPotLevel = getBlockPotLevel(level, pos);
        if (blockPotLevel <= 0) blockPotLevel = CrockPotRecipeHandler.INPUT_SLOT_COUNT;

        CategoryPlan plan = resolveCategoryPlan(recipe, network, level, blockPotLevel);
        if (plan == null) return null;

        List<IngredientSpec> result = new ArrayList<>(plan.fixed());
        LinkedHashMap<MaterialKey, ItemStack> grouped = new LinkedHashMap<>();
        for (ItemStack stack : plan.filler()) {
            MaterialKey key = MaterialKey.of(stack);
            grouped.compute(key, (ignored, existing) -> {
                if (existing == null) return stack.copyWithCount(1);
                existing.grow(1);
                return existing;
            });
        }
        for (ItemStack stack : grouped.values()) {
            result.add(new IngredientSpec(concreteIngredient(stack), stack.getCount()));
        }
        return result.isEmpty() ? null : result;
    }

    /** Endpoint-aware category planning used when BD is the selected backend. */
    @Nullable
    public static List<IngredientSpec> buildCategoryPlanIngredients(Recipe<?> recipe,
                                                                    @Nullable CraftStorageEndpoint endpoint,
                                                                    ServerPlayer player,
                                                                    Level level,
                                                                    @Nullable BlockPos pos) {
        int blockPotLevel = getBlockPotLevel(level, pos);
        if (blockPotLevel <= 0) blockPotLevel = CrockPotRecipeHandler.INPUT_SLOT_COUNT;
        CategoryPlan plan = resolveCategoryPlan(recipe, endpoint, player, level, blockPotLevel);
        if (plan == null) return null;
        return flattenCategoryPlan(plan);
    }

    /** A resolved DNF term: the fixed ingredients to reserve plus the filler items to place. */
    public record CategoryPlan(List<IngredientSpec> fixed, List<ItemStack> filler) {}

    /**
     * Resolve a Crock Pot recipe's requirement tree into a concrete placement plan.
     * <p>
     * The requirement tree is expanded into DNF alternative {@link CrockPotRecipeHandler.Term}s
     * (see {@code expandRequirements}); a recipe matches if ANY one term is satisfiable. This walks
     * the terms in declaration order against a single read-only network snapshot and returns the
     * first satisfiable one, so plan preview and craft execution — both calling this — always pick
     * the identical term and place identical items. Returns null when no term can be satisfied.
     */
    @Nullable
    public static CategoryPlan resolveCategoryPlan(Recipe<?> recipe, @Nullable INetwork network,
                                                   Level level, int blockPotLevel) {
        if (network == null || !CrockPotFoodValues.isReady()) return null;

        // One network snapshot shared by every term trial and by both callers.
        List<CrockPotFoodValues.Candidate> candidates = new ArrayList<>();
        Map<MaterialKey, Integer> baseAvail = new HashMap<>();
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty() || stack.getCount() <= 0) continue;
            candidates.add(new CrockPotFoodValues.Candidate(stack, stack.getCount()));
            baseAvail.merge(MaterialKey.of(stack), stack.getCount(), Integer::sum);
        }

        return resolveCategoryPlan(candidates, baseAvail, recipe, level, blockPotLevel);
    }

    /** Resolve category requirements from a backend-neutral item snapshot. */
    @Nullable
    public static CategoryPlan resolveCategoryPlan(Recipe<?> recipe,
                                                   @Nullable CraftStorageEndpoint endpoint,
                                                   ServerPlayer player,
                                                   Level level, int blockPotLevel) {
        if (endpoint == null || !CrockPotFoodValues.isReady()) return null;
        var snapshot = endpoint.snapshot(player).snapshot().orElse(null);
        if (snapshot == null) return null;
        List<CrockPotFoodValues.Candidate> candidates = new ArrayList<>();
        Map<MaterialKey, Integer> baseAvail = new HashMap<>();
        for (var entry : snapshot.items()) {
            if (entry.stack().isEmpty() || entry.amount() <= 0) continue;
            int amount = (int) Math.min(Integer.MAX_VALUE, entry.amount());
            candidates.add(new CrockPotFoodValues.Candidate(entry.stack(), amount));
            baseAvail.merge(MaterialKey.of(entry.stack()), amount, Integer::sum);
        }
        return resolveCategoryPlan(candidates, baseAvail, recipe, level, blockPotLevel);
    }

    private static CategoryPlan resolveCategoryPlan(
            List<CrockPotFoodValues.Candidate> candidates,
            Map<MaterialKey, Integer> baseAvail,
            Recipe<?> recipe, Level level, int blockPotLevel) {
        for (CrockPotRecipeHandler.Term term : CrockPotRecipeHandler.expandRequirements(recipe)) {
            CategoryPlan plan = tryResolveTerm(term, candidates, baseAvail, level, blockPotLevel);
            if (plan != null) return plan;
        }
        return null;
    }

    private static List<IngredientSpec> flattenCategoryPlan(CategoryPlan plan) {
        List<IngredientSpec> result = new ArrayList<>(plan.fixed());
        LinkedHashMap<MaterialKey, ItemStack> grouped = new LinkedHashMap<>();
        for (ItemStack stack : plan.filler()) {
            MaterialKey key = MaterialKey.of(stack);
            grouped.compute(key, (ignored, existing) -> {
                if (existing == null) return stack.copyWithCount(1);
                existing.grow(1);
                return existing;
            });
        }
        for (ItemStack stack : grouped.values()) {
            result.add(new IngredientSpec(concreteIngredient(stack), stack.getCount()));
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Attempt to satisfy a single DNF term: reserve its fixed ingredients from the snapshot, then
     * fill the remaining pot slots with food-value-aware filler under the term's category bounds.
     * Uses a working copy of availability so fixed items are not re-picked as filler. Returns null
     * (term not satisfiable) without mutating the shared snapshot.
     */
    @Nullable
    private static CategoryPlan tryResolveTerm(CrockPotRecipeHandler.Term term,
                                               List<CrockPotFoodValues.Candidate> candidates,
                                               Map<MaterialKey, Integer> baseAvail,
                                               Level level, int blockPotLevel) {
        Map<MaterialKey, Integer> avail = new HashMap<>(baseAvail);
        List<IngredientSpec> fixedSpecs = new ArrayList<>();
        List<ItemStack> fixedStacks = new ArrayList<>();
        int usedSlots = 0;

        for (IngredientSpec spec : term.fixed()) {
            if (spec.isEmpty()) continue;
            ItemStack pick = ItemStack.EMPTY;
            for (CrockPotFoodValues.Candidate candidate : candidates) {
                ItemStack option = candidate.stack();
                if (option.isEmpty() || !IngredientMatcher.test(spec.ingredient(), option)) continue;
                MaterialKey key = MaterialKey.of(option);
                if (avail.getOrDefault(key, 0) >= spec.count()) {
                    pick = option;
                    break;
                }
            }
            boolean availableNow = !pick.isEmpty();
            if (!availableNow) {
                for (ItemStack option : spec.ingredient().getItems()) {
                    if (!option.isEmpty() && option.getItem() != Items.AIR) {
                        pick = option;
                        break;
                    }
                }
            }
            if (pick.isEmpty()) return null;
            if (availableNow) {
                avail.merge(MaterialKey.of(pick), -spec.count(), Integer::sum);
                fixedSpecs.add(new IngredientSpec(concreteIngredient(pick), spec.count(), spec.role()));
            } else {
                // Preserve the requirement so CraftingResolver can recursively produce it.
                fixedSpecs.add(spec);
            }
            fixedStacks.add(pick.copyWithCount(spec.count()));
            usedSlots += spec.count();
        }

        int remaining = blockPotLevel - usedSlots;
        if (remaining < 0) return null; // more fixed items than the pot can hold

        float[] startFV = CrockPotFoodValues.combined(fixedStacks, level);
        if (remaining == 0) {
            // Pot is full of fixed items; accept the term (recipe match verified in-game).
            return new CategoryPlan(fixedSpecs, Collections.emptyList());
        }

        // Filler candidates draw from the decremented snapshot so fixed items aren't re-picked.
        List<CrockPotFoodValues.Candidate> fillerCands = new ArrayList<>();
        for (CrockPotFoodValues.Candidate c : candidates) {
            int available = avail.getOrDefault(MaterialKey.of(c.stack()), 0);
            if (available > 0) fillerCands.add(new CrockPotFoodValues.Candidate(c.stack(), available));
        }

        List<ItemStack> filler = CrockPotFoodValues.select(
                startFV, term.mins(), term.maxs(), remaining, fillerCands, level);
        if (filler == null) return null;
        return new CategoryPlan(fixedSpecs, filler);
    }

    // ── ingredient extraction (no filler for category recipes) ───

    private List<IngredientSpec> getIngredients() {
        // For category-constraint recipes, only extract the actual required
        // ingredients — remaining slots are filled by food-value selection.
        // For non-category recipes, include filler items as usual, padding to
        // the block's REAL input-slot count (this.potLevel, set from the block
        // in validateAndInit) — the pot won't cook until every input slot is
        // filled. Falls back to the fixed slot count if potLevel is unset.
        boolean pad = !hasCatConstraints;
        int targetSlots = potLevel > 0 ? potLevel : CrockPotRecipeHandler.INPUT_SLOT_COUNT;
        return CrockPotRecipeHandler.getSpecificIngredients(recipe, pad, targetSlots);
    }

    // ── plan warnings ────────────────────────────────────────────

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {
        if (!"crockpot".equals(recipeModTypeId)) return;

        // Match recursive-plan fuel selection to runtime auto-refueling.
        Item preferred = null;
        for (String id : RSIntegrationConfig.CROCKPOT_FUEL_PRIORITY.get()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item != null && item != Items.AIR && itemAvailable.getOrDefault(item, 0) > 0
                    && ForgeHooks.getBurnTime(new ItemStack(item), null) > 0) {
                preferred = item;
                break;
            }
        }
        if (preferred != null) {
            int fuelNeeded = Math.max(1, repeatCount / 4);
            neededCounts.merge(preferred, fuelNeeded, Integer::sum);
            itemSource.putIfAbsent(preferred, Ingredient.of(preferred));
            return;
        }
        CraftPacketUtils.addFuelToMaterials(itemAvailable, itemSource, neededCounts, repeatCount);
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        int blockPotLevel = getBlockPotLevel(level, pos);
        // Fall back to the fixed input-slot count (not the recipe's potLevel,
        // which is only the pot-tier gate) when no specific machine is selected.
        if (blockPotLevel <= 0) blockPotLevel = CrockPotRecipeHandler.INPUT_SLOT_COUNT;
        int slotReqs = CrockPotRecipeHandler.countSlotRequirements(recipe);
        int remaining = blockPotLevel - slotReqs;

        if (CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
            float[][] constraints = CrockPotRecipeHandler.parseCategoryConstraints(recipe);
            float[] mins = constraints[0];
            float[] maxs = constraints[1];

            if (remaining > 0) {
                warnings.add(Component.translatable("rsi.crockpot.food_value_filler",
                        remaining));
            }

            for (int c = 0; c < mins.length; c++) {
                if (mins[c] > 0) {
                    String catName = getCategoryName(c);
                    warnings.add(Component.translatable("rsi.crockpot.cat_min",
                            catName, String.format("%.1f", mins[c])));
                }
            }
            for (int c = 0; c < maxs.length; c++) {
                if (maxs[c] < Float.MAX_VALUE) {
                    String catName = getCategoryName(c);
                    warnings.add(Component.translatable("rsi.crockpot.cat_max",
                            catName, String.format("%.1f", maxs[c])));
                }
            }
        } else if (remaining > 0) {
            String fillerId = RSIntegrationConfig.CROCKPOT_FILLER_ITEM.get();
            warnings.add(Component.translatable("rsi.crockpot.filler_needed",
                    remaining, fillerId));
        }

        warnings.add(Component.translatable("rsi.crockpot.fuel_warning"));

        return warnings;
    }

    private static String getCategoryName(int ordinal) {
        // Names match FoodCategory enum: MEAT, MONSTER, FISH, EGG, FRUIT,
        // VEGGIE, DAIRY, SWEETENER, FROZEN, INEDIBLE
        String[] names = {"MEAT", "MONSTER", "FISH", "EGG", "FRUIT",
                "VEGGIE", "DAIRY", "SWEETENER", "FROZEN", "INEDIBLE"};
        return ordinal >= 0 && ordinal < names.length ? names[ordinal] : "?";
    }

    // ── block pot level ──────────────────────────────────────────

    /**
     * Read the Crock Pot block's actual pot level from its item handler.
     * The item handler has {@code potLevel + 2} slots (input + fuel + output).
     * Falls back to the recipe minimum if the block entity is unavailable
     * (e.g. during plan preview without a selected machine).
     */
    public static int getBlockPotLevel(Level level, BlockPos pos) {
        if (level == null || pos == null) return -1;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !CrockPotReflection.crockPotBEClass.isInstance(be)) return -1;
        IItemHandler handler = getItemHandler(be);
        if (handler == null) return -1;
        return handler.getSlots() - 2; // subtract fuel + output slots
    }

    // ── reflection helpers ───────────────────────────────────────

    private static void probeReflection() {
        if (itemHandlerProbed) return;
        itemHandlerProbed = true;
        try {
            itemHandlerField = CrockPotReflection.crockPotBEClass.getDeclaredField("itemHandler");
            itemHandlerField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Reflection probe failed", e);
        }
    }

    private static IItemHandler getItemHandler(BlockEntity be) {
        probeReflection();
        if (itemHandlerField != null) {
            try {
                return (IItemHandler) itemHandlerField.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] field access failed", e); }
        }
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    private static boolean isBurning(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("isBurning");
            return (boolean) m.invoke(be);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isFuel(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return ForgeHooks.getBurnTime(stack, null) > 0;
    }

    private boolean topUpFuel(IItemHandler handler) {
        int fuelSlot = potLevel;
        ItemStack current = handler.getStackInSlot(fuelSlot);
        if (!current.isEmpty() && !isFuel(current)) return false;

        if (current.isEmpty() && !suppliedFuelType.isEmpty()) {
            suppliedFuelType = ItemStack.EMPTY;
            suppliedFuelCount = 0;
        }
        if (!hasStorageAccess()) return isFuel(current);

        ItemStack fuelType = current.isEmpty() ? selectFuelFromStorage() : current.copyWithCount(1);
        if (fuelType.isEmpty()) return isFuel(current);

        if (!suppliedFuelType.isEmpty()
                && !ItemStack.isSameItemSameTags(suppliedFuelType, fuelType)) {
            suppliedFuelType = ItemStack.EMPTY;
            suppliedFuelCount = 0;
        }

        int room = CrockPotFuelPolicy.insertionRoom(
                current, fuelType, handler.getSlotLimit(fuelSlot));
        if (room <= 0) return isFuel(current);

        ItemStack extracted = extractExactFromStorage(player, fuelType.copyWithCount(1), room, false);
        if (extracted.isEmpty()) return isFuel(current);

        ItemStack remainder = handler.insertItem(fuelSlot, extracted, false);
        int inserted = extracted.getCount() - remainder.getCount();
        if (!remainder.isEmpty()) {
            insertIntoStorage(player, remainder, false);
        }
        if (inserted > 0) {
            if (suppliedFuelType.isEmpty()) {
                suppliedFuelType = extracted.copyWithCount(1);
            }
            suppliedFuelCount += inserted;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Batch-CrockPot] Supplied {} fuel item(s) at {}", inserted, myPos);
        }
        return isFuel(handler.getStackInSlot(fuelSlot));
    }

    private ItemStack selectFuelFromStorage() {
        List<ItemStack> candidates = new ArrayList<>();
        var endpoint = storageEndpoint();
        if (endpoint == null) return ItemStack.EMPTY;
        var snapshot = endpoint.snapshot(player).snapshot().orElse(null);
        if (snapshot == null) return ItemStack.EMPTY;
        for (var entry : snapshot.items()) {
            candidates.add(entry.stack());
        }
        ItemStack selected = CrockPotFuelPolicy.select(
                candidates, RSIntegrationConfig.CROCKPOT_FUEL_PRIORITY.get(),
                stack -> ForgeHooks.getBurnTime(stack, null));
        return selected != null ? selected : ItemStack.EMPTY;
    }

    private List<ItemStack> clearMachineSlotsAndRefund(boolean failureCleanup) {
        List<ItemStack> recoveredInputs = new ArrayList<>();
        if (!myLevel.hasChunkAt(myPos)) return recoveredInputs;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return recoveredInputs;
        if (!CrockPotReflection.crockPotBEClass.isInstance(be))
            return recoveredInputs;

        IItemHandler handler = getItemHandler(be);
        if (handler == null || handler.getSlots() < potLevel + 2) return recoveredInputs;

        if (!inventoryLease) return recoveredInputs;
        for (int slot = 0; slot < potLevel; slot++) {
            ItemStack current = handler.getStackInSlot(slot);
            int removable = MachineSlotOwnershipPolicy.removableAddedCount(
                    ItemStack.EMPTY, suppliedInputTypes[slot], suppliedInputCounts[slot], current);
            if (removable <= 0) continue;
            ItemStack s = handler.extractItem(slot, removable, false);
            if (!s.isEmpty()) recoveredInputs.add(s.copy());
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        ItemStack visibleOut = handler.getStackInSlot(potLevel + 1);
        if (!failureCleanup && !visibleOut.isEmpty() && isExpectedOutput(visibleOut)) {
            ItemStack out = handler.extractItem(potLevel + 1, visibleOut.getCount(), false);
            if (!out.isEmpty() && !usingSharedLedger) refundToRSNetwork(out);
        }
        refundSuppliedFuel(handler);
        be.setChanged();
        return recoveredInputs;
    }

    private boolean rollbackRejectedStart(IItemHandler handler, BlockEntity be) {
        clearMachineSlotsAndRefund(false);
        be.setChanged();
        forceChunkLoad(false);
        resetInventoryOwnership();
        return false;
    }

    private void refundSuppliedFuel(IItemHandler handler) {
        ItemStack current = handler.getStackInSlot(potLevel);
        int refundable = MachineSlotOwnershipPolicy.removableAddedCount(
                baselineFuel, suppliedFuelType, suppliedFuelCount, current);
        if (refundable > 0) {
            ItemStack refund = handler.extractItem(potLevel, refundable, false);
            if (!refund.isEmpty()) refundToRSNetwork(refund);
        }
        suppliedFuelType = ItemStack.EMPTY;
        suppliedFuelCount = 0;
    }

    private boolean acquireInventory(IItemHandler handler, BlockEntity be) {
        if (inventoryLease) return false;
        for (int slot = 0; slot < potLevel; slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) return false;
        }
        ItemStack output = handler.getStackInSlot(potLevel + 1);
        if (!output.isEmpty()) {
            ItemStack removed = handler.extractItem(potLevel + 1, output.getCount(), false);
            if (removed.isEmpty()) return false;
            refundToRSNetwork(removed);
            be.setChanged();
        }
        if (!handler.getStackInSlot(potLevel + 1).isEmpty()) return false;
        baselineFuel = handler.getStackInSlot(potLevel).copy();
        suppliedInputTypes = new ItemStack[potLevel];
        suppliedInputCounts = new int[potLevel];
        Arrays.fill(suppliedInputTypes, ItemStack.EMPTY);
        suppliedFuelType = ItemStack.EMPTY;
        suppliedFuelCount = 0;
        inventoryLease = true;
        return true;
    }

    private boolean isExpectedOutput(ItemStack stack) {
        ExpectedProduction expected = getExpectedProduction();
        return expected != null && IBatchDelegate.matchesProducedItem(stack, expected.item());
    }

    private void resetInventoryOwnership() {
        inventoryLease = false;
        baselineFuel = ItemStack.EMPTY;
        suppliedFuelType = ItemStack.EMPTY;
        suppliedFuelCount = 0;
        suppliedInputTypes = new ItemStack[0];
        suppliedInputCounts = new int[0];
    }

    private void refundToRSNetwork(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }
}
