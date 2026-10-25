package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.DefaultRSIIntegrationApi;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RegistrationOptions;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
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
import com.huanghuang.rsintegration.api.recipe.RecipeFlags;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ExternalIntegrationBatchDelegateTest extends BootstrapTest {
    @Test
    void publicItemMachineMovesReservedInputsAndCollectsRealOutputs() {
        ResourceLocation recipeId = resource("recipe");
        ResourceLocation processId = resource("process");
        BlockPos position = new BlockPos(4, 64, 8);
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        RecipeView view = recipeView(recipeId, processId);
        api.beginRegistration();
        api.registerRecipe(
                new StubRecipeIntegration(view),
                new RegistrationOptions(10, Map.of())
        );
        StubMachineIntegration machineIntegration =
                new StubMachineIntegration(processId);
        api.registerMachine(
                machineIntegration,
                new RegistrationOptions(10, Map.of())
        );
        api.freezeRegistration();

        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        AtomicReference<ItemStack> inputSlot = new AtomicReference<>(ItemStack.EMPTY);
        AtomicReference<ItemStack> outputSlot = new AtomicReference<>(ItemStack.EMPTY);
        BlockEntity blockEntity = mock(
                BlockEntity.class,
                withSettings().extraInterfaces(Container.class)
        );
        Container container = (Container) blockEntity;
        when(container.getItem(anyInt())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(0) == 0
                        ? inputSlot.get().copy()
                        : outputSlot.get().copy());
        doAnswer(invocation -> {
            int slot = invocation.getArgument(0);
            ItemStack stack = invocation.getArgument(1);
            if (slot == 0) inputSlot.set(stack.copy());
            else outputSlot.set(stack.copy());
            return null;
        }).when(container).setItem(anyInt(), any());
        when(container.getMaxStackSize()).thenReturn(64);
        when(container.getContainerSize()).thenReturn(2);

        when(level.hasChunkAt(position)).thenReturn(true);
        when(level.getBlockEntity(position)).thenReturn(blockEntity);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.getGameTime()).thenReturn(10L);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.level()).thenReturn(level);
        setServer(player, server);

        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(recipeId);
        RecipeManager recipeManager = mock(RecipeManager.class);
        doReturn(Optional.of(recipe)).when(recipeManager).byKey(recipeId);
        when(level.getRecipeManager()).thenReturn(recipeManager);

        ExternalIntegrationBatchDelegate delegate =
                new ExternalIntegrationBatchDelegate(api);
        var preparation = delegate.prepare(
                player, recipeId, null, position);
        assertTrue(
                preparation.state()
                        == IBatchDelegate.PreparationState.READY,
                () -> preparation.detail() + " / "
                        + preparation.userMessage()
        );
        assertTrue(delegate.startPreparedOperation(
                player,
                List.of(new ItemStack(Items.DIAMOND, 2)),
                new ExtractionLedger()
        ));
        assertEquals(Items.DIAMOND, inputSlot.get().getItem());
        assertEquals(2, inputSlot.get().getCount());

        machineIntegration.revision = 2L;
        var changed = delegate.observeCraft(level);
        assertEquals(IBatchDelegate.CraftPhase.FAILED, changed.phase());
        assertTrue(changed.detail().contains("MACHINE_DESCRIPTOR_CHANGED"));
        machineIntegration.revision = 1L;

        outputSlot.set(new ItemStack(Items.STICK, 1));
        List<ItemStack> outputs = delegate.collectAllResults(player);

        assertEquals(1, outputs.size());
        assertEquals(Items.STICK, outputs.get(0).getItem());
        assertTrue(outputSlot.get().isEmpty());
        delegate.onBatchFinished(player);
    }

    private static RecipeView recipeView(
            ResourceLocation recipeId,
            ResourceLocation processId
    ) {
        IntegrationResourceKey diamond = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(Items.DIAMOND),
                new CompoundTag()
        );
        MaterialRequirement input = new MaterialRequirement(
                resource("input"),
                new ItemIngredientView(
                        List.of(new ItemAlternative(diamond)),
                        ResourceMatchMode.ANY_IDENTITY
                ),
                2L,
                InputConsumption.consumed(),
                "ingredients",
                0,
                true
        );
        OutputDeclaration output = new OutputDeclaration(
                resource("output"),
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
                recipeId,
                processId,
                List.of(input),
                List.of(),
                List.of(output),
                new RecipeFlags(
                        true, false, true, false,
                        false, true, true)
        );
    }

    private static void setServer(ServerPlayer player, MinecraftServer server) {
        try {
            var field = ServerPlayer.class.getField("server");
            field.setAccessible(true);
            field.set(player, server);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ResourceLocation resource(String path) {
        return new ResourceLocation("test", path);
    }

    private record StubRecipeIntegration(RecipeView view)
            implements RecipeIntegration<Recipe<?>> {
        @Override
        public ResourceLocation id() {
            return resource("recipe_integration");
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
    }

    private static final class StubMachineIntegration
            implements MachineIntegration {
        private final ResourceLocation processId;
        private long revision = 1L;

        private StubMachineIntegration(ResourceLocation processId) {
            this.processId = processId;
        }

        @Override
        public ResourceLocation id() {
            return resource("machine_integration");
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
                    resource("machine"),
                    revision,
                    Set.of(processId),
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
}
