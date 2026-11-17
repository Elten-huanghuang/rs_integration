package com.huanghuang.rsintegration.feature.transmutation;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import com.huanghuang.rsintegration.testutil.CompatibilityTestJars;
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
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直接核对用户安装的 1.22.9 发行版，不在单测 JVM 中初始化可选模组。 */
class TransmutationTableCompatibilityTest {
    private static final String NATIVE_ROOT = "com/github/alexthe666/alexsmobs/";
    private static final String MIXIN_ROOT = "com/huanghuang/rsintegration/mixin/alexsmobs/";
    private static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;";

    @Test
    void nativeMenuExposesConstructorAndOriginalTransmutationEntry() throws Exception {
        try (JarFile jar = nativeJar()) {
            ClassNode menu = nativeClass(jar, "inventory/MenuTransmutationTable");
            method(menu, "<init>", "(ILnet/minecraft/world/entity/player/Inventory;"
                    + "Lnet/minecraft/world/inventory/ContainerLevelAccess;" + PLAYER
                    + "L" + NATIVE_ROOT + "tileentity/TileEntityTransmutationTable;)V");
            method(menu, "m_6366_", "(" + PLAYER + "I)Z");
            MethodNode transmute = method(menu, "transmute", "(" + PLAYER + "I)V");
            assertTrue(calls(transmute, NATIVE_ROOT + "tileentity/TileEntityTransmutationTable",
                    "postTransmute"));
            assertTrue(calls(transmute, "net/minecraft/world/entity/player/Player", "m_6749_"));
            assertTrue(menu.fields.stream().anyMatch(field -> field.name.equals("table")
                    && field.desc.equals("L" + NATIVE_ROOT + "tileentity/TileEntityTransmutationTable;")));
        }
    }

    @Test
    void nativeTickRerollsAndSynchronizesAllThreeOptions() throws Exception {
        try (JarFile jar = nativeJar()) {
            ClassNode tile = nativeClass(jar, "tileentity/TileEntityTransmutationTable");
            assertTrue(tile.fields.stream().anyMatch(field ->
                    field.name.equals("rerollPlayerUUID") && field.desc.equals("Ljava/util/UUID;")));
            method(tile, "getPossibility", "(I)Lnet/minecraft/world/item/ItemStack;");
            assertTrue(calls(method(tile, "tick", "()V"),
                    NATIVE_ROOT + "tileentity/TileEntityTransmutationTable", "randomizeResults"));
            assertTrue(calls(method(tile, "randomizeResults", "(" + PLAYER + ")V"),
                    NATIVE_ROOT + "AlexsMobs", "sendMSGToAll"));
        }
    }

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
        assertTrue(injection.values.contains(true));
        var refmap = JsonParser.parseString(Files.readString(Path.of(
                "build/tmp/compileJava/compileJava-refmap.json"))).getAsJsonObject();
        assertEquals("L" + NATIVE_ROOT + "inventory/MenuTransmutationTable;"
                        + "m_6366_(" + PLAYER + "I)Z",
                refmap.getAsJsonObject("mappings")
                        .getAsJsonObject(MIXIN_ROOT + "TransmutationTableMenuMixin")
                        .get("clickMenuButton").getAsString());
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

    private static JarFile nativeJar() throws Exception {
        return new JarFile(CompatibilityTestJars.requireMatching(".*alexsmobs-1\\.22\\.9\\.jar").toFile());
    }

    private static ClassNode nativeClass(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(NATIVE_ROOT + name + ".class");
        assertNotNull(entry);
        try (InputStream input = jar.getInputStream(entry)) {
            return read(input);
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
