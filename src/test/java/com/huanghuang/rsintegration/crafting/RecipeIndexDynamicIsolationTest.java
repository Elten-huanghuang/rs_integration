package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecipeIndexDynamicIsolationTest extends BootstrapTest {
    @Test
    void failedOptionalSpellSourceHasRuntimeAndLinkageIsolation() throws Exception {
        MethodNode method = readClass("RecipeIndex$BuildSession.class").methods.stream()
                .filter(candidate -> candidate.name.equals("advance") && candidate.desc.contains("LongSupplier"))
                .findFirst().orElseThrow();
        Set<String> protectedCalls = new HashSet<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.endsWith("IronSpellBooksRecipeCatalog$IncrementalCatalog")) {
                if (!call.name.equals("<init>") && !call.name.equals("advance")) continue;
                int callIndex = method.instructions.indexOf(call);
                for (String type : List.of("java/lang/RuntimeException", "java/lang/LinkageError")) {
                    assertTrue(method.tryCatchBlocks.stream().anyMatch(handler -> type.equals(handler.type)
                            && method.instructions.indexOf(handler.start) <= callIndex
                            && method.instructions.indexOf(handler.end) > callIndex));
                }
                protectedCalls.add(call.name);
            }
        }
        assertEquals(Set.of("<init>", "advance"), protectedCalls);
    }

    @Test
    void runtimeFingerprintStaysOnServerThreadAndNeverSubmitsThirdPartyWorker() throws Exception {
        ClassNode node = readClass("RecipeIndex.class");
        MethodNode refresh = node.methods.stream()
                .filter(candidate -> candidate.name.equals("refreshDynamicRuntimeIfNeeded"))
                .findFirst().orElseThrow();
        boolean serverGuard = false;
        boolean probesRuntime = false;
        for (AbstractInsnNode instruction : refresh.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("execute", call.name);
                if (call.name.equals("requireServerLevel")) serverGuard = true;
                if (call.name.equals("hasRuntimeDrift")) probesRuntime = true;
            }
        }
        assertTrue(serverGuard);
        assertTrue(probesRuntime);
        boolean backgroundProbe = false;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$refreshDynamicRuntimeIfNeeded$")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("hasRuntimeDrift")) {
                    backgroundProbe = true;
                }
            }
        }
        assertFalse(backgroundProbe);
    }

    @Test
    void driftChecksAreSampledAndWorldTimeResetDoesNotSuppressThem() {
        assertTrue(RecipeIndex.driftCheckDue(0, Long.MIN_VALUE));
        assertFalse(RecipeIndex.driftCheckDue(99, 0));
        assertTrue(RecipeIndex.driftCheckDue(100, 0));
        assertTrue(RecipeIndex.driftCheckDue(0, 1000));
    }

    private static ClassNode readClass(String className) throws Exception {
        try (var input = RecipeIndex.class.getResourceAsStream(className)) {
            assertNotNull(input);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
