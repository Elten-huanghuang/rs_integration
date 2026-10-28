package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切石机、锻造台这类没有处理时间的虚拟机器必须一次完成整批操作，
 * 而不是每 tick 只推进一个操作。这里覆盖批次规划、产出暂存与完成契约。
 */
class VanillaStonecutterInstantBatchTest extends BootstrapTest {

    private static final int INSTANT_BATCH_LIMIT = 64;

    @Test
    void prepareFlatBatchAdmitsWholeStonecutterBatch() throws Exception {
        VanillaMachineBatchDelegate delegate = newVirtualDelegate();

        assertEquals(INSTANT_BATCH_LIMIT, delegate.prepareFlatBatch(200));
        assertEquals(7, delegate.prepareFlatBatch(7));
        assertEquals(0, delegate.prepareFlatBatch(0));
    }

    @Test
    void instantBatchExpandsDispatchLimitAndParallelShare() throws Exception {
        VanillaMachineBatchDelegate delegate = newVirtualDelegate();

        assertEquals(INSTANT_BATCH_LIMIT, delegate.flatBatchOperationLimit(32));
        assertTrue(delegate.expandsFlatBatchOperationLimit());
        assertEquals(INSTANT_BATCH_LIMIT, delegate.preferredParallelBatchSize(300, 3));
        assertEquals(4, delegate.preferredParallelBatchSize(12, 3));
    }

    @Test
    void expectedProductionCoversWholeBatchInsteadOfOneOperation() throws Exception {
        VanillaMachineBatchDelegate delegate = newVirtualDelegate();
        delegate.prepareFlatBatch(12);

        assertNotNull(delegate.getExpectedProduction());
        assertEquals(12, delegate.getExpectedProduction().count());
    }

    @Test
    void stagedBatchIsSplitByMaxStackAndFullyCollected() throws Exception {
        VanillaMachineBatchDelegate delegate = newVirtualDelegate();
        ItemStack stone = new ItemStack(Items.STONE, 1);

        List<ItemStack> staged = VanillaMachineBatchDelegate.splitBatchStacks(stone, 100);

        assertEquals(2, staged.size());
        assertEquals(64, staged.get(0).getCount());
        assertEquals(36, staged.get(1).getCount());
        stageVirtualResults(delegate, stone, 100);

        List<ItemStack> collected = delegate.collectAllResults(null);

        assertEquals(100, collected.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(delegate.collectAllResults(null).isEmpty());
        assertFalse(delegate.isCraftComplete(null));
    }

    private static void stageVirtualResults(VanillaMachineBatchDelegate delegate,
                                            ItemStack singleResult, int operations) throws Exception {
        Method stage = VanillaMachineBatchDelegate.class.getDeclaredMethod(
                "stageVirtualResults", ItemStack.class, int.class);
        stage.setAccessible(true);
        stage.invoke(delegate, singleResult, operations);
    }

    private static VanillaMachineBatchDelegate newVirtualDelegate() throws Exception {
        VanillaMachineBatchDelegate delegate = new VanillaMachineBatchDelegate();
        setField(delegate, "kind", virtualMachineKind());
        ServerLevel level = Mockito.mock(ServerLevel.class);
        StonecutterRecipe recipe = Mockito.mock(StonecutterRecipe.class);
        Mockito.when(recipe.getResultItem(Mockito.nullable(RegistryAccess.class)))
                .thenReturn(new ItemStack(Items.STONE_SLAB));
        setField(delegate, "myLevel", level);
        setField(delegate, "recipe", recipe);
        return delegate;
    }

    private static Object virtualMachineKind() throws Exception {
        Class<?> kindClass = Class.forName(
                "com.huanghuang.rsintegration.mods.vanilla.VanillaMachineBatchDelegate$MachineKind");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object value = Enum.valueOf((Class<? extends Enum>) kindClass, "VIRTUAL");
        return value;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
