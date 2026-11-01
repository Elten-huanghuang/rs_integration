package com.huanghuang.rsintegration.mixin.obscuretooltips;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.client.storage.DiskTooltipBackground;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mockStatic;

class ObscureTooltipConfigMixinTest {
    private static final String TARGET = "dev.obscuria.tooltips.config.ClientConfig";
    private static final String MIXIN = ObscureTooltipConfigMixin.class.getName();

    @BeforeEach
    void loadClientConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(config);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(config);
    }

    @AfterEach
    void unloadClientConfig() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
    }

    @Test
    void disablingCompatibilityKeepsTheNativeDecision() throws Exception {
        RSIntegrationConfig.TOOLTIP_OBSCURE_COMPAT.set(false);
        try (var backgrounds = mockStatic(DiskTooltipBackground.class)) {
            backgrounds.when(() -> DiskTooltipBackground.hasCustomBackground(null)).thenReturn(true);
            var callback = callback();
            invoke(callback);
            assertFalse(callback.isCancelled());
            backgrounds.verifyNoInteractions();
        }
    }

    @Test
    void customBackgroundsBypassObscureTooltips() throws Exception {
        try (var backgrounds = mockStatic(DiskTooltipBackground.class)) {
            backgrounds.when(() -> DiskTooltipBackground.hasCustomBackground(null)).thenReturn(true);
            var callback = callback();
            invoke(callback);
            assertTrue(callback.isCancelled());
            assertEquals(Boolean.TRUE, callback.getReturnValue());
        }
    }

    @Test
    void otherItemsKeepNativeIgnoreRulesAndVisuals() throws Exception {
        try (var backgrounds = mockStatic(DiskTooltipBackground.class)) {
            for (boolean originallyIgnored : new boolean[]{false, true}) {
                var callback = new CallbackInfoReturnable<Boolean>("isIgnored", true, originallyIgnored);
                invoke(callback);
                assertFalse(callback.isCancelled());
                assertEquals(originallyIgnored, callback.getReturnValue());
            }
        }
    }

    @Test
    void absentOrIncompatibleOptionalModsAreSkipped() {
        var plugin = new RSIntegrationMixinPlugin();
        assertFalse(plugin.shouldApplyMixin("missing.optional.ClientConfig", MIXIN));
        assertFalse(plugin.shouldApplyMixin("java.lang.String", MIXIN));
        // 使用相同签名的测试类，避免把可选模组引入运行时依赖。
        assertTrue(plugin.shouldApplyMixin(CompatibleConfig.class.getName(), MIXIN));
        assertFalse(plugin.shouldApplyMixin(IncompatibleConfig.class.getName(), MIXIN));
    }

    @Test
    void compatibilityIsOnlyRegisteredOnTheClient() throws Exception {
        var config = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/rs_integration.mixins.json"))).getAsJsonObject();
        String relativeName = "obscuretooltips.ObscureTooltipConfigMixin";
        assertTrue(config.getAsJsonArray("client").asList().stream()
                .anyMatch(value -> relativeName.equals(value.getAsString())));
        assertFalse(config.getAsJsonArray("mixins").asList().stream()
                .anyMatch(value -> relativeName.equals(value.getAsString())));
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(MIXIN.replace('.', '/') + ".class")) {
            assertNotNull(input);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
            var annotation = node.invisibleAnnotations.stream()
                    .filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                    .findFirst().orElseThrow();
            assertEquals(List.of(TARGET),
                    annotation.values.get(annotation.values.indexOf("targets") + 1));
        }
    }

    @Test
    void suppliedModJarExposesTheExpectedIgnoreHook() throws Exception {
        String jar = System.getenv("RSI_OBSCURE_TOOLTIPS_JAR");
        assumeTrue(jar != null && !jar.isBlank());
        try (ZipFile archive = new ZipFile(jar)) {
            var entry = archive.getEntry(TARGET.replace('.', '/') + ".class");
            assertNotNull(entry);
            try (InputStream input = archive.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
                assertTrue(node.methods.stream().anyMatch(method ->
                        method.name.equals("isIgnored")
                                && method.desc.equals("(Lnet/minecraft/world/item/Item;)Z")));
            }
        }
    }

    private static CallbackInfoReturnable<Boolean> callback() {
        return new CallbackInfoReturnable<>("isIgnored", true);
    }

    private static void invoke(CallbackInfoReturnable<Boolean> callback) throws Exception {
        Method handler = ObscureTooltipConfigMixin.class.getDeclaredMethod(
                "rsi$keepCustomBackground", Item.class, CallbackInfoReturnable.class);
        handler.setAccessible(true);
        handler.invoke(null, null, callback);
    }

    private static final class CompatibleConfig {
        public static boolean isIgnored(Item item) {
            return false;
        }
    }

    private static final class IncompatibleConfig {
        public static boolean isIgnored(String item) {
            return false;
        }
    }
}
