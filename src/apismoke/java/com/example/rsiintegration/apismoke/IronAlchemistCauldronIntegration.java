package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.CancelReason;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.CraftObservation;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.execution.ExecutionPlanResult;
import com.huanghuang.rsintegration.api.execution.ExecutionRequestContext;
import com.huanghuang.rsintegration.api.execution.ExecutionResult;
import com.huanghuang.rsintegration.api.execution.FailureReason;
import com.huanghuang.rsintegration.api.execution.InputRoute;
import com.huanghuang.rsintegration.api.execution.LifecyclePhase;
import com.huanghuang.rsintegration.api.execution.MaterialDisposition;
import com.huanghuang.rsintegration.api.execution.MaterialOutcome;
import com.huanghuang.rsintegration.api.execution.OperationCleanupResult;
import com.huanghuang.rsintegration.api.execution.OperationCollection;
import com.huanghuang.rsintegration.api.execution.OperationContext;
import com.huanghuang.rsintegration.api.execution.OperationSnapshot;
import com.huanghuang.rsintegration.api.execution.OutputPort;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.execution.RunningOperation;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.FluidPortAccess;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.MachineFluidPort;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineItemPort;
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
import io.redspace.ironsspellbooks.block.alchemist_cauldron.AlchemistCauldronTile;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 用 Iron 炼金锅真实卷轴回收概率验证 CUSTOM 执行。
 */
final class IronAlchemistCauldronIntegration {
    private static final ResourceLocation BLOCK_ID =
            new ResourceLocation(
                    "irons_spellbooks",
                    "alchemist_cauldron");
    private static final ResourceLocation PROCESS_ID =
            id("irons_spellbooks/alchemist_cauldron");
    private static final ResourceLocation MACHINE_ID =
            id("irons_spellbooks/alchemist_cauldron/machine");
    private static final ResourceLocation MACHINE_INTEGRATION_ID =
            id("irons_spellbooks/alchemist_cauldron/machine_integration");
    private static final ResourceLocation RECIPE_INTEGRATION_ID =
            id("irons_spellbooks/alchemist_cauldron/recipe_integration");
    private static final ResourceLocation EXECUTION_INTEGRATION_ID =
            id("irons_spellbooks/alchemist_cauldron/execution");
    private static final ResourceLocation INPUT_ID =
            id("irons_spellbooks/alchemist_cauldron/scroll");
    private static final ResourceLocation OUTPUT_PORT_ID =
            id("irons_spellbooks/alchemist_cauldron/ink");
    private static final List<ResourceLocation> INK_FLUIDS = List.of(
            new ResourceLocation("irons_spellbooks", "common_ink"),
            new ResourceLocation("irons_spellbooks", "uncommon_ink"),
            new ResourceLocation("irons_spellbooks", "rare_ink"),
            new ResourceLocation("irons_spellbooks", "epic_ink"),
            new ResourceLocation("irons_spellbooks", "legendary_ink")
    );
    private static final String INPUT_GROUP = "scroll";
    private static final String OUTPUT_GROUP = "ink_output";
    private static final long DESCRIPTOR_REVISION = 1L;
    private static final int WATER_AMOUNT = 250;

    RecipeIntegration<IronAlchemistChanceRecipe> recipe() {
        return new ChanceRecipeIntegration();
    }

    MachineIntegration machine() {
        return new CauldronMachineIntegration();
    }

    RecipeExecutionIntegration execution() {
        return new ChanceExecutionIntegration();
    }

