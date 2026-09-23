package com.huanghuang.rsintegration.mods.tetra;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TetraJeiMixinContractTest {
    @Test
    void resultReplacementIsCancellableSoOpeningJeiDoesNotThrow() throws Exception {
        String resource = "com/huanghuang/rsintegration/mixin/jei/IngredientFilterTetraMixin.class";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            MethodNode hook = node.methods.stream()
                    .filter(method -> method.name.equals("rsi$filterTetraItems"))
                    .findFirst().orElseThrow();
            AnnotationNode inject = hook.visibleAnnotations.stream()
                    .filter(annotation -> annotation.desc.endsWith("/Inject;"))
                    .findFirst().orElseThrow();
            int flag = inject.values.indexOf("cancellable");
            assertEquals(Boolean.TRUE, flag < 0 ? null : inject.values.get(flag + 1),
                    "CallbackInfoReturnable.setReturnValue requires a cancellable injection");
        }
    }
}
