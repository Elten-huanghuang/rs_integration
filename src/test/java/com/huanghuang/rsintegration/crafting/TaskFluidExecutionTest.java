package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.MaterialDisposition;
import com.huanghuang.rsintegration.api.execution.MaterialOutcome;
import com.huanghuang.rsintegration.api.execution.CancelReason;
import com.huanghuang.rsintegration.api.execution.OperationCleanupResult;
import com.huanghuang.rsintegration.api.execution.ReservedMaterialView;
import com.huanghuang.rsintegration.api.execution.RunningOperation;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.FluidPortAccess;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.MachineFluidPort;
import com.huanghuang.rsintegration.api.machine.SupportScope;
import com.huanghuang.rsintegration.api.recipe.FluidIngredientView;
import com.huanghuang.rsintegration.api.recipe.FluidRequirement;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.external.ExternalIntegrationBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.registries.RegistryObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskFluidExecutionTest extends BootstrapTest {
    private static final ResourceLocation WATER_INPUT = id("water");
    private ServerPlayer player;
    private StorageSession session;
    private CraftStorageEndpoint endpoint;
    private RuntimeMaterialAccount account;
    private Field registryValue;
    private Object originalToken;
    private ServerLevel level;
    private BlockEntity machine;
    private FluidTank tank;

    @BeforeAll static void configure() { RuntimeMaterialAccountTest.configure(); }

    @BeforeEach void setup() throws Exception {
        registryValue = RegistryObject.class.getDeclaredField("value");
        registryValue.setAccessible(true);
        originalToken = registryValue.get(ModItems.ALCHEMIST_INK_FLUID);
        registryValue.set(ModItems.ALCHEMIST_INK_FLUID, InkFluidTestFixtures.tokenItem());
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        session = mock(StorageSession.class);
        when(session.reference()).thenReturn(new StorageReference(new StorageBackendId("test"), "network"));
        endpoint = () -> session;
        account = new RuntimeMaterialAccount(UUID.randomUUID(), player.getUUID(), mock(MinecraftServer.class), endpoint);
        level = mock(ServerLevel.class);
        machine = mock(BlockEntity.class);
        tank = spy(new FluidTank(Integer.MAX_VALUE));
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(machine);
        when(machine.getCapability(eq(ForgeCapabilities.FLUID_HANDLER), isNull()))
                .thenReturn(LazyOptional.of(() -> tank));
        clearInvocations(session);
    }

    @AfterEach void restoreRegistry() throws Exception {
        registryValue.set(ModItems.ALCHEMIST_INK_FLUID, originalToken);
    }

    @Test void snapshotAndExtractionKeepExactNbtAndLongAmounts() {
        FluidStack tagged = water(1);
        CompoundTag tag = new CompoundTag();
        tag.putString("grade", "pure");
        tagged.setTag(tag);
        account.add(InkFluidSupport.token(tagged).copyWithCount(Integer.MAX_VALUE));
        account.add(InkFluidSupport.token(tagged).copyWithCount(500));
        account.add(InkFluidSupport.token(water(900)));
        TaskFluidAccess access = capture();
        StorageFluidKey key = StorageFluidKey.fromStack(access.snapshot().backendId(), tagged);
        long total = (long) Integer.MAX_VALUE + 500;
        assertEquals(total, account.heldFluids().get(TaskFluidKey.of(tagged)));
        assertEquals(total, access.snapshot().countExact(key));
        assertEquals(total, access.extract(key, total, true).transferredAmount());
        assertEquals(0, access.extract(key, total + 1, false).transferredAmount());
        assertEquals(total, access.extract(key, total, false).transferredAmount());
        assertEquals(900, held());
        assertEquals(900L, account.heldFluids().get(TaskFluidKey.of(water(900))));
        verifyNoInteractions(session);
    }

    @Test void ordinaryInputUsesOnlyAccountAndRefundsRejectedAmountAcrossScopes() throws Exception {
        account.add(InkFluidSupport.token(water(1000)));
        tank.setCapacity(400);
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertFalse((boolean) invoke(delegate, "insertFluidInputs", new Class<?>[]{ServerPlayer.class}, player));
        }
        assertEquals(600, held());
        assertEquals(400, tank.getFluidAmount());
        invoke(delegate, "clearMachineState", new Class<?>[]{BlockEntity.class, ServerPlayer.class}, machine, null);
        assertEquals(1000, held());
        assertEquals(0, tank.getFluidAmount());
        verifyNoInteractions(session);
    }

    @Test void pureFluidRecipeStartsWithEmptySharedItemLayout() throws Exception {
        account.add(InkFluidSupport.token(water(1000)));
        ExternalIntegrationBatchDelegate delegate = spy(delegate(1000));
        doReturn(true).when(delegate).validateExecutionContext(player);
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertTrue(delegate.usesTaskFluidInputs());
            assertTrue(delegate.startOperation(OperationStartContext.chainReserved(player, new ExtractionLedger(),
                    MaterialPlan.none(), List.of(), InputBufferPlan.none())));
        }
        assertEquals(1000, tank.getFluidAmount());
        assertEquals(0, held());
        verifyNoInteractions(session);
    }

    @Test void unknownMachineFillFreezesAccountAndNeverRetriesOrSynthesizesRefund() throws Exception {
        account.add(InkFluidSupport.token(water(2000)));
        doAnswer(call -> { tank.setFluid(water(400)); throw new IllegalStateException("after side effect"); })
                .when(tank).fill(any(), eq(FluidAction.EXECUTE));
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertFalse((boolean) invoke(delegate, "insertFluidInputs", new Class<?>[]{ServerPlayer.class}, player));
            assertFalse((boolean) invoke(delegate, "insertFluidInputs", new Class<?>[]{ServerPlayer.class}, player));
        }
        assertTrue(account.uncertain());
        assertEquals(1000, held());
        invoke(delegate, "clearMachineState", new Class<?>[]{BlockEntity.class, ServerPlayer.class}, machine, player);
        assertEquals(400, tank.getFluidAmount());
        verify(tank, times(1)).fill(any(), eq(FluidAction.EXECUTE));
        verifyNoInteractions(session);
    }

    @Test void missingOrUnauthorizedAccountCannotFallBackToNetwork() throws Exception {
        ExternalIntegrationBatchDelegate missing = delegate(1000);
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertFalse((boolean) invoke(missing, "insertFluidInputs", new Class<?>[]{ServerPlayer.class}, player));
        }
        account.add(InkFluidSupport.token(water(1000)));
        ServerPlayer other = mock(ServerPlayer.class);
        when(other.getUUID()).thenReturn(UUID.randomUUID());
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertFalse((boolean) invoke(delegate(1000), "reserveCustomFluids",
                    new Class<?>[]{ServerPlayer.class, List.class}, other, new ArrayList<ReservedMaterial>()));
        }
        assertEquals(1000, held());
        verifyNoInteractions(session);
    }

    @Test void customSplitConsumedAndRecoveredRefundsOnlyExactPartOnce() throws Exception {
        account.add(InkFluidSupport.token(water(1000)));
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        List<ReservedMaterial> reserved = new ArrayList<>();
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertTrue((boolean) invoke(delegate, "reserveCustomFluids",
                    new Class<?>[]{ServerPlayer.class, List.class}, player, reserved));
        }
        assertEquals(0, held());
        List<MaterialDisposition> receipts = List.of(disposition(600, MaterialOutcome.CONSUMED),
                disposition(400, MaterialOutcome.RECOVERED));
        invoke(delegate, "settleCustomFluidInputs", new Class<?>[]{List.class, ServerPlayer.class}, receipts, null);
        invoke(delegate, "settleCustomFluidInputs", new Class<?>[]{List.class, ServerPlayer.class}, receipts, null);
        assertEquals(400, held());
        verifyNoInteractions(session);
    }

    @Test void customCancelledBeforeStartReturnsToOriginalAccountOnlyOnce() throws Exception {
        account.add(InkFluidSupport.token(water(1000)));
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertTrue((boolean) invoke(delegate, "reserveCustomFluids",
                    new Class<?>[]{ServerPlayer.class, List.class}, player, new ArrayList<ReservedMaterial>()));
        }
        invoke(delegate, "clearCustomOperation", new Class<?>[]{ServerPlayer.class}, (Object) null);
        invoke(delegate, "clearCustomOperation", new Class<?>[]{ServerPlayer.class}, (Object) null);
        assertEquals(1000, held());
        verifyNoInteractions(session);
    }

    @Test void unknownCustomTransferDoesNotRefundOriginalReservation() throws Exception {
        account.add(InkFluidSupport.token(water(1000)));
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertTrue((boolean) invoke(delegate, "reserveCustomFluids",
                    new Class<?>[]{ServerPlayer.class, List.class}, player, new ArrayList<ReservedMaterial>()));
        }
        invoke(delegate, "settleCustomFluidInputs", new Class<?>[]{List.class, ServerPlayer.class},
                List.of(disposition(1000, MaterialOutcome.UNCERTAIN)), player);
        invoke(delegate, "clearCustomOperation", new Class<?>[]{ServerPlayer.class}, player);
        assertTrue(account.uncertain());
        assertEquals(0, held());
        verifyNoInteractions(session);
    }

    @Test void taskFluidOutputStaysInGraphAndIsNotPublishedToPublicStorage() throws Exception {
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        set(delegate, "taskFluids", capture());
        CollectedOutput output = new CollectedOutput(IntegrationResourceKey.fluid(water(1)), 700,
                OutputKind.PRIMARY, id("output"));
        assertNull(invoke(delegate, "deliverCustomFluidOutput",
                new Class<?>[]{ServerPlayer.class, CollectedOutput.class}, player, output));
        set(delegate, "customCollectionComplete", true);
        List<?> results = (List<?>) invoke(delegate, "collectCustomResults", new Class<?>[]{ServerPlayer.class}, player);
        assertEquals(700, InkFluidSupport.fluid((ItemStack) results.get(0)).getAmount());
        assertTrue(delegate.drainReportedResources().isEmpty());
        assertTrue(((List<?>) invoke(delegate, "collectCustomResults", new Class<?>[]{ServerPlayer.class}, player)).isEmpty());
        assertEquals(0, held());
        verifyNoInteractions(session);
    }

    @Test void graphHandoffTransfersOnlyProducerFluidAndCannotRefundItTwice() throws Exception {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey fluid = MaterialKey.of(InkFluidSupport.token(water(1)));
        MaterialKey item = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource producer = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
        MaterialSource initial = new MaterialSource.InitialPool(fluid);
        broker.publish(producer, fluid, 700);
        broker.publish(initial, fluid, 300);
        broker.publish(producer, item, 2);
        account.add(InkFluidSupport.token(water(300)));
        var token = broker.reserve(new NodeId(1), List.of(new MaterialBroker.Request(producer, fluid, 700),
                new MaterialBroker.Request(initial, fluid, 300), new MaterialBroker.Request(producer, item, 2)));
        AsyncCraftChain chain = mock(AsyncCraftChain.class, CALLS_REAL_METHODS);
        set(chain, "graphMaterials", broker);
        set(chain, "materialAccount", account);
        set(chain, "virtualInventory", new ArrayList<ItemStack>());
        ParallelCraftGroup group = mock(ParallelCraftGroup.class);
        when(group.getChildCount()).thenReturn(1);
        when(group.getMaterialOperations()).thenReturn(1);
        when(group.usesTaskFluidInputs()).thenReturn(true);
        List<IngredientSpec> specs = List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 2));
        when(group.getFlatOperationMaterials()).thenReturn(specs);
        when(group.reservationMaterialPlan()).thenReturn(MaterialPlan.fromLegacy(specs));
        assertNotNull(invoke(chain, "reserveCountedOperationMaterials",
                new Class<?>[]{ParallelCraftGroup.class, ServerPlayer.class, ExtractionLedger.class,
                        MaterialBroker.ReservationToken.class}, group, player, new ExtractionLedger(), token));
        assertEquals(0, broker.available(producer, fluid), "物品预留完成后不能释放图内流体");
        assertEquals(3, broker.checkout(token).fragments().size());
        broker.commit(token);
        invoke(chain, "materializeTaskFluidGraphInputs", new Class<?>[]{MaterialBroker.ReservationToken.class}, token);
        invoke(chain, "materializeTaskFluidGraphInputs", new Class<?>[]{MaterialBroker.ReservationToken.class}, token);
        assertEquals(1000, held());
        broker.refund(token);
        assertEquals(0, broker.available(producer, fluid));
        assertEquals(0, broker.available(initial, fluid));
        assertEquals(2, broker.available(producer, item));
        verifyNoInteractions(session);
    }

    @Test void ordinaryFluidOutputIsCollectedAsAnOwnedToken() throws Exception {
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        set(delegate, "taskFluids", capture());
        RecipeView view = mock(RecipeView.class);
        OutputDeclaration output = mock(OutputDeclaration.class);
        when(output.resource()).thenReturn(IntegrationResourceKey.fluid(water(1)));
        when(output.amount()).thenReturn(new OutputAmount(700, 700));
        when(output.guaranteedMinimum()).thenReturn(700L);
        when(output.outputGroup()).thenReturn("out");
        when(view.outputs()).thenReturn(List.of(output));
        set(delegate, "recipeView", view);
        set(delegate, "descriptor", new MachineDescriptor(id("machine"), 1, Set.of(id("process")), List.of(),
                List.of(new MachineFluidPort("output", 0, null, InputOutputAccess.OUTPUT,
                        FluidPortAccess.FLUID_HANDLER, null, "out", 1000)), List.of(),
                MachineExecutionMode.TICKED_AND_BUSY, false, ConcurrencyCapabilities.machineSlot(),
                new SupportScope(List.of(), List.of(), false), false));
        tank.setFluid(water(700));
        List<ItemStack> results = delegate.collectAllResults(player);
        assertEquals(1, results.size());
        assertEquals(700, InkFluidSupport.fluid(results.get(0)).getAmount());
        assertEquals(0, tank.getFluidAmount());
        assertTrue(delegate.drainReportedResources().isEmpty());
        assertTrue(delegate.collectAllResults(player).isEmpty());
        verifyNoInteractions(session);
    }

    @Test void failedOperationReturnsAlreadyCollectedFluidOnlyOnce() throws Exception {
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        set(delegate, "taskFluids", capture());
        CollectedOutput output = new CollectedOutput(IntegrationResourceKey.fluid(water(1)), 700,
                OutputKind.PRIMARY, id("output"));
        invoke(delegate, "deliverCustomFluidOutput", new Class<?>[]{ServerPlayer.class, CollectedOutput.class}, player, output);
        invoke(delegate, "clearCustomOperation", new Class<?>[]{ServerPlayer.class}, player);
        invoke(delegate, "clearCustomOperation", new Class<?>[]{ServerPlayer.class}, player);
        assertEquals(700, held());
        verifyNoInteractions(session);
    }

    @Test void sameNetworkTasksCannotUseOrRefundEachOthersFluids() {
        account.add(InkFluidSupport.token(water(1000)));
        TaskFluidAccess first = capture();
        var key = first.snapshot().fluids().get(0).key();
        RuntimeMaterialAccount secondAccount = new RuntimeMaterialAccount(UUID.randomUUID(), player.getUUID(),
                mock(MinecraftServer.class), endpoint);
        secondAccount.add(InkFluidSupport.token(water(500)));
        try (var scope = RuntimeMaterialAccount.enter(secondAccount)) {
            TaskFluidAccess second = TaskFluidAccess.capture(player, endpoint);
            assertEquals(0, second.extract(key, 1000, false).transferredAmount());
            var extracted = first.extract(key, 1000, false);
            assertEquals(1000, extracted.transferredAmount());
            first.refund(extracted.transferredFluids());
            assertEquals(500, second.snapshot().countExact(key));
            assertEquals(1000, first.snapshot().countExact(key));
        }
        verifyNoInteractions(session);
    }

    @Test void startRejectedBeforeBaselineDoesNotDrainExistingMachineFluid() throws Exception {
        ExternalIntegrationBatchDelegate delegate = spy(delegate(1000));
        doReturn(true).when(delegate).validateExecutionContext(player);
        tank.setFluid(water(400));
        account.markTransferUncertain();
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertFalse(delegate.startPreparedOperation(player, List.of(new ItemStack(Items.IRON_INGOT)),
                    new ExtractionLedger()));
        }
        invoke(delegate, "clearMachineState", new Class<?>[]{BlockEntity.class, ServerPlayer.class}, machine, player);
        assertEquals(400, tank.getFluidAmount());
        assertEquals(0, held());
        assertEquals(1, delegate.failureRecoveredInputs().get(0).getCount());
    }

    @Test void customCancellationUsesVerifiedPartialRecoveryReceipt() throws Exception {
        account.add(InkFluidSupport.token(water(1000)));
        ExternalIntegrationBatchDelegate delegate = delegate(1000);
        List<ReservedMaterial> reserved = new ArrayList<>();
        try (var scope = RuntimeMaterialAccount.enter(account)) {
            assertTrue((boolean) invoke(delegate, "reserveCustomFluids",
                    new Class<?>[]{ServerPlayer.class, List.class}, player, reserved));
        }
        ReservedMaterialView materials = mock(ReservedMaterialView.class);
        when(materials.materials()).thenReturn(reserved);
        when(materials.find(WATER_INPUT.toString())).thenReturn(Optional.of(reserved.get(0)));
        set(delegate, "customMaterials", materials);
        RunningOperation operation = mock(RunningOperation.class);
        when(operation.onCancel(any(), any())).thenReturn(new OperationCleanupResult(true,
                List.of(disposition(400, MaterialOutcome.RECOVERED), disposition(600, MaterialOutcome.CONSUMED)),
                List.of(), List.of()));
        set(delegate, "runningOperation", operation);
        set(delegate, "operationStartContext", OperationStartContext.chainReserved(player, new ExtractionLedger(),
                MaterialPlan.none(), List.of(), InputBufferPlan.none()).withOperationIdentity(UUID.randomUUID(), UUID.randomUUID(), 1));
        delegate.prepareCancellation(player, CancelReason.PLAYER_REQUEST);
        invoke(delegate, "clearCustomOperation", new Class<?>[]{ServerPlayer.class}, player);
        invoke(delegate, "clearCustomOperation", new Class<?>[]{ServerPlayer.class}, player);
        assertEquals(400, held());
        verify(operation, times(1)).onCancel(any(), any());
        verifyNoInteractions(session);
    }

    @Test void publicFluidMatchingSelectsAccountNbtRatherThanNetworkCandidates() throws Exception {
        for (ResourceMatchMode mode : ResourceMatchMode.values()) {
            FluidStack actual = water(1000);
            CompoundTag fullTag = new CompoundTag();
            fullTag.putString("grade", "pure");
            fullTag.putInt("batch", 7);
            actual.setTag(fullTag);
            FluidStack template = actual.copy();
            if (mode == ResourceMatchMode.PARTIAL_IDENTITY) template.getTag().remove("batch");
            if (mode == ResourceMatchMode.ANY_IDENTITY) template.setTag(null);
            account.add(InkFluidSupport.token(actual));
            ExternalIntegrationBatchDelegate delegate = delegate(1000);
            RecipeView view = mock(RecipeView.class);
            when(view.fluids()).thenReturn(List.of(new FluidRequirement(WATER_INPUT,
                    new FluidIngredientView(List.of(IntegrationResourceKey.fluid(template)), mode),
                    1000, InputConsumption.consumed(), "fluid", null, true)));
            set(delegate, "recipeView", view);
            List<ReservedMaterial> reserved = new ArrayList<>();
            try (var scope = RuntimeMaterialAccount.enter(account)) {
                assertTrue((boolean) invoke(delegate, "reserveCustomFluids",
                        new Class<?>[]{ServerPlayer.class, List.class}, player, reserved), mode.name());
            }
            assertEquals(fullTag, reserved.get(0).resource().identity());
            assertEquals(0, held());
        }
        verifyNoInteractions(session);
    }

    private ExternalIntegrationBatchDelegate delegate(long amount) throws Exception {
        var constructor = ExternalIntegrationBatchDelegate.class.getDeclaredConstructor(RSIIntegrationApi.class);
        constructor.setAccessible(true);
        ExternalIntegrationBatchDelegate delegate = constructor.newInstance((Object) null);
        delegate.setStorageEndpoint(endpoint);
        RecipeView view = mock(RecipeView.class);
        when(view.materials()).thenReturn(List.of());
        when(view.outputs()).thenReturn(List.of());
        when(view.fluids()).thenReturn(List.of(new FluidRequirement(WATER_INPUT,
                new FluidIngredientView(List.of(IntegrationResourceKey.fluid(water(1))), ResourceMatchMode.EXACT_IDENTITY),
                amount, InputConsumption.consumed(), "fluid", null, true)));
        set(delegate, "recipeView", view);
        set(delegate, "level", level);
        set(delegate, "machinePos", BlockPos.ZERO);
        set(delegate, "executionKind", RecipeExecutionKind.STANDARD_IO);
        set(delegate, "descriptor", new MachineDescriptor(id("machine"), 1, Set.of(id("process")), List.of(),
                List.of(new MachineFluidPort("input", 0, null, InputOutputAccess.INPUT,
                        FluidPortAccess.FLUID_HANDLER, "fluid", null, Integer.MAX_VALUE)), List.of(),
                MachineExecutionMode.TICKED_AND_BUSY, false, ConcurrencyCapabilities.machineSlot(),
                new SupportScope(List.of(), List.of(), false), false));
        return delegate;
    }

    private TaskFluidAccess capture() {
        try (var scope = RuntimeMaterialAccount.enter(account)) { return TaskFluidAccess.capture(player, endpoint); }
    }
    private long held() { return account.heldMaterials().values().stream().mapToLong(Long::longValue).sum(); }
    private static FluidStack water(int amount) { return new FluidStack(Fluids.WATER, amount); }
    private static ResourceLocation id(String path) { return new ResourceLocation("test", path); }
    private static MaterialDisposition disposition(long amount, MaterialOutcome outcome) {
        return new MaterialDisposition(new ReservedMaterial(WATER_INPUT.toString(), IntegrationResourceKey.fluid(water(1)),
                amount, InputConsumption.consumed()), outcome);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
