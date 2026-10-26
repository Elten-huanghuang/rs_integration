package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineItemPort;
import com.huanghuang.rsintegration.api.machine.MachineState;
import com.huanghuang.rsintegration.api.machine.MachineStateProvider;
import com.huanghuang.rsintegration.api.machine.PlannedConcurrency;
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
import ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class IronFurnacesSmeltingIntegration {
    private static final int INPUT_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int OUTPUT_SLOT = 2;
    private static final String INPUT_GROUP = "input";
    private static final String FUEL_GROUP = "fuel";
    private static final String OUTPUT_GROUP = "result";

    private final ResourceLocation recipeIntegrationId =
            smokeId("ironfurnaces/smelting_recipe");
    private final ResourceLocation machineIntegrationId =
            smokeId("ironfurnaces/furnace_machine");
    private final ResourceLocation smeltingProcess =
            smokeId("ironfurnaces/smelting");
    private final ResourceLocation blastingProcess =
            smokeId("ironfurnaces/blasting");
    private final ResourceLocation smokingProcess =
            smokeId("ironfurnaces/smoking");

    RecipeIntegration<AbstractCookingRecipe> recipe() {
        return new FurnaceRecipeIntegration();
    }

    MachineIntegration machine() {
        return new FurnaceMachineIntegration();
    }

    private final class FurnaceRecipeIntegration
            implements RecipeIntegration<AbstractCookingRecipe> {
        @Override
        public ResourceLocation id() {
            return recipeIntegrationId;
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return recipe instanceof AbstractCookingRecipe
                    && recipe.getId() != null;
        }

        @Override
        public RecipeExecutionKind executionKind() {
            return RecipeExecutionKind.STANDARD_IO;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                AbstractCookingRecipe recipe
        ) {
            if (recipe.getIngredients().isEmpty()
                    || recipe.getIngredients().get(0).isEmpty()) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "cooking recipe has no input"));
            }
            ItemStack output = recipe.getResultItem(context.registries()).copy();
            if (output.isEmpty()) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "cooking recipe has no output"));
            }
            MaterialRequirement input = new MaterialRequirement(
                    smokeId("ironfurnaces/input"),
                    ItemIngredientView.anyOf(
                            recipe.getIngredients().get(0).getItems()),
                    1L,
                    InputConsumption.consumed(),
                    INPUT_GROUP,
                    INPUT_SLOT,
                    true
            );
            MaterialRequirement fuel = new MaterialRequirement(
                    smokeId("ironfurnaces/fuel"),
                    ItemIngredientView.anyOf(
                            new ItemStack(Items.COAL),
                            new ItemStack(Items.CHARCOAL)
                    ),
                    1L,
                    InputConsumption.fuel(200L),
                    FUEL_GROUP,
                    FUEL_SLOT,
                    true
            );
            OutputDeclaration result = new OutputDeclaration(
                    smokeId("ironfurnaces/output"),
                    OUTPUT_GROUP,
                    IntegrationResourceKey.item(output),
                    OutputAmount.exactly(output.getCount()),
                    OutputKind.PRIMARY,
                    OutputProbability.guaranteed(),
                    false,
                    false,
                    null
            );
            return new RecipeInspectionResult(new RecipeView(
                    recipe.getId(),
                    processFor(recipe.getType()),
                    List.of(input, fuel),
                    List.of(),
                    List.of(result),
                    new RecipeFlags(
                            true,
                            false,
                            true,
                            false,
                            true,
                            true,
                            true
                    )
            ), List.of());
        }
    }

    private final class FurnaceMachineIntegration
            implements MachineIntegration {
        private final Map<String, Revision> revisions =
                new ConcurrentHashMap<>();

        @Override
        public ResourceLocation id() {
            return machineIntegrationId;
        }

        @Override
        public List<MachineBinding> bindings() {
            return List.of(MachineBinding.blocks(
                    Set.of(
                            smeltingProcess,
                            blastingProcess,
                            smokingProcess
                    ),
                    true,
                    new ResourceLocation("ironfurnaces", "iron_furnace")
            ));
        }

        @Override
        public Map<ResourceLocation, PlannedConcurrency>
        planningConcurrency() {
            PlannedConcurrency profile =
                    PlannedConcurrency.machineSlot();
            return Map.of(
                    smeltingProcess, profile,
                    blastingProcess, profile,
                    smokingProcess, profile
            );
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            return blockEntity instanceof BlockIronFurnaceTileBase;
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            if (!(blockEntity instanceof BlockIronFurnaceTileBase furnace)) {
                return MachineDescriptorResult.rejected(diagnostic(
                        "bound block entity is not an Iron Furnace"));
            }
            if (furnace.isGenerator() || !furnace.isFurnace()) {
                return MachineDescriptorResult.rejected(diagnostic(
                        "only ordinary Iron Furnace mode is supported"));
            }
            return MachineDescriptorResult.ready(new MachineDescriptor(
                    smokeId("ironfurnaces/furnace"),
                    1L,
                    Set.of(smeltingProcess, blastingProcess, smokingProcess),
                    List.of(
                            new MachineItemPort(
                                    "input",
                                    INPUT_SLOT,
                                    null,
                                    InputOutputAccess.INPUT,
                                    ItemPortAccess.CONTAINER,
                                    INPUT_GROUP,
                                    null,
                                    64
                            ),
                            new MachineItemPort(
                                    "fuel",
                                    FUEL_SLOT,
                                    null,
                                    InputOutputAccess.INPUT,
                                    ItemPortAccess.CONTAINER,
                                    FUEL_GROUP,
                                    null,
                                    64
                            ),
                            new MachineItemPort(
                                    "output",
                                    OUTPUT_SLOT,
                                    null,
                                    InputOutputAccess.OUTPUT,
                                    ItemPortAccess.CONTAINER,
                                    null,
                                    OUTPUT_GROUP,
                                    64
                            )
                    ),
                    List.of(),
                    List.of(),
                    MachineExecutionMode.TICKED_AND_BUSY,
                    false,
                    ConcurrencyCapabilities.machineSlot(),
                    new SupportScope(List.of(), List.of(), false),
                    true
            ));
        }

        @Override
        public Optional<MachineStateProvider> stateProvider() {
            return Optional.of(this::readState);
        }

        private MachineState readState(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            if (!(blockEntity instanceof BlockIronFurnaceTileBase furnace)) {
                return MachineState.invalid(diagnostic(
                        "Iron Furnace block entity disappeared"));
            }
            boolean valid = !furnace.isRemoved()
                    && !furnace.isGenerator()
                    && furnace.isFurnace();
            Optional<Recipe<?>> active = activeRecipe(context, furnace);
            boolean busy = valid && furnace.cookTime > 0
                    && active.isPresent();
            int progress = busy ? furnace.cookTime : 0;
            int totalProgress = busy ? Math.max(
                    progress, furnace.totalCookTime) : 0;
            ResourceLocation activeRecipeId = busy
                    ? active.orElseThrow().getId() : null;
            ResourceLocation activeProcessId = busy
                    ? processFor(furnace.recipeType) : null;
            long signature = (((long) progress * 31L
                    + totalProgress) * 31L
                    + furnace.furnaceBurnTime) * 31L
                    + (busy ? 1L : 0L);
            long revision = revision(
                    context, signature, revisions);
            return new MachineState(
                    true,
                    valid,
                    busy,
                    progress,
                    totalProgress,
                    activeRecipeId,
                    activeProcessId,
                    revision,
                    null
            );
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<Recipe<?>> activeRecipe(
            MachineContext context,
            BlockIronFurnaceTileBase furnace
    ) {
        ItemStack input = furnace.getItem(INPUT_SLOT);
        if (input.isEmpty() || furnace.recipeType == null) {
            return Optional.empty();
        }
        RecipeType type = furnace.recipeType;
        return (Optional) context.level().getRecipeManager().getRecipeFor(
                type,
                new SimpleContainer(input),
                context.level()
        );
    }

    private ResourceLocation processFor(RecipeType<?> type) {
        if (type == RecipeType.BLASTING) return blastingProcess;
        if (type == RecipeType.SMOKING) return smokingProcess;
        return smeltingProcess;
    }

    private static long revision(
            MachineContext context,
            long signature,
            Map<String, Revision> revisions
    ) {
        String key = context.level().dimension().location()
                + "@" + context.position().asLong();
        return revisions.compute(key, (ignored, previous) ->
                previous == null || previous.signature() != signature
                        ? new Revision(
                        previous == null ? 1L : previous.value() + 1L,
                        signature)
                        : previous).value();
    }

    private static IntegrationDiagnostic diagnostic(String reason) {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.INTEGRATION_REPORTED_FAILURE,
                IntegrationSeverity.ERROR,
                smokeId("ironfurnaces/furnace"),
                Map.of("reason", reason)
        );
    }

    private static ResourceLocation smokeId(String path) {
        return new ResourceLocation(RsiApiSmokeMod.MOD_ID, path);
    }

    private record Revision(long value, long signature) {}
}
