package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecoveryRoutePolicyTest extends BootstrapTest {
    @Test
    void liveClassificationRequiresTheExpectedConsumptionAndGuaranteedOutput() {
        DeepPlanningFixture.initializeMod();
        var recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(ResourceLocation.parse("legendary_monsters:iron_ingot_knights_sword_half"));
        var route = new RecipeIndex.Entry(recipe, ModType.GENERIC, ResourceLocation.parse("minecraft:crafting"));
        var ctx = mock(ResolutionContext.class);
        var output = new ItemStack(Items.IRON_INGOT);
        // 配方声明的是不带 NBT 的剑刃材料，不用会自带 Damage 标签的成品剑当替身。
        var input = Ingredient.of(Items.FLINT);
        var ids = Map.of(Items.FLINT, ResourceLocation.parse("legendary_monsters:knights_sword_blade_half"),
                Items.IRON_INGOT, ResourceLocation.parse("minecraft:iron_ingot"));
        var primary = RecipeOutputSpec.item(MaterialKey.of(output), 1, OutputKind.PRIMARY);
        when(ctx.declaredRecipeOutputs(recipe)).thenReturn(List.of(primary));
        when(ctx.declaredRecursiveInputs(route)).thenReturn(List.of(new IngredientSpec(input, 1)));
        assertEquals(RecoveryRoutePolicy.EQUIPMENT_RECOVERY, RecoveryRoutePolicy.classify(route, output, ctx, ids::get));
        when(ctx.declaredRecursiveInputs(route)).thenReturn(List.of(new IngredientSpec(input, 1, DemandRole.CATALYST)));
        assertEquals(RecoveryRoutePolicy.NORMAL, RecoveryRoutePolicy.classify(route, output, ctx, ids::get));
        when(ctx.declaredRecursiveInputs(route)).thenReturn(List.of(new IngredientSpec(input, 1)));
        when(ctx.declaredRecipeOutputs(recipe)).thenReturn(List.of(primary,
                RecipeOutputSpec.item(MaterialKey.of(new ItemStack(Items.GOLD_INGOT)), 1, OutputKind.SECONDARY)));
        assertEquals(RecoveryRoutePolicy.NORMAL, RecoveryRoutePolicy.classify(route, output, ctx, ids::get));
    }
}
