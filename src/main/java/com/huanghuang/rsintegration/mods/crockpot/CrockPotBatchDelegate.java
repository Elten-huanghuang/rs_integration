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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
    private int potLevel;

    // Food-value category constraint state
    private boolean hasCatConstraints;
    private final float[] catMins = new float[CrockPotRecipeHandler.CAT_COUNT];
    private final float[] catMaxs = new float[CrockPotRecipeHandler.CAT_COUNT];

    // Reflection handle for the pot's private item handler — loaded once
    private static volatile boolean itemHandlerProbed;
    private static volatile java.lang.reflect.Field itemHandlerField;

    // Items extracted outside the ledger (food-value fillers) for refund on failure
    private final List<ItemStack> extraExtractedItems = new ArrayList<>();

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

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.potLevel = CrockPotRecipeHandler.getPotLevel(found);

        // Parse category constraints
        this.hasCatConstraints = CrockPotRecipeHandler.hasCategoryConstraints(found);
        if (hasCatConstraints) {
            float[][] constraints = CrockPotRecipeHandler.parseCategoryConstraints(found);
            System.arraycopy(constraints[0], 0, catMins, 0, CrockPotRecipeHandler.CAT_COUNT);
            System.arraycopy(constraints[1], 0, catMaxs, 0, CrockPotRecipeHandler.CAT_COUNT);
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] validateAndInit OK: recipe={} potLevel={} hasCatConstraints={}",
                recipeId, potLevel, hasCatConstraints);
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        // For recipes with category constraints, the single-craft path handles
        // food-value-aware item selection. Return null so the chain falls back
        // to tryStartSingleCraft().
        if (hasCatConstraints) return null;

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
            extraExtractedItems.clear();

            // Phase 1: extract specific ingredients from RS
            List<IngredientSpec> specs = getIngredients();
            if (specs != null) {
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                            player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                    if (reserved.isEmpty()) {
                        refundExtraExtracted();
                        return false;
                    }
                    materials.add(reserved.copy());
                }
            }

            // Phase 2: if category constraints exist, fill remaining slots from RS
            if (hasCatConstraints) {
                int remaining = potLevel - materials.size();
                if (remaining > 0) {
                    IItemHandler itemHandler = getItemHandlerAtPos();
                    float[] currentFV = computeCombinedFoodValues(materials);
                    List<ItemStack> fvItems = findAndExtractFoodValueItems(currentFV, remaining, itemHandler);
                    if (fvItems == null) {
                        refundExtraExtracted();
                        player.sendSystemMessage(Component.translatable("rsi.crockpot.error.food_values"));
                        // Detailed diagnostics: which max constraints are zero
                        String blocked = buildBlockedCategoriesMessage();
                        if (!blocked.isEmpty()) {
                            player.sendSystemMessage(Component.literal(blocked));
                        }
                        return false;
                    }
                    materials.addAll(fvItems);
                }
            }

            if (!ledger.commit(network, player)) {
                refundExtraExtracted();
                return false;
            }

            this.usingSharedLedger = false;
            if (!tryStartWithMaterials(player, materials, ledger)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        network.insertItem(mat.copy(), mat.getCount(), Action.PERFORM);
                }
                refundExtraExtracted();
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
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
        if (itemHandler == null || itemHandler.getSlots() < 6) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Cannot access item handler");
            return false;
        }

        forceChunkLoad(true);

        // Place materials into input slots
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
                    for (int back = 0; back < slot; back++) {
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
        ItemStack fuelSlot = itemHandler.getStackInSlot(4);
        if (fuelSlot.isEmpty() || !isFuel(fuelSlot)) {
            if (network != null) {
                ItemStack fuel = extractFuel(network, player);
                if (!fuel.isEmpty()) {
                    ItemStack fuelRemainder = itemHandler.insertItem(4, fuel, false);
                    if (!fuelRemainder.isEmpty() && network != null) {
                        network.insertItem(fuelRemainder, fuelRemainder.getCount(), Action.PERFORM);
                    }
                    be.setChanged();
                }
            }
        }

        if (!isBurning(be)) {
            ItemStack fuelNow = itemHandler.getStackInSlot(4);
            if (!isFuel(fuelNow) && network != null) {
                ItemStack fuel = extractFuel(network, player);
                if (!fuel.isEmpty()) {
                    ItemStack fuelRemainder = itemHandler.insertItem(4, fuel, false);
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

        ItemStack output = itemHandler.getStackInSlot(5);
        return !output.isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack result = itemHandler.extractItem(5, 64, false);
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearMachineSlotsAndRefund();
        refundExtraExtracted();
        forceChunkLoad(false);
        craftDone = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearMachineSlotsAndRefund();
        extraExtractedItems.clear();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // ── food-value-aware item selection ──────────────────────────

    /**
     * Find and extract items from the RS network whose food values satisfy
     * the recipe's category constraints. Returns null if constraints cannot
     * be satisfied.
     */
    private List<ItemStack> findAndExtractFoodValueItems(float[] currentFV, int remaining,
                                                          @Nullable IItemHandler itemHandler) {
        if (!CrockPotFoodValues.isReady()) return null;

        // Snapshot the network as selection candidates (respecting the pot's slot filter).
        List<CrockPotFoodValues.Candidate> candidates = new ArrayList<>();
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty() || stack.getCount() <= 0) continue;
            if (itemHandler != null && !itemHandler.isItemValid(0, stack)) continue;
            candidates.add(new CrockPotFoodValues.Candidate(stack, stack.getCount()));
        }

        // Same selection the plan preview ran — guarantees the craft consumes the previewed items.
        List<ItemStack> plan = CrockPotFoodValues.select(
                currentFV, catMins, catMaxs, remaining, candidates, myLevel);
        if (plan == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] findFV: no item set satisfies constraints. currentFV=[{}]",
                    fvToString(currentFV));
            return null;
        }

        List<ItemStack> result = new ArrayList<>();
        for (ItemStack want : plan) {
            ItemStack extracted = network.extractItem(want.copyWithCount(1), 1, Action.PERFORM);
            if (extracted.isEmpty()) return null; // network changed since selection — caller refunds
            result.add(extracted);
            extraExtractedItems.add(extracted.copy());
        }
        return result;
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

    private static String fvToString(float[] fv) {
        String[] cats = {"M", "MO", "F", "E", "FR", "V", "D", "S", "FZ", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fv.length; i++) {
            if (fv[i] != 0) sb.append(cats[i]).append("=").append(String.format("%.1f", fv[i])).append(" ");
        }
        return sb.isEmpty() ? "empty" : sb.toString().trim();
    }

    // ── food-value computation ───────────────────────────────────

    private float[] computeCombinedFoodValues(List<ItemStack> items) {
        return CrockPotFoodValues.combined(items, myLevel);
    }

    /**
     * Plan-build helper for pure-category Crock Pot recipes, which carry no fixed ingredient list —
     * a match is decided by the summed food values of the pot's contents. Runs the same food-value
     * selection the batch delegate uses at craft time so the plan preview shows exactly the items the
     * craft will place. Returns null if the network cannot satisfy the recipe's category constraints.
     */
    @Nullable
    public static List<IngredientSpec> buildCategoryPlanIngredients(Recipe<?> recipe,
                                                                    @Nullable INetwork network, Level level) {
        if (network == null || !CrockPotFoodValues.isReady()) return null;

        List<IngredientSpec> result = new ArrayList<>();
        List<ItemStack> fixedStacks = new ArrayList<>();
        int usedSlots = 0;

        // Fixed MustContain requirements first (empty for pure-category recipes).
        List<IngredientSpec> fixed = CrockPotRecipeHandler.getSpecificIngredients(recipe, false);
        if (fixed != null) {
            for (IngredientSpec spec : fixed) {
                if (spec.isEmpty()) continue;
                result.add(spec);
                usedSlots += spec.count();
                ItemStack[] matches = spec.ingredient().getItems();
                if (matches.length > 0) fixedStacks.add(matches[0].copyWithCount(spec.count()));
            }
        }

        int remaining = CrockPotRecipeHandler.getPotLevel(recipe) - usedSlots;
        if (remaining <= 0) return result.isEmpty() ? null : result;

        float[][] constraints = CrockPotRecipeHandler.parseCategoryConstraints(recipe);
        float[] startFV = CrockPotFoodValues.combined(fixedStacks, level);

        List<CrockPotFoodValues.Candidate> candidates = new ArrayList<>();
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty() || stack.getCount() <= 0) continue;
            candidates.add(new CrockPotFoodValues.Candidate(stack, stack.getCount()));
        }

        List<ItemStack> chosen = CrockPotFoodValues.select(
                startFV, constraints[0], constraints[1], remaining, candidates, level);
        if (chosen == null) return null;

        // Merge selected filler by item into IngredientSpecs (e.g. "apple ×3").
        LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack st : chosen) counts.merge(st.getItem(), 1, Integer::sum);
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            result.add(new IngredientSpec(Ingredient.of(new ItemStack(e.getKey())), e.getValue()));
        }
        return result.isEmpty() ? null : result;
    }

    private void refundExtraExtracted() {
        for (ItemStack stack : extraExtractedItems) {
            if (!stack.isEmpty() && network != null) {
                network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
            }
        }
        extraExtractedItems.clear();
    }

    // ── ingredient extraction (no filler for category recipes) ───

    private List<IngredientSpec> getIngredients() {
        // For category-constraint recipes, only extract the actual required
        // ingredients — remaining slots are filled by food-value selection.
        // For non-category recipes, include filler items as usual.
        boolean pad = !hasCatConstraints;
        return CrockPotRecipeHandler.getSpecificIngredients(recipe, pad);
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
        int potLevel = CrockPotRecipeHandler.getPotLevel(recipe);
        int slotReqs = CrockPotRecipeHandler.countSlotRequirements(recipe);
        int remaining = potLevel - slotReqs;

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

    private IItemHandler getItemHandlerAtPos() {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return null;
        return getItemHandler(be);
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
        ItemStack best = ItemStack.EMPTY;
        int bestScore = 0;
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            int burnTime = stack.getBurnTime(null);
            if (burnTime <= 0) continue;
            int score = stack.getCount();
            ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (rl != null && "minecraft".equals(rl.getNamespace())) score += 64;
            if (score > bestScore) {
                bestScore = score;
                best = stack;
            }
        }
        if (!best.isEmpty()) {
            var extracted = network.extractItem(best.copyWithCount(1), 1, Action.PERFORM);
            if (!extracted.isEmpty()) return extracted;
        }
        return ItemStack.EMPTY;
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
        ItemStack out = handler.extractItem(5, 64, false);
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
