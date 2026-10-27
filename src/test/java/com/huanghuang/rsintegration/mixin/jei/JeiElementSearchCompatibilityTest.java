package com.huanghuang.rsintegration.mixin.jei;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 校验 SolCarrot 搜索混入只依赖 JEI 的 IElementSearch 契约。
 *
 * <p>JEI 15.20.0.106 没有 allElements 字段，15.20.0.112 是 Set，
 * 15.20.0.129 才是 Map；按字段名做 @Shadow 会在中间版本上抛
 * InvalidMixinException 直接崩客户端。因此这里对 libs 下的每个 JEI
 * 发行 jar 断言接口方法仍然存在，并且混入本身不再声明任何字段。</p>
 */
class JeiElementSearchCompatibilityTest {

    private static final String TOKEN_INFO =
            "Lmezz/jei/gui/search/ElementPrefixParser$TokenInfo;";
    private static final String SEARCH_RESULTS = "(" + TOKEN_INFO + ")Ljava/util/Set;";
    private static final String GET_ALL_INGREDIENTS = "()Ljava/util/Collection;";
    private static final String I_ELEMENT_SEARCH = "mezz/jei/gui/search/IElementSearch";
    private static final String INJECTOR_DESCRIPTOR = "(" + TOKEN_INFO
            + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
    private static final String INJECTOR_NAME = "rsi$searchCurrentSolCarrotStatus";

    @Test
    void solCarrotMixinsDoNotShadowInternalFields() throws Exception {
        assertMixinShape(ElementSearchSolCarrotMixin.class);
        assertMixinShape(ElementSearchLowMemSolCarrotMixin.class);
    }

    @Test
    void jeiJarsExposeTheElementSearchContract() throws Exception {
        List<Path> jars = jeiJars();
        assumeTrue(!jars.isEmpty(), "libs 下没有 JEI 发行 jar，跳过契约校验");

        for (Path jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                ClassShape search = optionalShape(
                        jar, "mezz/jei/gui/search/ElementSearch.class");
                if (search == null) continue;
                assertContract(jarPath, search, "ElementSearch");

                ClassShape lowMem = optionalShape(
                        jar, "mezz/jei/gui/search/ElementSearchLowMem.class");
                if (lowMem != null) assertContract(jarPath, lowMem, "ElementSearchLowMem");

                ClassShape iface = optionalShape(jar, I_ELEMENT_SEARCH + ".class");
                assertTrue(iface != null, jarPath + " 缺少 " + I_ELEMENT_SEARCH);
                assertTrue(iface.methodDescriptors()
                                .getOrDefault("getAllIngredients", Set.of())
                                .contains(GET_ALL_INGREDIENTS),
                        jarPath + " 的 IElementSearch 缺少 getAllIngredients()");
                assertTrue(iface.methodDescriptors()
                                .getOrDefault("getSearchResults", Set.of())
                                .contains(SEARCH_RESULTS),
                        jarPath + " 的 IElementSearch 缺少 getSearchResults(TokenInfo)");
            }
        }
    }

    private static void assertMixinShape(Class<?> mixinClass) throws Exception {
        ClassShape shape = mixinShape(mixinClass);
        assertEquals(Set.of(), shape.fields(),
                mixinClass.getSimpleName() + " 不应再 @Shadow 任何 JEI 内部字段");
        assertEquals(Set.of(INJECTOR_DESCRIPTOR),
                shape.methodDescriptors().get(INJECTOR_NAME),
                mixinClass.getSimpleName() + " 的注入方法签名与 getSearchResults 不匹配");
    }

    private static void assertContract(Path jarPath, ClassShape shape, String entryName) {
        assertTrue(shape.interfaces().contains(I_ELEMENT_SEARCH),
                jarPath + " 的 " + entryName + " 未实现 IElementSearch");
        assertTrue(shape.methodDescriptors().getOrDefault("getSearchResults", Set.of())
                        .contains(SEARCH_RESULTS),
                jarPath + " 的 " + entryName + " 缺少 getSearchResults(TokenInfo)");
        assertTrue(shape.methodDescriptors().getOrDefault("getAllIngredients", Set.of())
                        .contains(GET_ALL_INGREDIENTS),
                jarPath + " 的 " + entryName + " 缺少 getAllIngredients()");
    }

    private static List<Path> jeiJars() throws Exception {
        Path libs = Path.of("libs");
        if (!Files.isDirectory(libs)) return List.of();
        try (Stream<Path> entries = Files.list(libs)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.contains("jei-1.20.1-forge-") && name.endsWith(".jar");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static ClassShape mixinShape(Class<?> mixinClass) throws Exception {
        String resource = mixinClass.getName().replace('.', '/') + ".class";
        try (InputStream input = mixinClass.getClassLoader().getResourceAsStream(resource)) {
            return shape(input);
        }
    }

    private static ClassShape optionalShape(JarFile jar, String entryName) throws Exception {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) return null;
        try (InputStream input = jar.getInputStream(entry)) {
            return shape(input);
        }
    }

    private static ClassShape shape(InputStream input) throws Exception {
        Set<String> fields = new HashSet<>();
        Set<String> methods = new HashSet<>();
        Set<String> interfaces = new HashSet<>();
        Map<String, Set<String>> methodDescriptors = new HashMap<>();
        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] classInterfaces) {
                if (classInterfaces != null) interfaces.addAll(List.of(classInterfaces));
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                fields.add(name);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.add(name);
                methodDescriptors.computeIfAbsent(name, ignored -> new HashSet<>())
                        .add(descriptor);
                return null;
            }
        }, 0);
        return new ClassShape(fields, methods, interfaces, methodDescriptors);
    }

    private record ClassShape(Set<String> fields, Set<String> methods,
                              Set<String> interfaces,
                              Map<String, Set<String>> methodDescriptors) {}
}
