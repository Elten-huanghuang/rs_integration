package com.huanghuang.rsintegration.feature.transmutation;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 检查可选模组缺席时 RSI 的嬗变台注入配置。 */
class TransmutationTableCompatibilityTest {
    private static final String MIXIN_ROOT = "com/huanghuang/rsintegration/mixin/alexsmobs/";
    private static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;";

    @Test
    void menuHookUsesVanillaMappingAndOnlyTheAutomaticButton() throws Exception {
        ClassNode mixin = compiledClass("TransmutationTableMenuMixin");
        MethodNode handler = method(mixin, "rsIntegration$autoTransmute",
                "(" + PLAYER + "ILorg/spongepowered/asm/mixin/injection/callback/"
                        + "CallbackInfoReturnable;)V");
        assertTrue(calls(handler, MIXIN_ROOT + "TransmutationTableMenuMixin", "transmute"));
        var injection = handler.visibleAnnotations.stream()
                .filter(annotation -> annotation.desc.endsWith("/Inject;"))
                .findFirst().orElseThrow();
        assertEquals(true, injection.values.get(injection.values.indexOf("cancellable") + 1));
        assertEquals(false, injection.values.get(injection.values.indexOf("remap") + 1));
        assertEquals(List.of("clickMenuButton(" + PLAYER + "I)Z", "m_6366_(" + PLAYER + "I)Z"),
                injection.values.get(injection.values.indexOf("method") + 1));
    }

    @Test
    void resultConfirmationRunsAfterNativeRandomization() throws Exception {
        MethodNode handler = method(compiledClass("TransmutationTableTileMixin"),
                "rsIntegration$confirmResults",
                "(" + PLAYER + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        var injection = handler.visibleAnnotations.stream()
                .filter(annotation -> annotation.desc.endsWith("/Inject;"))
                .findFirst().orElseThrow();
        assertTrue(injection.values.toString().contains("randomizeResults"));
        @SuppressWarnings("unchecked")
        List<AnnotationNode> points = (List<AnnotationNode>) injection.values.get(
                injection.values.indexOf("at") + 1);
        assertEquals("RETURN", points.get(0).values.get(points.get(0).values.indexOf("value") + 1));
        assertTrue(calls(handler,
                "com/huanghuang/rsintegration/feature/transmutation/TransmutationTableMenuAccess",
                "rsIntegration$onResultsChanged"));
    }

    @Test
    void bothMixinsAreCommonAndOptional() throws Exception {
        var config = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/rs_integration.mixins.json"))).getAsJsonObject();
        var plugin = new RSIntegrationMixinPlugin();
        for (String name : new String[]{"TransmutationTableMenuMixin", "TransmutationTableTileMixin"}) {
            String relative = "alexsmobs." + name;
            assertTrue(config.getAsJsonArray("mixins").asList().stream()
                    .anyMatch(value -> relative.equals(value.getAsString())));
            assertFalse(config.getAsJsonArray("client").asList().stream()
                    .anyMatch(value -> relative.equals(value.getAsString())));
            assertFalse(plugin.shouldApplyMixin("missing.alexsmobs.Target",
                    MIXIN_ROOT.replace('/', '.') + name));
        }
    }

    private static ClassNode compiledClass(String name) throws Exception {
        try (InputStream input = TransmutationTableCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(MIXIN_ROOT + name + ".class")) {
            assertNotNull(input);
            return read(input);
        }
    }

    private static ClassNode read(InputStream input) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(method -> name.equals(method.name)
                && descriptor.equals(method.desc)).findFirst().orElseThrow();
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        return Arrays.stream(method.instructions.toArray())
                .anyMatch(instruction -> instruction instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name));
    }
}
