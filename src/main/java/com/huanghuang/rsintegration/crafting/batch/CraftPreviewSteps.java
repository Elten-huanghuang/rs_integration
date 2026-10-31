package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladePlanning;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 将重复解析步骤折叠为可显示的预览步骤，保留批次数和状态身份。 */
public final class CraftPreviewSteps {
    private CraftPreviewSteps() {}

    record PreviewStep(ResolutionStep step, int batches) {}

    /** 将条件展示栈连接到服务端已规划的实际生产者，避免 UUID/额外 NBT 让树断层。 */
    static List<PlanStep> linkSlashBladeInputs(List<PlanStep> steps,
                                              Function<ResourceLocation, Recipe<?>> recipes, RegistryAccess access) {
        Map<BladeKey, List<PlanStep>> byBlade = new HashMap<>();
        for (PlanStep step : steps) {
            if (step.modType() != null && "slashblade".equals(step.modType().id())
                    && step.output().getTag() != null) {
                String name = step.output().getTag().getCompound("bladeState").getString("translationKey");
                byBlade.computeIfAbsent(new BladeKey(step.output().getItem(), name),
                        ignored -> new ArrayList<>()).add(step);
            }
        }
        if (byBlade.isEmpty()) return steps;
        List<PlanStep> result = new ArrayList<>(steps.size());
        for (PlanStep step : steps) {
            Recipe<?> recipe = recipes.apply(step.recipeId());
            List<Ingredient> ingredients = recipe == null ? List.of() : recipe.getIngredients();
            if (recipe != null && step.modType() != null && "slashblade".equals(step.modType().id())) {
                ingredients = SlashBladePlanning.requireOutputIngredients(
                        ingredients, recipe.getResultItem(access), step.output());
            }
            List<ItemStack> inputs = null;
            if (ingredients.size() == step.inputs().size()) {
                for (int i = 0; i < ingredients.size(); i++) {
                    Ingredient ingredient = ingredients.get(i);
                    CompoundTag requirement = SlashBladePlanning.requirement(ingredient);
                    if (requirement == null) continue;
                    ItemStack display = step.inputs().get(i);
                    String name = requirement.getCompound("bladeState").getString("translationKey");
                    for (PlanStep producer : byBlade.getOrDefault(new BladeKey(display.getItem(), name), List.of())) {
                        if (producer.recipeId().equals(step.recipeId())
                                || !SlashBladePlanning.matches(requirement, producer.output().getTag())) continue;
                        if (inputs == null) inputs = new ArrayList<>(step.inputs());
                        inputs.set(i, producer.output().copyWithCount(display.getCount()));
                        break;
                    }
                }
            }
            result.add(inputs == null ? step : new PlanStep(step.recipeId(), step.output(), step.batches(),
                    inputs, step.alternatives(), step.modType(), step.depth(), step.hasOrSiblings(),
                    step.recipeWidth(), step.recipeHeight(), step.alternativeModTypes(), step.inputRoles()));
        }
        return result;
    }

    private record BladeKey(Item item, String name) {}

    static List<PreviewStep> merge(
            @Nullable List<ResolutionStep> steps,
            Function<ResourceLocation, Recipe<?>> recipes, RegistryAccess access) {
        if (steps == null) return List.of();
        Map<String, PreviewStep> merged = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            ResolutionStep step = steps.get(i);
            String key = step.recipeId().toString();
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
