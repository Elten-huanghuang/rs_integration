package com.huanghuang.rsintegration.disk;

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
        assertEquals(1, calls(read("apiimpl/network/node/diskdrive/DiskDriveNetworkNode"), "lambda$new$2", "util/StackUtils", "createStorages"));
        ClassNode manipulator = read("apiimpl/network/node/diskmanipulator/DiskManipulatorNetworkNode");
        assertEquals(1, calls(manipulator, "lambda$new$2", "util/StackUtils", "createStorages"));
        assertEquals(1, calls(manipulator, "lambda$new$5", "util/StackUtils", "createStorages"));
        for (String method : List.of("isItemDiskDone", "isFluidDiskDone")) assertTrue(manipulator.methods.stream().anyMatch(candidate -> candidate.name.equals(method)));
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
