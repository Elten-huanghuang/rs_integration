package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void legacyCoreHasMatchingHeadAndReturnCallbacks() throws Exception {
        verifyCallbacks(Path.of("libs/sophisticatedcore-1.20.1-1.2.55.975.jar"), 1);
    }

    @Test
    void installedCoreHasMatchingHeadAndReturnCallbacks() throws Exception {
        String configured = System.getProperty("rsintegration.sophisticatedcore.compatibilityJar");
        assumeTrue(configured != null && !configured.isBlank());
        verifyCallbacks(Path.of(configured), 2);
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
        assertEquals(argumentCount, Type.getArgumentTypes(insertion.desc).length);
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
        assertTrue(target.methods.stream().anyMatch(method -> method.name.equals("pickupItems")
                && method.desc.equals("(Lnet/minecraft/world/entity/Entity;"
                + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I")),
                "磁铁的玩家上下文包装入口必须保持有效");
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
