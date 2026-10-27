package com.huanghuang.rsintegration.mods.profoundcurse;

import com.example.profound_curse.block.CursedPedestalBlockEntity;
import com.example.profound_curse.block.QuartzDisplayStandBlockEntity;
import com.example.profound_curse.recipe.PedestalCraftingRecipe;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RegistrationResult;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.execution.ExecutionPlanResult;
import com.huanghuang.rsintegration.api.execution.ExecutionRequestContext;
import com.huanghuang.rsintegration.api.execution.ExecutionResult;
import com.huanghuang.rsintegration.api.execution.InputRoute;
import com.huanghuang.rsintegration.api.execution.OperationContext;
import com.huanghuang.rsintegration.api.execution.OperationSnapshot;
import com.huanghuang.rsintegration.api.execution.OutputPort;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 使用公共 Integration API 接入神秘遗物的诅咒基座。
 *
 * <p>该实现同时作为 RSI 本体内置 API 适配示例：配方、机器和自定义执行流程全部
 * 通过公共契约注册，不依赖旧 ModType、反射 delegate 或额外兼容层。</p>
 */
public final class ProfoundCurseApiIntegration {
    static final ResourceLocation PROCESS_ID =
            id("profound_curse/pedestal_crafting");
    static final ResourceLocation RECIPE_INTEGRATION_ID =
            id("profound_curse/pedestal_crafting/recipe");
    static final ResourceLocation MACHINE_INTEGRATION_ID =
            id("profound_curse/pedestal_crafting/machine");
    static final ResourceLocation EXECUTION_INTEGRATION_ID =
            id("profound_curse/pedestal_crafting/execution");
    static final ResourceLocation MACHINE_ID =
            id("profound_curse/cursed_pedestal");
    static final ResourceLocation OUTPUT_PORT_ID =
            id("profound_curse/pedestal_crafting/output");
    static final ResourceLocation CATALYST_ENTRY_ID =
            id("profound_curse/pedestal_crafting/catalyst");

    private static final ResourceLocation CURSED_PEDESTAL_BLOCK_ID =
            new ResourceLocation("profound_curse", "cursed_pedestal");
    private static final long DESCRIPTOR_REVISION = 1L;
    private static final String OUTPUT_GROUP = "result";
    private static final SupportScope SUPPORT_SCOPE = new SupportScope(
            CursedPedestalStructure.DISPLAY_OFFSETS,
            List.of(),
            true
    );
    private static final ConcurrencyCapabilities CONCURRENCY =
            new ConcurrencyCapabilities(
                    ConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                    ConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                    ConcurrencyCapabilities.CleanupContract.ONLINE_ONLY,
                    ConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE,
                    ConcurrencyCapabilities.PreparationContract.RETRY_SAFE
            );

    private ProfoundCurseApiIntegration() {}

    public static void register() {
        RSIIntegrationApi api = RSIIntegrationApi.get().orElseThrow(
                () -> new IllegalStateException(
                        "RSI Integration API is unavailable"));
        if (!api.version().supports(1, 1, 0)) {
            throw new IllegalStateException(
                    "RSI Integration API 1.1 or newer is required, found "
                            + api.version());
        }
        requireAccepted(
                "Profound Curse recipe",
                api.registerRecipe(recipeIntegration())
        );
        requireAccepted(
                "Profound Curse machine",
                api.registerMachine(machineIntegration())
        );
        requireAccepted(
                "Profound Curse execution",
                api.registerExecution(executionIntegration())
        );
        RSIntegrationMod.LOGGER.info(
                "[RSI-ProfoundCurse] 已通过公共 API 注册诅咒基座兼容");
    }

    static RecipeIntegration<PedestalCraftingRecipe>
    recipeIntegration() {
        return new PedestalRecipeIntegration();
    }

    static MachineIntegration machineIntegration() {
        return new PedestalMachineIntegration();
    }

    static RecipeExecutionIntegration executionIntegration() {
        return new PedestalExecutionIntegration();
    }

    static MachineDescriptor descriptor() {
        return new MachineDescriptor(
                MACHINE_ID,
                DESCRIPTOR_REVISION,
                Set.of(PROCESS_ID),
                List.of(),
                List.of(),
                List.of(),
                MachineExecutionMode.CUSTOM,
                false,
                CONCURRENCY,
                SUPPORT_SCOPE,
                false
        );
    }

