package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
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
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
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

/** Batch delegate for Crock Pot cooking recipes (all pot levels). */
public final class CrockPotBatchDelegate extends com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;
    private int potLevel;        // block's actual pot level (input slot count)
    private int recipeMinLevel;  // recipe's minimum required pot level

    // Food-value category constraint state
    private boolean hasCatConstraints;
    private final float[] catMins = new float[CrockPotRecipeHandler.CAT_COUNT];
    private final float[] catMaxs = new float[CrockPotRecipeHandler.CAT_COUNT];

    // Reflection handle for the pot's private item handler — loaded once
    private static volatile boolean itemHandlerProbed;
    private static volatile java.lang.reflect.Field itemHandlerField;

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
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.recipeMinLevel = CrockPotRecipeHandler.getPotLevel(found);
        this.potLevel = getBlockPotLevel(level, pos);
        if (potLevel <= 0) this.potLevel = recipeMinLevel;

        if (potLevel < recipeMinLevel) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.crockpot.error.pot_level_too_low", recipeMinLevel, potLevel));
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Block potLevel={} < recipeMinLevel={} for {}",
                    potLevel, recipeMinLevel, recipeId);
            return false;
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

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (hasCatConstraints) {
            return buildCategoryPlanIngredients(recipe, network, myLevel, myPos);
        }

        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) return false;

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            List<ItemStack> materials = new ArrayList<>();

            if (hasCatConstraints) {
                // Category recipes: resolve the requirement tree into a concrete
                // placement plan via the SAME DNF term selection the plan preview
                // uses, so both agree on which OR branch to satisfy and which items
                // to place. Reserving fixed ingredients through ensureMaterialAvailable
                // keeps auto-craft/inventory fallback; filler is always network-sourced.
                CategoryPlan plan = resolveCategoryPlan(recipe, network, myLevel, potLevel);
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
                    ItemStack reserved = ledger.reserveFromNetwork(
                            Ingredient.of(want.getItem()), 1, network);
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
            if (!tryStartWithMaterials(player, materials, ledger)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        network.insertItem(mat.copy(), mat.getCount(), Action.PERFORM);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.craftDone = false;

        myLevel.getChunk(myPos);

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
        if (!itemHandler.getStackInSlot(potLevel + 1).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Output slot occupied at {}", myPos);
            return false;
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
                            remainder.getHoverName().getString());
                    for (int back = 0; back <= slot; back++) {
                        ItemStack refund = itemHandler.extractItem(back, 64, false);
                        if (!refund.isEmpty() && !usingSharedLedger && network != null)
                            network.insertItem(refund, refund.getCount(), Action.PERFORM);
                    }
                    be.setChanged();
                    return false;
                }
                slot++;
            }
        }
        be.setChanged();

        // Handle fuel
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        ItemStack fuelSlot = itemHandler.getStackInSlot(potLevel);
        if (fuelSlot.isEmpty() || !isFuel(fuelSlot)) {
            if (network != null) {
                ItemStack fuel = extractFuel(network, player);
                if (!fuel.isEmpty()) {
                    ItemStack fuelRemainder = itemHandler.insertItem(potLevel, fuel, false);
                    if (!fuelRemainder.isEmpty() && network != null) {
                        network.insertItem(fuelRemainder, fuelRemainder.getCount(), Action.PERFORM);
                    }
                    be.setChanged();
                }
            }
        }

        if (!isBurning(be)) {
            ItemStack fuelNow = itemHandler.getStackInSlot(potLevel);
            if (!isFuel(fuelNow) && network != null) {
                ItemStack fuel = extractFuel(network, player);
                if (!fuel.isEmpty()) {
                    ItemStack fuelRemainder = itemHandler.insertItem(potLevel, fuel, false);
                    if (!fuelRemainder.isEmpty() && network != null) {
                        network.insertItem(fuelRemainder, fuelRemainder.getCount(), Action.PERFORM);
                    }
                    be.setChanged();
                } else {
                    for (int back = 0; back < potLevel; back++) {
                        ItemStack refund = itemHandler.extractItem(back, 64, false);
                        if (!refund.isEmpty() && !usingSharedLedger && network != null)
                            network.insertItem(refund, refund.getCount(), Action.PERFORM);
                    }
                    player.sendSystemMessage(Component.translatable("rsi.crockpot.no_fuel"));
                    return false;
                }
            }
        }

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

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack result = itemHandler.extractItem(potLevel + 1, 64, false);
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearMachineSlotsAndRefund();
        forceChunkLoad(false);
        craftDone = false;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        clearMachineSlotsAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
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
        // Merge selected filler by item into IngredientSpecs (e.g. "apple ×3").
        LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack st : plan.filler()) counts.merge(st.getItem(), 1, Integer::sum);
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            result.add(new IngredientSpec(Ingredient.of(new ItemStack(e.getKey())), e.getValue()));
        }
        return result.isEmpty() ? null : result;
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
        Map<Item, Integer> baseAvail = new HashMap<>();
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty() || stack.getCount() <= 0) continue;
            candidates.add(new CrockPotFoodValues.Candidate(stack, stack.getCount()));
            baseAvail.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        for (CrockPotRecipeHandler.Term term : CrockPotRecipeHandler.expandRequirements(recipe)) {
            CategoryPlan plan = tryResolveTerm(term, candidates, baseAvail, level, blockPotLevel);
            if (plan != null) return plan;
        }
        return null;
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
                                               Map<Item, Integer> baseAvail,
                                               Level level, int blockPotLevel) {
        Map<Item, Integer> avail = new HashMap<>(baseAvail);
        List<IngredientSpec> fixedSpecs = new ArrayList<>();
        List<ItemStack> fixedStacks = new ArrayList<>();
        int usedSlots = 0;

        for (IngredientSpec spec : term.fixed()) {
            if (spec.isEmpty()) continue;
            ItemStack pick = ItemStack.EMPTY;
            for (ItemStack opt : spec.ingredient().getItems()) {
                if (opt.isEmpty()) continue;
                if (avail.getOrDefault(opt.getItem(), 0) >= spec.count()) { pick = opt; break; }
            }
            if (pick.isEmpty()) return null; // fixed ingredient not available → term fails
            avail.merge(pick.getItem(), -spec.count(), Integer::sum);
            fixedSpecs.add(spec);
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
            int a = avail.getOrDefault(c.stack().getItem(), 0);
            if (a > 0) fillerCands.add(new CrockPotFoodValues.Candidate(c.stack(), a));
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
                                       Map<Item, net.minecraft.world.item.crafting.Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {
        if (!"crockpot".equals(recipeModTypeId)) return;
        CraftPacketUtils.addFuelToMaterials(
                itemAvailable, itemSource, neededCounts, repeatCount);
    }

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        net.minecraft.server.level.ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
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
                        remaining).getString());
            }

            for (int c = 0; c < mins.length; c++) {
                if (mins[c] > 0) {
                    String catName = getCategoryName(c);
                    warnings.add(Component.translatable("rsi.crockpot.cat_min",
                            catName, String.format("%.1f", mins[c])).getString());
                }
            }
            for (int c = 0; c < maxs.length; c++) {
                if (maxs[c] < Float.MAX_VALUE) {
                    String catName = getCategoryName(c);
                    warnings.add(Component.translatable("rsi.crockpot.cat_max",
                            catName, String.format("%.1f", maxs[c])).getString());
                }
            }
        } else if (remaining > 0) {
            String fillerId = RSIntegrationConfig.CROCKPOT_FILLER_ITEM.get();
            warnings.add(Component.translatable("rsi.crockpot.filler_needed",
                    remaining, fillerId).getString());
        }

        warnings.add(Component.translatable("rsi.crockpot.fuel_warning").getString());

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
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
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
        return stack.getBurnTime(null) > 0;
    }

    private static ItemStack extractFuel(INetwork network, ServerPlayer player) {
        // 1. Try the configured priority list in order — take the first one the
        //    network can supply. Items resolve from RSIntegrationConfig.CROCKPOT_FUEL_PRIORITY.
        for (String id : RSIntegrationConfig.CROCKPOT_FUEL_PRIORITY.get()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            Item pref = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
            if (pref == null || pref == Items.AIR) continue;
            ItemStack probe = new ItemStack(pref);
            if (!isFuel(probe)) continue; // datapack may have stripped burn time
            ItemStack extracted = network.extractItem(probe, 1, Action.PERFORM);
            if (!extracted.isEmpty()) return extracted;
        }

        // 2. Fallback: any burnable solid item, preferring the largest stack so we
        //    drain bulk clutter first. Skip anything unsafe to burn (see isSafeBulkFuel):
        //    tools/bows have burn time but are valuable; container fuels strand a
        //    container in the single fuel slot; NBT items may be enchanted/named.
        ItemStack best = ItemStack.EMPTY;
        int bestCount = 0;
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            if (stack.getBurnTime(null) <= 0) continue;
            if (!isSafeBulkFuel(stack)) continue;
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                best = stack;
            }
        }
        if (!best.isEmpty()) {
            var extracted = network.extractItem(best.copyWithCount(1), 1, Action.PERFORM);
            if (!extracted.isEmpty()) return extracted;
        }
        return ItemStack.EMPTY;
    }

    /**
     * Whether a burnable item is safe to auto-consume as bulk fuel. Excludes:
     * <ul>
     *   <li>Damageable items — bows, fishing rods, wooden tools all have burn time
     *       but are gear a player never wants silently burned;</li>
     *   <li>Container-return fuels (lava bucket, etc.) — the empty container would
     *       be stranded in the single fuel slot;</li>
     *   <li>Items carrying NBT — may be enchanted, renamed, or hold custom data.</li>
     * </ul>
     * The configured fuel-priority list bypasses this check, so coal/charcoal
     * are always eligible.
     */
    private static boolean isSafeBulkFuel(ItemStack stack) {
        if (stack.isDamageableItem()) return false;
        if (!stack.getCraftingRemainingItem().isEmpty()) return false;
        return !stack.hasTag();
    }

    private void clearMachineSlotsAndRefund() {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return;
        if (!CrockPotReflection.crockPotBEClass.isInstance(be))
            return;

        IItemHandler handler = getItemHandler(be);
        if (handler == null || handler.getSlots() < 6) return;

        for (int slot = 0; slot < potLevel; slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        ItemStack out = handler.extractItem(potLevel + 1, 64, false);
        if (!out.isEmpty() && !usingSharedLedger) refundToRSNetwork(out);
        be.setChanged();
    }

    private void refundToRSNetwork(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
            if (!leftover.isEmpty() && player != null) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] Chunk load failed", e);
        }
    }
}
