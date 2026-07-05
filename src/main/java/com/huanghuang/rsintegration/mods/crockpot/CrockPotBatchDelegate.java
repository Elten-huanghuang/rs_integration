package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CrockPotBatchDelegate implements com.huanghuang.rsintegration.crafting.batch.IBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private INetwork network;
    private boolean craftDone;
    private boolean usingSharedLedger;
    private int potLevel;

    // Food-value category constraint state
    private boolean hasCatConstraints;
    private final float[] catMins = new float[CrockPotRecipeHandler.CAT_COUNT];
    private final float[] catMaxs = new float[CrockPotRecipeHandler.CAT_COUNT];

    // Reflection handles — loaded once
    private static volatile boolean reflectionProbed;
    private static volatile java.lang.reflect.Field itemHandlerField;
    private static volatile Class<?> foodValuesClass;
    private static volatile Class<?> foodValuesDefClass;
    private static volatile Class<?> foodCategoryClass;
    private static volatile Object[] foodCategoryValues;
    private static volatile Method getFoodValuesMethod;
    private static volatile Method fvGetMethod;

    // Cache: Item → food value float[10]
    private final Map<Item, float[]> foodValueCache = new HashMap<>();
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
            foodValueCache.clear();
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

        ExtractionLedger ledger = new ExtractionLedger();
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
                    ledger.rollback(player);
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
                    ledger.rollback(player);
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
        if (!be.getClass().getName().equals("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity")) {
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
    public boolean isCraftComplete(ServerLevel level) {
        BlockEntity be = level.getBlockEntity(myPos);
        if (be == null) return false;
        if (!be.getClass().getName().equals("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity"))
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
    public void onBatchFailed(ServerPlayer player, String reason) {
        clearMachineSlotsAndRefund();
        refundExtraExtracted();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
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
    @Nullable
    private List<ItemStack> findAndExtractFoodValueItems(float[] currentFV, int remaining,
                                                          @Nullable IItemHandler itemHandler) {
        List<ItemStack> result = new ArrayList<>();
        float[] current = Arrays.copyOf(currentFV, CrockPotRecipeHandler.CAT_COUNT);

        ensureFoodValueReflection();
        if (foodValuesDefClass == null || foodValuesClass == null || foodCategoryValues == null) {
            return null;
        }

        int consecutiveFailures = 0;
        for (int r = 0; r < remaining; r++) {
            boolean constraintsMet = true;
            for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
                if (catMins[c] - current[c] > 0.001f) { constraintsMet = false; break; }
            }

            ItemStack bestItem = ItemStack.EMPTY;
            float bestScore = -1f;

            var stacks = network.getItemStorageCache().getList().getStacks();
            for (var entry : stacks) {
                ItemStack stack = entry.getStack();
                if (stack.isEmpty() || stack.getCount() <= 0) continue;

                if (itemHandler != null && !itemHandler.isItemValid(0, stack)) continue;

                float[] fv = computeItemFoodValues(stack);
                if (fv == null) continue;

                if (violatesMax(current, fv)) continue;

                float score;
                if (!constraintsMet) {
                    // Reward filling deficits; penalize noise in categories
                    // the recipe doesn't target (avoids competing recipes).
                    score = 0;
                    for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
                        float deficit = catMins[c] - current[c];
                        if (deficit > 0) {
                            score += Math.min(fv[c], deficit) * 10f;
                        } else if (fv[c] > 0 && catMins[c] <= 0.001f) {
                            score -= fv[c] * 5f;
                        }
                    }
                } else {
                    // Constraints met: prefer items whose food values are in
                    // categories the recipe actually targets. Penalize values
                    // in non-target categories — they add noise that can
                    // shift the output to a different recipe.
                    score = 0;
                    boolean hasAnyFV = false;
                    for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
                        if (fv[c] <= 0) continue;
                        hasAnyFV = true;
                        if (catMins[c] > 0.001f) {
                            score += fv[c] * 10f;
                        } else {
                            score -= fv[c] * 5f;
                        }
                    }
                    if (!hasAnyFV) {
                        score = 50; // completely neutral filler
                    }
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestItem = stack;
                }
            }

            // If strict pass found nothing and constraints are met, try the
            // configured filler item as a safe neutral fallback.
            if (bestItem.isEmpty() && constraintsMet) {
                bestItem = tryExtractFillerItem();
                if (!bestItem.isEmpty()) bestScore = 99;
            }

            if (bestItem.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] findFV: no valid item. constraintsMet={} currentFV=[{}]",
                        constraintsMet, fvToString(current));
                return null;
            }

            ItemStack extracted = network.extractItem(bestItem.copyWithCount(1), 1, Action.PERFORM);
            if (extracted.isEmpty()) {
                if (++consecutiveFailures > 3) return null;
                r--;
                continue;
            }
            consecutiveFailures = 0;

            {
                float[] sfv = computeItemFoodValues(extracted);
                RSIntegrationMod.LOGGER.info("[RSI-Batch-CrockPot] findFV: round {} '{}' score={} FV=[{}]",
                        result.size(), extracted.getHoverName().getString(), bestScore,
                        sfv != null ? fvToString(sfv) : "null");
            }

            result.add(extracted);
            extraExtractedItems.add(extracted.copy());
            float[] fv = computeItemFoodValues(extracted);
            if (fv != null) {
                for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
                    current[c] += fv[c];
                }
            }
        }

        for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
            if (current[c] < catMins[c] - 0.001f) return null;
        }
        return result;
    }

    /**
     * Try to extract the configured filler item from RS, bypassing isItemValid.
     * Validates post-extraction that the item passes the max-constraint check
     * with its actual food values (if any).
     */
    @Nullable
    private ItemStack tryExtractFillerItem() {
        Ingredient filler = CrockPotRecipeHandler.resolveFillerIngredient();
        if (filler == null) return null;
        for (ItemStack candidate : filler.getItems()) {
            if (candidate.isEmpty()) continue;
            ItemStack extracted = network.extractItem(candidate.copy(), 1, Action.PERFORM);
            if (!extracted.isEmpty()) {
                float[] fv = computeItemFoodValues(extracted);
                if (fv != null) {
                    for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
                        float sum = 0;
                        for (ItemStack already : extraExtractedItems) {
                            float[] afv = computeItemFoodValues(already);
                            if (afv != null) sum += afv[c];
                        }
                        if (fv[c] > catMaxs[c] - sum + 0.001f) {
                            // Filler violates max — refund and skip
                            network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
                            return null;
                        }
                    }
                }
                RSIntegrationMod.LOGGER.info("[RSI-Batch-CrockPot] findFV: using config filler '{}'", extracted.getHoverName().getString());
                return extracted;
            }
        }
        return null;
    }

    private boolean violatesMax(float[] current, float[] fv) {
        for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
            if (current[c] + fv[c] > catMaxs[c] + 0.001f) return true;
        }
        return false;
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

    private static float sumPositive(float[] fv) {
        float s = 0;
        for (float v : fv) if (v > 0) s += v;
        return s;
    }

    private static String fvToString(float[] fv) {
        String[] cats = {"M", "MO", "F", "E", "FR", "V", "D", "S", "FZ", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fv.length; i++) {
            if (fv[i] != 0) sb.append(cats[i]).append("=").append(String.format("%.1f", fv[i])).append(" ");
        }
        return sb.isEmpty() ? "empty" : sb.toString().trim();
    }

    // ── food-value computation via reflection ────────────────────

    private static void ensureFoodValueReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            foodCategoryClass = Class.forName("com.sihenzhang.crockpot.base.FoodCategory");
            foodCategoryValues = (Object[]) foodCategoryClass.getMethod("values").invoke(null);
            foodValuesClass = Class.forName("com.sihenzhang.crockpot.base.FoodValues");
            foodValuesDefClass = Class.forName("com.sihenzhang.crockpot.recipe.FoodValuesDefinition");
            getFoodValuesMethod = foodValuesDefClass.getMethod("getFoodValues", ItemStack.class, Level.class);
            fvGetMethod = foodValuesClass.getMethod("get", foodCategoryClass);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] FoodValue reflection probe failed: {}", e.toString());
        }
    }

    @Nullable
    private float[] computeItemFoodValues(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Item item = stack.getItem();
        float[] cached = foodValueCache.get(item);
        if (cached != null) return cached;

        ensureFoodValueReflection();
        if (getFoodValuesMethod == null || fvGetMethod == null || foodCategoryValues == null) {
            return null;
        }

        try {
            Object fv = getFoodValuesMethod.invoke(null, stack, myLevel);
            if (fv == null) return null;

            float[] result = new float[CrockPotRecipeHandler.CAT_COUNT];
            for (int i = 0; i < CrockPotRecipeHandler.CAT_COUNT; i++) {
                result[i] = (float) fvGetMethod.invoke(fv, foodCategoryValues[i]);
            }
            foodValueCache.put(item, result);

            // Diagnostic: log first few computed items to verify reflection works
            if (foodValueCache.size() <= 3) {
                RSIntegrationMod.LOGGER.info("[RSI-Batch-CrockPot] computeFV: {} -> [{}]",
                        stack.getHoverName().getString(), fvToString(result));
            }
            return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] computeItemFoodValues failed for {}: {}",
                    stack.getHoverName().getString(), e.toString());
            return null;
        }
    }

    private float[] computeCombinedFoodValues(List<ItemStack> items) {
        float[] total = new float[CrockPotRecipeHandler.CAT_COUNT];
        for (ItemStack stack : items) {
            float[] fv = computeItemFoodValues(stack);
            if (fv != null) {
                for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
                    total[c] += fv[c];
                }
            }
        }
        return total;
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

    @Nullable
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
        ensureFoodValueReflection();
        if (itemHandlerField != null) return;
        try {
            Class<?> beClass = Class.forName("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity");
            itemHandlerField = beClass.getDeclaredField("itemHandler");
            itemHandlerField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Reflection probe failed: {}", e.toString());
        }
    }

    @Nullable
    private static IItemHandler getItemHandler(BlockEntity be) {
        probeReflection();
        if (itemHandlerField != null) {
            try {
                return (IItemHandler) itemHandlerField.get(be);
            } catch (Exception ignored) {}
        }
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    @Nullable
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
        if (!be.getClass().getName().equals("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity"))
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
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] Chunk load failed: {}", e.toString());
        }
    }
}