    private static final class PedestalRecipeIntegration
            implements RecipeIntegration<PedestalCraftingRecipe> {
        @Override
        public ResourceLocation id() {
            return RECIPE_INTEGRATION_ID;
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return recipe instanceof PedestalCraftingRecipe;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                PedestalCraftingRecipe recipe
        ) {
            if (recipe.getIngredients().isEmpty()
                    || recipe.getIngredients().size()
                    > CursedPedestalStructure.DISPLAY_SLOT_COUNT) {
                return rejected(
                        IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED,
                        "pedestal_display_ingredient_count_is_unsupported"
                );
            }
            List<MaterialRequirement> materials = new ArrayList<>();
            for (int index = 0;
                 index < recipe.getIngredients().size();
                 index++) {
                Ingredient ingredient = recipe.getIngredients().get(index);
                ItemIngredientView view = ingredientView(ingredient);
                if (view == null) {
                    return rejected(
                            IntegrationDiagnosticCode.RECIPE_INGREDIENT_NOT_ENUMERABLE,
                            "pedestal_display_ingredient_is_empty"
                    );
                }
                materials.add(new MaterialRequirement(
                        displayEntryId(index),
                        view,
                        1L,
                        InputConsumption.consumed(),
                        CursedPedestalStructure.DISPLAY_GROUP,
                        index,
                        true
                ));
            }
            ItemIngredientView catalyst =
                    ingredientView(recipe.getCatalyst());
            if (catalyst == null) {
                return rejected(
                        IntegrationDiagnosticCode.RECIPE_INGREDIENT_NOT_ENUMERABLE,
                        "pedestal_catalyst_is_empty"
                );
            }
            materials.add(new MaterialRequirement(
                    CATALYST_ENTRY_ID,
                    catalyst,
                    1L,
                    InputConsumption.consumed(),
                    CursedPedestalStructure.CATALYST_GROUP,
                    CursedPedestalStructure.CATALYST_SLOT,
                    true
            ));

            ItemStack result = recipe.getResult().copy();
            if (result.isEmpty()) {
                return rejected(
                        IntegrationDiagnosticCode.OUTPUT_DECLARATION_INVALID,
                        "pedestal_result_is_empty"
                );
            }
            if (result.getCount() != 1) {
                return rejected(
                        IntegrationDiagnosticCode.OUTPUT_DECLARATION_INVALID,
                        "pedestal_native_slot_only_supports_one_result"
                );
            }
            OutputDeclaration output = new OutputDeclaration(
                    OUTPUT_PORT_ID,
                    OUTPUT_GROUP,
                    IntegrationResourceKey.item(result),
                    OutputAmount.exactly(result.getCount()),
                    OutputKind.PRIMARY,
                    OutputProbability.guaranteed(),
                    false,
                    true,
                    null
            );
            return new RecipeInspectionResult(
                    new RecipeView(
                            recipe.getId(),
                            PROCESS_ID,
                            materials,
                            List.of(),
                            List.of(output),
                            new RecipeFlags(
                                    true,
                                    true,
                                    true,
                                    false,
                                    false,
                                    false,
                                    recipe.isOrdered()
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
            return jeiRecipe instanceof PedestalCraftingRecipe recipe
                    ? Optional.of(recipe) : Optional.empty();
        }
    }

    private static final class PedestalMachineIntegration
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
                    CURSED_PEDESTAL_BLOCK_ID
            ));
        }

