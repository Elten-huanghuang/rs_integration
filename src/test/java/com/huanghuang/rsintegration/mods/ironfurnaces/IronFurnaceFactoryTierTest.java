package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class IronFurnaceFactoryTierTest extends BootstrapTest {
    private static Method splitHook;

    @BeforeAll
    static void initializeModContext() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @BeforeAll
    static void loadSplitHook() throws Exception {
        // 将实际编译的注入回调转换为静态入口，单测直接执行它而不启动 Mixin/Forge。
        String resource = "com/huanghuang/rsintegration/mixin/ironfurnaces/BlockIronFurnaceTileBaseMixin.class";
        ClassNode mixin = new ClassNode();
        try (InputStream input = IronFurnaceFactoryTierTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new ClassReader(input).accept(mixin, 0);
        }
        var hook = mixin.methods.stream().filter(method -> method.name.equals("rsi$keepReservedFactoryLanes"))
                .findFirst().orElseThrow();
        AnnotationNode inject = hook.visibleAnnotations.stream()
                .filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("split(ZII)V"), inject.values.get(inject.values.indexOf("method") + 1));
        assertEquals(Boolean.TRUE, inject.values.get(inject.values.indexOf("cancellable") + 1));
        assertEquals(1, inject.values.get(inject.values.indexOf("require") + 1));
        AnnotationNode at = (AnnotationNode) ((List<?>) inject.values.get(inject.values.indexOf("at") + 1)).get(0);
        assertEquals("HEAD", at.values.get(at.values.indexOf("value") + 1));

        String name = "com/huanghuang/rsintegration/mods/ironfurnaces/FactorySplitHookFixture";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        hook.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        hook.name = "invoke";
        hook.desc = "(Lnet/minecraft/world/level/block/entity/BlockEntity;"
                + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
        hook.localVariables = null;
        hook.accept(writer);
        writer.visitEnd();
        Class<?> fixture = new ClassLoader(IronFurnaceFactoryTierTest.class.getClassLoader()) {
            Class<?> define(byte[] bytes) {
                return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define(writer.toByteArray());
        splitHook = fixture.getMethod("invoke", BlockEntity.class, CallbackInfo.class);
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

    @Test
    void nativeAutoSplitMovesRainbowInputsOutsideReservedLanes() throws ReflectiveOperationException {
        Fixture fixture = fixture(2);
        BlockIronFurnaceTileBase furnace = (BlockIronFurnaceTileBase) get(fixture.delegate, "furnace");
        ServerLevel level = (ServerLevel) get(fixture.delegate, "level");
        set(fixture.delegate, "rainbowMode", true);
        set(fixture.delegate, "queuedMaterial", new ItemStack(Items.IRON_ORE));
        set(fixture.delegate, "queuedOperations", 12);
        assertTrue(startNextBatch(fixture.delegate));
        assertEquals(List.of(7), fixture.written);

        // 调用模组原生分配逻辑，验证输入确实会越过集成预留的通道。
        doCallRealMethod().when(furnace).split(false, 0, 6);
        doCallRealMethod().when(furnace).fillEmptySlots(0, 6);
        doCallRealMethod().when(furnace).getSplitCounts(any(), any());
        furnace.split(false, 0, 6);
        for (int lane = 0; lane < 6; lane++) {
            assertEquals(2, fixture.items[7 + lane].getCount());
            fixture.items[13 + lane] = new ItemStack(Items.IRON_INGOT, 2);
            fixture.items[7 + lane] = ItemStack.EMPTY;
        }
        furnace.factoryCookTime = new int[6];

        assertEquals(CraftPhase.WORKING, fixture.delegate.observeMachineCraft(level, furnace).phase());
        assertEquals(10, Arrays.stream(fixture.items, 13, 19).mapToInt(ItemStack::getCount).sum());
    }

    @ParameterizedTest
    @CsvSource({"0, 12", "1, 65", "2, 12", "2, 129", "2, 385"})
    void splitGuardKeepsEveryProductCollectibleAndRestoresNativeSplitAfterCompletion(int tier, int operations)
            throws ReflectiveOperationException {
        Fixture fixture = fixture(tier);
        BlockIronFurnaceTileBase furnace = (BlockIronFurnaceTileBase) get(fixture.delegate, "furnace");
        ServerLevel level = (ServerLevel) get(fixture.delegate, "level");
        set(fixture.delegate, "rainbowMode", true);
        set(fixture.delegate, "queuedMaterial", new ItemStack(Items.IRON_ORE));
        set(fixture.delegate, "queuedOperations", operations);
        assertTrue(startNextBatch(fixture.delegate));
        furnace.factoryCookTime = new int[6];

        CraftPhase phase;
        int windows = 0;
        do {
            assertTrue(++windows <= 4, "物理批次未能完成");
            assertTrue(runGuardedSplit(furnace, false, tier));
            assertTrue(runGuardedSplit(furnace, true, tier));
            for (int slot = 7; slot <= 12; slot++) {
                ItemStack input = fixture.items[slot];
                if (input.isEmpty()) continue;
                fixture.items[slot + 6] = new ItemStack(Items.IRON_INGOT, input.getCount());
                fixture.items[slot] = ItemStack.EMPTY;
            }
            phase = fixture.delegate.observeMachineCraft(level, furnace).phase();
        } while (phase == CraftPhase.WORKING);

        assertEquals(CraftPhase.DONE, phase);
        List<ItemStack> results = fixture.delegate.collectAllResults(mock(ServerPlayer.class));
        assertEquals(operations, results.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(Arrays.stream(fixture.items, 7, 19).allMatch(ItemStack::isEmpty));
        assertTrue(IronFurnaceFactoryLeases.hasActiveLease(furnace));
        fixture.delegate.onBatchFinished(mock(ServerPlayer.class));
        assertFalse(IronFurnaceFactoryLeases.hasActiveLease(furnace));

        int firstLane = tier == 0 ? 2 : tier == 1 ? 1 : 0;
        fixture.items[7 + firstLane] = new ItemStack(Items.IRON_ORE, 12);
        assertFalse(runGuardedSplit(furnace, false, tier));
        assertEquals(12, Arrays.stream(fixture.items, 7, 13).mapToInt(ItemStack::getCount).sum());
        assertTrue(Arrays.stream(fixture.items, 7, 13).filter(stack -> !stack.isEmpty()).count() > 1);
    }

    @Test
    void discardedPreparationAndServerCleanupReleaseTheSplitGuard() throws ReflectiveOperationException {
        Fixture fixture = fixture(2);
        BlockIronFurnaceTileBase furnace = (BlockIronFurnaceTileBase) get(fixture.delegate, "furnace");
        assertFalse(IronFurnaceFactoryLeases.hasActiveLease(furnace));
        assertTrue((boolean) invoke(fixture.delegate, "ensureFactoryLeases", new Class<?>[]{int.class}, 1));
        assertTrue(IronFurnaceFactoryLeases.hasActiveLease(furnace));
        fixture.delegate.releasePreparationResources();
        assertFalse(IronFurnaceFactoryLeases.hasActiveLease(furnace));

        set(fixture.delegate, "factoryLeaseKey", IronFurnaceFactoryLeases.key(
                (ServerLevel) get(fixture.delegate, "level"), BlockPos.ZERO));
        assertTrue((boolean) invoke(fixture.delegate, "ensureFactoryLeases", new Class<?>[]{int.class}, 1));
        IronFurnacesBatchDelegate.clearFactoryLeases();
        assertFalse(IronFurnaceFactoryLeases.hasActiveLease(furnace));
    }

    private static boolean runGuardedSplit(BlockIronFurnaceTileBase furnace, boolean finishing, int tier)
            throws ReflectiveOperationException {
        CallbackInfo callback = new CallbackInfo("split", true);
        splitHook.invoke(null, furnace, callback);
        if (!callback.isCancelled()) {
            int first = tier == 0 ? 2 : tier == 1 ? 1 : 0;
            doCallRealMethod().when(furnace).split(finishing, first, 6 - first);
            doCallRealMethod().when(furnace).fillEmptySlots(first, 6 - first);
            doCallRealMethod().when(furnace).getSplitCounts(any(), any());
            furnace.split(finishing, first, 6 - first);
        }
        return callback.isCancelled();
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
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(furnace.getLevel()).thenReturn(level);
        when(furnace.getBlockPos()).thenReturn(BlockPos.ZERO);
        SmeltingRecipe smelting = mock(SmeltingRecipe.class);
        when(smelting.getResultItem(RegistryAccess.EMPTY)).thenReturn(new ItemStack(Items.IRON_INGOT));
        when(smelting.getIngredients()).thenReturn(NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_ORE)));

        IronFurnacesBatchDelegate delegate = new IronFurnacesBatchDelegate();
        set(delegate, "furnace", furnace);
        set(delegate, "factoryMode", true);
        set(delegate, "level", level);
        set(delegate, "pos", BlockPos.ZERO);
        set(delegate, "recipe", smelting);
        set(delegate, "factoryLeaseKey", IronFurnaceFactoryLeases.key(level, BlockPos.ZERO));
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
        return invoke(delegate, name, new Class<?>[0]);
    }

    private static Object invoke(IronFurnacesBatchDelegate delegate, String name,
                                 Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        Method method = IronFurnacesBatchDelegate.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(delegate, arguments);
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
