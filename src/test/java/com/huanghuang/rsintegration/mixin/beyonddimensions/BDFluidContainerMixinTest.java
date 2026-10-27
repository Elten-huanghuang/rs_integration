package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验超越维度流体容器混入的版本隔离与注入点。
 *
 * <p>第四个参数在 0.7.x 是 boolean shift，0.8 起改成 GLFW 修饰键掩码。两套逻辑分别写在
 * {@link BDFluidContainerMixin}（旧版）与 {@link BDFluidContainerModifiersMixin}（新版）里，
 * 由 {@code RSIntegrationMixinPlugin} 按版本启用其中一个，因此这里要同时确认：</p>
 * <ul>
 *   <li>两套混入各自只声明自己那一版的描述符；</li>
 *   <li>插件在同一次运行里只放行其中一个；</li>
 *   <li>目标类里每个 4 参 {@code customClickHandler} 都有对应的注入点。</li>
 * </ul>
 */
class BDFluidContainerMixinTest {

    private static final String TARGET_RESOURCE =
            "com/wintercogs/beyonddimensions/common/menu/BDBaseMenu.class";
    private static final String TARGET_CLASS =
            "com.wintercogs.beyonddimensions.common.menu.BDBaseMenu";
    private static final String KEY_AMOUNT =
            "Lcom/wintercogs/beyonddimensions/api/storage/key/KeyAmount;";
    private static final String CALLBACK_INFO =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String SHIFT_SELECTOR = "customClickHandler(I" + KEY_AMOUNT + "IZ)V";
    private static final String MODIFIERS_SELECTOR = "customClickHandler(I" + KEY_AMOUNT + "II)V";
    /** 0.8 新增的公共交互 API，插件用它区分新旧终端版本。 */
    private static final String MODERN_MARKER =
            "com/wintercogs/beyonddimensions/api/menu/interaction/SlotInteractions.class";
    /** 容器点击重载固定是 slot、KeyAmount、button 加第四个状态参数。 */
    private static final int CLICK_ARG_COUNT = 4;

    @Test
    void legacyMixinOnlyPinsTheBooleanClickShape() throws Exception {
        assertEquals(Set.of(SHIFT_SELECTOR), selectorsOf(BDFluidContainerMixin.class),
                "旧版混入只应接管 0.7.x 的 boolean shift 描述符");
    }

    @Test
    void modernMixinOnlyPinsTheModifiersClickShape() throws Exception {
        assertEquals(Set.of(MODIFIERS_SELECTOR), selectorsOf(BDFluidContainerModifiersMixin.class),
                "新版混入只应接管 0.8 起的修饰键掩码描述符");
    }

    @Test
    void bothMixinsUseOptionalInjections() throws Exception {
        for (Class<?> mixinType : List.of(BDFluidContainerMixin.class,
                BDFluidContainerModifiersMixin.class)) {
            for (MethodNode method : mixinMethods(mixinType)) {
                String selector = injectSelector(method);
                if (selector == null) continue;
                assertEquals(0, injectRequire(method),
                        method.name + " 必须 require = 0，否则未知版本会直接崩客户端");
                assertHandlerMatchesSelector(method, selector);
            }
        }
    }

