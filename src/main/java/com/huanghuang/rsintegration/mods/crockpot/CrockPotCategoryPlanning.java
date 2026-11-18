package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialAccess;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotFoodValues.Candidate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主线程冻结配方和库存语义；求解只消费一次请求的快照。 */
public final class CrockPotCategoryPlanning {
    private CrockPotCategoryPlanning() {}

    private record Fixed(IngredientSpec spec, List<MaterialKey> options, @Nullable MaterialKey fallback) {}
    private record Term(List<Fixed> fixed, float[] mins, float[] maxs) {}

    public static final class Context {
        private final MaterialKey target;
        private final int slots;
        private final List<Term> terms;
        private final List<Candidate> candidates;
        private final Map<MaterialKey, Integer> stock;
        private final Map<MaterialKey, float[]> values;

        private Context(MaterialKey target, int slots, List<Term> terms, List<Candidate> candidates,
                        Map<MaterialKey, Integer> stock, Map<MaterialKey, float[]> values) {
            this.target = target;
            this.slots = slots;
            this.terms = terms;
            this.candidates = candidates;
            this.stock = stock;
            this.values = values;
        }
    }

    @Nullable
    public static Context capture(MaterialAccess access, Recipe<?> recipe, @Nullable ItemStack target,
                                  @Nullable CraftStorageEndpoint endpoint, ServerPlayer player,
                                  Level level, int slots) {
        requireCaptureThread(level);
        if (endpoint == null) return null;
        var snapshot = access.snapshot(player, endpoint).snapshot().orElse(null);
        if (snapshot == null) return null;
        Map<StackKey, Long> stock = new LinkedHashMap<>();
        for (var item : snapshot.items()) {
            stock.merge(StackKey.of(item.stack(), true), Math.min(Integer.MAX_VALUE, item.amount()),
                    (a, b) -> Math.min(Integer.MAX_VALUE, a + b));
        }
        return capture(recipe, target, stock, level, slots);
    }

    @Nullable
    public static Context capture(Recipe<?> recipe, @Nullable ItemStack target, Map<StackKey, Long> available,
                                  Level level, int slots) {
        requireCaptureThread(level);
        if (slots <= 0 || !CrockPotFoodValues.isReady()) return null;
        ItemStack output = CrockPotRecipeHandler.declaredOutput(recipe, level.registryAccess());
        if (output.isEmpty()) return null;
        if (target != null && !target.isEmpty() && (!ItemStack.isSameItem(target, output)
                || target.hasTag() && !ItemStack.isSameItemSameTags(target, output))) return null;

        Ingredient preferred = CrockPotRecipeHandler.resolveFillerIngredient();
        Map<MaterialKey, Integer> stock = new LinkedHashMap<>();
        Map<MaterialKey, float[]> values = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (var entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            ItemStack stack = entry.getKey().toStack();
            if (stack.isEmpty()) continue;
            MaterialKey key = MaterialKey.of(stack);
            stock.put(key, (int) Math.min(Integer.MAX_VALUE, entry.getValue()));
            candidates.add(new Candidate(key, captureValues(key, stack, level, values),
                    preferred != null && IngredientMatcher.test(preferred, stack)));
        }
        List<Term> terms = new ArrayList<>();
        for (var term : CrockPotRecipeHandler.expandRequirements(recipe)) {
            List<Fixed> fixed = new ArrayList<>();
            for (var spec : term.fixed()) {
                if (spec.isEmpty()) continue;
                // 自定义材料谓词可能依赖模组状态，匹配也须留在捕获阶段。
                List<MaterialKey> options = stock.keySet().stream()
                        .filter(key -> IngredientMatcher.test(spec.ingredient(), key.toStack(1))).toList();
                MaterialKey fallback = null;
                for (ItemStack option : spec.ingredient().getItems()) {
                    if (option.isEmpty()) continue;
                    fallback = MaterialKey.of(option);
                    captureValues(fallback, option, level, values);
                    break;
                }
                fixed.add(new Fixed(spec, options, fallback));
            }
            terms.add(new Term(fixed, term.mins().clone(), term.maxs().clone()));
        }
        return new Context(MaterialKey.of(output), slots, terms, candidates, stock, values);
    }

    private static void requireCaptureThread(Level level) {
        var server = level.getServer();
        if (server != null && !server.isSameThread()) {
            throw new IllegalStateException("烹饪锅材料上下文必须在服务器主线程捕获");
        }
    }

    private static float[] captureValues(MaterialKey key, ItemStack stack, Level level, Map<MaterialKey, float[]> values) {
        if (!values.containsKey(key)) {
            float[] result = CrockPotFoodValues.compute(stack, level);
            values.put(key, result == null ? null : result.clone());
        }
        return values.get(key);
    }

    public record Plan(MaterialKey target, List<IngredientSpec> fixed, List<ItemStack> filler) {
        public List<IngredientSpec> ingredients() {
            List<IngredientSpec> result = new ArrayList<>(fixed);
            Map<MaterialKey, Integer> grouped = new LinkedHashMap<>();
            for (ItemStack stack : filler) grouped.merge(MaterialKey.of(stack), 1, Integer::sum);
            grouped.forEach((key, count) -> result.add(new IngredientSpec(ingredient(key.toStack(1)), count)));
            return result;
        }
    }

    static Ingredient ingredient(ItemStack stack) {
        return stack.hasTag() ? StrictNBTIngredient.of(stack.copyWithCount(1)) : Ingredient.of(stack);
    }

    @Nullable
    public static List<IngredientSpec> ingredients(@Nullable Context context) {
        Plan plan = solve(context);
        return plan == null ? null : plan.ingredients();
    }

    @Nullable
    public static Plan solve(@Nullable Context context) {
        if (context == null) return null;
        for (Term term : context.terms) {
            Map<MaterialKey, Integer> remaining = new HashMap<>(context.stock);
            List<IngredientSpec> fixed = new ArrayList<>();
            float[] total = new float[CrockPotRecipeHandler.CAT_COUNT];
            int slots = context.slots;
            for (Fixed requirement : term.fixed()) {
                IngredientSpec spec = requirement.spec();
                if (spec.count() > slots) { slots = -1; break; }
                slots -= spec.count();
                MaterialKey pick = requirement.options().stream()
                        .filter(key -> remaining.getOrDefault(key, 0) >= spec.count()).findFirst().orElse(null);
                if (pick != null) {
                    remaining.merge(pick, -spec.count(), Integer::sum);
                    fixed.add(new IngredientSpec(ingredient(pick.toStack(1)), spec.count(), spec.consumption()));
                } else {
                    // 固定材料可由递归规划补产，保留原始谓词与消耗语义。
                    pick = requirement.fallback();
                    if (pick == null) { slots = -1; break; }
                    fixed.add(spec);
                }
                float[] values = context.values.get(pick);
                if (values != null) for (int c = 0; c < total.length; c++) total[c] += values[c] * spec.count();
            }
            if (slots < 0) continue;
            List<ItemStack> filler = CrockPotFoodValues.select(total, term.mins(), term.maxs(),
                    slots, context.candidates, remaining);
            if (filler != null) return new Plan(context.target, List.copyOf(fixed), List.copyOf(filler));
        }
        return null;
    }
}
