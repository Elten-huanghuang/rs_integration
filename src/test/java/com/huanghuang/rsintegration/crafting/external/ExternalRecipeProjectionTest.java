package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.ItemAlternative;
import com.huanghuang.rsintegration.api.recipe.ItemIngredientView;
import com.huanghuang.rsintegration.api.recipe.MaterialRequirement;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbability;
import com.huanghuang.rsintegration.api.recipe.OutputProbabilityMode;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeFlags;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ExternalRecipeProjectionTest extends BootstrapTest {
    @Test
    void mapsInputsCountsConsumptionAndExactNbt() {
        CompoundTag identity = new CompoundTag();
        identity.putString("variant", "fine");
        RecipeView view = view(
                List.of(material(
                        "input",
                        ingredient(ResourceMatchMode.EXACT_IDENTITY,
                                item(Items.DIAMOND, identity)),
                        3L,
                        InputConsumption.durability(2),
                        true)),
                List.of(output("result", Items.STICK, 2L))
        );

        ExternalRecipeProjection.Result result = inspect(view);
        assertTrue(result.available());
        List<IngredientSpec> ingredients =
                ExternalRecipeProjection.ingredients(result.view());

        assertEquals(1, ingredients.size());
        assertEquals(3, ingredients.get(0).count());
        assertEquals("DURABILITY",
                ingredients.get(0).consumption().kind().name());
        ItemStack candidate = new ItemStack(Items.DIAMOND);
        candidate.setTag(identity.copy());
        assertTrue(ingredients.get(0).ingredient().test(candidate));
        assertFalse(ingredients.get(0).ingredient().test(
                new ItemStack(Items.DIAMOND)));
    }

    @Test
    void anyIdentityIgnoresNbtAndPartialIdentityRequiresSubset() {
        CompoundTag template = new CompoundTag();
        template.putString("quality", "fine");
        CompoundTag nested = new CompoundTag();
        nested.putString("school", "fire");
        template.put("spell", nested);

        IngredientSpec any = ExternalRecipeProjection.ingredients(view(
                List.of(material(
                        "any",
                        ingredient(ResourceMatchMode.ANY_IDENTITY,
                                item(Items.DIAMOND, template)),
                        1L,
                        InputConsumption.consumed(),
                        true)),
                List.of(output("result", Items.STICK, 1L))
        )).get(0);
        ItemStack unrelated = new ItemStack(Items.DIAMOND);
        unrelated.getOrCreateTag().putString("other", "value");
        assertTrue(any.ingredient().test(unrelated));

        IngredientSpec partial = ExternalRecipeProjection.ingredients(view(
                List.of(material(
                        "partial",
                        ingredient(ResourceMatchMode.PARTIAL_IDENTITY,
                                item(Items.DIAMOND, template)),
                        1L,
                        InputConsumption.consumed(),
                        true)),
                List.of(output("result", Items.STICK, 1L))
        )).get(0);
        ItemStack richer = unrelated.copy();
        richer.getOrCreateTag().putString("quality", "fine");
        CompoundTag spell = new CompoundTag();
        spell.putString("school", "fire");
        spell.putInt("level", 3);
        richer.getOrCreateTag().put("spell", spell);
        assertTrue(partial.ingredient().test(richer));
        richer.getOrCreateTag().putString("quality", "plain");
        assertFalse(partial.ingredient().test(richer));
    }

    @Test
    void exactSecondaryProjectionDoesNotInventProbabilisticOutputs() {
        OutputDeclaration primary = output("primary", Items.DIAMOND, 1L);
        OutputDeclaration secondary = output("secondary", Items.STICK, 4L);
        OutputDeclaration probabilistic = new OutputDeclaration(
                id("probabilistic"),
                "output",
                item(Items.APPLE, new CompoundTag()),
                OutputAmount.exactly(1L),
                OutputKind.SECONDARY,
                new OutputProbability(
                        OutputProbabilityMode.PROBABILISTIC, 0.25D, 1, "bonus"),
                false,
                false,
                null
        );

        List<ItemStack> outputs = ExternalRecipeProjection.exactSecondaryOutputs(
                view(List.of(), List.of(primary, secondary, probabilistic)));

        assertEquals(1, outputs.size());
        assertEquals(Items.STICK, outputs.get(0).getItem());
        assertEquals(4, outputs.get(0).getCount());
    }

    @Test
    void oversizedSlotAmountIsRejectedInsteadOfTruncated() {
        RecipeView view = view(
                List.of(material(
                        "input",
                        ingredient(ResourceMatchMode.ANY_IDENTITY,
                                item(Items.DIAMOND, new CompoundTag())),
                        (long) Integer.MAX_VALUE + 1L,
                        InputConsumption.consumed(),
                        true)),
                List.of(output("result", Items.STICK, 1L))
        );

        ExternalRecipeProjection.Result result = inspect(view);

        assertFalse(result.available());
        assertEquals(
                IntegrationDiagnosticCode.MATERIAL_DECLARATION_INVALID,
                result.diagnostics().get(0).code());
    }

    private static ExternalRecipeProjection.Result inspect(RecipeView view) {
        return ExternalRecipeProjection.inspect(
                new StubIntegration(view),
                mock(Recipe.class),
                RegistryAccess.EMPTY
        );
    }

    private static RecipeView view(
            List<MaterialRequirement> materials,
            List<OutputDeclaration> outputs
    ) {
        boolean runtimeIdentity = outputs.stream().anyMatch(
                OutputDeclaration::dynamicIdentity);
        boolean probabilistic = outputs.stream().anyMatch(output ->
                output.probability().mode() != OutputProbabilityMode.GUARANTEED);
        boolean fuel = materials.stream().anyMatch(material ->
                material.consumption().kind() == InputConsumption.Kind.FUEL);
        return new RecipeView(
                id("recipe"),
                id("process"),
                materials,
                List.of(),
                outputs,
                new RecipeFlags(
                        true,
                        outputs.stream().anyMatch(OutputDeclaration::targetProducing),
                        !probabilistic,
                        runtimeIdentity,
                        fuel,
                        true,
                        false
                )
        );
    }

    private static MaterialRequirement material(
            String entry,
            ItemIngredientView ingredient,
            long amount,
            InputConsumption consumption,
            boolean recursive
    ) {
        return new MaterialRequirement(
                id(entry),
                ingredient,
                amount,
                consumption,
                "ingredients",
                0,
                recursive
        );
    }

    private static ItemIngredientView ingredient(
            ResourceMatchMode mode,
            IntegrationResourceKey resource
    ) {
        return new ItemIngredientView(
                List.of(new ItemAlternative(resource)),
                mode
        );
    }

    private static OutputDeclaration output(
            String port,
            Item item,
            long amount
    ) {
        return new OutputDeclaration(
                id(port),
                "output",
                item(item, new CompoundTag()),
                OutputAmount.exactly(amount),
                OutputKind.PRIMARY,
                OutputProbability.guaranteed(),
                false,
                false,
                null
        );
    }

    private static IntegrationResourceKey item(
            Item item,
            CompoundTag identity
    ) {
        return new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(item),
                identity
        );
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }

    private record StubIntegration(RecipeView view)
            implements RecipeIntegration<Recipe<?>> {
        @Override
        public ResourceLocation id() {
            return new ResourceLocation("test", "integration");
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return true;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                Recipe<?> recipe
        ) {
            return new RecipeInspectionResult(view, List.of());
        }
    }
}
