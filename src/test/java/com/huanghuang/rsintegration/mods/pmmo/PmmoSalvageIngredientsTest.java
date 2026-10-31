package com.huanghuang.rsintegration.mods.pmmo;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmmoSalvageIngredientsTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{Damage:97}",
            "{Enchantments:[{id:\"minecraft:efficiency\",lvl:3s}]}",
            "{display:{Name:'{\"text\":\"Salvage\"}'},RepairCost:5}",
            "{Damage:97,Unbreakable:1b,itemModifier:\"celestial_forge:sluggish\"}"})
    void declarationAndPlannerAcceptEveryNbtStateOfTheInputItem(String nbt) throws Exception {
        PmmoSalvageRecipeWrapper recipe = recipe();
        PmmoSalvageRecipeHandler handler = new PmmoSalvageRecipeHandler();
        var spec = handler.getIngredients(recipe).get(0);
        ItemStack actual = new ItemStack(Items.IRON_PICKAXE);
        actual.setTag(TagParser.parseTag(nbt));

        assertTrue(handler.preferHandlerIngredients());
        assertTrue(recipe.getIngredients().get(0).test(actual));
        assertTrue(IngredientMatcher.test(spec.ingredient(), actual));
        assertFalse(IngredientMatcher.requiresNbt(spec.ingredient()));
        assertEquals(DemandRole.CONSUMED, spec.role());
        var projected = ImmutableRecipeGraphProjector.projectIngredient(spec);
        assertNotNull(projected);
        assertEquals(NbtMatchMode.ANY, projected.nbtMatchMode());
        assertEquals("", projected.alternatives().get(0).nbt());
    }

    @Test
    void bulkSalvageCombinesDifferentNbtStatesWithoutAcceptingAnotherItem() throws Exception {
        var declared = new PmmoSalvageRecipeHandler().getIngredients(recipe()).get(0);
        Ingredient ingredient = declared.ingredient();
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(97);
        ItemStack enchanted = new ItemStack(Items.IRON_PICKAXE);
        enchanted.setTag(TagParser.parseTag("{Enchantments:[{id:\"minecraft:efficiency\",lvl:3s}]}"));
        Map<StackKey, Long> stock = Map.of(new StackKey(worn.getItem(), worn.getTag().toString()), 1L,
                new StackKey(enchanted.getItem(), enchanted.getTag().toString()), 1L);
        var spec = new ScaledIngredientSpec(ingredient, 2L, declared.consumption());

        var allocated = DirectMaterialAllocator.allocateScaledSingleVariant(List.of(spec), stock);

        assertTrue(allocated.feasible());
        assertEquals(2L, allocated.allocations().stream()
                .mapToLong(DirectMaterialAllocator.Allocation::count).sum());
        assertEquals(2, allocated.allocations().size());
        assertFalse(ingredient.test(new ItemStack(Items.DIAMOND_PICKAXE)));
    }

    private static PmmoSalvageRecipeWrapper recipe() {
        var output = new PmmoSalvageDefinition.Output(new ResourceLocation("minecraft", "iron_ingot"),
                3, 0.5, 1.0, Map.of(), Map.of(), Map.of());
        var definition = new PmmoSalvageDefinition(new ResourceLocation("minecraft", "iron_pickaxe"),
                List.of(output));
        return new PmmoSalvageRecipeWrapper(definition, output);
    }
}
