package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.RSIntegrationMod;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeGraphWarmUpContractTest {
    @Test
    void serverStartupBuildsCompleteGenerationBeforeTicks() throws IOException {
        Set<String> calls = methodCalls(RSIntegrationMod.class, null);

        assertTrue(calls.contains(owner(RecipeIndex.class) + ".warmUp"));
        assertTrue(calls.contains(owner(GenericCraftPacket.class) + ".tickWarmUpRequests"));
    }

    @Test
    void playerCraftRequestsWaitForOnePublishedGeneration() throws IOException {
        Set<String> calls = methodCalls(GenericCraftPacket.class, "warmUpReady");

        assertTrue(calls.contains(owner(RecipeIndex.class) + ".isReady"));
        assertFalse(calls.contains(owner(ImmutableRecipeGraphProjector.class) + ".isReady"));
    }

    @Test
    void explicitWarmUpEntryPointRemainsBinaryCompatible() throws IOException {
        Set<String> calls = methodCalls(RecipeIndex.class, "warmUp");

        assertTrue(calls.contains(owner(RecipeIndex.class) + ".buildSynchronously"));
    }

    @Test
    void ordinaryIndexAccessNeverBuildsGenerationSynchronously() throws IOException {
        Set<String> calls = methodCalls(RecipeIndex.class, "get");

        assertFalse(calls.contains(owner(RecipeIndex.class) + ".buildSynchronously"));
        assertFalse(calls.contains(owner(RecipeIndex.class) + ".warmUp"));
    }

    @Test
    void freezingRecipeIndexDoesNotCopyTheCompletedMapTwice() throws IOException {
        Set<String> calls = methodCalls(RecipeIndex.class, "freezeIndex");

        assertTrue(calls.contains(owner(Collections.class) + ".unmodifiableMap"));
        assertFalse(calls.contains(owner(Map.class) + ".copyOf"));
    }

    @Test
    void craftingProjectionBypassesGenericReflectiveExtraction() throws IOException {
        Set<String> calls = methodCalls(ImmutableRecipeGraphProjector.class, "projectCraftingRecipe");
        String utilityOwner = owner(CraftPacketUtils.class);

        assertTrue(calls.contains(utilityOwner + ".extractCraftingIngredientSpecs"));
        assertFalse(calls.contains(utilityOwner + ".extractIngredientSpecs"));
    }

    private static Set<String> methodCalls(Class<?> type, String targetMethod) throws IOException {
        Set<String> calls = new HashSet<>();
        new ClassReader(classBytes(type)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (targetMethod != null && !name.equals(targetMethod)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        calls.add(owner + "." + name);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + owner(type) + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing class resource " + resource);
            return input.readAllBytes();
        }
    }

    private static String owner(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
