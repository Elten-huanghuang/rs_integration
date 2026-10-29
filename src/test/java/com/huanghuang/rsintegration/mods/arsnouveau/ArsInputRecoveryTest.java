package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArsInputRecoveryTest extends BootstrapTest {
    @BeforeAll
    static void initializeModRuntime() throws Exception {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void offlineAbortRecoversActualInputAndPedestalSurvivorsOnly(boolean apparatus) throws Exception {
        Fixture fixture = new Fixture(apparatus);
        assertTrue(fixture.start());
        fixture.machine.stack.shrink(1);
        fixture.pedestal.stack.shrink(2);

        fixture.delegate.onBatchFailed(null, "offline");
        fixture.delegate.onBatchFailed(null, "repeat");

        assertEquals(List.of(2, 2), fixture.delegate.failureRecoveredInputs()
                .stream().map(ItemStack::getCount).toList());
        assertTrue(fixture.machine.stack.isEmpty());
        assertTrue(fixture.pedestal.stack.isEmpty());
        assertTrue(fixture.delegate.physicalFailureCleanupCompleted());
        verifyNoInteractions(fixture.endpoint);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortKeepsCompletedOutputAndForeignPedestalItems(boolean apparatus) throws Exception {
        Fixture fixture = new Fixture(apparatus);
        assertTrue(fixture.start());
        fixture.machine.stack = new ItemStack(Items.NETHERITE_INGOT);
        fixture.pedestal.stack = tagged("foreign", 4);

        fixture.delegate.onBatchFailed(null, "offline");

        assertTrue(fixture.delegate.failureRecoveredInputs().isEmpty());
        assertEquals(Items.NETHERITE_INGOT, fixture.machine.stack.getItem());
        assertEquals("foreign", fixture.pedestal.stack.getTag().getString("owner"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementMachineIsNotStoppedOrClaimed(boolean apparatus) throws Exception {
        Fixture fixture = new Fixture(apparatus);
        assertTrue(fixture.start());
        Machine replacement = fixture.holder(BlockPos.ZERO, true);
        replacement.isCrafting = true;
        replacement.counter = 50;
        replacement.stack = tagged("central", 3);

        fixture.delegate.onBatchFailed(null, "replaced");

        assertEquals(1, fixture.delegate.failureRecoveredInputs().size());
        assertEquals(4, fixture.delegate.failureRecoveredInputs().get(0).getCount());
        assertEquals(3, fixture.machine.stack.getCount());
        assertEquals(3, replacement.stack.getCount());
        assertTrue(replacement.isCrafting);
        assertEquals(50, replacement.counter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingMachineStillRecoversItsOriginalSupportPedestal(boolean apparatus) throws Exception {
        Fixture fixture = new Fixture(apparatus);
        assertTrue(fixture.start());
        when(fixture.level.getBlockEntity(BlockPos.ZERO)).thenReturn(null);

        fixture.delegate.onBatchFailed(null, "missing");

        assertEquals(1, fixture.delegate.failureRecoveredInputs().size());
        assertEquals(4, fixture.delegate.failureRecoveredInputs().get(0).getCount());
        assertEquals(3, fixture.machine.stack.getCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialPlacementFailureReturnsHeldAndPlacedInputsThroughOneReceipt(boolean apparatus)
            throws Exception {
        Fixture fixture = new Fixture(apparatus);
        fixture.machine.positions = List.of(BlockPos.ZERO.east(), BlockPos.ZERO.west());
        set(fixture.delegate, "pedestalLayout",
                ArsPedestalLayout.captureAllowEmpty(fixture.level, BlockPos.ZERO));
        List<ItemStack> materials = List.of(
                tagged("central", 3), tagged("pedestal", 4), tagged("unplaced", 2));

        assertFalse(fixture.delegate.startPreparedOperation(
                fixture.player, materials, fixture.ledger));
        fixture.delegate.onBatchFailed(null, "start rejected");

        assertEquals(List.of(3, 4, 2), fixture.delegate.failureRecoveredInputs()
                .stream().map(ItemStack::getCount).toList());
        assertTrue(fixture.machine.stack.isEmpty());
        assertTrue(fixture.pedestal.stack.isEmpty());
        verifyNoInteractions(fixture.endpoint);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedBusyStartDoesNotClearManualInputsOrRefundTheSharedLedger(boolean apparatus)
            throws Exception {
        Fixture fixture = new Fixture(apparatus);
        fixture.machine.stack = new ItemStack(Items.GOLD_INGOT);
        fixture.machine.isCrafting = true;

        assertFalse(fixture.start());
        fixture.delegate.onBatchFailed(null, "busy");

        assertEquals(List.of(3, 4), fixture.delegate.failureRecoveredInputs()
                .stream().map(ItemStack::getCount).toList());
        assertEquals(Items.GOLD_INGOT, fixture.machine.stack.getItem());
        assertTrue(fixture.machine.isCrafting);
        verifyNoInteractions(fixture.endpoint);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableWorldDoesNotInventRefundsForPlacedInputs(boolean apparatus) throws Exception {
        Fixture fixture = new Fixture(apparatus);
        assertTrue(fixture.start());
        fixture.delegate.setMachineServer(mock(MinecraftServer.class));

        fixture.delegate.onBatchFailed(null, "dimension unavailable");

        assertTrue(fixture.delegate.failureRecoveredInputs().isEmpty());
        assertEquals(3, fixture.machine.stack.getCount());
        assertEquals(4, fixture.pedestal.stack.getCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortDoesNotStopAnotherCraftInTheSameBlockEntity(boolean apparatus) throws Exception {
        Fixture fixture = new Fixture(apparatus);
        assertTrue(fixture.start());
        fixture.machine.stack = tagged("another craft", 3);
        fixture.machine.isCrafting = true;
        fixture.machine.counter = 80;

        fixture.delegate.onBatchFailed(null, "external replacement");

        assertTrue(fixture.machine.isCrafting);
        assertEquals(80, fixture.machine.counter);
        assertEquals("another craft", fixture.machine.stack.getTag().getString("owner"));
        assertEquals(1, fixture.delegate.failureRecoveredInputs().size());
    }

    @Test
    void busyRepeatDoesNotRefundASecondCopyOfReusableCatalysts() throws Exception {
        Fixture fixture = new Fixture(false);
        assertTrue(fixture.start());
        fixture.machine.stack = new ItemStack(Items.NETHERITE_INGOT);
        assertEquals(Items.NETHERITE_INGOT,
                fixture.delegate.collectResult(fixture.player).getItem());
        fixture.pedestal.stack.shrink(2);
        fixture.machine.stack = new ItemStack(Items.GOLD_INGOT);

        assertFalse(fixture.start());
        fixture.delegate.onBatchFailed(null, "repeat busy");

        assertEquals(List.of(2, 3), fixture.delegate.failureRecoveredInputs()
                .stream().map(ItemStack::getCount).toList());
        assertEquals(Items.GOLD_INGOT, fixture.machine.stack.getItem());
        assertTrue(fixture.pedestal.stack.isEmpty());
        verifyNoInteractions(fixture.endpoint);
    }

    public static final class Machine extends BlockEntity implements Container {
        public ItemStack stack = ItemStack.EMPTY;
        public boolean isCrafting;
        public int counter;
        private final boolean hidesWhileCrafting;
        List<BlockPos> positions = List.of(BlockPos.ZERO.east());

        Machine(BlockPos pos, boolean hidesWhileCrafting) {
            super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState());
            this.hidesWhileCrafting = hidesWhileCrafting;
        }

        public List<BlockPos> pedestalList() { return positions; }
        public Object getRecipeNow() { return stack.isEmpty() ? null : this; }
        @Override public int getContainerSize() { return 1; }
        @Override public boolean isEmpty() { return stack.isEmpty(); }
        @Override public ItemStack getItem(int slot) {
            return hidesWhileCrafting && isCrafting ? ItemStack.EMPTY : stack;
        }
        @Override public ItemStack removeItem(int slot, int amount) {
            return hidesWhileCrafting && isCrafting ? ItemStack.EMPTY : stack.split(amount);
        }
        @Override public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot, 64); }
        @Override public void setItem(int slot, ItemStack value) {
            stack = value;
            if (hidesWhileCrafting && !value.isEmpty()) isCrafting = true;
        }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public void clearContent() { stack = ItemStack.EMPTY; }
        @Override public void setChanged() {}
    }

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        final ExtractionLedger ledger = new ExtractionLedger();
        final AbstractBatchDelegate delegate;
        final Machine machine;
        final Machine pedestal;

        Fixture(boolean apparatus) throws Exception {
            MinecraftServer server = mock(MinecraftServer.class);
            Field serverField = ServerPlayer.class.getField("server");
            serverField.setAccessible(true);
            serverField.set(player, server);
            when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
            when(player.serverLevel()).thenReturn(level);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.hasChunkAt(any())).thenReturn(true);
            machine = holder(BlockPos.ZERO, apparatus);
            pedestal = holder(BlockPos.ZERO.east(), false);
            delegate = apparatus ? new ArsApparatusBatchDelegate() : new ArsImbuementBatchDelegate();
            delegate.setMachineDim(Level.OVERWORLD.location());
            delegate.setMachineServer(server);
            ledger.setStorageEndpoint(endpoint);
            set(delegate, "player", player);
            set(delegate, "dimension", Level.OVERWORLD);
            set(delegate, "machinePos", BlockPos.ZERO);
            set(delegate, "expectedOutput", new ItemStack(Items.NETHERITE_INGOT));
            set(delegate, "pedestalLayout",
                    ArsPedestalLayout.captureAllowEmpty(level, BlockPos.ZERO));
        }

        Machine holder(BlockPos pos, boolean apparatus) {
            Machine holder = new Machine(pos, apparatus);
            holder.setLevel(level);
            when(level.getBlockEntity(pos)).thenReturn(holder);
            return holder;
        }

        boolean start() {
            return delegate.startPreparedOperation(player,
                    List.of(tagged("central", 3), tagged("pedestal", 4)), ledger);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ItemStack tagged(String owner, int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putString("owner", owner);
        return stack;
    }
}