    @Test
    void everyClickOverloadOnTheClasspathHasASelector() throws Exception {
        Set<String> selectors = new HashSet<>(selectorsOf(BDFluidContainerMixin.class));
        selectors.addAll(selectorsOf(BDFluidContainerModifiersMixin.class));

        int checked = 0;
        for (String shape : targetClickShapes()) {
            checked++;
            assertTrue(selectors.contains(shape), "BDBaseMenu 的 " + shape + " 没有对应注入点");
        }
        for (Path jarPath : beyondDimensionsJars()) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                JarEntry entry = jar.getJarEntry(TARGET_RESOURCE);
                if (entry == null) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    for (String shape : clickShapes(input)) {
                        checked++;
                        assertTrue(selectors.contains(shape),
                                jarPath.getFileName() + " 的 " + shape + " 没有对应注入点");
                    }
                }
            }
        }
        assertTrue(checked > 0, "没有找到任何 BDBaseMenu#customClickHandler 形状");
    }

    @Test
    void pluginEnablesExactlyOneMixinPerVersion() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();
        boolean modern = testClassLoader().getResource(MODERN_MARKER) != null;

        assertEquals(modern, plugin.shouldApplyMixin(TARGET_CLASS,
                        BDFluidContainerModifiersMixin.class.getName()),
                "新版混入应只在 0.8 及以上启用");
        assertEquals(!modern, plugin.shouldApplyMixin(TARGET_CLASS,
                        BDFluidContainerMixin.class.getName()),
                "旧版混入应只在 0.8 以下启用");

        assertFalse(plugin.shouldApplyMixin(
                        "com.wintercogs.beyonddimensions.common.menu.AbsentMenu",
                        BDFluidContainerModifiersMixin.class.getName()),
                "目标类不存在时新版混入必须拒绝");
        assertFalse(plugin.shouldApplyMixin(
                        "com.wintercogs.beyonddimensions.common.menu.AbsentMenu",
                        BDFluidContainerMixin.class.getName()),
                "目标类不存在时旧版混入必须拒绝");
    }

    private static Set<String> selectorsOf(Class<?> mixinType) throws Exception {
        Set<String> selectors = new HashSet<>();
        for (MethodNode method : mixinMethods(mixinType)) {
            String selector = injectSelector(method);
            if (selector != null) selectors.add(selector);
        }
        return selectors;
    }

    private static List<MethodNode> mixinMethods(Class<?> mixinType) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = mixinClassResource(mixinType)) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        return node.methods;
    }

    private static InputStream mixinClassResource(Class<?> mixinType) {
        String resource = mixinType.getName().replace('.', '/') + ".class";
        return testClassLoader().getResourceAsStream(resource);
    }

    /**
     * 主类和测试运行时分属不同类加载器，这里统一走测试侧的加载器，
     * 才能同时看到混入类与被测模组的目标类。
     */
    private static ClassLoader testClassLoader() {
        return BDFluidContainerMixinTest.class.getClassLoader();
    }

    private static String injectSelector(MethodNode method) {
        AnnotationNode inject = annotation(method);
        if (inject == null) return null;
        Object raw = value(inject, "method");
        // @Inject#method 是 String[]，单个选择器在字节码里也是长度 1 的列表。
        if (raw instanceof String selector) return selector;
        List<?> selectors = (List<?>) raw;
        assertEquals(1, selectors.size(), method.name + " 应只声明一个注入点选择器");
        return (String) selectors.get(0);
    }

    private static int injectRequire(MethodNode method) {
        AnnotationNode inject = annotation(method);
        if (inject == null) return -1;
        Object require = value(inject, "require");
        return require == null ? -1 : (Integer) require;
    }

    private static AnnotationNode annotation(MethodNode method) {
        AnnotationNode found = findInject(method.invisibleAnnotations);
        return found != null ? found : findInject(method.visibleAnnotations);
    }

    private static AnnotationNode findInject(List<AnnotationNode> annotations) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(
                    "Lorg/spongepowered/asm/mixin/injection/Inject;")) return annotation;
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    /**
     * 注入方法的描述符必须等于“注入点选择器的参数部分 + CallbackInfo”，其中
     * {@code @Coerce} 参数允许用 Object 代替目标类型。
     */
    private static void assertHandlerMatchesSelector(MethodNode method, String selector) {
        String targetDescriptor = selector.substring(selector.indexOf('('));

        List<String> targetArgs = argumentTypes(targetDescriptor);
        List<String> handlerArgs = argumentTypes(method.desc);
        assertEquals(targetArgs.size() + 1, handlerArgs.size(),
                method.name + " 的参数个数与 " + selector + " 不匹配");
        assertEquals(CALLBACK_INFO, handlerArgs.get(handlerArgs.size() - 1),
                method.name + " 的最后一个参数必须是 CallbackInfo");
        for (int i = 0; i < targetArgs.size(); i++) {
            String expected = targetArgs.get(i);
            String actual = handlerArgs.get(i);
            assertTrue(expected.equals(actual) || "Ljava/lang/Object;".equals(actual),
                    method.name + " 的第 " + (i + 1) + " 个参数应为 " + expected + "，实际为 " + actual);
        }
    }

    private static List<String> argumentTypes(String descriptor) {
        List<String> types = new ArrayList<>();
        for (Type type : Type.getArgumentTypes(descriptor)) types.add(type.getDescriptor());
        return types;
    }

    private static Set<String> targetClickShapes() throws Exception {
        Set<String> shapes = new HashSet<>();
        try (InputStream input = testClassLoader().getResourceAsStream(TARGET_RESOURCE)) {
            if (input != null) shapes.addAll(clickShapes(input));
        }
        return shapes;
    }

    private static Set<String> clickShapes(InputStream input) throws Exception {
        Set<String> shapes = new HashSet<>();
        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                // 4 参重载是容器点击（slot、KeyAmount、button 加 shift 或修饰键掩码），
                // 带 amount 的重载属于“取指定数量”通道，不需要注入点。
                if ("customClickHandler".equals(name)
                        && Type.getArgumentTypes(descriptor).length == CLICK_ARG_COUNT) {
                    shapes.add(name + descriptor);
                }
                return null;
            }
        }, 0);
        return shapes;
    }

    private static List<Path> beyondDimensionsJars() throws Exception {
        Path libs = Path.of("libs");
        if (!Files.isDirectory(libs)) return List.of();
        try (Stream<Path> entries = Files.list(libs)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("beyonddimensions-") && name.endsWith(".jar");
                    })
                    .toList();
        }
    }
}
