package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class MalumAltarCaptureCompatibilityTest {
    private static final String HELPER = "com/sammy/malum/common/block/curiosities/spirit_altar/AltarCraftingHelper";
    private static final String ALTAR = "com/sammy/malum/common/block/curiosities/spirit_altar/SpiritAltarBlockEntity";
    private static final String MIXIN = "com/huanghuang/rsintegration/mixin/malum/AltarCraftingHelperMixin";
    private static final String PARAMETERS = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;";
    private static final String DEFAULT_CAPTURE = PARAMETERS + ")Ljava/util/List;";
    private static final String RANGED_CAPTURE = PARAMETERS + "III)Ljava/util/List;";

    @Test
    void bothInjectionCallbacksMatchTheActualMalumJar() throws Exception {
        try (ZipFile jar = new ZipFile(Path.of("libs/malum-1.20.1-1.6.6.jar").toFile());
             InputStream input = getClass().getClassLoader().getResourceAsStream(MIXIN + ".class")) {
            assertNotNull(input);
            ClassNode helper = read(jar.getInputStream(jar.getEntry(HELPER + ".class")));
            ClassNode mixin = read(input);
            int callbacks = 0;
            for (MethodNode method : mixin.methods) {
                AnnotationNode inject = annotations(method).stream()
                        .filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElse(null);
                if (inject == null) continue;
                callbacks++;
                List<?> selectors = (List<?>) value(inject, "method");
                assertEquals(1, selectors.size());
                String selector = (String) selectors.get(0);
                MethodNode target = helper.methods.stream()
                        .filter(candidate -> selector.equals(candidate.name + candidate.desc))
                        .findFirst().orElseThrow();
                String callback = target.desc.substring(0, target.desc.indexOf(')'))
                        + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
                assertEquals(callback, method.desc);
                assertEquals(1, value(inject, "require"));
                assertEquals(true, value(inject, "cancellable"));
                assertTrue((method.access & Opcodes.ACC_PRIVATE) != 0);
                assertTrue((method.access & Opcodes.ACC_STATIC) != 0);
                List<?> locations = (List<?>) value(inject, "at");
                assertEquals(target.desc.equals(DEFAULT_CAPTURE) ? "HEAD" : "RETURN",
                        value((AnnotationNode) locations.get(0), "value"));
            }
            assertEquals(2, callbacks);
            MethodNode defaultCapture = helper.methods.stream()
                    .filter(method -> method.name.equals("capturePedestals") && method.desc.equals(DEFAULT_CAPTURE))
                    .findFirst().orElseThrow();
            assertTrue(calls(defaultCapture, HELPER, "capturePedestals", RANGED_CAPTURE));
            ClassNode altar = read(jar.getInputStream(jar.getEntry(ALTAR + ".class")));
            for (String name : List.of("recalculateRecipes", "consume")) {
                MethodNode method = altar.methods.stream().filter(candidate -> candidate.name.equals(name))
                        .findFirst().orElseThrow();
                assertTrue(calls(method, HELPER, "capturePedestals", DEFAULT_CAPTURE), name);
            }
        }
    }

    @Test
    void optionalIntegrationIsSkippedWhenTheTargetIsAbsent() {
        assertFalse(new RSIntegrationMixinPlugin().shouldApplyMixin(
                "absent.malum.AltarCraftingHelper", MIXIN.replace('/', '.')));
    }

    private static boolean calls(MethodNode method, String owner, String name, String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private static ClassNode read(InputStream input) throws Exception {
        try (input) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return null;
    }
}
