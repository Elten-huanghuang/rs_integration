package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.compat.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FluidContainerExecutionTest extends BootstrapTest {
    private MockedStatic<FluidUtil> fluidUtil;
    private MockedStatic<HistoryStagesCompat> stages;

    @BeforeAll static void initializeModLogger() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @BeforeEach void supplyBucketCapabilities() {
        stages = mockStatic(HistoryStagesCompat.class);
        // 单元测试没有 Forge 字节码转换器，能力查询边界使用真实桶实现。
        fluidUtil = mockStatic(FluidUtil.class, CALLS_REAL_METHODS);
        fluidUtil.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            var handler = FluidContainerBucketTestFixtures.handler(stack);
            return handler == null ? LazyOptional.empty() : LazyOptional.of(() -> handler);
        });
    }

    @AfterEach void releaseBucketCapabilities() {
        fluidUtil.close();
        stages.close();
    }

    @Test void capabilityValidationAcceptsRealVanillaBucketHandlers() {
        assertTrue(FluidContainerCatalog.isValid(FluidContainerCatalogTest.waterFill()));
        assertTrue(FluidContainerCatalog.isValid(FluidContainerCatalogTest.waterDrain()));
    }

    @Test void batchFillConsumesExactCountsAndCanOnlyBeCollectedOnce() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterFill();
        ServerPlayer player = player();
        GenericBatchDelegate delegate = delegate(recipe, 3);
        assertTrue(delegate.tryStartWithMaterials(player,
                List.of(new ItemStack(Items.BUCKET, 3), FluidContainerCatalogTest.token(new FluidStack(Fluids.WATER, 3000))),
                new ExtractionLedger()));
        var results = delegate.collectAllResults(player);
        assertEquals(1, results.size());
        assertTrue(results.get(0).is(Items.WATER_BUCKET));
        assertEquals(3, results.get(0).getCount());
        assertTrue(delegate.collectAllResults(player).isEmpty());
        assertTrue(delegate.collectResult(player).isEmpty());
    }

    @Test void batchDrainReturnsFluidAndOneEmptyContainerPerInput() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterDrain();
        ServerPlayer player = player();
        GenericBatchDelegate delegate = delegate(recipe, 2);
        assertTrue(delegate.tryStartWithMaterials(player, List.of(new ItemStack(Items.WATER_BUCKET, 2)), new ExtractionLedger()));
        var results = delegate.collectAllResults(player);
        assertEquals(2, results.size());
        assertEquals(2000, InkFluidSupport.fluid(results.get(0)).getAmount());
        assertTrue(results.get(1).is(Items.BUCKET));
        assertEquals(2, results.get(1).getCount());
        assertTrue(delegate.collectAllResults(player).isEmpty());
    }

    @Test void thirdPartyBucketConversionExecutesWithItsOwnEmptyBucket() throws Exception {
        var buckets = FluidContainerBucketTestFixtures.buckets("execution_wood");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(buckets.filled())), List.of(),
                FluidContainerBucketTestFixtures::handler, FluidContainerCatalogTest::token);
        var fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
        var drain = recipes.stream().filter(r -> !r.filling()).findFirst().orElseThrow();
        assertTrue(FluidContainerCatalog.isValid(fill));
        assertTrue(FluidContainerCatalog.isValid(drain));
        ServerPlayer player = player();
        GenericBatchDelegate fillDelegate = delegate(fill, 2);
        assertTrue(fillDelegate.tryStartWithMaterials(player,
                List.of(new ItemStack(buckets.empty(), 2),
                        FluidContainerCatalogTest.token(new FluidStack(Fluids.WATER, 2000))),
                new ExtractionLedger()));
        var filled = fillDelegate.collectAllResults(player);
        assertEquals(1, filled.size());
        assertTrue(filled.get(0).is(buckets.filled()));
        assertEquals(2, filled.get(0).getCount());

        GenericBatchDelegate drainDelegate = delegate(drain, 2);
        assertTrue(drainDelegate.tryStartWithMaterials(player, filled, new ExtractionLedger()));
        var drained = drainDelegate.collectAllResults(player);
        assertEquals(2, drained.size());
        assertEquals(2000, InkFluidSupport.fluid(drained.get(0)).getAmount());
        assertTrue(drained.get(1).is(buckets.empty()));
        assertEquals(2, drained.get(1).getCount());
        assertTrue(drainDelegate.collectAllResults(player).isEmpty());
    }

    @Test void bucketSubclassWithoutCapabilitiesCanFillAndDrainInBatch() throws Exception {
        var buckets = FluidContainerBucketTestFixtures.subclassBuckets("execution_poisonwater");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(buckets.filled())), List.of(),
                FluidContainerBucketTestFixtures::handler, FluidContainerCatalogTest::token);
        var fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
        var drain = recipes.stream().filter(r -> !r.filling()).findFirst().orElseThrow();
        assertTrue(FluidContainerCatalog.isValid(fill));
        assertTrue(FluidContainerCatalog.isValid(drain));
        ServerPlayer player = player();
        GenericBatchDelegate fillDelegate = delegate(fill, 2);
        assertTrue(fillDelegate.tryStartWithMaterials(player,
                List.of(new ItemStack(Items.BUCKET, 2), FluidContainerCatalogTest.token(
                        new FluidStack(((BucketItem) buckets.filled()).getFluid(), 2000))), new ExtractionLedger()));
        var filled = fillDelegate.collectAllResults(player);
        assertEquals(1, filled.size());
        assertTrue(filled.get(0).is(buckets.filled()));
        assertEquals(2, filled.get(0).getCount());

        GenericBatchDelegate drainDelegate = delegate(drain, 2);
        assertTrue(drainDelegate.tryStartWithMaterials(player, filled, new ExtractionLedger()));
        var drained = drainDelegate.collectAllResults(player);
        assertEquals(2, drained.size());
        assertEquals(2000, InkFluidSupport.fluid(drained.get(0)).getAmount());
        assertEquals(((BucketItem) buckets.filled()).getFluid(), InkFluidSupport.fluid(drained.get(0)).getFluid());
        assertTrue(drained.get(1).is(Items.BUCKET));
        assertEquals(2, drained.get(1).getCount());
        assertTrue(drainDelegate.collectAllResults(player).isEmpty());
    }

    @Test void singleDrainExtractsTheFilledBucketAndReturnsItsEmptyContainerOnce() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterDrain();
        ServerPlayer player = player();
        Field server = ServerPlayer.class.getField("server");
        server.setAccessible(true);
        server.set(player, mock(MinecraftServer.class));
        StorageBackendId backend = new StorageBackendId("test");
        StorageSession session = mock(StorageSession.class, CALLS_REAL_METHODS);
        when(session.reference()).thenReturn(new StorageReference(backend, "single-drain"));
        ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
        when(session.snapshotItems(player)).thenReturn(StorageSnapshotResult.success(new StorageSnapshot(backend,
                List.of(new StoredItem(StorageItemKey.fromItemStack(backend, bucket), 1)))));
        when(session.extractMatching(player, recipe.specs().get(0).ingredient(), 1, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 1, List.of(bucket)));
        GenericBatchDelegate delegate = delegate(recipe, 1);
        delegate.setStorageEndpoint(() -> session);
        assertTrue(delegate.tryStartSingleCraft(player));
        var results = delegate.collectAllResults(player);
        assertEquals(2, results.size());
        assertEquals(1000, InkFluidSupport.fluid(results.get(0)).getAmount());
        assertTrue(results.get(1).is(Items.BUCKET));
        assertEquals(1, results.get(1).getCount());
        assertTrue(delegate.collectAllResults(player).isEmpty());
        verify(session, times(1)).extractMatching(player, recipe.specs().get(0).ingredient(), 1, false);
    }

    @Test void mismatchedFluidNbtAndBatchCountsProduceNothing() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterFill();
        ItemStack tagged = FluidContainerCatalogTest.token(new FluidStack(Fluids.WATER, 1000));
        tagged.getOrCreateTag().putString("wrong", "variant");
        for (List<ItemStack> materials : List.of(
                List.of(new ItemStack(Items.BUCKET), FluidContainerCatalogTest.token(new FluidStack(Fluids.LAVA, 1000))),
                List.of(new ItemStack(Items.BUCKET), tagged),
                List.of(new ItemStack(Items.BUCKET), FluidContainerCatalogTest.token(new FluidStack(Fluids.WATER, 999))),
                List.of(new ItemStack(Items.BUCKET, 2), FluidContainerCatalogTest.token(new FluidStack(Fluids.WATER, 1000))))) {
            GenericBatchDelegate delegate = delegate(recipe, 1);
            ServerPlayer player = player();
            assertFalse(delegate.tryStartWithMaterials(player, materials, new ExtractionLedger()));
            assertTrue(delegate.collectAllResults(player).isEmpty());
        }
    }

    @Test void invalidatedCapabilityCannotExecuteAnOldDefinition() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterFill();
        GenericBatchDelegate delegate = delegate(recipe, 1);
        try (MockedStatic<FluidContainerCatalog> catalog = mockStatic(FluidContainerCatalog.class, CALLS_REAL_METHODS)) {
            catalog.when(() -> FluidContainerCatalog.isValid(recipe)).thenReturn(false);
            assertFalse(delegate.tryStartWithMaterials(player(), List.of(new ItemStack(Items.BUCKET),
                    FluidContainerCatalogTest.token(new FluidStack(Fluids.WATER, 1000))), new ExtractionLedger()));
        }
    }

    @Test void syntheticIdCanBeResolvedByThePacketPath() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterFill();
        FluidContainerCatalogTest.graph(List.of(recipe));
        ServerLevel level = mock(ServerLevel.class);
        RecipeManager manager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(manager);
        when(manager.byKey(recipe.getId())).thenReturn(Optional.empty());
        var resolve = GenericCraftPacket.class.getDeclaredMethod("resolveRecipe", ServerLevel.class, recipe.getId().getClass());
        resolve.setAccessible(true);
        assertSame(recipe, resolve.invoke(null, level, recipe.getId()));
    }

    @Test void delegateInitializationResolvesSyntheticIdWithoutAMachine() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterFill();
        FluidContainerCatalogTest.graph(List.of(recipe));
        ServerPlayer player = player();
        ServerLevel level = player.serverLevel();
        when(level.dimension()).thenReturn(ServerLevel.OVERWORLD);
        RecipeManager manager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(manager);
        when(manager.byKey(recipe.getId())).thenReturn(Optional.empty());
        assertTrue(new GenericBatchDelegate().validateAndInit(player, recipe.getId(), null, BlockPos.ZERO));
    }

    @Test void cancellingSharedConversionLeavesTheLedgerAsSoleRefundOwner() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterDrain();
        ServerPlayer player = player();
        GenericBatchDelegate delegate = delegate(recipe, 1);
        ExtractionLedger ledger = new ExtractionLedger();
        delegate.useSharedLedger(ledger);
        StorageSession session = mock(StorageSession.class);
        delegate.setStorageEndpoint(() -> session);
        assertTrue(delegate.tryStartWithMaterials(player, List.of(new ItemStack(Items.WATER_BUCKET)), ledger));
        delegate.onBatchFailed(player, "cancelled");
        // 逻辑转换没有物理产物；终止后由链退款，代理不能私自插入产物。
        verifyNoInteractions(session);
        assertEquals(ExtractionLedger.State.IDLE, ledger.state());
    }

    @Test void partialFluidExtractionRefundsTheBucketAndActualFluidOnce() throws Exception {
        FluidContainerRecipe recipe = FluidContainerCatalogTest.waterFill();
        ServerPlayer player = player();
        Field server = ServerPlayer.class.getField("server");
        server.setAccessible(true);
        server.set(player, mock(MinecraftServer.class));
        StorageBackendId backend = new StorageBackendId("test");
        StorageSession session = mock(StorageSession.class, CALLS_REAL_METHODS);
        when(session.reference()).thenReturn(new StorageReference(backend, "fluid-container"));
        ItemStack bucket = new ItemStack(Items.BUCKET);
        ItemStack water = FluidContainerCatalogTest.token(new FluidStack(Fluids.WATER, 1000));
        when(session.snapshotItems(player)).thenReturn(StorageSnapshotResult.success(new StorageSnapshot(backend,
                List.of(new StoredItem(StorageItemKey.fromItemStack(backend, bucket), 1),
                        new StoredItem(StorageItemKey.fromItemStack(backend, water), 1000)))));
        var bucketSpec = recipe.specs().get(0);
        var waterSpec = recipe.specs().get(1);
        when(session.extractMatching(player, bucketSpec.ingredient(), 1, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 1, List.of(bucket)));
        when(session.extractMatching(player, waterSpec.ingredient(), 1000, false)).thenReturn(
                StorageOperationResult.extracted(StorageOperationMode.PERFORM, 1000, List.of(water.copyWithCount(600))));
        List<ItemStack> refunded = new ArrayList<>();
        doAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            refunded.add(stack.copy());
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
        }).when(session).insert(eq(player), any(ItemStack.class), eq(false));
        ExtractionLedger ledger = new ExtractionLedger();
        assertEquals(1, ledger.reserveFromEndpoint(bucketSpec.ingredient(), 1, () -> session, player).getCount());
        assertEquals(1000, ledger.reserveFromEndpoint(waterSpec.ingredient(), 1000, () -> session, player).getCount());
        assertFalse(ledger.commit(null, player));
        ledger.refundCommitted(null, player);
        ledger.refundCommitted(null, player);
        assertEquals(2, refunded.size());
        assertTrue(refunded.get(0).is(Items.BUCKET));
        assertEquals(600, InkFluidSupport.fluid(refunded.get(1)).getAmount());
    }

    private static ServerPlayer player() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.serverLevel()).thenReturn(level);
        when(player.level()).thenReturn(level);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        return player;
    }

    private static GenericBatchDelegate delegate(FluidContainerRecipe recipe, int executions) throws Exception {
        GenericBatchDelegate delegate = new GenericBatchDelegate();
        field(delegate, "recipe", recipe);
        field(delegate, "pendingResult", ItemStack.EMPTY);
        field(delegate, "myPos", BlockPos.ZERO);
        delegate.prepareGraphBatch(executions);
        StorageSession session = mock(StorageSession.class);
        delegate.setStorageEndpoint(() -> session);
        return delegate;
    }
    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