    private static final class ChanceRecipeIntegration
            implements RecipeIntegration<IronAlchemistChanceRecipe> {
        @Override
        public ResourceLocation id() {
            return RECIPE_INTEGRATION_ID;
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return recipe instanceof IronAlchemistChanceRecipe;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                IronAlchemistChanceRecipe recipe
        ) {
            Fluid fluid = recipe.resolvedOutputFluid();
            if (fluid == null || fluid == Fluids.EMPTY) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "missing_output_fluid"));
            }
            MaterialRequirement input = new MaterialRequirement(
                    INPUT_ID,
                    ItemIngredientView.anyOf(
                            recipe.input().getItems()),
                    1L,
                    InputConsumption.consumed(),
                    INPUT_GROUP,
                    0,
                    true
            );
            OutputDeclaration output = new OutputDeclaration(
                    OUTPUT_PORT_ID,
                    OUTPUT_GROUP,
                    IntegrationResourceKey.fluid(
                            new FluidStack(
                                    fluid,
                                    recipe.outputAmount())),
                    OutputAmount.exactly(recipe.outputAmount()),
                    OutputKind.PRIMARY,
                    OutputProbability.probabilistic(
                            recycleChance(),
                            16,
                            "irons_spellbooks_scroll_recycle"),
                    false,
                    false,
                    INPUT_ID
            );
            return new RecipeInspectionResult(
                    new RecipeView(
                            recipe.getId(),
                            PROCESS_ID,
                            List.of(input),
                            List.of(),
                            List.of(output),
                            new RecipeFlags(
                                    true,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    true
                            )
                    ),
                    List.of()
            );
        }

        @Override
        public RecipeExecutionKind executionKind() {
            return RecipeExecutionKind.CUSTOM;
        }

        @Override
        public Optional<Recipe<?>> resolveJeiRecipe(
                Object jeiRecipe,
                ItemStack displayed,
                RegistryAccess registries
        ) {
            if (jeiRecipe == null
                    || !"io.redspace.ironsspellbooks.jei.AlchemistCauldronJeiRecipe"
                    .equals(jeiRecipe.getClass().getName())
            ) {
                return Optional.empty();
            }
            FluidStack output = jeiOutputFluid(jeiRecipe);
            if (output == null || output.isEmpty()) {
                return Optional.empty();
            }
            ResourceLocation fluid = ForgeRegistries.FLUIDS.getKey(
                    output.getFluid());
            ResourceLocation recipeId = chanceRecipeId(fluid);
            if (recipeId == null) {
                return Optional.empty();
            }
            ItemStack input = displayed;
            if (input.isEmpty()) {
                input = jeiInputItem(jeiRecipe);
            }
            if (input.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new IronAlchemistChanceRecipe(
                    recipeId,
                    Ingredient.of(input.getItem()),
                    fluid,
                    WATER_AMOUNT
            ));
        }
    }

    private static final class CauldronMachineIntegration
            implements MachineIntegration {
        @Override
        public ResourceLocation id() {
            return MACHINE_INTEGRATION_ID;
        }

        @Override
        public List<MachineBinding> bindings() {
            return List.of(MachineBinding.blocks(
                    Set.of(PROCESS_ID),
                    false,
                    BLOCK_ID
            ));
        }

        @Override
        public Map<ResourceLocation, PlannedConcurrency>
        planningConcurrency() {
            return Map.of(
                    PROCESS_ID,
                    PlannedConcurrency.delegateResult()
            );
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            return blockEntity instanceof AlchemistCauldronTile;
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            if (!(blockEntity instanceof AlchemistCauldronTile)) {
                return MachineDescriptorResult.rejected(diagnostic(
                        "block_entity_is_not_iron_alchemist_cauldron"));
            }
            return MachineDescriptorResult.ready(descriptor());
        }
    }

    private static final class ChanceExecutionIntegration
            implements RecipeExecutionIntegration {
        @Override
        public ResourceLocation id() {
            return EXECUTION_INTEGRATION_ID;
        }

        @Override
        public boolean supports(
                RecipeView recipe,
                MachineContext machine
        ) {
            return PROCESS_ID.equals(recipe.processId())
                    && MACHINE_INTEGRATION_ID.equals(
                    machine.integrationId());
        }

        @Override
        public ExecutionPlanResult createPlan(
                ExecutionRequestContext context
        ) {
            BlockEntity blockEntity = context.machine().level()
                    .getBlockEntity(context.machine().position());
            if (!(blockEntity instanceof AlchemistCauldronTile)) {
                return ExecutionPlanResult.rejected(diagnostic(
                        "machine_missing_during_plan"));
            }
            MaterialRequirement input =
                    context.recipe().materials().get(0);
            OutputDeclaration output =
                    context.recipe().outputs().get(0);
            ExecutionPlan plan = new ExecutionPlan(
                    context.operationId(),
                    context.recipe().recipeId(),
                    MACHINE_ID,
                    RECIPE_INTEGRATION_ID,
                    MACHINE_INTEGRATION_ID,
                    EXECUTION_INTEGRATION_ID,
                    context.recipeContext().recipeRevision(),
                    DESCRIPTOR_REVISION,
                    context.recipe().definitionFingerprint(),
                    descriptor().descriptorFingerprint(),
                    List.of(new InputRoute(
                            input.entryId().toString(),
                            0,
                            INPUT_GROUP,
                            1L
                    )),
                    List.of(),
                    List.of(),
                    1,
                    List.of(outputPort(output)),
                    new SupportScope(List.of(), List.of(), false),
                    ConcurrencyCapabilities.machineSlot()
            );
            return ExecutionPlanResult.ready(plan);
        }

        @Override
        public ExecutionResult start(
                OperationContext context,
                ExecutionPlan plan
        ) {
            ReservedMaterial material = context.materials()
                    .find(INPUT_ID.toString())
                    .orElse(null);
            if (material == null
                    || material.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                return ExecutionResult.rejected(diagnostic(
                        "reserved_scroll_is_missing"));
            }
            ItemStack scroll = stack(material.resource());
            if (scroll.isEmpty()
                    || !(context.machine().level()
                    .getBlockEntity(context.machine().position())
                    instanceof AlchemistCauldronTile cauldron)) {
                return ExecutionResult.rejected(diagnostic(
                        "iron_alchemist_cauldron_is_unavailable"));
            }
            Item ink;
            try {
                ink = AlchemistCauldronTile.getInkFromScroll(scroll);
            } catch (RuntimeException | LinkageError failure) {
                return ExecutionResult.rejected(diagnostic(
                        "scroll_recycle_resolution_failed"));
            }
            Fluid inkFluid = inkFluid(ink);
            if (inkFluid == Fluids.EMPTY) {
                return ExecutionResult.rejected(diagnostic(
                        "scroll_has_no_recyclable_ink"));
            }
            ResourceLocation actualFluid =
                    ForgeRegistries.FLUIDS.getKey(inkFluid);
            IronAlchemistChanceRecipe recipe =
                    liveChanceRecipe(context, plan);
            if (recipe == null
                    || actualFluid == null
                    || !actualFluid.equals(recipe.outputFluid())) {
                return ExecutionResult.rejected(diagnostic(
                        "scroll_rarity_does_not_match_smoke_recipe"));
            }
            IFluidHandler tank = cauldron.getCapability(
                    ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
            if (tank == null || !tankIsEmpty(tank)) {
                return ExecutionResult.rejected(diagnostic(
                        "iron_alchemist_cauldron_must_be_empty"));
            }
            FluidStack water = new FluidStack(
                    Fluids.WATER,
                    WATER_AMOUNT
            );
            if (tank.fill(water, IFluidHandler.FluidAction.EXECUTE)
                    != WATER_AMOUNT) {
                return ExecutionResult.rejected(diagnostic(
                        "iron_alchemist_cauldron_water_fill_failed"));
            }
            try {
                cauldron.tryMeltInput(scroll.copy());
            } catch (RuntimeException | LinkageError failure) {
                return ExecutionResult.rejected(diagnostic(
                        "iron_alchemist_scroll_recycle_failed"));
            }
            return ExecutionResult.started(new ChanceOperation(
                    context.machine(),
                    material,
                    actualFluid,
                    recipe.outputAmount(),
                    plan.outputPorts().get(0).portId(),
                    context.currentTick()
            ));
        }

        @Nullable
        private static IronAlchemistChanceRecipe liveChanceRecipe(
                OperationContext context,
                ExecutionPlan plan
        ) {
            Recipe<?> recipe = context.machine().level()
                    .getRecipeManager()
                    .byKey(plan.recipeId())
                    .orElse(null);
            return recipe instanceof IronAlchemistChanceRecipe chance
                    ? chance : null;
        }
    }

    private static final class ChanceOperation
            implements RunningOperation {
        private final MachineContext machine;
        private final ReservedMaterial material;
        private final ResourceLocation fluidId;
        private final int amount;
        private final ResourceLocation portId;
        private final long startedAtTick;
        private long collectionSequence;
        private boolean complete;

        private ChanceOperation(
                MachineContext machine,
                ReservedMaterial material,
                ResourceLocation fluidId,
                int amount,
                ResourceLocation portId,
                long startedAtTick
        ) {
            this.machine = machine;
            this.material = material;
            this.fluidId = fluidId;
            this.amount = amount;
            this.portId = portId;
            this.startedAtTick = startedAtTick;
        }

        @Override
        public CraftObservation observe(ServerLevel level) {
            return new CraftObservation(
                    LifecyclePhase.READY_TO_COLLECT,
                    1,
                    1,
                    complete ? 2L : 1L,
                    null,
                    List.of()
            );
        }

        @Override
        public OperationCollection collect(
                ServerPlayer player
        ) {
            collectionSequence++;
            List<CollectedOutput> outputs = List.of();
            if (!complete) {
                CollectedOutput output = drainInk();
                if (output != null) outputs = List.of(output);
                complete = true;
            }
            return new OperationCollection(
                    collectionSequence,
                    outputs,
                    List.of(new MaterialDisposition(
                            material,
                            MaterialOutcome.CONSUMED
                    )),
                    complete,
                    List.of()
            );
        }

        @Nullable
        private CollectedOutput drainInk() {
            BlockEntity blockEntity = machine.level()
                    .getBlockEntity(machine.position());
            if (!(blockEntity instanceof AlchemistCauldronTile)) {
                return null;
            }
            IFluidHandler tank = blockEntity.getCapability(
                    ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
            if (tank == null) return null;
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
            if (fluid == null || fluid == Fluids.EMPTY) return null;
            FluidStack expected = new FluidStack(fluid, amount);
            FluidStack drained = tank.drain(
                    expected,
                    IFluidHandler.FluidAction.EXECUTE
            );
            if (drained.isEmpty()) return null;
            return CollectedOutput.primary(
                    IntegrationResourceKey.fluid(drained),
                    drained.getAmount(),
                    portId
            );
        }

        @Override
        public OperationSnapshot snapshot() {
            CompoundTag state = new CompoundTag();
            state.putBoolean("complete", complete);
            state.putLong("started_at", startedAtTick);
            state.putString("fluid", fluidId.toString());
            state.putInt("amount", amount);
            return new OperationSnapshot(
                    complete
                            ? LifecyclePhase.SUCCEEDED
                            : LifecyclePhase.READY_TO_COLLECT,
                    collectionSequence,
                    state
            );
        }

        @Override
        public OperationCleanupResult onFailure(
                FailureReason reason,
                @Nullable ServerPlayer player
        ) {
            return cleanup();
        }

        @Override
        public OperationCleanupResult onCancel(
                CancelReason reason,
                @Nullable ServerPlayer player
        ) {
            return cleanup();
        }

        private OperationCleanupResult cleanup() {
            return new OperationCleanupResult(
                    true,
                    List.of(new MaterialDisposition(
                            material,
                            MaterialOutcome.CONSUMED
                    )),
                    List.of(),
                    List.of()
            );
        }

        @Override
        public void onSettled(
                @Nullable ServerPlayer player
        ) {
        }
    }

    private static MachineDescriptor descriptor() {
        return new MachineDescriptor(
                MACHINE_ID,
                DESCRIPTOR_REVISION,
                Set.of(PROCESS_ID),
                List.of(new MachineItemPort(
                        "scroll",
                        0,
                        null,
                        InputOutputAccess.INPUT,
                        ItemPortAccess.CONTAINER,
                        INPUT_GROUP,
                        null,
                        1
                )),
                List.of(new MachineFluidPort(
                        "ink",
                        0,
                        null,
                        InputOutputAccess.OUTPUT,
                        FluidPortAccess.FLUID_HANDLER,
                        null,
                        OUTPUT_GROUP,
                        4000
                )),
                List.of(),
                MachineExecutionMode.CUSTOM,
                false,
                ConcurrencyCapabilities.machineSlot(),
                new SupportScope(List.of(), List.of(), false),
                false
        );
    }

    private static OutputPort outputPort(
            OutputDeclaration declaration
    ) {
        return new OutputPort(
                declaration.portId(),
                declaration.outputGroup(),
                declaration.resource(),
                declaration.amount(),
                declaration.outputKind(),
                declaration.probability(),
                declaration.dynamicIdentity(),
                declaration.targetProducing(),
                declaration.sourceInputEntryId()
        );
    }

    private static boolean tankIsEmpty(IFluidHandler tank) {
        for (int index = 0; index < tank.getTanks(); index++) {
            if (!tank.getFluidInTank(index).isEmpty()) return false;
        }
        return true;
    }

    private static ItemStack stack(
            IntegrationResourceKey resource
    ) {
        var item = ForgeRegistries.ITEMS.getValue(resource.id());
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        if (!resource.identity().isEmpty()) {
            stack.setTag(resource.identity());
        }
        return stack;
    }

    private static Fluid inkFluid(@Nullable Item ink) {
        if (ink == null) return Fluids.EMPTY;
        try {
            Object supplied = ink.getClass()
                    .getMethod("fluid")
                    .invoke(ink);
            if (supplied instanceof Supplier<?> supplier
                    && supplier.get() instanceof Fluid fluid) {
                return fluid;
            }
        } catch (ReflectiveOperationException
                 | RuntimeException
                 | LinkageError ignored) {
        }
        return Fluids.EMPTY;
    }

    @Nullable
    static FluidStack jeiOutputFluid(Object jeiRecipe) {
        try {
            Object value = jeiRecipe.getClass()
                    .getMethod("results")
                    .invoke(jeiRecipe);
            if (!(value instanceof List<?> results)) return null;
            for (Object result : results) {
                if (result instanceof FluidStack fluid
                        && !fluid.isEmpty()) {
                    return fluid.copy();
                }
            }
        } catch (ReflectiveOperationException
                 | RuntimeException
                 | LinkageError ignored) {
        }
        return null;
    }

    private static ItemStack jeiInputItem(Object jeiRecipe) {
        try {
            Object value = jeiRecipe.getClass()
                    .getMethod("itemIn")
                    .invoke(jeiRecipe);
            if (!(value instanceof Ingredient ingredient)
                    || ingredient.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack[] items = ingredient.getItems();
            return items.length == 0
                    ? ItemStack.EMPTY : items[0].copy();
        } catch (ReflectiveOperationException
                 | RuntimeException
                 | LinkageError ignored) {
            return ItemStack.EMPTY;
        }
    }

    @Nullable
    static ResourceLocation chanceRecipeId(
            @Nullable ResourceLocation fluid
    ) {
        if (fluid == null || !INK_FLUIDS.contains(fluid)) {
            return null;
        }
        String path = fluid.getPath();
        String rarity = path.substring(
                0, path.length() - "_ink".length());
        return new ResourceLocation(
                RsiApiSmokeMod.MOD_ID,
                "iron_alchemist/" + rarity
        );
    }

    private static double recycleChance() {
        try {
            Object value = Class.forName(
                            "io.redspace.ironsspellbooks.config.ServerConfigs")
                    .getField("SCROLL_RECYCLE_CHANCE")
                    .get(null);
            if (value instanceof ForgeConfigSpec.ConfigValue<?> config
                    && config.get() instanceof Number number) {
                return Math.max(
                        0.0D,
                        Math.min(1.0D, number.doubleValue())
                );
            }
        } catch (ReflectiveOperationException
                 | RuntimeException
                 | LinkageError ignored) {
        }
        return 0.75D;
    }

    private static IntegrationDiagnostic diagnostic(
            String reason
    ) {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.EXECUTION_PREPARATION_FAILED,
                IntegrationSeverity.ERROR,
                EXECUTION_INTEGRATION_ID,
                Map.of("reason", reason)
        );
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(RsiApiSmokeMod.MOD_ID, path);
    }
}
