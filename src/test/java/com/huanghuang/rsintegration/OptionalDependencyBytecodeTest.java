package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.crafting.plan.PlanWarnings;
import com.huanghuang.rsintegration.mixin.jei.RecipeGuiLayoutsMixin;
import com.huanghuang.rsintegration.mixin.wizardterracurios.BuffItemMixin;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OptionalDependencyBytecodeTest {
    @Test
    void sharedJeiAndPlanningClassesDoNotLinkBotaniaTypes() throws IOException {
        assertNoBotaniaTypeReference(RecipeGuiLayoutsMixin.class);
        assertNoBotaniaTypeReference(PlanWarnings.class);
    }

    @Test
    void wizardTerraCuriosMixinSoftFailsAcrossApiVersions() throws IOException {
        byte[] bytecode = classBytes(BuffItemMixin.class);
        AtomicBoolean hasShadow = new AtomicBoolean();
        AtomicReference<Integer> injectRequire = new AtomicReference<>();

        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor,
                                                             boolean visible) {
                        if ("Lorg/spongepowered/asm/mixin/Shadow;".equals(annotationDescriptor)) {
                            hasShadow.set(true);
                        }
                        AnnotationVisitor delegate = super.visitAnnotation(annotationDescriptor, visible);
                        if (!name.equals("rsi$applyDiskBuffs")
                                || !"Lorg/spongepowered/asm/mixin/injection/Inject;"
                                .equals(annotationDescriptor)) {
                            return delegate;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visit(String key, Object value) {
                                if ("require".equals(key)) injectRequire.set((Integer) value);
                                super.visit(key, value);
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertFalse(hasShadow.get(), "optional-mod mixin must not require target members via @Shadow");
        assertEquals(0, injectRequire.get(), "optional target method must use require = 0");
    }

    private static void assertNoBotaniaTypeReference(Class<?> type) throws IOException {
        String constantPool = new String(classBytes(type), StandardCharsets.ISO_8859_1);
        assertFalse(constantPool.contains("vazkii/botania"),
                () -> type.getName() + " directly links optional Botania bytecode");
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing class resource " + resource);
            return input.readAllBytes();
        }
    }
}
