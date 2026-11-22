package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.crafting.batch.MachineOperation;
import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import com.huanghuang.rsintegration.crafting.OperationResourceCoordinator;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ParallelCraftGroupTickBudgetTest extends BootstrapTest {
    @BeforeAll
    static void initializeRuntime() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @Test
    void directOutputStartsWithAMachineLeaseAndWithoutWorldCapture() {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.serverLevel()).thenReturn(level);
        BlockPos pos = new BlockPos(-3, -60, 0);
        var machine = new BoundMachine(Level.OVERWORLD.location(), pos, ModType.CUSTOM_GUI, "test");
        SlotDelegate delegate = new SlotDelegate() {
            @Override public ItemStack getExpectedOutput() { return new ItemStack(Items.DIAMOND); }
            @Override public BatchConcurrencyCapabilities concurrencyCapabilities() {
                return BatchConcurrencyCapabilities.delegateResult();
            }
        };
        ParallelCraftGroup group = new ParallelCraftGroup(List.of(machine),
                ModType.CUSTOM_GUI, new ResourceLocation("test", "direct_output"),
                player, 1, false, BatchConcurrencyCapabilities.ownedWorldCapture(),
                null, 1, ignored -> delegate);
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        group.setOperationKernel(new OperationExecutionKernel(new OperationResourceCoordinator(
                        machines, captures, new OperationBudget(1, 1))),
                UUID.randomUUID(), new NodeId(0), new OperationBudget(1, 1));

        OperationLifecycle lifecycle = new OperationLifecycle(group);
        assertTrue(lifecycle.start(OperationStartContext.delegateExtracted(player, ExtractionLedger.external())));
        assertEquals(1, group.getRunningOperations());
        assertEquals(1, machines.size());
        assertEquals(0, captures.size());
        assertEquals(MachineOperation.CraftPhase.DONE, lifecycle.poll(level).phase());
        assertEquals(1, group.drainSettledResults().stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(0, machines.size());
    }

    @Test
    void transferResumesBeforeObservingAndNeverTransfersTheSameWorkerTwice() {
        Fixture fixture = new Fixture();
        fixture.delegates.get(0).onTransfer = () -> fixture.allowed.set(false);

        assertEquals(MachineOperation.CraftPhase.WORKING, new OperationLifecycle(fixture.group).poll(fixture.level).phase());
        assertEquals(1, fixture.delegates.get(0).transfers);
        assertEquals(0, fixture.delegates.get(1).transfers);
        assertEquals(0, fixture.delegates.get(0).observations);

        fixture.allowed.set(true);
        assertEquals(MachineOperation.CraftPhase.DONE, new OperationLifecycle(fixture.group).poll(fixture.level).phase());
        fixture.verifyCompleted();
    }

    @Test
    void observationResumesWithoutRepeatingTransfersCollectionOrCompletion() {
        Fixture fixture = new Fixture();
        fixture.delegates.get(0).onObserve = () -> fixture.allowed.set(false);
        assertEquals(MachineOperation.CraftPhase.WORKING, new OperationLifecycle(fixture.group).poll(fixture.level).phase());
        assertEquals(1, fixture.group.getCompletedOperations());
        assertEquals(1, fixture.delegates.get(0).observations);
        assertEquals(0, fixture.delegates.get(1).observations);

        fixture.allowed.set(true);
        assertEquals(MachineOperation.CraftPhase.DONE, new OperationLifecycle(fixture.group).poll(fixture.level).phase());
        fixture.verifyCompleted();
    }

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final List<SlotDelegate> delegates = new ArrayList<>();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final ParallelCraftGroup group;

        Fixture() {
            when(player.serverLevel()).thenReturn(level);
            List<BoundMachine> machines = List.of(
                    new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, ModType.CUSTOM_GUI, "test"),
                    new BoundMachine(Level.OVERWORLD.location(), new BlockPos(1, 0, 0), ModType.CUSTOM_GUI, "test"));
            group = new ParallelCraftGroup(machines, ModType.CUSTOM_GUI,
                    new ResourceLocation("test", "budget_group"), player, 2, false,
                    BatchConcurrencyCapabilities.machineSlot(), null, 2, machine -> {
                        SlotDelegate delegate = new SlotDelegate();
                        delegates.add(delegate);
                        return delegate;
                    });
            assertTrue(group.machineOperation().start(OperationStartContext.delegateExtracted(
                    player, ExtractionLedger.external())));
            assertEquals(2, group.getRunningOperations());
            group.setObservationAllowed(allowed::get);
        }

        void verifyCompleted() {
            assertEquals(2, group.getCompletedOperations());
            assertEquals(0, group.getRunningOperations());
            assertEquals(2, group.drainSettledResults().stream().mapToInt(ItemStack::getCount).sum());
            assertTrue(group.drainSettledResults().isEmpty());
            for (SlotDelegate delegate : delegates) {
                assertEquals(1, delegate.transfers);
                assertEquals(1, delegate.advances);
                assertEquals(1, delegate.observations);
                assertEquals(1, delegate.collections);
                assertEquals(1, delegate.completions);
            }
        }
    }

    private static class SlotDelegate implements IBatchDelegate, MachineOperation {
        @Override public MachineOperation machineOperation() { return this; }

        Runnable onTransfer = () -> {};
        Runnable onObserve = () -> {};
        int transfers;
        int advances;
        int observations;
        int collections;
        int completions;

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipe,
                                       ResourceLocation dimension, BlockPos position) { return true; }
        @Override
        public boolean start(OperationStartContext context) { return true; }
        @Override
        public void advance(ServerLevel level) { advances++; }
        @Override
        public CraftObservation observe(ServerLevel level) {
            observations++;
            onObserve.run();
            return new CraftObservation(true ? CraftPhase.DONE : CraftPhase.WORKING);
        }
        private void transferAvailable(ServerLevel level) {
            transfers++;
            onTransfer.run();
        }
        @Override public OperationOutputSource outputSource() { return OperationOutputSource.incremental(this::transferAvailable, OperationOutputSource.single(player -> { collections++;
            return new ItemStack(Items.DIAMOND); }, outputContract())); }
        @Override
        public void cleanup(ServerPlayer player) { completions++; }
        @Override
        public void recover(ServerPlayer player, String reason) {}
        @Override
        public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }
}