        @Override
        public Map<ResourceLocation, PlannedConcurrency>
        planningConcurrency() {
            return Map.of(
                    PROCESS_ID,
                    new PlannedConcurrency(
                            ConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                            ConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                            ConcurrencyCapabilities.CleanupContract.ONLINE_ONLY,
                            ConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE,
                            ConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                            CursedPedestalStructure.DISPLAY_OFFSETS
                    )
            );
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            return blockEntity instanceof CursedPedestalBlockEntity
                    && !(blockEntity instanceof QuartzDisplayStandBlockEntity);
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            if (!(blockEntity instanceof CursedPedestalBlockEntity)
                    || blockEntity instanceof QuartzDisplayStandBlockEntity) {
                return MachineDescriptorResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.MACHINE_UNSUPPORTED,
                        "block_entity_is_not_cursed_pedestal"
                ));
            }
            return MachineDescriptorResult.ready(descriptor());
        }
    }

    private static final class PedestalExecutionIntegration
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
            if (context.recipe().materials().size()
                    > CursedPedestalStructure.DISPLAY_SLOT_COUNT + 1
                    || context.recipe().outputs().size() != 1
                    || !(context.liveRecipe()
                    instanceof PedestalCraftingRecipe recipe)) {
                return ExecutionPlanResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_PREPARATION_FAILED,
                        "pedestal_recipe_contract_changed"
                ));
            }
            List<InputRoute> inputs = new ArrayList<>();
            for (int index = 0;
                 index < context.recipe().materials().size();
                 index++) {
                MaterialRequirement material =
                        context.recipe().materials().get(index);
                inputs.add(new InputRoute(
                        material.entryId().toString(),
                        material.inputSlot() == null
                                ? index : material.inputSlot(),
                        material.inputGroup(),
                        material.amount()
                ));
            }
            OutputDeclaration output =
                    context.recipe().outputs().get(0);
            MachineDescriptor descriptor = descriptor();
            return ExecutionPlanResult.ready(new ExecutionPlan(
                    context.operationId(),
                    recipe.getId(),
                    MACHINE_ID,
                    RECIPE_INTEGRATION_ID,
                    MACHINE_INTEGRATION_ID,
                    EXECUTION_INTEGRATION_ID,
                    context.recipeContext().recipeRevision(),
                    DESCRIPTOR_REVISION,
                    context.recipe().definitionFingerprint(),
                    descriptor.descriptorFingerprint(),
                    inputs,
                    List.of(),
                    List.of(),
                    CursedPedestalStructure.CRAFT_DURATION,
                    List.of(outputPort(output)),
                    descriptor.supportScope(),
                    descriptor.concurrency()
            ));
        }

        @Override
        public ExecutionResult start(
                OperationContext context,
                ExecutionPlan plan
        ) {
            ServerLevel level = context.machine().level();
            BlockPos pedestalPos = context.machine().position();
            if (!CursedPedestalStructure.structureChunksLoaded(
                    level, pedestalPos)) {
                return ExecutionResult.retry(
                        20,
                        diagnostic(
                                IntegrationDiagnosticCode.EXECUTION_START_RETRY,
                                "pedestal_structure_chunks_are_not_loaded"
                        )
                );
            }
            CursedPedestalBlockEntity pedestal =
                    CursedPedestalStructure.pedestal(
                            level, pedestalPos);
            if (pedestal == null) {
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_START_REJECTED,
                        "cursed_pedestal_is_missing"
                ));
            }
            Recipe<?> live = level.getRecipeManager()
                    .byKey(plan.recipeId()).orElse(null);
            if (!(live instanceof PedestalCraftingRecipe recipe)) {
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_OPERATION_STALE,
                        "pedestal_recipe_is_missing"
                ));
            }
            List<BlockPos> displayPositions =
                    CursedPedestalStructure.displayPositions(
                            pedestalPos);
            for (BlockPos pos : displayPositions) {
                if (CursedPedestalStructure.displayStand(
                        level, pos) == null) {
                    return ExecutionResult.rejected(diagnostic(
                            IntegrationDiagnosticCode.PORT_BINDING_UNAVAILABLE,
                            "eight_quartz_display_stands_are_required"
                    ));
                }
            }
            if (pedestal.hasItem() || pedestal.isCrafting()
                    || displayPositions.stream().anyMatch(pos -> {
                    QuartzDisplayStandBlockEntity stand =
                            CursedPedestalStructure.displayStand(
                                    level, pos);
                    return stand != null && stand.hasItem();
            })) {
                return ExecutionResult.retry(
                        20,
                        diagnostic(
                                IntegrationDiagnosticCode.MACHINE_BUSY,
                                "cursed_pedestal_structure_is_busy"
                        )
                );
            }

            ReservedMaterial[] reservedBySlot =
                    reservedMaterialsBySlot(context, plan);
            if (reservedBySlot == null) {
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_PREPARATION_FAILED,
                        "reserved_pedestal_materials_are_incomplete"
                ));
            }
            List<ReservedMaterial> reserved = new ArrayList<>();
            for (int index = 0;
                 index < recipe.getIngredients().size();
                 index++) {
                ReservedMaterial material = reservedBySlot[index];
                ItemStack stack = stack(material.resource());
                if (stack.isEmpty()
                        || !CursedPedestalStructure.placeDisplayItem(
                        level, displayPositions.get(index), stack)) {
                    rollbackPlacement(level, pedestalPos);
                    return ExecutionResult.rejected(diagnostic(
                            IntegrationDiagnosticCode.EXECUTION_START_REJECTED,
                            "pedestal_display_item_placement_failed"
                    ));
                }
                reserved.add(material);
            }
            ReservedMaterial catalyst = reservedBySlot[
                    CursedPedestalStructure.CATALYST_SLOT];
            ItemStack catalystStack = stack(catalyst.resource());
            if (catalystStack.isEmpty()
                    || !CursedPedestalStructure.placeCatalyst(
                    level, pedestalPos, catalystStack)) {
                rollbackPlacement(level, pedestalPos);
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_START_REJECTED,
                        "pedestal_catalyst_placement_failed"
                ));
            }
            reserved.add(catalyst);

            PedestalCraftingRecipe firstMatch = level.getRecipeManager()
                    .getRecipes()
                    .stream()
                    .filter(candidate ->
                            candidate instanceof PedestalCraftingRecipe)
                    .map(candidate ->
                            (PedestalCraftingRecipe) candidate)
                    .filter(candidate -> candidate.matches(
                            level, pedestalPos))
                    .findFirst()
                    .orElse(null);
            if (firstMatch == null
                    || !recipe.getId().equals(firstMatch.getId())) {
                rollbackPlacement(level, pedestalPos);
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_PREPARATION_FAILED,
                        "pedestal_recipe_order_is_ambiguous"
                ));
            }
            try {
                pedestal.tryStartCrafting(level, pedestalPos);
            } catch (RuntimeException | LinkageError failure) {
                rollbackPlacement(level, pedestalPos);
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_START_REJECTED,
                        "pedestal_start_threw"
                ));
            }
            if (!pedestal.isCrafting()) {
                rollbackPlacement(level, pedestalPos);
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_START_REJECTED,
                        "pedestal_recipe_did_not_start"
                ));
            }
            return ExecutionResult.started(new CursedPedestalOperation(
                    context.machine(),
                    plan.recipeId(),
                    OUTPUT_PORT_ID,
                    IntegrationResourceKey.item(recipe.getResult()),
                    reserved,
                    context.currentTick()
            ));
        }

        @Override
        public ExecutionResult resume(
                OperationContext context,
                ExecutionPlan plan,
                OperationSnapshot snapshot
        ) {
            ServerLevel level = context.machine().level();
            BlockPos pedestalPos = context.machine().position();
            if (!(level.getBlockEntity(pedestalPos)
                    instanceof CursedPedestalBlockEntity)
                    || level.getBlockEntity(pedestalPos)
                    instanceof QuartzDisplayStandBlockEntity) {
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_OPERATION_STALE,
                        "cursed_pedestal_is_missing_after_restart"
                ));
            }
            Recipe<?> live = level.getRecipeManager()
                    .byKey(plan.recipeId()).orElse(null);
            if (!(live instanceof PedestalCraftingRecipe recipe)) {
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_OPERATION_STALE,
                        "pedestal_recipe_is_missing_after_restart"
                ));
            }
            ReservedMaterial[] reservedBySlot =
                    reservedMaterialsBySlot(context, plan);
            if (reservedBySlot == null) {
                return ExecutionResult.rejected(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_OPERATION_STALE,
                        "reserved_pedestal_materials_are_missing_after_restart"
                ));
            }
            List<ReservedMaterial> reserved = new ArrayList<>();
            for (int index = 0;
                 index < recipe.getIngredients().size();
                 index++) {
                ReservedMaterial material = reservedBySlot[index];
                if (material == null) {
                    return ExecutionResult.rejected(diagnostic(
                            IntegrationDiagnosticCode.EXECUTION_OPERATION_STALE,
                            "pedestal_display_material_is_missing_after_restart"
                    ));
                }
                reserved.add(material);
            }
            reserved.add(
                    reservedBySlot[CursedPedestalStructure.CATALYST_SLOT]);
            long persistedStart = snapshot.state()
                    .getLong("started_at");
            return ExecutionResult.started(new CursedPedestalOperation(
                    context.machine(),
                    plan.recipeId(),
                    OUTPUT_PORT_ID,
                    plan.outputPorts().get(0).resource(),
                    reserved,
                    persistedStart > 0L
                            ? persistedStart : context.currentTick()
            ));
        }
    }

    @Nullable
    private static ReservedMaterial[] reservedMaterialsBySlot(
            OperationContext context,
            ExecutionPlan plan
    ) {
        ReservedMaterial[] materials =
                new ReservedMaterial[
                        CursedPedestalStructure.DISPLAY_SLOT_COUNT + 1];
        for (InputRoute route : plan.itemInputs()) {
            if (route.slot() < 0
                    || route.slot() >= materials.length
                    || materials[route.slot()] != null) {
                return null;
            }
            ReservedMaterial material = context.materials()
                    .find(route.entryId()).orElse(null);
            if (material == null
                    || material.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM
                    || material.amount() != route.amount()) {
                return null;
            }
            materials[route.slot()] = material;
        }
        if (materials[CursedPedestalStructure.CATALYST_SLOT] == null) {
            return null;
        }
        for (ReservedMaterial material : materials) {
            if (material != null && material.amount() != 1L) {
                return null;
            }
        }
        return materials;
    }

    private static void rollbackPlacement(
            ServerLevel level,
            BlockPos pedestalPos
    ) {
        CursedPedestalBlockEntity pedestal =
                CursedPedestalStructure.pedestal(
                        level, pedestalPos);
        if (pedestal != null) {
            CursedPedestalStructure.removeStoredItem(
                    level, pedestalPos, pedestal);
        }
        for (BlockPos pos : CursedPedestalStructure.displayPositions(
                pedestalPos)) {
            QuartzDisplayStandBlockEntity stand =
                    CursedPedestalStructure.displayStand(level, pos);
            if (stand != null) {
                CursedPedestalStructure.removeStoredItem(
                        level, pos, stand);
            }
        }
        CursedPedestalStructure.refreshAllVisualStates(
                level, pedestalPos);
    }

    @Nullable
    private static ItemIngredientView ingredientView(
            @Nullable Ingredient ingredient
    ) {
        if (ingredient == null || ingredient.isEmpty()) return null;
        try {
            ItemStack[] items = ingredient.getItems();
            return items.length == 0
                    ? null : ItemIngredientView.anyOf(items);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static ItemStack stack(IntegrationResourceKey resource) {
        Item item = ForgeRegistries.ITEMS.getValue(resource.id());
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        if (!resource.identity().isEmpty()) {
            stack.setTag(resource.identity());
        }
        return stack;
    }

    private static OutputPort outputPort(OutputDeclaration declaration) {
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

    private static ResourceLocation displayEntryId(int index) {
        return id("profound_curse/pedestal_crafting/display/" + index);
    }

    private static RecipeInspectionResult rejected(
            IntegrationDiagnosticCode code,
            String reason
    ) {
        return RecipeInspectionResult.rejected(new IntegrationDiagnostic(
                code,
                IntegrationSeverity.ERROR,
                RECIPE_INTEGRATION_ID,
                Map.of("reason", reason)
        ));
    }

    private static IntegrationDiagnostic diagnostic(
            IntegrationDiagnosticCode code,
            String reason
    ) {
        return new IntegrationDiagnostic(
                code,
                IntegrationSeverity.ERROR,
                EXECUTION_INTEGRATION_ID,
                Map.of("reason", reason)
        );
    }

    private static void requireAccepted(
            String label,
            RegistrationResult result
    ) {
        if (result.accepted()) return;
        throw new IllegalStateException(
                label + " registration failed: status="
                        + result.status()
                        + ", id=" + result.registeredId()
                        + ", diagnostics=" + result.diagnostics()
        );
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(
                RSIntegrationMod.MOD_ID, path);
    }
}
