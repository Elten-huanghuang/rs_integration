package com.example.rsiintegration.apismoke;

import com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantingApparatusRecipe;
import com.hollingsworth.arsnouveau.common.block.tile.EnchantingApparatusTile;
import com.hollingsworth.arsnouveau.common.block.tile.ImbuementTile;
import com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ars Nouveau 的标准 I/O 公共 API 案例。
 *
 * <p>只处理固定物品输出的 Imbuement Chamber 与普通 Enchanting
 * Apparatus 配方。动态附魔、护甲升级和 Scribes' Table 仍由 RSI
 * 内部兼容层负责。</p>
 */
final class ArsNouveauIntegration {
    static final int MAX_PEDESTALS = 12;
    private static final int CENTRAL_SLOT = 0;
    private static final int PEDESTAL_SLOT_START = 1;
    private static final int OUTPUT_SLOT = 32;

    private static final String REAGENT_GROUP = "reagent";
    private static final String PEDESTAL_GROUP = "pedestal";
    private static final String OUTPUT_GROUP = "result";

    private static final ResourceLocation IMBUEMENT_BLOCK =
            new ResourceLocation("ars_nouveau", "imbuement_chamber");
    private static final ResourceLocation APPARATUS_BLOCK =
            new ResourceLocation("ars_nouveau", "enchanting_apparatus");
    private static final ResourceLocation IMBUEMENT_PROCESS =
            id("ars_nouveau/imbuement");
    private static final ResourceLocation APPARATUS_PROCESS =
            id("ars_nouveau/enchanting_apparatus");

    private final ResourceLocation recipeIntegrationId =
            id("ars_nouveau/recipe");
    private final ResourceLocation machineIntegrationId =
            id("ars_nouveau/machine");
    private final ResourceLocation machineId =
            id("ars_nouveau/machine");

    RecipeIntegration<Recipe<?>> recipe() {
        return new ArsRecipeIntegration();
    }

    MachineIntegration machine() {
        return new ArsMachineIntegration();
    }

