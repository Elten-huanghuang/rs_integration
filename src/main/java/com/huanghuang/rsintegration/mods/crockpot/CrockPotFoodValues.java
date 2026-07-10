package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.reflection.probes.CrockPotReflection;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Crock Pot food-value logic used by both plan building and batch execution.
 * <p>
 * Crock Pot cooking recipes have no fixed ingredient list — a recipe matches when the summed
 * {@link net.minecraft.world.item.crafting.Recipe} food values of the pot's contents satisfy a set
 * of per-category min/max constraints. {@link #select} performs the same greedy per-slot item
 * choice the batch delegate uses, but without consuming anything, so plan building can preview the
 * exact items execution will place. Keeping one implementation guarantees the plan and the craft
 * pick identical items from an unchanged network.
 */
public final class CrockPotFoodValues {

    private CrockPotFoodValues() {}

    private static final int CAT = CrockPotRecipeHandler.CAT_COUNT;

    private static volatile boolean probed;
    private static volatile Object[] categoryValues;   // FoodCategory.values()
    private static volatile Method getFoodValues;       // static FoodValuesDefinition.getFoodValues(ItemStack, Level)
    private static volatile Method fvGet;                // FoodValues.get(FoodCategory)

    // Food values are static per-item data; a global cache survives across recipes and delegates.
    private static final Map<Item, float[]> CACHE = new ConcurrentHashMap<>();

    /** One network item and how many are available for selection. */
    public record Candidate(ItemStack stack, int available) {}

    public static boolean isReady() {
        ensureReflection();
        return getFoodValues != null && fvGet != null && categoryValues != null;
    }

    private static void ensureReflection() {
        if (probed) return;
        synchronized (CrockPotFoodValues.class) {
            if (probed) return;
            try {
                if (CrockPotReflection.foodCategoryClass != null
                        && CrockPotReflection.foodValuesDefinitionClass != null
                        && CrockPotReflection.foodValuesClass != null) {
                    categoryValues = (Object[]) CrockPotReflection.foodCategoryClass.getMethod("values").invoke(null);
                    getFoodValues = CrockPotReflection.foodValuesDefinitionClass
                            .getMethod("getFoodValues", ItemStack.class, Level.class);
                    fvGet = CrockPotReflection.foodValuesClass.getMethod("get", CrockPotReflection.foodCategoryClass);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CrockPot] Food-value reflection probe failed", e);
            }
            probed = true;
        }
    }

    /** Food values by category (length {@link CrockPotRecipeHandler#CAT_COUNT}), or null for non-food/unavailable. */
    @Nullable
    public static float[] compute(ItemStack stack, Level level) {
        if (stack.isEmpty()) return null;
        Item item = stack.getItem();
        float[] cached = CACHE.get(item);
        if (cached != null) return cached;
        ensureReflection();
        if (getFoodValues == null || fvGet == null || categoryValues == null) return null;
        try {
            Object fv = getFoodValues.invoke(null, stack, level);
            if (fv == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] getFoodValues returned null for {}",
                        stack.getHoverName().getString());
                return null;
            }
            float[] result = new float[CAT];
            for (int i = 0; i < CAT; i++) {
                result[i] = (float) fvGet.invoke(fv, categoryValues[i]);
            }
            CACHE.put(item, result);
            return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-CrockPot] computeFoodValues failed for {}",
                    stack.getHoverName().getString(), e);
            return null;
        }
    }

    /** Sum the food values of a set of already-chosen stacks (count-aware). */
    public static float[] combined(List<ItemStack> items, Level level) {
        float[] total = new float[CAT];
        for (ItemStack stack : items) {
            float[] fv = compute(stack, level);
            if (fv == null) continue;
            int count = Math.max(1, stack.getCount());
            for (int c = 0; c < CAT; c++) total[c] += fv[c] * count;
        }
        return total;
    }

    /**
     * Greedily choose up to {@code remaining} items whose food values, added to {@code startFV},
     * satisfy every category minimum without breaching any maximum. Nothing is consumed — the
     * caller extracts the returned items.
     *
     * <p>Two-phase selection: Phase 1 picks items that reduce category deficits. Once all
     * minimums are met, Phase 2 fills any remaining slots with the most neutral items
     * (lowest total food-value magnitude) that don't breach any maximum. Crock Pot requires
     * every slot up to potLevel to be filled for cooking to start, so stopping early would
     * leave the pot unable to begin.</p>
     *
     * @return the chosen items (each count 1), or null if the constraints cannot be met.
     */
    @Nullable
    public static List<ItemStack> select(float[] startFV, float[] catMins, float[] catMaxs,
                                         int remaining, List<Candidate> candidates, Level level) {
        if (!isReady() || remaining <= 0) return null;

        float[] current = Arrays.copyOf(startFV, CAT);
        Map<Item, Integer> avail = new HashMap<>();
        for (Candidate c : candidates) avail.merge(c.stack().getItem(), c.available(), Integer::sum);

        List<ItemStack> chosen = new ArrayList<>();

        // Phase 1: fill deficits greedily
        for (int r = 0; r < remaining; r++) {
            if (meetsMins(current, catMins)) break;

            ItemStack best = ItemStack.EMPTY;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (Candidate cand : candidates) {
                ItemStack stack = cand.stack();
                if (stack.isEmpty() || avail.getOrDefault(stack.getItem(), 0) <= 0) continue;
                float[] fv = compute(stack, level);
                if (fv == null || !reducesDeficit(current, catMins, fv) || breachesMax(current, catMaxs, fv)) continue;
                float score = deficitScore(current, catMins, fv);
                if (score > bestScore) { bestScore = score; best = stack; }
            }
            if (best.isEmpty()) return null; // no item can make progress toward a minimum

            avail.merge(best.getItem(), -1, Integer::sum);
            chosen.add(best.copyWithCount(1));
            float[] fv = compute(best, level);
            if (fv != null) for (int c = 0; c < CAT; c++) current[c] += fv[c];
        }

        if (!meetsMins(current, catMins)) return null;

        // Phase 2: fill remaining slots with neutral items (lowest total food-value
        // magnitude) that don't breach any max.  Needed because Crock Pot requires
        // all slots up to potLevel to be occupied for cooking to start.
        int slotsLeft = remaining - chosen.size();
        for (int r = 0; r < slotsLeft; r++) {
            ItemStack best = ItemStack.EMPTY;
            float bestImpact = Float.POSITIVE_INFINITY; // lower = more neutral
            for (Candidate cand : candidates) {
                ItemStack stack = cand.stack();
                if (stack.isEmpty() || avail.getOrDefault(stack.getItem(), 0) <= 0) continue;
                float[] fv = compute(stack, level);
                if (fv == null || breachesMax(current, catMaxs, fv)) continue;
                float impact = foodValueMagnitude(fv);
                if (impact < bestImpact) { bestImpact = impact; best = stack; }
            }
            if (best.isEmpty()) break; // no neutral filler available — pot starts with fewer slots

            avail.merge(best.getItem(), -1, Integer::sum);
            chosen.add(best.copyWithCount(1));
            float[] fv = compute(best, level);
            if (fv != null) for (int c = 0; c < CAT; c++) current[c] += fv[c];
        }

        return chosen;
    }

    private static boolean meetsMins(float[] current, float[] mins) {
        for (int c = 0; c < CAT; c++) if (mins[c] - current[c] > 0.001f) return false;
        return true;
    }

    private static boolean reducesDeficit(float[] current, float[] mins, float[] fv) {
        for (int c = 0; c < CAT; c++) if (mins[c] - current[c] > 0.001f && fv[c] > 0) return true;
        return false;
    }

    private static boolean breachesMax(float[] current, float[] maxs, float[] fv) {
        for (int c = 0; c < CAT; c++) if (current[c] + fv[c] > maxs[c] + 0.001f) return true;
        return false;
    }

    /** Sum of absolute food values — lower means more neutral (less likely to shift recipe match). */
    private static float foodValueMagnitude(float[] fv) {
        float sum = 0;
        for (int c = 0; c < CAT; c++) sum += Math.abs(fv[c]);
        return sum;
    }

    /** Reward filling deficits (×10); penalize food values in categories the recipe doesn't want (×5). */
    private static float deficitScore(float[] current, float[] mins, float[] fv) {
        float score = 0;
        for (int c = 0; c < CAT; c++) {
            float deficit = mins[c] - current[c];
            if (deficit > 0) score += Math.min(fv[c], deficit) * 10f;
            else if (fv[c] > 0 && mins[c] <= 0.001f) score -= fv[c] * 5f;
        }
        return score;
    }
}
