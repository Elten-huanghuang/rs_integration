package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.reflection.probes.CrockPotReflection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    private static volatile Method fvIsEmpty;            // FoodValues.isEmpty()

    record Candidate(MaterialKey key, float[] values, boolean preferred) {}

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
                    fvIsEmpty = CrockPotReflection.foodValuesClass.getMethod("isEmpty");
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
        ensureReflection();
        if (getFoodValues == null || fvGet == null || categoryValues == null) return null;
        try {
            Object fv = getFoodValues.invoke(null, stack, level);
            if (fv == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] getFoodValues returned null for {}",
                        stack.getHoverName().getString());
                return null;
            }
            // Non-food items (no food_values recipe) get an EMPTY FoodValues object,
            // not null — CrockPot uses FoodValues.isEmpty()/has() to reject them. We
            // must mirror that: an all-zero FoodValues would otherwise be the most
            // "neutral" filler and get auto-selected (e.g. an angler trophy accessory).
            // Treat empty as non-food so select()'s null-checks skip it entirely.
            if (fvIsEmpty != null && (boolean) fvIsEmpty.invoke(fv)) {
                return null;
            }
            float[] result = new float[CAT];
            for (int i = 0; i < CAT; i++) {
                result[i] = (float) fvGet.invoke(fv, categoryValues[i]);
            }
            return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-CrockPot] computeFoodValues failed for {}",
                    stack.getHoverName().getString(), e);
            return null;
        }
    }

    /**
     * 选出恰好 {@code remaining} 个材料，与 {@code startFV} 合计后满足所有属性上下限。
     * 只扣减当前分支的库存副本，实际取出由调用方负责。
     *
     * <p>先贪心选择能补足属性下限的食材，再优先用配置的填充物补位；填充物不可用时，
     * 选择属性影响最小且不突破上限的食材。烹饪锅必须填满四个输入槽才能开煮，
     * 因此任何槽位无法补齐时都不返回方案。</p>
     *
     * @return 每组数量为 1 的已选材料；无法满足条件时返回 null。
     */
    @Nullable
    static List<ItemStack> select(float[] startFV, float[] catMins, float[] catMaxs,
                                  int remaining, List<Candidate> candidates, Map<MaterialKey, Integer> avail) {
        if (remaining < 0) return null;

        float[] current = Arrays.copyOf(startFV, CAT);
        if (breachesMax(current, catMaxs, new float[CAT])) return null;

        List<ItemStack> chosen = new ArrayList<>();

        // Phase 1: fill deficits greedily
        for (int r = 0; r < remaining; r++) {
            if (meetsMins(current, catMins)) break;

            Candidate best = null;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (Candidate cand : candidates) {
                if (avail.getOrDefault(cand.key(), 0) <= 0) continue;
                float[] fv = cand.values();
                if (fv == null || !reducesDeficit(current, catMins, fv) || breachesMax(current, catMaxs, fv)) continue;
                float score = deficitScore(current, catMins, fv);
                if (score > bestScore) { bestScore = score; best = cand; }
            }
            if (best == null) return null;
            take(best, avail, chosen, current);
        }

        if (!meetsMins(current, catMins)) return null;
        if (remaining == 0) return chosen;

        // 默认填充物也有食材属性，只有不突破配方上限时才能优先用于补位。
        int slotsLeft = remaining - chosen.size();
        for (int r = 0; r < slotsLeft; r++) {
            Candidate best = null;
            float bestImpact = Float.POSITIVE_INFINITY; // lower = more neutral
            boolean bestPreferred = false;
            for (Candidate cand : candidates) {
                if (avail.getOrDefault(cand.key(), 0) <= 0) continue;
                float[] fv = cand.values();
                if (fv == null || breachesMax(current, catMaxs, fv)) continue;
                float impact = foodValueMagnitude(fv);
                boolean preferred = cand.preferred();
                if (best == null || (preferred && !bestPreferred)
                        || (preferred == bestPreferred && impact < bestImpact)) {
                    bestImpact = impact;
                    best = cand;
                    bestPreferred = preferred;
                }
            }
            if (best == null) return null;
            take(best, avail, chosen, current);
        }

        return chosen;
    }

    private static void take(Candidate candidate, Map<MaterialKey, Integer> avail,
                             List<ItemStack> chosen, float[] current) {
        avail.merge(candidate.key(), -1, Integer::sum);
        chosen.add(candidate.key().toStack(1));
        for (int c = 0; c < CAT; c++) current[c] += candidate.values()[c];
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
