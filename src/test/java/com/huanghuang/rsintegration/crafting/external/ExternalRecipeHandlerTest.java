package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.ItemAlternative;
import com.huanghuang.rsintegration.api.recipe.ItemIngredientView;
import com.huanghuang.rsintegration.api.recipe.MaterialRequirement;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbability;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ExternalRecipeHandlerTest extends BootstrapTest {
    @Test
    void handlerUsesClaimPriorityAndCachesProjectionPerRevision() {
        AtomicInteger inspections = new AtomicInteger();
        RecipeView view = view();
        StubIntegration integration = new StubIntegration(view, inspections);
        ExternalRecipeHandler handler = new ExternalRecipeHandler(
                integration,
                ModType.EXTERNAL_INTEGRATION,
                37,
                recipe -> true
        );
        Recipe<?> recipe = mock(Recipe.class);

        assertEquals(37, handler.claimPriority());
        assertTrue(handler.canHandle(recipe));
        assertSame(ModType.EXTERNAL_INTEGRATION,
                ModType.fromBlockKey(
                        ModType.EXTERNAL_INTEGRATION.id()
                                + "||block.example.machine"));
        List<IngredientSpec> first = handler.getIngredients(recipe);
        List<IngredientSpec> second = handler.getIngredients(recipe);

        assertEquals(1, first.size());
        assertEquals(2, second.get(0).count());
        assertEquals(1, inspections.get());
        assertEquals(Items.STICK,
                handler.getResultItem(recipe, RegistryAccess.EMPTY).getItem());
        assertEquals(1, inspections.get());
    }

    @Test
    void declaredOutputsPreserveProbabilityAndQuantityRange() {
        IntegrationResourceKey input = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(Items.DIAMOND),
                new CompoundTag()
        );
        MaterialRequirement material = new MaterialRequirement(
                id("input"),
                new ItemIngredientView(
                        List.of(new ItemAlternative(input)),
                        ResourceMatchMode.ANY_IDENTITY
                ),
                1L,
                InputConsumption.consumed(),
                "ingredients",
                0,
                true
        );
        OutputDeclaration primary = output(
                "primary", Items.STICK, OutputAmount.exactly(1L),
                OutputProbability.guaranteed(), OutputKind.PRIMARY
        );
        OutputDeclaration secondary = output(
                "secondary", Items.APPLE, new OutputAmount(1L, 3L),
                OutputProbability.probabilistic(
                        0.25D, 4, "bonus"), OutputKind.SECONDARY
        );
        RecipeView view = new RecipeView(
                id("recipe"),
                id("process"),
                List.of(material),
                List.of(),
                List.of(primary, secondary),
                new RecipeFlags(
                        true, false, false, false,
                        false, true, false)
        );
        ExternalRecipeHandler handler = new ExternalRecipeHandler(
                new StubIntegration(view, new AtomicInteger()),
                ModType.EXTERNAL_INTEGRATION,
                1,
                recipe -> true
        );

        var outputs = handler.getDeclaredOutputs(
                mock(Recipe.class), RegistryAccess.EMPTY);

        assertEquals(2, outputs.size());
        assertEquals(0, outputs.get(1).minimumPerOperation());
        assertEquals(3, outputs.get(1).maximumPerOperation());
        assertEquals(0, outputs.get(1).expectedPerOperation());
        assertEquals(0.25D, outputs.get(1).probability().chance());
    }

    private static RecipeView view() {
        IntegrationResourceKey input = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(Items.DIAMOND),
                new CompoundTag()
        );
        MaterialRequirement material = new MaterialRequirement(
                id("input"),
                new ItemIngredientView(
                        List.of(new ItemAlternative(input)),
                        ResourceMatchMode.ANY_IDENTITY
                ),
                2L,
                InputConsumption.consumed(),
                "ingredients",
                0,
                true
        );
        OutputDeclaration output = new OutputDeclaration(
                id("output"),
                "output",
                new IntegrationResourceKey(
                        IntegrationResourceKey.ResourceKind.ITEM,
                        BuiltInRegistries.ITEM.getKey(Items.STICK),
                        new CompoundTag()
                ),
                OutputAmount.exactly(1L),
                OutputKind.PRIMARY,
                OutputProbability.guaranteed(),
                false,
                false,
                null
        );
        return new RecipeView(
                id("recipe"),
                id("process"),
                List.of(material),
                List.of(),
                List.of(output),
                new RecipeFlags(
                        true, false, true, false,
                        false, true, true)
        );
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }

    private static OutputDeclaration output(
            String port,
            Item item,
            OutputAmount amount,
            OutputProbability probability,
            OutputKind kind
    ) {
        return new OutputDeclaration(
                id(port),
                "output",
                new IntegrationResourceKey(
                        IntegrationResourceKey.ResourceKind.ITEM,
                        BuiltInRegistries.ITEM.getKey(item),
                        new CompoundTag()
                ),
                amount,
                kind,
                probability,
                false,
                false,
                null
        );
    }

    private record StubIntegration(
            RecipeView view,
            AtomicInteger inspections
    ) implements RecipeIntegration<Recipe<?>> {
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
            inspections.incrementAndGet();
            return new RecipeInspectionResult(view, List.of());
        }
    }
}
