package com.huanghuang.rsintegration.storage.rs;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 检查实际依赖字节码中的注入位置；不将普通单元测试冒充运行中的 Mixin 测试。 */
class RSStorageInjectionContractTest {
    private static final String RS = "com/refinedmods/refinedstorage/";

    @Test
    void mixedGridLifecycleAndLegacySnapshotHooksMatchDependency() throws IOException {
        ClassNode menu = read(RS + "container/GridContainerMenu");
        MethodNode broadcast = menu.methods.stream().filter(candidate ->
                candidate.name.equals("m_38946_") || candidate.name.equals("broadcastChanges")).findFirst().orElseThrow();
        int subscriptions = 0;
        for (AbstractInsnNode instruction : broadcast.instructions)
            if (instruction instanceof MethodInsnNode invoke && invoke.name.equals("getStorageCache")) subscriptions++;
        assertEquals(2, subscriptions);
        assertTrue(menu.methods.stream().anyMatch(candidate -> candidate.name.equals("m_6877_") || candidate.name.equals("removed")));
        ClassNode screen = read(RS + "screen/grid/GridScreen");
        assertTrue(screen.methods.stream().anyMatch(candidate -> candidate.name.equals("m_6375_") || candidate.name.equals("mouseClicked")));
        ClassNode tracker = read(RS + "integration/jei/IngredientTracker");
        method(tracker, "<init>", "(L" + RS + "container/GridContainerMenu;)V");
        assertTrue(tracker.fields.stream().anyMatch(field -> field.name.equals("storedItems")
                && field.desc.equals("Ljava/util/Map;")));
        for (String kind : List.of("Item", "Fluid")) {
            ClassNode packet = read(RS + "network/grid/Grid" + kind + "UpdateMessage");
            MethodNode handler = packet.methods.stream().filter(candidate -> candidate.name.equals("lambda$handle$0"))
                    .findFirst().orElseThrow();
            assertTrue(handler.desc.endsWith("L" + RS + "screen/grid/GridScreen;)V"));
            assertTrue((handler.access & 8) != 0);
        }
    }

    @Test
    void queuedActionScopeOccursAfterBothNodeStateCallbacks() throws IOException {
        MethodNode method = method(read(RS + "apiimpl/network/NetworkNodeGraph"), "invalidate",
                "(L" + RS + "api/util/Action;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V");
        int connected = -1;
        int disconnected = -1;
        int batch = -1;
        int count = 0;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invoke) {
                if (invoke.name.equals("onConnected")) connected = index;
                if (invoke.name.equals("onDisconnected")) disconnected = index;
                if (invoke.owner.equals("java/util/Set") && invoke.name.equals("forEach")) {
                    batch = index;
                    count++;
                }
            }
            index++;
        }
        assertEquals(1, count);
        assertTrue(connected >= 0 && disconnected > connected && batch > disconnected);
    }

    @Test
    void storageAndGridHooksExistForBothResourceTypesAndPortableGrids() throws IOException {
        for (String type : List.of("Item", "Fluid")) {
            method(read(RS + "apiimpl/storage/cache/" + type + "StorageCache"), "invalidate",
                    "(L" + RS + "api/storage/cache/InvalidateCause;)V");
            for (String prefix : List.of("", "Portable")) {
                ClassNode listener = read(RS + "apiimpl/storage/cache/listener/" + prefix + type + "GridStorageCacheListener");
                method(listener, "onInvalidated", "()V");
                method(listener, "onAttached", "()V");
                read(RS + "network/grid/" + prefix + "Grid" + type + "UpdateMessage");
            }
        }
    }

    @Test
    void snapshotRegistrationAndFluidContainerPreflightMatchDependency() throws IOException {
        method(read(RS + "network/PacketSplitter"), "registerMessage",
                "(IILjava/lang/Class;Ljava/util/function/BiConsumer;Ljava/util/function/Function;Ljava/util/function/BiConsumer;)V");
        MethodNode extract = method(read(RS + "apiimpl/network/grid/handler/FluidGridHandler"), "onExtract",
                "(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/UUID;Z)V");
        int count = 0;
        for (AbstractInsnNode instruction : extract.instructions) {
            if (instruction instanceof MethodInsnNode invoke
                    && invoke.owner.equals(RS + "util/NetworkUtils")
                    && invoke.name.equals("extractBucketFromPlayerInventoryOrNetwork")) {
                assertEquals("(Lnet/minecraft/world/entity/player/Player;L" + RS
                        + "api/network/INetwork;Ljava/util/function/Consumer;)V", invoke.desc);
                count++;
            }
        }
        assertEquals(1, count);
    }

    @Test
    void saveHookExistsUnderDevelopmentAndReleaseMappings() throws IOException {
        method(read(RS + "apiimpl/util/RSSavedData"), "save", "(Ljava/io/File;)V");
        try (JarFile jar = new JarFile(Path.of("libs", "refinedstorage-1.12.4.jar").toFile());
             InputStream input = jar.getInputStream(jar.getJarEntry(RS + "apiimpl/util/RSSavedData.class"))) {
            ClassNode release = new ClassNode();
            new ClassReader(input).accept(release, 0);
            method(release, "m_77757_", "(Ljava/io/File;)V");
        }
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError(type.name + ": " + name + descriptor));
    }

    private static ClassNode read(String type) throws IOException {
        try (InputStream input = RSStorageInjectionContractTest.class.getClassLoader().getResourceAsStream(type + ".class")) {
            assertNotNull(input, type);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
