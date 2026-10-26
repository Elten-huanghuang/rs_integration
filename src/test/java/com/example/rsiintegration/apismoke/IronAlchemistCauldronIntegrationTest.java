package com.example.rsiintegration.apismoke;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.api.recipe.OutputProbabilityMode;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IronAlchemistCauldronIntegrationTest
        extends BootstrapTest {
    @Test
    void chanceRecipeJsonUsesTheRegisteredSerializerShape() {
        IronAlchemistChanceRecipe recipe =
                new IronAlchemistChanceRecipe.Serializer().fromJson(
                        new ResourceLocation(
                                "rsi_api_smoke",
                                "iron_alchemist/common"),
                        JsonParser.parseString("""
                                {
                                  "input_item": "minecraft:paper",
                                  "output_fluid": "minecraft:water",
                                  "output_amount": 250
                                }
                                """).getAsJsonObject()
                );

        assertEquals(1, recipe.input().getItems().length);
        assertEquals(Items.PAPER, recipe.input().getItems()[0].getItem());
        assertEquals(
                new ResourceLocation("minecraft", "water"),
                recipe.outputFluid()
        );
        assertEquals(250, recipe.outputAmount());
    }

    @Test
    void smokeRecipeProjectsIronRecycleAsProbabilisticFluidOutput() {
        IronAlchemistChanceRecipe recipe =
                new IronAlchemistChanceRecipe(
                        new ResourceLocation(
                                "rsi_api_smoke",
                                "iron_alchemist_chance"),
                        Ingredient.of(Items.PAPER),
                        new ResourceLocation("minecraft", "water"),
                        250
                );
        IronAlchemistCauldronIntegration integration =
                new IronAlchemistCauldronIntegration();

        RecipeInspectionResult result = integration.recipe().inspect(
                new RecipeContext(
                        new ResourceLocation(
                                "rsi_api_smoke",
                                "iron_alchemist"),
                        RegistryAccess.EMPTY,
                        1L
                ),
                recipe
        );

        RecipeView view = result.view();
        assertNotNull(view);
        assertEquals(
                RecipeExecutionKind.CUSTOM,
                integration.recipe().executionKind()
        );
        assertTrue(view.flags().recursivePlanning());
        assertFalse(view.flags().targetProduction());
        assertFalse(view.flags().deterministicOutput());
        assertEquals(1, view.materials().size());
        assertEquals(1, view.outputs().size());
        assertEquals(
                OutputProbabilityMode.PROBABILISTIC,
                view.outputs().get(0).probability().mode()
        );
        assertFalse(view.outputs().get(0).targetProducing());
        assertEquals(
                new ResourceLocation("minecraft", "water"),
                view.outputs().get(0).resource().id()
        );
    }

    @Test
    void machineAndExecutionUseTheRealCauldronContract() {
        IronAlchemistCauldronIntegration integration =
                new IronAlchemistCauldronIntegration();

        assertEquals(
                new ResourceLocation(
                        "irons_spellbooks",
                        "alchemist_cauldron"),
                integration.machine().bindings().get(0)
                        .blockIds().get(0)
        );
        assertEquals(
                new ResourceLocation(
                        "rsi_api_smoke",
                        "irons_spellbooks/alchemist_cauldron"),
                integration.machine().bindings().get(0)
                        .acceptedProcesses().iterator().next()
        );
    }

    @Test
    void everyNativeInkRarityMapsToItsLiveSmokeRecipe() {
        for (String rarity : new String[]{
                "common", "uncommon", "rare", "epic", "legendary"
        }) {
            assertEquals(
                    new ResourceLocation(
                            "rsi_api_smoke",
                            "iron_alchemist/" + rarity
                    ),
                    IronAlchemistCauldronIntegration.chanceRecipeId(
                            new ResourceLocation(
                                    "irons_spellbooks",
                                    rarity + "_ink"
                            )
                    )
            );
        }
    }

    @Test
    void jeiResolverReadsFluidOutputInsteadOfScrollNbt() {
        FluidStack output =
                IronAlchemistCauldronIntegration.jeiOutputFluid(
                        new FakeJeiRecipe()
                );

        assertNotNull(output);
        assertEquals(Fluids.WATER, output.getFluid());
        assertEquals(250, output.getAmount());
    }

    public static final class FakeJeiRecipe {
        public List<Object> results() {
            return List.of(new FluidStack(Fluids.WATER, 250));
        }
    }
}
