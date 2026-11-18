package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.crafting.batch.OperationStartContext.ReservedMaterial;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mixin.minecraft.AbstractFurnaceAccessor;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.wrapper.InvWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实炉库存和计时访问器验证天境炉的收货协议，不启动天境注册表或服务器。 */
class AetherOutputReceptionTest extends BootstrapTest {
    private static final BlockPos POS = new BlockPos(4, 64, 8);
    private AetherFurnaceBatchDelegate delegate;
    private TestFreezer furnace;
    private ServerLevel level;
    private ServerPlayer player;
    private MockedStatic<ForcedChunkTicketManager> tickets;

    @BeforeAll
    static void configure() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @BeforeEach
    void createFurnace() throws Exception {
        tickets = mockStatic(ForcedChunkTicketManager.class);
        delegate = new AetherFurnaceBatchDelegate();
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        when(player.serverLevel()).thenReturn(level);
        furnace = new TestFreezer();
        when(level.hasChunkAt(POS)).thenReturn(true);
        when(level.getBlockEntity(POS)).thenReturn(furnace);
        when(level.getBlockState(POS)).thenReturn(Blocks.FURNACE.defaultBlockState());
        when(level.getRecipeManager()).thenReturn(new RecipeManager());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        furnace.setLevel(level);
        furnace.setItem(1, new ItemStack(Items.COAL, 8));
        set("myLevel", level);
        set("myPos", POS);
        set("recipe", recipe());
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        when(endpoint.extractExact(eq(player), any(ItemStack.class), anyLong(), eq(false)))
                .thenAnswer(call -> StorageOperationResult.extracted(
                        StorageOperationMode.PERFORM, call.getArgument(2, Long.class), List.of()));
        delegate.setStorageEndpoint(endpoint);
    }

    @AfterEach
    void closeTickets() {
        if (tickets != null) tickets.close();
    }

