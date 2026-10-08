package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class RecipeIndexDynamicIsolationTest extends BootstrapTest {
    @Test
    void failedOptionalSpellSourceHasRuntimeAndLinkageIsolation() throws Exception {
        MethodNode method = recipeIndex().methods.stream()
                .filter(candidate -> candidate.name.equals("indexIronSpellBooks"))
                .findFirst().orElseThrow();
        MethodInsnNode sourceCall = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("allRecipes")) {
                sourceCall = call;
            }
        }
        assertNotNull(sourceCall);
        int callIndex = method.instructions.indexOf(sourceCall);
        for (String type : new String[]{"java/lang/RuntimeException", "java/lang/LinkageError"}) {
            assertTrue(method.tryCatchBlocks.stream().anyMatch(handler -> type.equals(handler.type)
                    && method.instructions.indexOf(handler.start) <= callIndex
                    && method.instructions.indexOf(handler.end) > callIndex));
        }
    }

    @Test
    void runtimeFingerprintStaysOnServerThreadAndNeverSubmitsThirdPartyWorker() throws Exception {
        ClassNode node = recipeIndex();
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

    private static ClassNode recipeIndex() throws Exception {
        try (var input = RecipeIndex.class.getResourceAsStream("RecipeIndex.class")) {
            assertNotNull(input);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
