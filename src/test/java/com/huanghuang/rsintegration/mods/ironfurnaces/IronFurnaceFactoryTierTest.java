package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class IronFurnaceFactoryTierTest extends BootstrapTest {
    @BeforeAll
    static void initializeModContext() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @BeforeEach
    void loadConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @AfterEach
    void releaseLeases() {
        IronFurnacesBatchDelegate.clearFactoryLeases();
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
    }

    @ParameterizedTest
    @CsvSource({"0, 2, 9, 10", "1, 4, 8, 11", "2, 6, 7, 12"})
    void dispatchUsesOnlyEnabledSlotsAndQueuesOverflow(int tier, int lanes, int firstInput, int lastInput)
            throws ReflectiveOperationException {
        Fixture fixture = fixture(tier);
        int capacity = profile(fixture.delegate).physicalCapacity();
        assertEquals(lanes * profile(fixture.delegate).laneCapacity(), capacity);
        set(fixture.delegate, "queuedMaterial", new ItemStack(Items.IRON_ORE));
        set(fixture.delegate, "queuedOperations", capacity + 1);

        assertTrue(startNextBatch(fixture.delegate));

        assertEquals(1, get(fixture.delegate, "queuedOperations"));
        assertEquals(capacity, get(fixture.delegate, "activePhysicalOperations"));
        assertEquals(lanes, fixture.written.size());
        assertEquals(capacity, fixture.written.stream().mapToInt(slot -> fixture.items[slot].getCount()).sum());
        assertTrue(fixture.written.stream().allMatch(slot -> slot >= firstInput && slot <= lastInput));
        assertEquals(lanes, get(fixture.delegate, "plannedFactoryLanes"));
        for (int slot = 7; slot <= 12; slot++) {
            if (slot < firstInput || slot > lastInput) assertTrue(fixture.items[slot].isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 9", "1, 8", "2, 7"})
    void singleOperationReservesAnActiveSlot(int tier, int expectedInput) throws ReflectiveOperationException {
        Fixture fixture = fixture(tier);
        set(fixture.delegate, "queuedMaterial", new ItemStack(Items.IRON_ORE));
        set(fixture.delegate, "queuedOperations", 1);

        assertTrue(startNextBatch(fixture.delegate));

        assertEquals(List.of(expectedInput), fixture.written);
        assertEquals(1, fixture.items[expectedInput].getCount());
        assertEquals(0, get(fixture.delegate, "queuedOperations"));
    }

    @ParameterizedTest
    @CsvSource({"0, 2", "1, 4", "2, 6"})
    void flatAndParallelBatchPlanningUsesActualTierCapacity(int tier, int lanes)
            throws ReflectiveOperationException {
        Fixture fixture = fixture(tier);
        int capacity = profile(fixture.delegate).physicalCapacity();

        fixture.delegate.prepareFlatBatch(capacity + 1);

        assertEquals(lanes, get(fixture.delegate, "plannedFactoryLanes"));
        assertEquals(capacity, fixture.delegate.preferredParallelBatchSize(1000, 1));
        assertEquals(capacity, fixture.delegate.flatBatchOperationLimit(1));
    }

    @ParameterizedTest
    @CsvSource({"0, 9, 10", "1, 8, 11", "2, 7, 12"})
    void occupiedActiveSlotsCannotBeReplacedWithDisabledSlots(int tier, int firstInput, int lastInput)
            throws ReflectiveOperationException {
        Fixture fixture = fixture(tier);
        for (int slot = firstInput; slot <= lastInput; slot++) {
            fixture.items[slot] = new ItemStack(Items.COBBLESTONE);
        }
        set(fixture.delegate, "queuedMaterial", new ItemStack(Items.IRON_ORE));
        set(fixture.delegate, "queuedOperations", 1);

        assertFalse(startNextBatch(fixture.delegate));

        assertTrue(fixture.written.isEmpty());
        assertEquals(1, get(fixture.delegate, "queuedOperations"));
    }

    private static Fixture fixture(int tier) throws ReflectiveOperationException {
        BlockIronFurnaceTileBase furnace = mock(BlockIronFurnaceTileBase.class);
        furnace.recipeType = RecipeType.SMELTING;
        when(furnace.getTier()).thenReturn(tier);
        when(furnace.isFactory()).thenReturn(true);
        when(furnace.getEnergy()).thenReturn(10000);
        when(furnace.getMaxStackSize()).thenReturn(64);
        ItemStack[] items = new ItemStack[19];
        Arrays.fill(items, ItemStack.EMPTY);
        List<Integer> written = new ArrayList<>();
        when(furnace.getItem(anyInt())).thenAnswer(invocation -> items[invocation.getArgument(0, Integer.class)]);
        doAnswer(invocation -> {
            int slot = invocation.getArgument(0);
            items[slot] = invocation.getArgument(1);
            written.add(slot);
            return null;
        }).when(furnace).setItem(anyInt(), any());

        ServerLevel level = mock(ServerLevel.class);
        when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(furnace);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        SmeltingRecipe smelting = mock(SmeltingRecipe.class);
        when(smelting.getResultItem(RegistryAccess.EMPTY)).thenReturn(new ItemStack(Items.IRON_INGOT));
        when(smelting.getIngredients()).thenReturn(NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_ORE)));

        IronFurnacesBatchDelegate delegate = new IronFurnacesBatchDelegate();
        set(delegate, "furnace", furnace);
        set(delegate, "factoryMode", true);
        set(delegate, "level", level);
        set(delegate, "pos", BlockPos.ZERO);
        set(delegate, "recipe", smelting);
        set(delegate, "factoryLeaseKey", "test:" + tier);
        return new Fixture(delegate, items, written);
    }

    private static IronFurnaceBatchProfile profile(IronFurnacesBatchDelegate delegate)
            throws ReflectiveOperationException {
        return (IronFurnaceBatchProfile) invoke(delegate, "batchProfile");
    }

    private static boolean startNextBatch(IronFurnacesBatchDelegate delegate)
            throws ReflectiveOperationException {
        return (boolean) invoke(delegate, "startNextPhysicalBatch");
    }

    private static Object invoke(IronFurnacesBatchDelegate delegate, String name)
            throws ReflectiveOperationException {
        Method method = IronFurnacesBatchDelegate.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(delegate);
    }

    private static void set(IronFurnacesBatchDelegate delegate, String name, Object value)
            throws ReflectiveOperationException {
        Field field = IronFurnacesBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }

    private static Object get(IronFurnacesBatchDelegate delegate, String name)
            throws ReflectiveOperationException {
        Field field = IronFurnacesBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(delegate);
    }

    private record Fixture(IronFurnacesBatchDelegate delegate, ItemStack[] items, List<Integer> written) {}
}