    @Test
    void collectedOutputWaitsForInputAndTimerAndDistinctIdleTicks() {
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(delegate.supportsOutputReception());
            assertTrue(start(3));
            assertEquals(3, session.current().remaining("aether:furnace:output"));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
            assertEquals(CraftPhase.WORKING, observe(1));
            assertEquals(3, session.current().received("aether:furnace:output"));
            assertTrue(furnace.getItem(2).isEmpty());
            furnace.setItem(0, ItemStack.EMPTY);
            furnace.progress = 20;
            assertEquals(CraftPhase.WORKING, observe(2));
            furnace.progress = 0;
            assertEquals(CraftPhase.WORKING, observe(3));
            assertEquals(CraftPhase.WORKING, observe(3));
            assertEquals(CraftPhase.DONE, observe(4));
            assertTrue(delegate.collectStructuredResults(player).isEmpty());
            assertEquals(3, delegate.drainReceivedOutputs().get(0).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    @Test
    void partialCollectionAndIncrementalPublicationCannotReturnTheSameResourceTwice() {
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(3));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT));
            assertEquals(CraftPhase.WORKING, observe(1));
            assertEquals(1, delegate.drainReceivedOutputs().get(0).getCount());
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
            assertEquals(CraftPhase.WORKING, observe(2));
            assertTrue(furnace.getItem(2).isEmpty());
            assertEquals(2, delegate.drainReceivedOutputs().get(0).getCount());
            furnace.setItem(0, ItemStack.EMPTY);
            assertEquals(CraftPhase.WORKING, observe(3));
            assertEquals(CraftPhase.DONE, observe(4));
            assertTrue(delegate.collectAllResults(player).isEmpty());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    @Test
    void emptyInputWithoutReceivedOutputDoesNotFinish() {
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(2));
            furnace.setItem(0, ItemStack.EMPTY);
            assertEquals(CraftPhase.WORKING, observe(1));
            assertEquals(CraftPhase.WORKING, observe(2));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
            assertEquals(CraftPhase.DONE, observe(3));
            assertEquals(2, delegate.drainReceivedOutputs().get(0).getCount());
        }
    }

    @Test
    void actualOutputBeyondTheDeclarationIsStillHeld() {
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(2));
            furnace.setItem(0, ItemStack.EMPTY);
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 4));
            assertEquals(CraftPhase.WORKING, observe(1));
            assertEquals(CraftPhase.DONE, observe(2));
            assertEquals(4, delegate.drainReceivedOutputs().get(0).getCount());
        }
    }

    @Test
    void failedStartClosesReceptionButKeepsSynchronousDelivery() {
        furnace.setItem(1, new ItemStack(Items.COAL));
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            furnace.onInputInserted = () -> {
                assertFalse(session.current().complete());
                // 模拟受控收取在投料回调中已经移交实物，后续启动失败仍须保留。
                assertEquals(1, session.current().collect(TaskOutputReception.resource(
                        new ItemStack(Items.IRON_INGOT)), 1));
            };
            assertFalse(start(11));
            assertTrue(session.closed());
            assertTrue(furnace.getItem(0).isEmpty());
            assertEquals(11, delegate.failureRecoveredInputs().get(0).getCount());
            assertEquals(1, delegate.drainReceivedOutputs().get(0).getCount());
        }
    }

    @Test
    void cancellationKeepsReceivedOutputAndRecoversOnlyRemainingInput() {
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(3));
            furnace.setItem(0, new ItemStack(Items.IRON_ORE, 2));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT));
            assertEquals(CraftPhase.WORKING, observe(1));
            delegate.onBatchFailed(player, "cancelled");
            assertTrue(session.closed());
            assertTrue(furnace.getItem(0).isEmpty());
            assertEquals(2, delegate.failureRecoveredInputs().get(0).getCount());
            assertEquals(1, delegate.drainReceivedOutputs().get(0).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
        }
    }

    @Test
    void dynamicAssemblyAndIncubatorDoNotOptIn() throws Exception {
        assertTrue(AetherFurnaceBatchDelegate.inheritsFixedCookingOutput(recipe()));
        SmeltingRecipe dynamic = new SmeltingRecipe(new ResourceLocation("test", "dynamic"), "",
                CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE),
                new ItemStack(Items.IRON_INGOT), 0, 200) {
            @Override public ItemStack assemble(Container inventory, RegistryAccess access) {
                return inventory.getItem(0).copy();
            }
        };
        assertFalse(AetherFurnaceBatchDelegate.inheritsFixedCookingOutput(dynamic));
        set("recipe", dynamic);
        assertFalse(delegate.supportsOutputReception());
        set("recipe", recipe());
        set("isIncubator", true);
        assertFalse(delegate.supportsOutputReception());
    }

    @Test
    void dynamicResultAndMissingTimerDoNotOptIn() throws Exception {
        SmeltingRecipe dynamic = new SmeltingRecipe(new ResourceLocation("test", "dynamic_result"), "",
                CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE),
                new ItemStack(Items.IRON_INGOT), 0, 200) {
            @Override public ItemStack getResultItem(RegistryAccess access) {
                return new ItemStack(Items.GOLD_INGOT);
            }
        };
        assertFalse(AetherFurnaceBatchDelegate.inheritsFixedCookingOutput(dynamic));
        when(level.getBlockEntity(POS)).thenReturn(new FurnaceBlockEntity(POS,
                Blocks.FURNACE.defaultBlockState()));
        assertFalse(delegate.supportsOutputReception());
    }

    @Test
    void sameItemConversionAndContainerRemaindersDoNotOptIn() throws Exception {
        SmeltingRecipe sameItem = new SmeltingRecipe(new ResourceLocation("test", "same_item"), "",
                CookingBookCategory.MISC, Ingredient.of(Items.IRON_INGOT),
                new ItemStack(Items.IRON_INGOT), 0, 200);
        set("recipe", sameItem);
        assertFalse(delegate.supportsOutputReception());
        SmeltingRecipe withContainer = new SmeltingRecipe(new ResourceLocation("test", "container"), "",
                CookingBookCategory.MISC, Ingredient.of(Items.LAVA_BUCKET),
                new ItemStack(Items.IRON_INGOT), 0, 200);
        set("recipe", withContainer);
        assertFalse(delegate.supportsOutputReception());
    }

    @Test
    void outputsMarkedToCopyInputNbtAreNotTreatedAsFixedIdentity() {
        ItemStack output = mock(ItemStack.class);
        when(output.is(any(TagKey.class))).thenReturn(true);
        assertFalse(AetherFurnaceBatchDelegate.fixedFurnaceResources(recipe(), output));
    }

    @Test
    void partialInsertionIsRemovedAndItsRemainderIsIncludedInRecovery() {
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            furnace.partialInput = true;
            assertFalse(start(3));
            assertTrue(session.closed());
            assertTrue(furnace.getItem(0).isEmpty());
            assertEquals(3, delegate.failureRecoveredInputs().stream().mapToInt(ItemStack::getCount).sum());
        }
    }

    @Test
    void disabledInputBufferDoesNotDisableSingleOperationReception() {
        boolean old = RSIntegrationConfig.ENABLE_AETHER_FURNACE_INPUT_BUFFER.get();
        try {
            RSIntegrationConfig.ENABLE_AETHER_FURNACE_INPUT_BUFFER.set(false);
            assertFalse(delegate.supportsInputBuffer());
            assertTrue(delegate.supportsOutputReception());
            assertEquals(1, delegate.outputContract().ports().size());
        } finally {
            RSIntegrationConfig.ENABLE_AETHER_FURNACE_INPUT_BUFFER.set(old);
        }
    }

    @Test
    void replacedMachineAndWrongNbtDoNotContributeToReception() {
        try (var session = TaskOutputReception.open()) {
            delegate.setOutputReception(session);
            assertTrue(start(1));
            ItemStack tagged = new ItemStack(Items.IRON_INGOT);
            tagged.getOrCreateTag().putString("owner", "other");
            furnace.setItem(2, tagged);
            assertEquals(CraftPhase.FAILED, observe(1));
            assertEquals(1, furnace.getItem(2).getCount());
            assertTrue(delegate.drainReceivedOutputs().isEmpty());
            assertEquals(CraftPhase.FAILED,
                    delegate.observeMachineCraft(level, new TestFreezer()).phase());
        }
    }

    @Test
    void legacyPreparedEntryStillReturnsTheSlotStack() {
        delegate.prepareGraphBatch(2);
        assertTrue(delegate.startPreparedOperation(player,
                List.of(new ItemStack(Items.IRON_ORE, 2)), mock(ExtractionLedger.class)));
        furnace.setItem(0, ItemStack.EMPTY);
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
        assertEquals(2, delegate.collectResult(player).getCount());
        assertTrue(delegate.drainReceivedOutputs().isEmpty());
    }

    private boolean start(int operations) {
        delegate.prepareGraphBatch(operations);
        return delegate.startOperation(OperationStartContext.chainReserved(player,
                mock(ExtractionLedger.class), delegate.materialPlan(),
                List.of(new ReservedMaterial("slot:0", new ItemStack(Items.IRON_ORE, operations))), null));
    }

    private CraftPhase observe(long tick) {
        when(level.getGameTime()).thenReturn(tick);
        return delegate.observeMachineCraft(level, furnace).phase();
    }

    private void set(String name, Object value) throws Exception {
        Field field = AetherFurnaceBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }

    private static Recipe<?> recipe() {
        return new SmeltingRecipe(new ResourceLocation("test", "aether_output"), "",
                CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE),
                new ItemStack(Items.IRON_INGOT), 0, 200);
    }

    private static final class TestFreezer extends FurnaceBlockEntity implements AbstractFurnaceAccessor {
        private int progress;
        private Runnable onInputInserted;
        private boolean partialInput;
        private final LazyOptional<InvWrapper> inventory = LazyOptional.of(() -> new InvWrapper(this) {
            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (partialInput && !simulate && slot == 0 && stack.getCount() > 1) {
                    ItemStack rejected = super.insertItem(slot, stack.copyWithCount(1), false);
                    return stack.copyWithCount(stack.getCount() - 1 + rejected.getCount());
                }
                return super.insertItem(slot, stack, simulate);
            }
        });

        private TestFreezer() {
            super(POS, Blocks.FURNACE.defaultBlockState());
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            return capability == ForgeCapabilities.ITEM_HANDLER ? inventory.cast()
                    : super.getCapability(capability, side);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            super.setItem(slot, stack);
            if (slot == 0 && !stack.isEmpty() && onInputInserted != null) onInputInserted.run();
        }

        @Override public int rsi$getCookingProgress() { return progress; }
        @Override public int rsi$getCookingTotalTime() { return 200; }
        @Override public int rsi$callGetBurnDuration(ItemStack stack) { return stack.is(Items.COAL) ? 400 : 0; }
    }
}
