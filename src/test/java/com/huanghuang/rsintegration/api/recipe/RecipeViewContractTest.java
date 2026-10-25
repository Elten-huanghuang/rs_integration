package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeViewContractTest {
    private static final ResourceLocation RECIPE_ID =
            new ResourceLocation("test", "recipe");
    private static final ResourceLocation PROCESS_ID =
            new ResourceLocation("test", "process");

    @Test
    void inputConsumptionRejectsInvalidKindSpecificValues() {
        assertEquals(InputConsumption.Kind.DURABILITY,
                InputConsumption.durability(1).kind());
        assertThrows(IllegalArgumentException.class,
                () -> InputConsumption.durability(0));
        assertThrows(IllegalArgumentException.class,
                () -> InputConsumption.fuel(0L));
        assertThrows(IllegalArgumentException.class,
                () -> InputConsumption.transformed(null));
    }

    @Test
    void recipeViewRejectsDuplicateInputAndOutputIds() {
        MaterialRequirement material = material("input", InputConsumption.consumed());
        OutputDeclaration output = output("output", OutputKind.PRIMARY);

        assertThrows(IllegalArgumentException.class, () -> new RecipeView(
                RECIPE_ID, PROCESS_ID, List.of(material, material), List.of(),
                List.of(output), flags()));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView(
                RECIPE_ID, PROCESS_ID, List.of(material), List.of(),
                List.of(output, output), flags()));
    }

    @Test
    void transformedInputMustLinkMatchingOutput() {
        MaterialRequirement transformed = material(
                "input", InputConsumption.transformed(outputId("output")));
        OutputDeclaration wrongKind = output("output", OutputKind.PRIMARY);

        assertThrows(IllegalArgumentException.class, () -> new RecipeView(
                RECIPE_ID, PROCESS_ID, List.of(transformed), List.of(),
                List.of(wrongKind), flags()));
    }

    @Test
    void flagsMustMatchDeclaredSemantics() {
        MaterialRequirement material = material("input", InputConsumption.consumed());
        OutputDeclaration output = output("output", OutputKind.PRIMARY);
        RecipeFlags inconsistent = new RecipeFlags(
                true, true, true, false, false, true, true);

        assertThrows(IllegalArgumentException.class, () -> new RecipeView(
                RECIPE_ID, PROCESS_ID, List.of(material), List.of(),
                List.of(output), inconsistent));
    }

    @Test
    void definitionFingerprintIsStableAcrossNbtKeyOrderAndChangesWithSemantics() {
        CompoundTag first = new CompoundTag();
        first.putString("name", "alpha");
        first.putInt("amount", 2);
        CompoundTag second = new CompoundTag();
        second.putInt("amount", 2);
        second.putString("name", "alpha");
        MaterialRequirement firstMaterial = material("input",
                InputConsumption.consumed(), first, 2L);
        MaterialRequirement secondMaterial = material("input",
                InputConsumption.consumed(), second, 2L);
        OutputDeclaration output = output("output", OutputKind.PRIMARY);

        RecipeView firstView = new RecipeView(
                RECIPE_ID, PROCESS_ID, List.of(firstMaterial), List.of(),
                List.of(output), flags());
        RecipeView secondView = new RecipeView(
                RECIPE_ID, PROCESS_ID, List.of(secondMaterial), List.of(),
                List.of(output), flags());
        RecipeView changedAmount = new RecipeView(
                RECIPE_ID, PROCESS_ID,
                List.of(material("input", InputConsumption.consumed(), second, 3L)),
                List.of(), List.of(output), flags());

        assertEquals(firstView.definitionFingerprint(),
                secondView.definitionFingerprint());
        assertNotEquals(firstView.definitionFingerprint(),
                changedAmount.definitionFingerprint());
    }

    private static RecipeFlags flags() {
        return new RecipeFlags(true, false, true, false, false, true, true);
    }

    private static MaterialRequirement material(
            String entry, InputConsumption consumption) {
        return material(entry, consumption, new CompoundTag(), 1L);
    }

    private static MaterialRequirement material(
            String entry, InputConsumption consumption, CompoundTag identity, long amount) {
        IntegrationResourceKey resource = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                new ResourceLocation("test", "item_" + entry), identity);
        return new MaterialRequirement(
                inputId(entry),
                new ItemIngredientView(
                        List.of(new ItemAlternative(resource)),
                        ResourceMatchMode.EXACT_IDENTITY),
                amount,
                consumption,
                "ingredients",
                0,
                true);
    }

    private static OutputDeclaration output(String port, OutputKind kind) {
        IntegrationResourceKey resource = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                new ResourceLocation("test", "result"),
                new CompoundTag());
        return new OutputDeclaration(
                outputId(port), "output", resource, OutputAmount.exactly(1L),
                kind, OutputProbability.guaranteed(), false, false, null);
    }

    private static ResourceLocation inputId(String path) {
        return new ResourceLocation("test", "input/" + path);
    }

    private static ResourceLocation outputId(String path) {
        return new ResourceLocation("test", "output/" + path);
    }
}
