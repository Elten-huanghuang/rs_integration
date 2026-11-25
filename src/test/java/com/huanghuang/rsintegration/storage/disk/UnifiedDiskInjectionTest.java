package com.huanghuang.rsintegration.storage.disk;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.mixin.refinedstorage.DiskManipulatorThroughputMixin;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageOutputReception;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.util.Annotations;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 检查实际 RS 字节码中的调用位置，防止 lambda 名称和接口调用发生变化。 */
class UnifiedDiskInjectionTest extends BootstrapTest {
    private static final String RS = "com/refinedmods/refinedstorage/";
    private ClassNode read(String name) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RS + name + ".class")) {
            assertNotNull(input); ClassNode result = new ClassNode(); new ClassReader(input).accept(result, 0); return result;
        }
    }
    private ClassNode readOwn(String name) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input); ClassNode result = new ClassNode(); new ClassReader(input).accept(result, 0); return result;
        }
    }
    private int calls(ClassNode node, String method, String owner, String called) {
        int count = 0;
        for (MethodNode candidate : node.methods) if (candidate.name.equals(method)) {
            for (var instruction : candidate.instructions) {
                if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals(RS + owner) && invoke.name.equals(called)) count++;
            }
        }
        return count;
    }
    @Test void diskDriveAndManipulatorScopesHaveExactExpectedCallSites() throws Exception {
        ClassNode drive = read("apiimpl/network/node/diskdrive/DiskDriveNetworkNode");
        assertEquals(1, calls(drive, "lambda$new$2", "util/StackUtils", "createStorages"));
        assertTrue(drive.methods.stream().anyMatch(candidate -> candidate.name.equals("onConnectedStateChange")),
                "磁盘仓必须保留联网状态回调，用于区块重载后的自动重挂");
        ClassNode manipulator = read("apiimpl/network/node/diskmanipulator/DiskManipulatorNetworkNode");
        assertEquals(1, calls(manipulator, "lambda$new$2", "util/StackUtils", "createStorages"));
        assertEquals(1, calls(manipulator, "lambda$new$5", "util/StackUtils", "createStorages"));
        for (String method : List.of("isItemDiskDone", "isFluidDiskDone")) assertTrue(manipulator.methods.stream().anyMatch(candidate -> candidate.name.equals(method)));
    }
    @Test void diskManipulatorBatchTargetsStillExist() throws Exception {
        ClassNode manipulator = read("apiimpl/network/node/diskmanipulator/DiskManipulatorNetworkNode");
        assertEquals(1, calls(manipulator, "update", "inventory/item/UpgradeItemHandler", "getSpeed"));
        for (String method : List.of("insertItemIntoNetwork", "extractItemFromNetwork",
                "insertFluidIntoNetwork", "extractFluidFromNetwork", "moveDriveToOutput")) {
            assertTrue(manipulator.methods.stream().anyMatch(candidate -> candidate.name.equals(method)),
                    () -> "缺少 RS 磁盘操纵器方法: " + method);
        }
        assertTrue(manipulator.methods.stream().anyMatch(candidate ->
                        candidate.name.equals("insertFluidIntoNetwork")
                                && candidate.desc.equals("(L" + RS
                                + "api/storage/disk/IStorageDisk;I)V")),
                "insertFluidIntoNetwork 必须保留 slot 参数");
    }
    @Test void mixinHelpersStayOutsideTheMixinPackage() throws Exception {
        ClassNode mixin = readOwn("com/huanghuang/rsintegration/mixin/refinedstorage/DiskManipulatorThroughputMixin");
        assertTrue(mixin.innerClasses == null || mixin.innerClasses.stream()
                        .noneMatch(inner -> inner.name.startsWith(mixin.name + "$")),
                "Mixin 内部类会被 Mixin 框架禁止直接引用");
    }
    @ParameterizedTest
    @ValueSource(strings = {"Item", "Fluid"})
    void diskReturnHookMatchesOnlyTheOriginalRejectPath(String kind) throws Exception {
        ClassNode manipulator = read("apiimpl/network/node/diskmanipulator/DiskManipulatorNetworkNode");
        ClassNode mixin = readOwn("com/huanghuang/rsintegration/mixin/refinedstorage/DiskManipulatorThroughputMixin");
        String extract = "extract" + kind + "FromNetwork";
        String insert = "insert" + kind;
        var callback = mixin.methods.stream().filter(method -> method.name.equals("rsi$returnRejected" + kind + "s"))
                .findFirst().orElseThrow();
        var wrap = Annotations.getVisible(callback, WrapOperation.class);
        assertNotNull(wrap);
        assertEquals(List.of(extract), Annotations.getValue(wrap, "method"));
        List<AnnotationNode> locations = Annotations.getValue(wrap, "at", true);
        assertEquals(1, locations.size());

        MethodNode original = manipulator.methods.stream().filter(method -> method.name.equals(extract))
                .findFirst().orElseThrow();
        assertEquals(1, calls(manipulator, extract, "api/network/INetwork", insert));
        for (var instruction : original.instructions) {
            if (!(instruction instanceof MethodInsnNode invoke)
                    || !invoke.owner.equals(RS + "api/network/INetwork") || !invoke.name.equals(insert)) continue;
            assertEquals("L" + invoke.owner + ";" + invoke.name + invoke.desc,
                    Annotations.getValue(locations.get(0), "target"));
        }
        assertEquals(1, calls(manipulator, insert + "IntoNetwork", "api/network/INetwork", insert));
    }
    @Test void itemDiskReturnsReachStorageButOnlyActiveImportsFeedTheTask() throws Exception {
        var network = mock(INetwork.class);
        var stack = new ItemStack(Items.IRON_INGOT, 4);
        var key = TaskOutputReception.resource(stack);
        var stored = new AtomicInteger();
        Operation<ItemStack> insert = args -> RefinedStorageOutputReception.insertItem(
                (INetwork) args[0], (ItemStack) args[1], (int) args[2], (Action) args[3], (remaining, count) -> {
                    stored.addAndGet(count);
                    return ItemStack.EMPTY;
                });
        try (var task = waiting(network, key)) {
            var method = DiskManipulatorThroughputMixin.class.getDeclaredMethod("rsi$returnRejectedItems",
                    INetwork.class, ItemStack.class, int.class, Action.class, Operation.class);
            method.setAccessible(true);
            assertSame(ItemStack.EMPTY, method.invoke(new DiskManipulatorThroughputMixin() {},
                    network, stack, stack.getCount(), Action.PERFORM, insert));
            assertEquals(4, stored.get());
            assertEquals(0, task.received(key));
            assertTrue(insert.call(network, stack, stack.getCount(), Action.PERFORM).isEmpty());
            assertEquals(4, task.received(key));
            assertEquals(4, stored.get());
        }
    }
    @Test void fluidDiskReturnsReachStorageButOnlyActiveImportsFeedTheTask() throws Exception {
        var network = mock(INetwork.class);
        var stack = new FluidStack(Fluids.WATER, 4);
        var key = TaskOutputReception.resource(stack);
        var stored = new AtomicInteger();
        Operation<FluidStack> insert = args -> RefinedStorageOutputReception.insertFluid(
                (INetwork) args[0], (FluidStack) args[1], (int) args[2], (Action) args[3], (remaining, amount) -> {
                    stored.addAndGet(amount);
                    return FluidStack.EMPTY;
                });
        try (var task = waiting(network, key)) {
            var method = DiskManipulatorThroughputMixin.class.getDeclaredMethod("rsi$returnRejectedFluids",
                    INetwork.class, FluidStack.class, int.class, Action.class, Operation.class);
            method.setAccessible(true);
            assertSame(FluidStack.EMPTY, method.invoke(new DiskManipulatorThroughputMixin() {},
                    network, stack, stack.getAmount(), Action.PERFORM, insert));
            assertEquals(4, stored.get());
            assertEquals(0, task.received(key));
            assertTrue(insert.call(network, stack, stack.getAmount(), Action.PERFORM).isEmpty());
            assertEquals(4, task.received(key));
            assertEquals(4, stored.get());
        }
    }
    private TaskOutputReception.Session waiting(INetwork network, IntegrationResourceKey key) {
        var task = TaskOutputReception.open();
        task.bindNetwork(network, () -> true);
        task.prepare(List.of(new TaskOutputReception.Demand("out", key, 4, 4))).activate();
        return task;
    }
    @Test void itemAndFluidNetworkHooksAndCacheIterationExistOnce() throws Exception {
        ClassNode network = read("apiimpl/network/Network");
        for (String kind : List.of("Item", "Fluid")) {
            assertEquals(1, calls(network, "insert" + kind, "api/storage/IStorage", "insert"));
            assertEquals(1, calls(network, "extract" + kind, "api/storage/IStorage", "extract"));
            ClassNode cache = read("apiimpl/storage/cache/" + kind + "StorageCache");
            assertEquals(1, calls(cache, "invalidate", "api/storage/IStorage", "getStacks"));
            assertTrue(cache.fields.stream().anyMatch(field -> field.name.equals("list") && field.desc.equals("L" + RS + "api/util/IStackList;")));
        }
    }
}