    private final class ArsRecipeIntegration
            implements RecipeIntegration<Recipe<?>> {
        @Override
        public ResourceLocation id() {
            return recipeIntegrationId;
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return recipe instanceof ImbuementRecipe
                    || recipe != null
                    && recipe.getClass()
                    == EnchantingApparatusRecipe.class;
        }

        @Override
        public RecipeExecutionKind executionKind() {
            return RecipeExecutionKind.STANDARD_IO;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                Recipe<?> recipe
        ) {
            Projection projection = project(recipe);
            if (projection == null) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "unsupported Ars Nouveau recipe shape"));
            }
            ResourceLocation recipeId = recipe.getId();
            if (recipeId == null) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "recipe id is missing"));
            }
            List<MaterialRequirement> materials = new ArrayList<>();
            for (int index = 0;
                 index < projection.pedestalIngredients().size();
                 index++) {
                Ingredient ingredient =
                        projection.pedestalIngredients().get(index);
                if (ingredient == null || ingredient.isEmpty()) continue;
                materials.add(material(
                        recipeId,
                        "pedestal/" + index,
                        ingredient,
                        PEDESTAL_GROUP,
                        index,
                        projection.imbuement()
                                ? InputConsumption.catalyst()
                                : InputConsumption.consumed()
                ));
            }
            if (projection.centralIngredient() == null
                    || projection.centralIngredient().isEmpty()) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "central ingredient is missing"));
            }
            materials.add(material(
                    recipeId,
                    "reagent",
                    projection.centralIngredient(),
                    REAGENT_GROUP,
                    CENTRAL_SLOT,
                    InputConsumption.consumed()
            ));
            if (materials.size() > MAX_PEDESTALS + 1) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "recipe exceeds supported pedestal count"));
            }
            ItemStack output = projection.output();
            if (output.isEmpty()) {
                return RecipeInspectionResult.rejected(diagnostic(
                        "recipe output is empty"));
            }
            OutputDeclaration result = new OutputDeclaration(
                    ArsNouveauIntegration.id("ars_nouveau/output"),
                    OUTPUT_GROUP,
                    IntegrationResourceKey.item(output),
                    OutputAmount.exactly(output.getCount()),
                    OutputKind.PRIMARY,
                    OutputProbability.guaranteed(),
                    false,
                    false,
                    null
            );
            RecipeView view = new RecipeView(
                    recipeId,
                    projection.processId(),
                    materials,
                    List.of(),
                    List.of(result),
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

    private final class ArsMachineIntegration
            implements MachineIntegration {
        @Override
        public ResourceLocation id() {
            return machineIntegrationId;
        }

        @Override
        public List<MachineBinding> bindings() {
            return List.of(
                    MachineBinding.blocks(
                            Set.of(IMBUEMENT_PROCESS),
                            false,
                            IMBUEMENT_BLOCK
                    ),
                    MachineBinding.blocks(
                            Set.of(APPARATUS_PROCESS),
                            false,
                            APPARATUS_BLOCK
                    )
            );
        }

        @Override
        public Map<ResourceLocation, PlannedConcurrency>
        planningConcurrency() {
            PlannedConcurrency profile = new PlannedConcurrency(
                    ConcurrencyCapabilities.MaterialOwnership
                            .CHAIN_RESERVED,
                    ConcurrencyCapabilities.OutputOwnership
                            .MACHINE_SLOT,
                    ConcurrencyCapabilities.CleanupContract
                            .SEPARABLE_OFFLINE,
                    ConcurrencyCapabilities.SideEffects
                            .ADJACENT_MACHINE,
                    ConcurrencyCapabilities.PreparationContract
                            .RETRY_SAFE,
                    planningSupportOffsets()
            );
            return Map.of(
                    IMBUEMENT_PROCESS, profile,
                    APPARATUS_PROCESS, profile
            );
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            return blockEntity instanceof ImbuementTile
                    || blockEntity instanceof EnchantingApparatusTile;
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            if (!(blockEntity instanceof Container)) {
                return MachineDescriptorResult.rejected(diagnostic(
                        "Ars machine is not a container"));
            }
            boolean imbuement =
                    blockEntity instanceof ImbuementTile;
            boolean apparatus =
                    blockEntity instanceof EnchantingApparatusTile;
            if (!imbuement && !apparatus) {
                return MachineDescriptorResult.rejected(diagnostic(
                        "bound block entity is not a supported Ars machine"));
            }
            List<MachineItemPort> ports = new ArrayList<>();
            ports.add(new MachineItemPort(
                    "reagent",
                    CENTRAL_SLOT,
                    null,
                    InputOutputAccess.INPUT,
                    ItemPortAccess.MACHINE_INVENTORY,
                    REAGENT_GROUP,
                    null,
                    1
            ));
            for (int index = 0; index < MAX_PEDESTALS; index++) {
                ports.add(new MachineItemPort(
                        "pedestal_" + index,
                        PEDESTAL_SLOT_START + index,
                        null,
                        InputOutputAccess.INPUT,
                        ItemPortAccess.MACHINE_INVENTORY,
                        PEDESTAL_GROUP,
                        null,
                        1
                ));
            }
            ports.add(new MachineItemPort(
                    "result",
                    OUTPUT_SLOT,
                    null,
                    InputOutputAccess.OUTPUT,
                    ItemPortAccess.MACHINE_INVENTORY,
                    null,
                    OUTPUT_GROUP,
                    64
            ));
            return MachineDescriptorResult.ready(new MachineDescriptor(
                    machineId,
                    1L,
                    Set.of(imbuement
                            ? IMBUEMENT_PROCESS : APPARATUS_PROCESS),
                    ports,
                    List.of(),
                    List.of(),
                    MachineExecutionMode.OUTPUT_DELTA_ONLY,
                    false,
                    ConcurrencyCapabilities.machineSlot(),
                    new SupportScope(
                            supportOffsets(
                                    context.position(),
                                    blockEntity,
                                    apparatus
                            ),
                            List.of(),
                            false
                    ),
                    false
            ));
        }

        @Override
        public Optional<IItemHandler> internalItemHandler(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            if (!(blockEntity instanceof Container container)) {
                return Optional.empty();
            }
            boolean imbuement =
                    blockEntity instanceof ImbuementTile;
            boolean apparatus =
                    blockEntity instanceof EnchantingApparatusTile;
            if (!imbuement && !apparatus) return Optional.empty();
            return Optional.of(new ArsMachineInventory(
                    blockEntity,
                    container,
                    apparatus
            ));
        }
    }

    private static MaterialRequirement material(
            ResourceLocation recipeId,
            String path,
            Ingredient ingredient,
            String group,
            int slot,
            InputConsumption consumption
    ) {
        return new MaterialRequirement(
                new ResourceLocation(
                        recipeId.getNamespace(),
                        recipeId.getPath() + "/" + path),
                ItemIngredientView.anyOf(ingredient.getItems()),
                1L,
                consumption,
                group,
                slot,
                true
        );
    }

    @Nullable
    private static Projection project(Recipe<?> recipe) {
        if (recipe instanceof ImbuementRecipe imbuement) {
            return new Projection(
                    imbuement.input,
                    List.copyOf(imbuement.pedestalItems),
                    imbuement.output.copy(),
                    IMBUEMENT_PROCESS,
                    true
            );
        }
        if (recipe != null
                && recipe.getClass()
                == EnchantingApparatusRecipe.class) {
            EnchantingApparatusRecipe apparatus =
                    (EnchantingApparatusRecipe) recipe;
            return new Projection(
                    apparatus.reagent,
                    List.copyOf(apparatus.pedestalItems),
                    apparatus.result.copy(),
                    APPARATUS_PROCESS,
                    false
            );
        }
        return null;
    }

    private static List<BlockPos> supportOffsets(
            BlockPos machinePos,
            BlockEntity machine,
            boolean apparatus
    ) {
        return pedestalPositions(machine, apparatus).stream()
                .map(position -> position.subtract(machinePos))
                .toList();
    }

    private static List<BlockPos> planningSupportOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                if (x == 0 && z == 0) continue;
                offsets.add(new BlockPos(x, 0, z));
            }
        }
        return List.copyOf(offsets);
    }

    private static List<BlockPos> pedestalPositions(
            BlockEntity machine,
            boolean apparatus
    ) {
        String methodName = apparatus
                ? "pedestalList" : "getNearbyPedestals";
        try {
            Method method = machine.getClass()
                    .getMethod(methodName);
            Object value = method.invoke(machine);
            if (!(value instanceof List<?> raw)) return List.of();
            List<BlockPos> positions = new ArrayList<>();
            for (Object entry : raw) {
                if (entry instanceof BlockPos pos) {
                    positions.add(pos.immutable());
                }
            }
            return List.copyOf(positions);
        } catch (ReflectiveOperationException
                 | RuntimeException ignored) {
            return List.of();
        }
    }

    private static IntegrationDiagnostic diagnostic(String reason) {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.INTEGRATION_REPORTED_FAILURE,
                IntegrationSeverity.ERROR,
                id("ars_nouveau/machine"),
                Map.of("reason", reason)
        );
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(RsiApiSmokeMod.MOD_ID, path);
    }

    private record Projection(
            Ingredient centralIngredient,
            List<Ingredient> pedestalIngredients,
            ItemStack output,
            ResourceLocation processId,
            boolean imbuement
    ) {}

    /**
     * 把 Ars 的中央槽和相邻底座映射为稳定、无序方向的 API 内部槽。
     */
    private static final class ArsMachineInventory
            implements IItemHandler {
        private final BlockEntity machine;
        private final Container central;
        private final boolean apparatus;

        private ArsMachineInventory(
                BlockEntity machine,
                Container central,
                boolean apparatus
        ) {
            this.machine = machine;
            this.central = central;
            this.apparatus = apparatus;
        }

        @Override
        public int getSlots() {
            return OUTPUT_SLOT + 1;
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            if (slot == CENTRAL_SLOT || slot == OUTPUT_SLOT) {
                return central.getItem(CENTRAL_SLOT).copy();
            }
            Container pedestal = pedestal(slot);
            return pedestal == null
                    ? ItemStack.EMPTY
                    : pedestal.getItem(0).copy();
        }

        @Override
        public @NotNull ItemStack insertItem(
                int slot,
                ItemStack stack,
                boolean simulate
        ) {
            if (stack.isEmpty() || slot == OUTPUT_SLOT) {
                return stack;
            }
            Container target = slot == CENTRAL_SLOT
                    ? central : pedestal(slot);
            if (target == null || !target.getItem(0).isEmpty()) {
                return stack;
            }
            ItemStack offered = stack.copyWithCount(1);
            if (!simulate) {
                target.setItem(0, offered);
                target.setChanged();
                boolean accepted = ItemStack.isSameItemSameTags(
                        target.getItem(0), offered)
                        || slot == CENTRAL_SLOT && isBusy();
                if (!accepted) {
                    target.setItem(0, ItemStack.EMPTY);
                    target.setChanged();
                    return stack;
                }
            }
            ItemStack remainder = stack.copy();
            remainder.shrink(1);
            return remainder;
        }

        @Override
        public @NotNull ItemStack extractItem(
                int slot,
                int amount,
                boolean simulate
        ) {
            if (amount <= 0) return ItemStack.EMPTY;
            Container target = slot == CENTRAL_SLOT
                    || slot == OUTPUT_SLOT
                    ? central : pedestal(slot);
            if (target == null || target.getItem(0).isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack extracted = simulate
                    ? target.getItem(0).copyWithCount(
                    Math.min(amount, target.getItem(0).getCount()))
                    : target.removeItem(
                    0, Math.min(amount, target.getItem(0).getCount()));
            if (!simulate && slot == OUTPUT_SLOT
                    && !extracted.isEmpty() && apparatus) {
                clearPedestals();
            }
            return extracted;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot == OUTPUT_SLOT) return 64;
            return slot == CENTRAL_SLOT || pedestal(slot) != null
                    ? 1 : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return !stack.isEmpty()
                    && slot != OUTPUT_SLOT
                    && getSlotLimit(slot) == 1;
        }

        @Nullable
        private Container pedestal(int slot) {
            if (slot < PEDESTAL_SLOT_START
                    || slot >= PEDESTAL_SLOT_START + MAX_PEDESTALS) {
                return null;
            }
            List<BlockPos> positions = pedestalPositions();
            int index = slot - PEDESTAL_SLOT_START;
            if (index < 0 || index >= positions.size()) return null;
            Level level = machine.getLevel();
            if (level == null) return null;
            BlockEntity pedestal =
                    level.getBlockEntity(positions.get(index));
            return pedestal instanceof Container container
                    ? container : null;
        }

        private List<BlockPos> pedestalPositions() {
            return ArsNouveauIntegration.pedestalPositions(
                    machine, apparatus);
        }

        private boolean isBusy() {
            try {
                if (apparatus) {
                    var field = machine.getClass()
                            .getField("isCrafting");
                    return field.getBoolean(machine);
                }
                var field = machine.getClass()
                        .getDeclaredField("craftTicks");
                field.setAccessible(true);
                int ticks = field.getInt(machine);
                return ticks > 0 && ticks < 100;
            } catch (ReflectiveOperationException
                     | RuntimeException ignored) {
                return false;
            }
        }

        private void clearPedestals() {
            for (int index = 0; index < MAX_PEDESTALS; index++) {
                Container pedestal = pedestal(
                        PEDESTAL_SLOT_START + index);
                if (pedestal == null) continue;
                pedestal.setItem(0, ItemStack.EMPTY);
                pedestal.setChanged();
            }
            machine.setChanged();
        }
    }
}
