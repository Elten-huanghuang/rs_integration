package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IronSpellConfigMixinContractTest {
    private static final String TARGET = "io.redspace.ironsspellbooks.api.config.SpellConfigManager";
    private static final String MIXIN = "com.huanghuang.rsintegration.mixin.ironsspellbooks.SpellConfigManagerMixin";

    @Test
    void registeredOnBothPhysicalSides() throws Exception {
        var config = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/rs_integration.mixins.json"))).getAsJsonObject();
        assertTrue(config.getAsJsonArray("mixins").asList().stream()
                .anyMatch(entry -> entry.getAsString().equals("ironsspellbooks.SpellConfigManagerMixin")));
    }

    @Test
    void skipsMissingOptionalMod() {
        assertFalse(new RSIntegrationMixinPlugin().shouldApplyMixin(TARGET, MIXIN));
    }

    @Test
    void hookRunsAtEveryNormalReturnWithoutDependingOnJeiOrClientClasses() throws Exception {
        ClassNode node;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(MIXIN.replace('.', '/') + ".class")) {
            assertNotNull(input);
            node = read(input);
        }
        MethodNode hook = node.methods.stream().filter(method -> method.name.startsWith("rsi$"))
                .findFirst().orElseThrow();
        AnnotationNode inject = hook.visibleAnnotations.stream()
                .filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("buildConfigManager(Ljava/util/Map;Z)Z"), value(inject, "method"));
        assertEquals(1, value(inject, "require"));
        AnnotationNode at = (AnnotationNode) ((List<?>) value(inject, "at")).get(0);
        assertEquals("RETURN", value(at, "value"));
        int resetCalls = 0;
        for (AbstractInsnNode instruction : hook.instructions) {
            assertFalse(instruction instanceof JumpInsnNode, "false may still publish usable config");
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(call.owner.contains("/client/") || call.owner.contains("/jei/"));
                if (call.name.equals("onSpellConfigApplied")) resetCalls++;
            }
        }
        assertEquals(1, resetCalls);
    }

    @Test
    void invalidatesRecipesAndPlanningAfterNativeReset() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/mods/"
                + "ironsspellbooks/IronSpellBooksRecipeCatalog.java"));
        String hook = source.substring(source.indexOf("public static void onSpellConfigApplied()"),
                source.indexOf("public static boolean hasRuntimeDrift()"));
        assertTrue(hook.indexOf("IronSpellRarityCache.resetAll") < hook.indexOf("catalog = null;"));
        assertTrue(hook.contains("catch (RuntimeException | LinkageError failure)"));
        assertTrue(hook.contains("without aborting config sync"));
        assertTrue(hook.indexOf("catch (RuntimeException | LinkageError failure)")
                < hook.indexOf("catalog = null;"));
        assertTrue(hook.contains("CraftPlanningRevision.bump()"));
        assertFalse(hook.contains("RecipeIndex.invalidate("), "avoid index -> catalog lock inversion");
        assertTrue(source.contains("\"fingerprint_rarity\", label, level,"));
        assertTrue(source.contains("() -> spell.getRarity(level).getValue()"));
    }

    @Test
    void serverTicksRetryInvalidatedInFlightGenerationWithoutRetryingFailures() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/"
                + "crafting/RecipeIndex.java"));
        int refreshStart = source.indexOf("void refreshDynamicRuntimeIfNeeded(Level level)");
        int warmUpStart = source.indexOf("void warmUp(Level level)");
        assertTrue(refreshStart >= 0, "未找到动态配方刷新方法");
        assertTrue(warmUpStart > refreshStart, "未找到刷新方法之后的预热方法");
        String refresh = source.substring(refreshStart, warmUpStart);
        assertTrue(refresh.contains("if (!isReady(level))"));
        assertTrue(refresh.contains("if (!generationBuildFailed()) warmUp(level);"));
        assertTrue(refresh.indexOf("if (!isReady(level))") < refresh.indexOf("hasRuntimeDrift()"),
                "clearing the catalog removes the fingerprint used by drift detection");
    }

    @Test
    void legacyCompileDependencyDoesNotHaveTheNewResetApi() throws Exception {
        try (ZipFile zip = new ZipFile("libs/irons_spellbooks-1.20.1-3.4.0.9.jar")) {
            ClassNode spell = read(zip.getInputStream(zip.getEntry(
                    "io/redspace/ironsspellbooks/api/spells/AbstractSpell.class")));
            assertFalse(spell.methods.stream().anyMatch(method -> method.name.equals("resetRarityWeights")
                    && method.desc.equals("()V")));
        }
    }

    @Test
    void realRuntimeJarHasExpectedConfigPublicationAndResetApi() throws Exception {
        String runtimeJar = System.getenv("RSI_IRON_COMPAT_JAR");
        assumeTrue(runtimeJar != null, "set RSI_IRON_COMPAT_JAR to verify an installed Iron build");
        try (ZipFile zip = new ZipFile(runtimeJar)) {
            ClassNode manager = read(zip.getInputStream(zip.getEntry(TARGET.replace('.', '/') + ".class")));
            MethodNode build = manager.methods.stream().filter(method ->
                    method.name.equals("buildConfigManager") && method.desc.equals("(Ljava/util/Map;Z)Z"))
                    .findFirst().orElseThrow();
            boolean publishesConfig = false;
            boolean normalReturn = false;
            for (AbstractInsnNode instruction : build.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.name.equals("config")) publishesConfig = true;
                if (instruction.getOpcode() == Opcodes.IRETURN) normalReturn = true;
            }
            assertTrue(publishesConfig);
            assertTrue(normalReturn);
            ClassNode spell = read(zip.getInputStream(zip.getEntry(
                    "io/redspace/ironsspellbooks/api/spells/AbstractSpell.class")));
            assertTrue(spell.methods.stream().anyMatch(method -> method.name.equals("resetRarityWeights")
                    && method.desc.equals("()V") && (method.access & Opcodes.ACC_PUBLIC) != 0));
        }
    }

    private static ClassNode read(InputStream input) throws Exception {
        try (input) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        throw new AssertionError("Missing annotation value: " + name);
    }
}
