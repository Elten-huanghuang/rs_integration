package com.huanghuang.rsintegration;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards packet registration against eager dedicated-server client linkage. */
class RegisteredPacketDistSafetyTest {
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Path CLASS_ROOT = Path.of("build", "classes", "java", "main");
    private static final Pattern REGISTRATION = Pattern.compile(
            "registerMessage\\s*\\((?s:.{0,1000}?)(\\w+)\\.class");

    @Test
    void registeredPacketsOnlyReachClientCodeThroughDedicatedHandlers() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT));
        assertTrue(Files.isDirectory(CLASS_ROOT));

        Set<String> registered = registeredPacketNames();
        Map<String, Path> classesBySimpleName = new HashMap<>();
        Set<String> clientPackageClasses = new HashSet<>();
        Set<String> clientOnlyClasses = new HashSet<>();

        try (Stream<Path> classes = Files.walk(CLASS_ROOT)) {
            classes.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                byte[] bytes = read(path);
                ClassReader reader = new ClassReader(bytes);
                String internalName = reader.getClassName();
                classesBySimpleName.putIfAbsent(simpleName(internalName), path);
                if (internalName.contains("/client/")) clientPackageClasses.add(internalName);
                if (isClientOnly(bytes)) clientOnlyClasses.add(internalName);
            });
        }

        List<String> missing = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (String packet : registered) {
            Path classFile = classesBySimpleName.get(packet);
            if (classFile == null) {
                missing.add(packet);
                continue;
            }
            Set<String> references = referencedTypes(read(classFile));
            for (String reference : references) {
                if (reference.startsWith("net/minecraft/client/")) {
                    offenders.add(packet + " -> " + reference.replace('/', '.'));
                } else if (clientPackageClasses.contains(reference)) {
                    offenders.add(packet + " -> " + reference.replace('/', '.'));
                } else if (clientOnlyClasses.contains(reference)
                        && !reference.endsWith("ClientPacketHandler")) {
                    offenders.add(packet + " -> @OnlyIn " + reference.replace('/', '.'));
                }
            }
        }

        assertEquals(List.of(), missing, "registered packet classes missing from compiled output");
        assertEquals(List.of(), offenders,
                "registered packet bytecode hard-links client classes. Forge/JVM may resolve the "
                        + "class while creating the registerMessage method reference and crash a "
                        + "dedicated server. Route client work through a dist-gated "
                        + "*ClientPacketHandler class.");
    }

    private static Set<String> registeredPacketNames() throws IOException {
        Set<String> names = new HashSet<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            sources.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source = new String(read(path), StandardCharsets.UTF_8);
                Matcher matcher = REGISTRATION.matcher(source);
                while (matcher.find()) names.add(matcher.group(1));
            });
        }
        return names;
    }

    private static boolean isClientOnly(byte[] bytes) {
        boolean[] clientOnly = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!"Lnet/minecraftforge/api/distmarker/OnlyIn;".equals(descriptor)) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitEnum(String name, String enumDescriptor, String value) {
                        if ("value".equals(name) && "CLIENT".equals(value)) clientOnly[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return clientOnly[0];
    }

    private static Set<String> referencedTypes(byte[] bytes) {
        Set<String> references = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                addInternalName(references, superName);
                if (interfaces != null) {
                    for (String type : interfaces) addInternalName(references, type);
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                addType(references, Type.getType(descriptor));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                addMethodDescriptor(references, descriptor);
                if (exceptions != null) {
                    for (String type : exceptions) addInternalName(references, type);
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        addInternalName(references, type);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        addInternalName(references, owner);
                        addType(references, Type.getType(fieldDescriptor));
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        addInternalName(references, owner);
                        addMethodDescriptor(references, methodDescriptor);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        addMethodDescriptor(references, dynamicDescriptor);
                        addHandle(references, bootstrapMethodHandle);
                        for (Object argument : bootstrapMethodArguments) {
                            if (argument instanceof Type type) addType(references, type);
                            else if (argument instanceof Handle handle) addHandle(references, handle);
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type type) addType(references, type);
                        else if (value instanceof Handle handle) addHandle(references, handle);
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                        addType(references, Type.getType(descriptor));
                    }

                    @Override
                    public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                                   org.objectweb.asm.Label end,
                                                   org.objectweb.asm.Label handler,
                                                   String type) {
                        addInternalName(references, type);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return references;
    }

    private static void addMethodDescriptor(Set<String> references, String descriptor) {
        for (Type type : Type.getArgumentTypes(descriptor)) addType(references, type);
        addType(references, Type.getReturnType(descriptor));
    }

    private static void addHandle(Set<String> references, Handle handle) {
        addInternalName(references, handle.getOwner());
        if (handle.getDesc().startsWith("(")) addMethodDescriptor(references, handle.getDesc());
        else addType(references, Type.getType(handle.getDesc()));
    }

    private static void addType(Set<String> references, Type type) {
        if (type.getSort() == Type.ARRAY) addType(references, type.getElementType());
        else if (type.getSort() == Type.OBJECT) references.add(type.getInternalName());
        else if (type.getSort() == Type.METHOD) addMethodDescriptor(references, type.getDescriptor());
    }

    private static void addInternalName(Set<String> references, String internalName) {
        if (internalName != null) references.add(internalName);
    }

    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String name = slash >= 0 ? internalName.substring(slash + 1) : internalName;
        int dollar = name.indexOf('$');
        return dollar >= 0 ? name.substring(0, dollar) : name;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
