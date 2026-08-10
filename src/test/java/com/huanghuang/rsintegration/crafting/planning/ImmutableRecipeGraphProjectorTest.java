package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmutableRecipeGraphProjectorTest extends BootstrapTest {
    @Test
    void rejectsReusableCatalystBecausePureGraphCannotPreserveItsRole() {
        assertNull(ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(
                Ingredient.of(Items.IRON_BLOCK), 1, DemandRole.CATALYST)));
    }


    @Test
    void vanillaIngredientDoesNotProjectDisplayNbtOrDamageAsARequirement() {
        ItemStack damaged = new ItemStack(Items.IRON_HELMET);
        damaged.setDamageValue(37);

        IngredientRef projected = ImmutableRecipeGraphProjector.projectIngredient(
                new IngredientSpec(Ingredient.of(damaged), 1));

        assertTrue(projected.alternatives().get(0).nbt().isEmpty());
    }

    @Test
    void strictIngredientStillProjectsExactNbt() {
        ItemStack damaged = new ItemStack(Items.IRON_HELMET);
        damaged.setDamageValue(37);

        IngredientRef projected = ImmutableRecipeGraphProjector.projectIngredient(
                new IngredientSpec(StrictNBTIngredient.of(damaged), 1));

        assertEquals(damaged.getTag().toString(), projected.alternatives().get(0).nbt());
    }
}
