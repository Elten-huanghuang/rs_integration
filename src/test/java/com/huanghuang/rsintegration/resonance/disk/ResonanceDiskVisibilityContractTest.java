package com.huanghuang.rsintegration.resonance.disk;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonanceDiskVisibilityContractTest {
    private static final Path CLASS_ROOT = Path.of("build", "classes", "java", "main");
    private static final String WRAPPER =
            "com/huanghuang/rsintegration/resonance/disk/ResonanceDiskWrapper";

    @Test
    void publicRsViewIsEmptyAndDoesNotReadDelegateStacks() throws IOException {
        byte[] bytes = Files.readAllBytes(CLASS_ROOT.resolve(WRAPPER + ".class"));
        boolean[] found = {false};
        boolean[] returnsSharedEmptyList = {false};
        boolean[] readsDelegateStacks = {false};

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"getStacks".equals(name)) return null;
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if ("java/util/List".equals(owner) && "of".equals(methodName)
                                && "()Ljava/util/List;".equals(methodDescriptor)) {
                            returnsSharedEmptyList[0] = true;
                        }
                        if ("getStacks".equals(methodName)
                                && "com/refinedmods/refinedstorage/api/storage/IStorage".equals(owner)) {
                            readsDelegateStacks[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(found[0]);
        assertTrue(returnsSharedEmptyList[0], "RS public storage view must remain empty");
        assertFalse(readsDelegateStacks[0], "RS public view must not expose resonance contents");
    }

    @Test
    void publicRsExtractionIsRejectedWithoutTouchingTheDelegate() throws IOException {
        byte[] bytes = Files.readAllBytes(CLASS_ROOT.resolve(WRAPPER + ".class"));
        boolean[] found = {false};
        boolean[] returnsEmptyStack = {false};
        boolean[] readsDelegate = {false};

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"extract".equals(name)
                        || !descriptor.startsWith("(Lnet/minecraft/world/item/ItemStack;")) {
                    return null;
                }
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC
                                && "net/minecraft/world/item/ItemStack".equals(owner)
                                && "EMPTY".equals(fieldName)) {
                            returnsEmptyStack[0] = true;
                        }
                        if (opcode == Opcodes.GETFIELD && WRAPPER.equals(owner)
                                && "delegate".equals(fieldName)) {
                            readsDelegate[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(found[0]);
        assertTrue(returnsEmptyStack[0], "RS extraction must reject resonance contents");
        assertFalse(readsDelegate[0], "RS extraction must not debit the resonance delegate");
    }

    @Test
    void integrationsNeverCallThePublicRsView() throws IOException {
        List<String> callers = new ArrayList<>();
        try (Stream<Path> classes = Files.walk(CLASS_ROOT)) {
            for (Path path : classes.filter(p -> p.toString().endsWith(".class")).toList()) {
                if (path.endsWith(Path.of(WRAPPER + ".class"))) continue;
                scanCalls(path, callers);
            }
        }
        assertEquals(List.of(), callers,
                "explicit resonance integrations must use getInternalStacks(), not getStacks()");
    }

    private static void scanCalls(Path path, List<String> callers) throws IOException {
        new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
            private String className;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                className = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (WRAPPER.equals(owner) && "getStacks".equals(methodName)) {
                            callers.add(className + "#" + name);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
}
