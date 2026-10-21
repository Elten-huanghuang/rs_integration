package com.huanghuang.rsintegration.storage.disk;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 检查实际 RS 字节码中的调用位置，防止 lambda 名称和接口调用发生变化。 */
class UnifiedDiskInjectionTest {
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
