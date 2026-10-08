package com.huanghuang.rsintegration.crafting.fluid;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** 检查实际 RS 依赖字节码，避免单元测试通过但运行时找不到注入入口。 */
class BucketTerminalInjectionContractTest {
    private static final String RS = "com/refinedmods/refinedstorage/";

    @Test
    void bucketHooksMatchDevelopmentAndReleaseDependency() throws IOException {
        assertHooks(read(RS + "util/StackUtils"), read(RS + "apiimpl/network/grid/handler/FluidGridHandler"));
        try (JarFile jar = new JarFile(Path.of("libs", "refinedstorage-1.12.4.jar").toFile())) {
            assertHooks(read(jar, RS + "util/StackUtils"),
                    read(jar, RS + "apiimpl/network/grid/handler/FluidGridHandler"));
        }
    }

    private static void assertHooks(ClassNode stacks, ClassNode handler) {
        String fluidRead = "(Lnet/minecraft/world/item/ItemStack;Z)Lorg/apache/commons/lang3/tuple/Pair;";
        MethodNode read = method(stacks, "getFluid", fluidRead);
        assertTrue((read.access & Opcodes.ACC_STATIC) != 0);
        MethodNode held = method(handler, "onInsertHeldContainer", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        MethodNode insert = method(handler, "onInsert",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;");
        int heldCalls = 0;
        for (AbstractInsnNode instruction : held.instructions) {
            if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals(handler.name)
                    && invoke.name.equals(insert.name) && invoke.desc.equals(insert.desc)) heldCalls++;
        }
        assertEquals(1, heldCalls);
        int reads = 0;
        for (AbstractInsnNode instruction : insert.instructions) {
            if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals(stacks.name)
                    && invoke.name.equals(read.name)) {
                assertEquals(fluidRead, invoke.desc);
                AbstractInsnNode argument = instruction.getPrevious();
                while (argument.getOpcode() < 0) argument = argument.getPrevious();
                // 先模拟容量，再实际排空，两条路径必须共用读取入口。
                assertEquals(reads == 0 ? Opcodes.ICONST_1 : Opcodes.ICONST_0, argument.getOpcode());
                reads++;
            }
        }
        assertEquals(2, reads);
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError(type.name + ": " + name + descriptor));
    }

    private static ClassNode read(String type) throws IOException {
        try (InputStream input = BucketTerminalInjectionContractTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull(input, type);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static ClassNode read(JarFile jar, String type) throws IOException {
        try (InputStream input = jar.getInputStream(jar.getJarEntry(type + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
