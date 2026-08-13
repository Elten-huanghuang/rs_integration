package com.huanghuang.rsintegration.resonance.item;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonanceDiskItemInteractionContractTest {
    @Test
    void overridesStorageDiskDismantleInteractionWithoutCallingSuper() throws IOException {
        AtomicBoolean declaresUse = new AtomicBoolean();
        AtomicBoolean callsStorageDiskUse = new AtomicBoolean();

        new ClassReader(classBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("use") && !name.equals("m_7203_")) return null;
                declaresUse.set(true);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if ("com/refinedmods/refinedstorage/item/StorageDiskItem".equals(owner)
                                && (methodName.equals("use") || methodName.equals("m_7203_"))) {
                            callsStorageDiskUse.set(true);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(declaresUse.get(), "resonance disk must override the inherited use action");
        assertFalse(callsStorageDiskUse.get(), "override must not invoke RS disk dismantling");
    }

    private static byte[] classBytes() throws IOException {
        Path path = Path.of("build", "classes", "java", "main", "com", "huanghuang",
                "rsintegration", "resonance", "item", "ResonanceDiskItem.class");
        return Files.readAllBytes(path);
    }
}
