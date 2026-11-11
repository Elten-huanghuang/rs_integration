package com.huanghuang.rsintegration.mods.vanilla;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过实际缓冲批次启动入口验证燃料数量，防止把快照展示栈当成库存。 */
class VanillaMachineFuelTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("test");
    private static final BlockPos POS = new BlockPos(-1, -60, 1);

    private VanillaMachineBatchDelegate delegate;
    private FurnaceBlockEntity furnace;
    private ServerPlayer player;
    private CraftStorageEndpoint endpoint;
    private MockedStatic<ForgeHooks> fuels;

    @BeforeAll
    static void configure() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @BeforeEach
    void createFurnace() throws Exception {
        // 纯单测不启动 Forge 的燃料表注册，固定煤炭的原版燃烧时间。
        fuels = mockStatic(ForgeHooks.class);
        fuels.when(() -> ForgeHooks.getBurnTime(any(ItemStack.class), any()))
                .thenAnswer(call -> call.getArgument(0, ItemStack.class).is(Items.COAL) ? 1600 : 0);
        delegate = new VanillaMachineBatchDelegate();
        furnace = new FurnaceBlockEntity(POS, Blocks.FURNACE.defaultBlockState());
        player = mock(ServerPlayer.class);
        endpoint = mock(CraftStorageEndpoint.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getBlockState(POS)).thenReturn(Blocks.FURNACE.defaultBlockState());
        when(level.getRecipeManager()).thenReturn(new RecipeManager());
        furnace.setLevel(level);
        setField("myLevel", level);
        setField("myPos", POS);
        setField("furnaceBE", furnace);
        setField("recipe", new SmeltingRecipe(
                new ResourceLocation("minecraft", "iron_ingot_from_smelting_iron_ore"),
                "", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE),
                new ItemStack(Items.IRON_INGOT), 0.7F, 200));
        Field kind = VanillaMachineBatchDelegate.class.getDeclaredField("kind");
        kind.setAccessible(true);
        kind.set(delegate, Arrays.stream(kind.getType().getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals("FURNACE"))
                .findFirst().orElseThrow());
        delegate.setStorageEndpoint(endpoint);
        when(endpoint.extractExact(eq(player), any(ItemStack.class), anyLong(), eq(false)))
                .thenAnswer(call -> StorageOperationResult.extracted(
                        StorageOperationMode.PERFORM, call.getArgument(2, Long.class),
                        List.of(call.getArgument(1, ItemStack.class)
                                .copyWithCount(Math.toIntExact(call.getArgument(2, Long.class))))));
    }

    @AfterEach
    void closeFuelHook() {
        if (fuels != null) fuels.close();
    }

    @ParameterizedTest
    @CsvSource({"11,64,2", "64,64,8", "64,9223372036854775807,8"})
    void bufferedBatchUsesActualSnapshotAmount(int operations, long stock, int expectedFuel) {
        StorageSnapshot snapshot = stock(stock);
        assertEquals(1, snapshot.items().get(0).stack().getCount());

        assertTrue(start(operations));

        assertEquals(operations, furnace.getItem(0).getCount());
        assertTrue(furnace.getItem(1).is(Items.COAL));
        assertEquals(expectedFuel, furnace.getItem(1).getCount());
        assertEquals(operations, delegate.getExpectedProduction().count());
        assertNull(delegate.lastStartFailureDetail());
        verify(endpoint).extractExact(eq(player), argThat(stack -> stack.is(Items.COAL)),
                eq((long) expectedFuel), eq(false));
        assertEquals(stock, snapshot.items().get(0).amount());
        assertEquals(1, snapshot.items().get(0).stack().getCount());
    }

    @Test
    void genuinelyInsufficientFuelRejectsBatchAndRemovesInput() {
        stock(1);

        assertFalse(start(11));

        assertTrue(furnace.getItem(0).isEmpty());
        assertTrue(furnace.getItem(1).isEmpty());
        assertNotNull(delegate.lastStartFailureDetail());
        assertTrue(delegate.lastStartFailureDetail().contains("燃料"));
        assertTrue(delegate.lastStartFailureDetail().contains("操作数=11"));
        assertTrue(delegate.lastStartFailureDetail().contains("minecraft:iron_ingot_from_smelting_iron_ore"));
        verify(endpoint, never()).extractExact(any(ServerPlayer.class), any(ItemStack.class),
                anyLong(), anyBoolean());
    }

    @Test
    void emptyStorageRejectsBatch() {
        stock(0);

        assertFalse(start(11));

        assertTrue(furnace.getItem(0).isEmpty());
        assertTrue(furnace.getItem(1).isEmpty());
    }

    @Test
    void existingFuelIsToppedUpOnlyByMissingAmount() {
        stock(64);
        furnace.setItem(1, new ItemStack(Items.COAL));

        assertTrue(start(11));

        assertEquals(2, furnace.getItem(1).getCount());
        verify(endpoint).extractExact(eq(player), argThat(stack -> stack.is(Items.COAL)),
                eq(1L), eq(false));
        verify(endpoint, never()).snapshot(any(ServerPlayer.class));
    }

    @Test
    void successfulRetryClearsPreviousFuelFailure() {
        stock(1);
        assertFalse(start(11));
        assertNotNull(delegate.lastStartFailureDetail());

        stock(64);
        assertTrue(start(11));

        assertNull(delegate.lastStartFailureDetail());
        assertEquals(11, furnace.getItem(0).getCount());
        assertEquals(2, furnace.getItem(1).getCount());
    }

    private StorageSnapshot stock(long amount) {
        StorageSnapshot snapshot = new StorageSnapshot(BACKEND, amount == 0 ? List.of()
                : List.of(new StoredItem(StorageItemKey.fromItemStack(
                        BACKEND, new ItemStack(Items.COAL)), amount)));
        when(endpoint.snapshot(player)).thenReturn(StorageSnapshotResult.success(snapshot));
        return snapshot;
    }

    private boolean start(int operations) {
        return delegate.startBufferedOperation(player, delegate.inputBufferPlan(operations),
                mock(ExtractionLedger.class));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = VanillaMachineBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }
}
