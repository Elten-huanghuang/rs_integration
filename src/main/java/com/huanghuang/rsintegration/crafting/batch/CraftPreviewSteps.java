package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 将重复解析步骤折叠为可显示的预览步骤，保留批次数和状态身份。 */
public final class CraftPreviewSteps {
    private CraftPreviewSteps() {}

    record PreviewStep(ResolutionStep step, int batches) {}

    static List<PreviewStep> merge(
            @Nullable List<ResolutionStep> steps,
            Function<ResourceLocation, Recipe<?>> recipes, RegistryAccess access) {
        if (steps == null) return List.of();
        Map<String, PreviewStep> merged = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            ResolutionStep step = steps.get(i);
            String key = step.recipeId().toString();
            Recipe<?> recipe = recipes.apply(step.recipeId());
            if (recipe != null && CraftExecutionPolicy.isSelfAmplifyingRecipe(recipe, access)) {
                key += "|amplification-stage:" + i;
            }
            if (step.syntheticInput() != null && step.syntheticOutput() != null) {
                key += "|" + IngredientKey.of(step.syntheticInput()).hashCode()
                        + "|" + IngredientKey.of(step.syntheticOutput()).hashCode();
            } else if (step.syntheticOutput() != null) {
                key += "|state:" + step.syntheticOutput().getTag();
            }
            PreviewStep previous = merged.get(key);
            int batches = step.executions();
            if (previous != null) {
                long combined = (long) previous.batches() + batches;
                if (combined > Integer.MAX_VALUE) {
                    key += "|batch-segment:" + i;
                    previous = null;
                } else {
                    batches = (int) combined;
                }
                if (previous != null) step = previous.step();
            }
            merged.put(key, new PreviewStep(step, batches));
        }
        return List.copyOf(merged.values());
    }
}
