package com.example.rsiintegration;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RegistrationResult;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.execution.ExecutionPlanResult;
import com.huanghuang.rsintegration.api.execution.ExecutionRequestContext;
import com.huanghuang.rsintegration.api.execution.ExecutionResult;
import com.huanghuang.rsintegration.api.execution.OperationContext;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineItemPort;
import com.huanghuang.rsintegration.api.machine.SupportScope;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.ItemAlternative;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public final class PublicIntegrationExample {
    public static final ResourceLocation PROCESS =
            new ResourceLocation("examplemod", "example_process");

    private PublicIntegrationExample() {}

    public record RegisteredComponents(
            RecipeIntegration<?> recipe,
            MachineIntegration machine,
            RecipeExecutionIntegration execution,
            RegistrationResult recipeResult
    ) {
        public RegisteredComponents {
            requireNonNull(recipe, "recipe");
            requireNonNull(machine, "machine");
            requireNonNull(execution, "execution");
            requireNonNull(recipeResult, "recipeResult");
        }
    }

    public static RegisteredComponents register(
            RSIIntegrationApi api
    ) {
        requireNonNull(api, "api");
        ExampleRecipeIntegration recipeIntegration =
                new ExampleRecipeIntegration();
        ExampleMachineIntegration machineIntegration =
                new ExampleMachineIntegration();
        ExampleExecutionIntegration executionIntegration =
                new ExampleExecutionIntegration();

        RegistrationResult recipeResult =
                api.registerRecipe(recipeIntegration);
        api.registerMachine(machineIntegration);
        api.registerExecution(executionIntegration);
        return new RegisteredComponents(
                recipeIntegration,
                machineIntegration,
                executionIntegration,
                recipeResult
        );
    }

    private static final class ExampleRecipeIntegration
            implements RecipeIntegration<Recipe<?>> {
        private final RecipeView view = exampleView();

        @Override
        public ResourceLocation id() {
            return new ResourceLocation("examplemod", "recipe_integration");
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

        @Override
        public RecipeExecutionKind executionKind() {
            return RecipeExecutionKind.STANDARD_IO;
        }

        public RecipeView view() {
            return view;
        }
    }

    private static final class ExampleMachineIntegration
            implements MachineIntegration {
        @Override
        public ResourceLocation id() {
            return new ResourceLocation("examplemod", "machine_integration");
        }

        @Override
        public List<MachineBinding> bindings() {
            return List.of(MachineBinding.blocks(
                    Set.of(PROCESS),
                    true,
                    new ResourceLocation("examplemod", "machine")
            ));
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            return true;
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            return MachineDescriptorResult.ready(new MachineDescriptor(
                    new ResourceLocation("examplemod", "machine"),
                    1L,
                    Set.of(PROCESS),
                    List.of(
                            new MachineItemPort(
                                    "input",
                                    0,
                                    null,
                                    InputOutputAccess.INPUT,
                                    ItemPortAccess.CONTAINER,
                                    "ingredients",
                                    null,
                                    64
                            ),
                            new MachineItemPort(
                                    "output",
                                    1,
                                    null,
                                    InputOutputAccess.OUTPUT,
                                    ItemPortAccess.CONTAINER,
                                    null,
                                    "output",
                                    64
                            )
                    ),
                    List.of(),
                    List.of(),
                    MachineExecutionMode.IMMEDIATE_ON_INSERT,
                    false,
                    ConcurrencyCapabilities.machineSlot(),
                    new SupportScope(List.of(), List.of(), false),
                    false
            ));
        }
    }

    private static final class ExampleExecutionIntegration
            implements RecipeExecutionIntegration {
        @Override
        public ResourceLocation id() {
            return new ResourceLocation("examplemod", "execution_integration");
        }

        @Override
        public boolean supports(RecipeView recipe, MachineContext machine) {
            return PROCESS.equals(recipe.processId());
        }

        @Override
        public ExecutionPlanResult createPlan(ExecutionRequestContext context) {
            return ExecutionPlanResult.rejected(diagnostic());
        }

        @Override
        public ExecutionResult start(
                OperationContext context,
                ExecutionPlan plan
        ) {
            return ExecutionResult.rejected(diagnostic());
        }

        private IntegrationDiagnostic diagnostic() {
            return new IntegrationDiagnostic(
                    IntegrationDiagnosticCode.EXECUTION_PREPARATION_FAILED,
                    IntegrationSeverity.ERROR,
                    id(),
                    Map.of("reason", "example_only")
            );
        }
    }

    public static RecipeView exampleView() {
        IntegrationResourceKey diamond = item(Items.DIAMOND);
        IntegrationResourceKey stick = item(Items.STICK);
        return new RecipeView(
                new ResourceLocation("examplemod", "recipe"),
                PROCESS,
                List.of(new MaterialRequirement(
                        new ResourceLocation("examplemod", "input"),
                        new ItemIngredientView(
                                List.of(new ItemAlternative(diamond)),
                                ResourceMatchMode.EXACT_IDENTITY
                        ),
                        1L,
                        InputConsumption.consumed(),
                        "ingredients",
                        0,
                        true
                )),
                List.of(),
                List.of(new OutputDeclaration(
                        new ResourceLocation("examplemod", "output"),
                        "output",
                        stick,
                        OutputAmount.exactly(1L),
                        OutputKind.PRIMARY,
                        OutputProbability.guaranteed(),
                        false,
                        true,
                        null
                )),
                new RecipeFlags(
                        true,
                        true,
                        true,
                        false,
                        false,
                        true,
                        false
                )
        );
    }

    private static IntegrationResourceKey item(
            Item item
    ) {
        return new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(item),
                new CompoundTag()
        );
    }
}
