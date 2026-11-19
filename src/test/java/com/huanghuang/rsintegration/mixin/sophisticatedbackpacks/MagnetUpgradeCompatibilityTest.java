package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MagnetUpgradeCompatibilityTest {
    private static final String TARGET =
            "net/p3pp3rf1y/sophisticatedcore/upgrades/magnet/MagnetUpgradeWrapper.class";
    private static final String MIXIN =
            "com/huanghuang/rsintegration/mixin/sophisticatedbackpacks/MagnetUpgradeWrapperMixin.class";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
    private static final String CALLBACK =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private static final String WRAP_OPERATION =
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String DRAGON_EGG_COLLECTOR =
            "com/huanghuang/rsintegration/mods/sophisticatedbackpacks/RSMagnetDragonEggCollector";

    @Test
    void legacyCoreHasMatchingMagnetHooks() throws Exception {
        verifyCallbacks(Path.of("libs/sophisticatedcore-1.20.1-1.2.55.975.jar"), 1);
    }

    @Test
    void installedCoreHasMatchingMagnetHooks() throws Exception {
        String configured = System.getProperty("rsintegration.sophisticatedcore.compatibilityJar");
        assumeTrue(configured != null && !configured.isBlank());
        verifyCallbacks(Path.of(configured), 0);
    }

    @Test
    void insertionContractAcceptsOnlyTheTwoSupportedCompleteDescriptors() {
        assertEquals(1, supportedArgumentCount("(Lnet/minecraft/world/entity/item/ItemEntity;)Z"));
        assertEquals(2, supportedArgumentCount("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/item/ItemEntity;)Z"));
        assertThrows(AssertionError.class, () -> supportedArgumentCount("(Lnet/minecraft/world/entity/item/ItemEntity;)V"));
        assertThrows(AssertionError.class, () -> supportedArgumentCount("(Ljava/lang/Object;)Z"));
        assertThrows(AssertionError.class, () -> supportedArgumentCount("(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/entity/player/Player;)Z"));
    }

    private static int supportedArgumentCount(String descriptor) {
        assertTrue(List.of("(Lnet/minecraft/world/entity/item/ItemEntity;)Z",
                "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/item/ItemEntity;)Z")
                .contains(descriptor), "不支持的完整方法描述符: " + descriptor);
        return Type.getArgumentTypes(descriptor).length;
    }

    private static void verifyCallbacks(Path jarPath, int argumentCount) throws Exception {
        ClassNode target;
        try (ZipFile archive = new ZipFile(jarPath.toFile())) {
            assertNotNull(archive.getEntry(TARGET), jarPath.toString());
            try (InputStream input = archive.getInputStream(archive.getEntry(TARGET))) {
                target = read(input);
            }
        }
        List<MethodNode> insertions = target.methods.stream()
                .filter(method -> method.name.equals("tryToInsertItem")).toList();
        assertEquals(1, insertions.size());
        MethodNode insertion = insertions.get(0);
        int actualArguments = supportedArgumentCount(insertion.desc);
        if (argumentCount != 0) assertEquals(argumentCount, actualArguments);
        String callbackDescriptor = insertion.desc.substring(0, insertion.desc.indexOf(')'))
                + CALLBACK + ")V";
        ClassNode mixin;
        try (InputStream input = MagnetUpgradeCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(MIXIN)) {
            assertNotNull(input);
            mixin = read(input);
        }
        for (String site : List.of("HEAD", "RETURN")) {
            List<MethodNode> matching = new ArrayList<>();
            for (MethodNode handler : mixin.methods) {
                AnnotationNode inject = annotation(handler, INJECT);
                if (inject == null) continue;
                List<?> selectors = (List<?>) value(inject, "method");
                List<?> locations = (List<?>) value(inject, "at");
                if (!selectors.contains(insertion.name + insertion.desc)
                        || locations.stream().map(AnnotationNode.class::cast)
                        .noneMatch(at -> site.equals(value(at, "value")))) continue;
                matching.add(handler);
                // 在真实模组字节码上验证整个回调签名，包含新版新增的 Player 参数。
                assertEquals(callbackDescriptor, handler.desc, jarPath + ": " + handler.name);
                assertEquals(false, value(inject, "remap"));
                assertEquals(0, value(inject, "require"));
                if (site.equals("HEAD")) assertEquals(true, value(inject, "cancellable"));
                AnnotationNode group = annotation(handler, GROUP);
                assertNotNull(group, "必须保证至少有一种版本的回调生效");
                assertEquals(1, value(group, "min"));
                if (site.equals("HEAD")) assertEquals(1, value(group, "max"));
            }
            assertEquals(1, matching.size(), jarPath + ": " + site);
        }
        verifySharedPickupHook(target, mixin);
    }

    private static void verifySharedPickupHook(ClassNode target, ClassNode mixin) {
        MethodNode hook = mixin.methods.stream()
                .filter(method -> annotation(method, WRAP_OPERATION) != null)
                .findFirst().orElseThrow();
        AnnotationNode wrap = annotation(hook, WRAP_OPERATION);
        List<?> methods = (List<?>) value(wrap, "method");
        List<?> locations = (List<?>) value(wrap, "at");
        List<Object> wrappedCalls = locations.stream().map(AnnotationNode.class::cast)
                .map(at -> value(at, "target")).toList();
        long matchingCalls = target.methods.stream()
                .filter(method -> methods.contains(method.name))
                .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && wrappedCalls.contains("L" + call.owner + ";" + call.name + call.desc))
                .count();
        assertEquals(1, matchingCalls, "龙蛋转换需要接在共用的物品吸取调用上");

        assertTrue(Arrays.stream(hook.instructions.toArray())
                .anyMatch(instruction -> instruction instanceof MethodInsnNode call
                        && DRAGON_EGG_COLLECTOR.equals(call.owner) && "collect".equals(call.name)),
                "共用入口必须转换龙蛋");
    }

    private static ClassNode read(InputStream input) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations.stream().filter(annotation -> descriptor.equals(annotation.desc))
                .findFirst().orElse(null);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return null;
    }
}
