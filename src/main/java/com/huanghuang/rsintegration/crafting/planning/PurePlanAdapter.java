package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.ProductionTarget;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Bridges pure planner output to the existing generic plan representation. */
public final class PurePlanAdapter {
    private PurePlanAdapter() {}

    @Nullable
    private static ItemStack demandedStack(ImmutableRecipeGraph.MaterialRef material) {
        if (material == null || !material.isItem()) return null;
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(material.itemId()));
        try {
            if (!material.nbt().isEmpty()) stack.setTag(TagParser.parseTag(material.nbt()));
        } catch (CommandSyntaxException exception) {
            throw new IllegalArgumentException("Invalid planned output state", exception);
        }
        return stack;
    }

    @Nullable
    private static ProductionTarget fluidProductionTarget(
            ImmutableRecipeGraph.MaterialRef material,
            long executions
    ) {
        if (material == null || !material.isFluid()) return null;
        var fluid = ForgeRegistries.FLUIDS.getValue(material.resourceId());
        if (fluid == null) return null;
        FluidStack stack = new FluidStack(fluid, 1);
        if (!material.nbt().isEmpty()) {
            try {
                stack.setTag(TagParser.parseTag(material.nbt()));
            } catch (CommandSyntaxException exception) {
                throw new IllegalArgumentException(
                        "Invalid planned fluid state", exception);
            }
        }
        return ProductionTarget.of(
                IntegrationResourceKey.fluid(stack),
                Math.max(1L, executions)
        );
    }

    @Nullable
    private static ProductionTarget productionTarget(
            RecipeNode node,
            long executions
    ) {
        return fluidProductionTarget(node.output(), executions);
    }

    public static List<ResolutionStep> toResolutionSteps(PureRecipePlanner.Result result,
                                                          ImmutableRecipeGraph graph) {
        List<ResolutionStep> projected = new ArrayList<>();
        for (PureRecipePlanner.PlannedStep step : result.steps()) {
            ImmutableRecipeGraph.RecipeNode node = findRecipe(graph, step.recipeId());
            if (node == null) {
                throw new IllegalStateException(
                        "Planned recipe missing from immutable graph: " + step.recipeId());
            }
            long remaining = Math.max(1L, step.batches());
            while (remaining > 0L) {
                int executions = (int) Math.min(Integer.MAX_VALUE, remaining);
                projected.add(new ResolutionStep(
                        step.recipeId(), ModType.byId(node.modTypeId()),
                        node.recipeTypeId(), List.of(), List.of(), false,
                        executions, null, demandedStack(step.demandedOutput()),
                        productionTarget(
                                node,
                                SaturatingLongMath.multiply(
                                        node.outputCount(),
                                        executions
                                )
                        ),
                        node.magicCosts()));
                remaining -= executions;
            }
        }
        return List.copyOf(projected);
    }

    private static ImmutableRecipeGraph.RecipeNode findRecipe(
            ImmutableRecipeGraph graph, ResourceLocation recipeId) {
        ImmutableRecipeGraph.RecipeNode indexed = graph.recipesById().get(recipeId);
        if (indexed != null) return indexed;
        return graph.recipesByOutput().values().stream()
                .flatMap(List::stream)
                .filter(recipe -> recipe.recipeId().equals(recipeId))
                .findFirst()
                .orElse(null);
    }
}
