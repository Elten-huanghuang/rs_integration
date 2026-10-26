package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineItemPort;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.SupportScope;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.ItemIngredientView;
import com.huanghuang.rsintegration.api.recipe.MaterialRequirement;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbability;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeFlags;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FarmersDelightCookingPotIntegration {
    private static final int INPUT_SLOTS = 6;
    private static final int CONTAINER_SLOT = 7;
    private static final int OUTPUT_SLOT = 8;
    private static final String INGREDIENTS = "ingredients";
    private static final String CONTAINER = "container";
    private static final String MEAL = "meal";

    private final ResourceLocation recipeIntegrationId =
            smokeId("farmersdelight/cooking_pot_recipe");
    private final ResourceLocation machineIntegrationId =
            smokeId("farmersdelight/cooking_pot_machine");
    private final ResourceLocation processId =
            smokeId("farmersdelight/cooking_pot");

    RecipeIntegration<CookingPotRecipe> recipe() {
        return new CookingPotRecipeIntegration();
    }

    MachineIntegration machine() {
        return new CookingPotMachineIntegration();
    }

    private final class CookingPotRecipeIntegration
            implements RecipeIntegration<CookingPotRecipe> {
        @Override
        public ResourceLocation id() {
            return recipeIntegrationId;
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return recipe instanceof CookingPotRecipe;
        }

        @Override
        public RecipeExecutionKind executionKind() {
            return RecipeExecutionKind.STANDARD_IO;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                CookingPotRecipe recipe
        ) {
            ItemStack output = recipe.getResultItem(context.registries()).copy();
            if (output.isEmpty()) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "cooking pot recipe has no output"));
            }
            List<MaterialRequirement> materials = new ArrayList<>();
            int logicalSlot = 0;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                ItemStack[] alternatives = ingredient.getItems();
                if (alternatives.length == 0) continue;
                materials.add(new MaterialRequirement(
                        smokeId("cooking_pot/input/" + logicalSlot),
                        ItemIngredientView.anyOf(alternatives),
                        1L,
                        InputConsumption.consumed(),
                        INGREDIENTS,
                        logicalSlot,
                        true
                ));
                logicalSlot++;
                if (logicalSlot >= INPUT_SLOTS) break;
            }
            ItemStack container = recipe.getOutputContainer().copy();
            if (!container.isEmpty()) {
                container.setCount(Math.max(1, output.getCount()));
                materials.add(new MaterialRequirement(
                        smokeId("cooking_pot/container"),
                        ItemIngredientView.anyOf(container),
                        container.getCount(),
                        InputConsumption.consumed(),
                        CONTAINER,
                        CONTAINER_SLOT,
                        true
                ));
            }
            if (materials.isEmpty()) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "cooking pot recipe has no inputs"));
            }
            OutputDeclaration meal = new OutputDeclaration(
                    smokeId("cooking_pot/output"),
                    MEAL,
                    IntegrationResourceKey.item(output),
                    OutputAmount.exactly(output.getCount()),
                    OutputKind.PRIMARY,
                    OutputProbability.guaranteed(),
                    false,
                    false,
                    null
            );
            RecipeView view = new RecipeView(
                    recipe.getId(),
                    processId,
                    materials,
                    List.of(),
                    List.of(meal),
                    new RecipeFlags(
                            true,
                            false,
                            true,
                            false,
                            false,
                            false,
                            true
                    )
            );
            return new RecipeInspectionResult(view, List.of());
        }
    }

    private final class CookingPotMachineIntegration
            implements MachineIntegration {
        @Override
        public ResourceLocation id() {
            return machineIntegrationId;
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            return blockEntity instanceof CookingPotBlockEntity;
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            if (!(blockEntity instanceof CookingPotBlockEntity)) {
                return MachineDescriptorResult.rejected(
                        diagnostic("bound block entity is not a cooking pot"));
            }
            List<MachineItemPort> ports = new ArrayList<>();
            for (int slot = 0; slot < INPUT_SLOTS; slot++) {
                ports.add(new MachineItemPort(
                        "ingredient_" + slot,
                        slot,
                        null,
                        InputOutputAccess.INPUT,
                        ItemPortAccess.ITEM_HANDLER,
                        INGREDIENTS,
                        null,
                        64
                ));
            }
            ports.add(new MachineItemPort(
                    CONTAINER,
                    CONTAINER_SLOT,
                    null,
                    InputOutputAccess.INPUT,
                    ItemPortAccess.ITEM_HANDLER,
                    CONTAINER,
                    null,
                    64
            ));
            ports.add(new MachineItemPort(
                    "meal",
                    OUTPUT_SLOT,
                    null,
                    InputOutputAccess.OUTPUT,
                    ItemPortAccess.ITEM_HANDLER,
                    null,
                    MEAL,
                    64
            ));
            return MachineDescriptorResult.ready(new MachineDescriptor(
                    smokeId("farmersdelight/cooking_pot"),
                    1L,
                    Set.of(processId),
                    ports,
                    List.of(),
                    List.of(),
                    MachineExecutionMode.OUTPUT_DELTA_ONLY,
                    false,
                    ConcurrencyCapabilities.machineSlot(),
                    new SupportScope(List.of(), List.of(), false),
                    true
            ));
        }
    }

    private static IntegrationDiagnostic diagnostic(String reason) {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.INTEGRATION_REPORTED_FAILURE,
                IntegrationSeverity.ERROR,
                smokeId("farmersdelight/cooking_pot"),
                Map.of("reason", reason)
        );
    }

    private static ResourceLocation smokeId(String path) {
        return new ResourceLocation(RsiApiSmokeMod.MOD_ID, path);
    }
}
