package com.example.rsiintegration.apismoke;

import com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantingApparatusRecipe;
import com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArsNouveauIntegrationTest extends BootstrapTest {
    @Test
    void machineBindingsUseRealArsBlockIds() {
        ArsNouveauIntegration integration =
                new ArsNouveauIntegration();

        List<MachineBinding> bindings =
                integration.machine().bindings();

        assertEquals(2, bindings.size());
        assertEquals(
                new ResourceLocation("ars_nouveau", "imbuement_chamber"),
                bindings.get(0).blockIds().get(0)
        );
        assertEquals(
                new ResourceLocation(
                        "ars_nouveau", "enchanting_apparatus"),
                bindings.get(1).blockIds().get(0)
        );
    }

    @Test
    void imbuementRecipeProjectsOrderedStandardIoMaterials() {
        ImbuementRecipe recipe = new ImbuementRecipe(
                new ResourceLocation("ars_nouveau", "test_imbuement"),
                Ingredient.of(Items.DIAMOND),
                new ItemStack(Items.EMERALD),
                100,
                List.of(Ingredient.of(Items.GOLD_INGOT))
        );
        ArsNouveauIntegration integration =
                new ArsNouveauIntegration();

        RecipeInspectionResult result = integration.recipe().inspect(
                recipeContext(), recipe);

        RecipeView view = result.view();
        assertNotNull(view);
        assertEquals(2, view.materials().size());
        assertEquals("pedestal", view.materials().get(0).inputGroup());
        assertEquals(
                InputConsumption.Kind.CATALYST,
                view.materials().get(0).consumption().kind()
        );
        assertEquals("reagent", view.materials().get(1).inputGroup());
        assertEquals(
                InputConsumption.Kind.CONSUMED,
                view.materials().get(1).consumption().kind()
        );
        assertTrue(view.flags().orderedInputs());
        assertEquals(
                new ResourceLocation("minecraft", "emerald"),
                view.outputs().get(0).resource().id()
        );
    }

    @Test
    void ordinaryApparatusRecipeProjectsAsConsumedMaterials() {
        EnchantingApparatusRecipe recipe =
                new EnchantingApparatusRecipe(
                        new ResourceLocation(
                                "ars_nouveau", "test_apparatus"),
                        List.of(Ingredient.of(Items.AMETHYST_SHARD)),
                        Ingredient.of(Items.STICK),
                        new ItemStack(Items.BLAZE_ROD),
                        50,
                        false
                );
        ArsNouveauIntegration integration =
                new ArsNouveauIntegration();

        RecipeInspectionResult result = integration.recipe().inspect(
                recipeContext(), recipe);

        RecipeView view = result.view();
        assertNotNull(view);
        assertEquals(2, view.materials().size());
        assertTrue(view.materials().stream().allMatch(material ->
                material.consumption().kind()
                        == InputConsumption.Kind.CONSUMED));
        assertEquals(
                new ResourceLocation("minecraft", "blaze_rod"),
                view.outputs().get(0).resource().id()
        );
    }

    private static RecipeContext recipeContext() {
        return new RecipeContext(
                new ResourceLocation("rsi_api_smoke", "ars_nouveau"),
                RegistryAccess.EMPTY,
                1L
        );
    }
}
