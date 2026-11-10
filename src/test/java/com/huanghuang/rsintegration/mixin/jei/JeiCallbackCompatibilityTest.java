package com.huanghuang.rsintegration.mixin.jei;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用发行版实际字节码检查回调，防止 JEI 优化后覆写 this 或方法参数。 */
class JeiCallbackCompatibilityTest {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String WRAP_METHOD = "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final Type OPERATION = Type.getObjectType(
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation");

    @Test
    void callbacksKeepValidReceiversAndArgumentsAtTheirInjectionSites() throws Exception {
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("libs"))) {
            files.filter(path -> path.getFileName().toString()
                            .matches("jei-1\\.20\\.1-forge-.*\\.jar"))
                    .sorted().forEach(jars::add);
        }
        String configured = System.getProperty("rsintegration.jei.compatibilityJar");
        if (configured != null && !configured.isBlank()) jars.add(Path.of(configured));
        assertFalse(jars.isEmpty(), "至少需要一个 JEI 发行 jar 校验回调");

        List<String> errors = new ArrayList<>();
        int checked = 0;
        for (Path path : jars) {
            try (JarFile jar = new JarFile(path.toFile())) {
                for (ClassNode mixin : jeiMixins()) {
                    // 与插件的守卫保持一致：新版 JEI 已移除旧输入处理器，Tetra 排序混入不适用。
                    if (mixin.name.endsWith("/IngredientListOverlayTetraSortMixin")
                            && jar.getJarEntry("mezz/jei/gui/input/handlers/CombinedInputHandler.class") == null) continue;
                    AnnotationNode annotation = annotation(mixin.invisibleAnnotations, MIXIN);
                    if (annotation == null) annotation = annotation(mixin.visibleAnnotations, MIXIN);
                    List<String> targets = new ArrayList<>();
                    for (Type type : JeiCallbackCompatibilityTest.<Type>list(annotation, "value")) {
                        targets.add(type.getInternalName());
                    }
                    for (String target : JeiCallbackCompatibilityTest.<String>list(annotation, "targets")) {
                        targets.add(target.replace('.', '/'));
                    }
                    for (String targetName : targets) {
                        var entry = jar.getJarEntry(targetName + ".class");
                        if (entry == null) continue;
                        ClassNode target;
                        try (InputStream input = jar.getInputStream(entry)) {
                            target = read(input);
                        }
                        // 新旧 BookmarkOverlay 混入共用目标类名，按 @Shadow 字段类型选择适用版本。
                        if (!matchesShadowFields(mixin, target)) continue;
                        for (MethodNode callback : mixin.methods) {
                            AnnotationNode inject = annotation(callback.visibleAnnotations, INJECT);
                            AnnotationNode wrapper = annotation(callback.visibleAnnotations, WRAP_METHOD);
                            if (wrapper != null) {
                                checked += checkWrapper(path, mixin, callback, wrapper, target);
                            }
                            if (inject == null) continue;
                            for (MethodNode method : target.methods) {
                                if (!list(inject, "method").contains(method.name)
                                        && !list(inject, "method").contains(method.name + method.desc)) continue;
                                Frame<BasicValue>[] frames = new Analyzer<>(new TypedInterpreter())
                                        .analyze(target.name, method);
                                for (AnnotationNode at : JeiCallbackCompatibilityTest.<AnnotationNode>list(inject, "at")) {
                                    for (int index : injectionSites(method, at)) {
                                        Frame<BasicValue> frame = frames[index];
                                        if (frame == null) continue;
                                        checked++;
                                        String context = path.getFileName() + " " + mixin.name
                                                + "." + callback.name + " -> " + target.name + "." + method.name;
                                        int local = 0;
                                        if ((callback.access & Opcodes.ACC_STATIC) == 0) {
                                            checkLocal(frame, local++, Type.getObjectType(target.name), context, errors);
                                        } else if ((method.access & Opcodes.ACC_STATIC) == 0) {
                                            local++;
                                        }
                                        for (Type argument : Type.getArgumentTypes(callback.desc)) {
                                            if (argument.getDescriptor().startsWith(
                                                    "Lorg/spongepowered/asm/mixin/injection/callback/")) break;
                                            checkLocal(frame, local, argument, context, errors);
                                            local += argument.getSize();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked > 0, "没有找到可验证的 JEI 回调");
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    private static boolean matchesShadowFields(ClassNode mixin, ClassNode target) {
        return mixin.fields.stream()
                .filter(field -> annotation(field.visibleAnnotations, SHADOW) != null)
                .allMatch(field -> target.fields.stream().anyMatch(candidate ->
                        field.name.equals(candidate.name) && field.desc.equals(candidate.desc)));
    }

    private static int checkWrapper(Path path, ClassNode mixin, MethodNode wrapper,
                                    AnnotationNode annotation, ClassNode target) {
        int checked = 0;
        for (String selector : JeiCallbackCompatibilityTest.<String>list(annotation, "method")) {
            List<MethodNode> methods = target.methods.stream()
                    .filter(method -> selector.equals(method.name)
                            || selector.equals(method.name + method.desc)).toList();
            if (!Integer.valueOf(0).equals(value(annotation, "require"))) {
                assertFalse(methods.isEmpty(), path + " 缺少被包装方法 " + selector);
            }
            for (MethodNode method : methods) {
                String context = path + " " + mixin.name + "." + wrapper.name;
                List<Type> arguments = new ArrayList<>(List.of(Type.getArgumentTypes(method.desc)));
                arguments.add(OPERATION);
                assertTrue(arguments.equals(List.of(Type.getArgumentTypes(wrapper.desc))),
                        context + " 的包装参数与原方法不匹配");
                assertTrue(Type.getReturnType(method.desc).equals(Type.getReturnType(wrapper.desc)),
                        context + " 的返回类型与原方法不匹配");
                assertTrue((method.access & Opcodes.ACC_STATIC) == (wrapper.access & Opcodes.ACC_STATIC),
                        context + " 的 static 修饰与原方法不匹配");
                checked++;
            }
        }
        return checked;
    }

    private static void checkLocal(Frame<BasicValue> frame, int local, Type expected,
                                   String context, List<String> errors) {
        BasicValue actual = frame.getLocal(local);
        BasicValue required = new TypedInterpreter().newValue(expected);
        if (!required.equals(actual)) {
            errors.add(context + " local[" + local + "] 需要 " + expected + "，实际 " + actual);
        }
    }

    private static List<Integer> injectionSites(MethodNode method, AnnotationNode at) {
        String kind = (String) value(at, "value");
        List<Integer> sites = new ArrayList<>();
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            int opcode = instruction.getOpcode();
            boolean matches = switch (kind) {
                case "HEAD" -> opcode >= 0 && sites.isEmpty();
                case "TAIL", "RETURN" -> opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
                case "FIELD" -> instruction instanceof FieldInsnNode field
                        && ("L" + field.owner + ";" + field.name + ":" + field.desc).equals(value(at, "target"));
                case "INVOKE" -> instruction instanceof MethodInsnNode call
                        && ("L" + call.owner + ";" + call.name + call.desc).equals(value(at, "target"));
                default -> throw new IllegalStateException("未支持的注入位置：" + kind);
            };
            if (matches) sites.add(i);
            if (matches && "HEAD".equals(kind)) break;
        }
        if ("TAIL".equals(kind) && !sites.isEmpty()) return List.of(sites.get(sites.size() - 1));
        Integer ordinal = (Integer) value(at, "ordinal");
        if (ordinal != null && ordinal >= 0) {
            sites = ordinal < sites.size() ? List.of(sites.get(ordinal)) : List.of();
        }
        String[] shift = (String[]) value(at, "shift");
        if (shift != null) {
            assertTrue("AFTER".equals(shift[1]), "仅支持默认位置或 AFTER");
            sites = sites.stream().map(index -> index + 1).toList();
        }
        return sites;
    }

    private static List<ClassNode> jeiMixins() throws Exception {
        List<ClassNode> result = new ArrayList<>();
        ClassLoader loader = JeiCallbackCompatibilityTest.class.getClassLoader();
        try (var reader = new InputStreamReader(loader.getResourceAsStream("rs_integration.mixins.json"),
                StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            for (var name : config.getAsJsonArray("client")) {
                if (!name.getAsString().startsWith("jei.")) continue;
                String resource = "com/huanghuang/rsintegration/mixin/"
                        + name.getAsString().replace('.', '/') + ".class";
                try (InputStream input = loader.getResourceAsStream(resource)) {
                    result.add(read(input));
                }
            }
        }
        return result;
    }

    private static ClassNode read(InputStream input) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG);
        return node;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) return null;
        return annotations.stream().filter(annotation -> descriptor.equals(annotation.desc))
                .findFirst().orElse(null);
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(AnnotationNode annotation, String key) {
        Object value = value(annotation, key);
        return value == null ? List.of() : (List<T>) value;
    }

    private static final class TypedInterpreter extends BasicInterpreter {
        private TypedInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public BasicValue newValue(Type type) {
            if (type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
                return new BasicValue(type);
            }
            return super.newValue(type);
        }
    }
}
