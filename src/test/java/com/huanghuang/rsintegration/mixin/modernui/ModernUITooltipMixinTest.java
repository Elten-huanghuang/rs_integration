package com.huanghuang.rsintegration.mixin.modernui;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.client.storage.DiskTooltipBackground;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.event.RenderTooltipEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ModernUITooltipMixinTest extends BootstrapTest {
    private static final String TARGET = "icyllis.modernui.mc.forge.UIManagerForge";
    private static final String MIXIN = ModernUITooltipMixin.class.getName();
    private static final String EVENT_DESCRIPTOR =
            "(Lnet/minecraftforge/client/event/RenderTooltipEvent$Pre;)V";
    private final ModernUITooltipMixin handler = new ModernUITooltipMixin() {};

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
    void themedItemsSkipBothEarlyDrawingAndEventCancellation() throws Exception {
        var event = event(new ItemStack(Items.DIAMOND));
        try (var backgrounds = mockStatic(DiskTooltipBackground.class)) {
            backgrounds.when(() -> DiskTooltipBackground.hasCustomBackground(Items.DIAMOND))
                    .thenReturn(true);
            for (String method : List.of("onRenderTooltipH", "onRenderTooltipL")) {
                var callback = new CallbackInfo(method, true);
                invoke(event, callback);
                assertTrue(callback.isCancelled());
            }
        }
    }

    @Test
    void otherItemsAndTextOnlyTooltipsKeepModernUI() throws Exception {
        try (var backgrounds = mockStatic(DiskTooltipBackground.class)) {
            for (ItemStack stack : List.of(new ItemStack(Items.DIAMOND), ItemStack.EMPTY)) {
                var callback = new CallbackInfo("onRenderTooltipH", true);
                invoke(event(stack), callback);
                assertFalse(callback.isCancelled());
            }
        }
    }

    @Test
    void compatibilitySwitchRestoresNativeCallbacks() throws Exception {
        RSIntegrationConfig.TOOLTIP_MODERN_UI_COMPAT.set(false);
        try (var backgrounds = mockStatic(DiskTooltipBackground.class)) {
            backgrounds.when(() -> DiskTooltipBackground.hasCustomBackground(Items.DIAMOND))
                    .thenReturn(true);
            for (String method : List.of("onRenderTooltipH", "onRenderTooltipL")) {
                var callback = new CallbackInfo(method, true);
                invoke(event(new ItemStack(Items.DIAMOND)), callback);
                assertFalse(callback.isCancelled());
            }
            backgrounds.verifyNoInteractions();
        }
    }

    @Test
    void missingOrChangedModernUIIsSkippedBeforeLoadingItsClasses() {
        var plugin = new RSIntegrationMixinPlugin();
        assertFalse(plugin.shouldApplyMixin("missing.optional.UIManagerForge", MIXIN));
        assertTrue(plugin.shouldApplyMixin(CompatibleManager.class.getName(), MIXIN));
        assertFalse(plugin.shouldApplyMixin(IncompleteManager.class.getName(), MIXIN));
    }

    @Test
    void bothNativeCallbacksAreInjectedOnlyOnTheClient() throws Exception {
        var config = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/rs_integration.mixins.json"))).getAsJsonObject();
        String relativeName = "modernui.ModernUITooltipMixin";
        assertTrue(config.getAsJsonArray("client").asList().stream()
                .anyMatch(value -> relativeName.equals(value.getAsString())));
        assertFalse(config.getAsJsonArray("mixins").asList().stream()
                .anyMatch(value -> relativeName.equals(value.getAsString())));
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(MIXIN.replace('.', '/') + ".class")) {
            assertNotNull(input);
            ClassNode node = readClass(input);
            var target = node.invisibleAnnotations.stream()
                    .filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                    .findFirst().orElseThrow();
            assertEquals(List.of(TARGET), target.values.get(target.values.indexOf("targets") + 1));
            MethodNode method = node.methods.stream()
                    .filter(value -> value.name.equals("rsi$keepCustomBackground"))
                    .findFirst().orElseThrow();
            assertEquals(0, method.access & Opcodes.ACC_STATIC);
            var injection = method.visibleAnnotations.stream()
                    .filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                    .findFirst().orElseThrow();
            assertEquals(List.of("onRenderTooltipH" + EVENT_DESCRIPTOR,
                            "onRenderTooltipL" + EVENT_DESCRIPTOR),
                    injection.values.get(injection.values.indexOf("method") + 1));
        }
    }

    @Test
    void suppliedJarHasBothExpectedInstanceCallbacks() throws Exception {
        String path = System.getenv("RSI_MODERN_UI_JAR");
        assumeTrue(path != null && !path.isBlank());
        try (ZipFile archive = new ZipFile(path)) {
            var entry = archive.getEntry(TARGET.replace('.', '/') + ".class");
            assertNotNull(entry);
            try (InputStream input = archive.getInputStream(entry)) {
                var methods = readClass(input).methods.stream().filter(method ->
                        EVENT_DESCRIPTOR.equals(method.desc)
                                && (method.access & Opcodes.ACC_STATIC) == 0)
                        .map(method -> method.name).toList();
                assertTrue(methods.containsAll(Set.of("onRenderTooltipH", "onRenderTooltipL")));
            }
        }
    }

    private static RenderTooltipEvent.Pre event(ItemStack stack) {
        var event = mock(RenderTooltipEvent.Pre.class);
        when(event.getItemStack()).thenReturn(stack);
        return event;
    }

    private void invoke(RenderTooltipEvent.Pre event, CallbackInfo callback) throws Exception {
        Method method = ModernUITooltipMixin.class.getDeclaredMethod(
                "rsi$keepCustomBackground", RenderTooltipEvent.Pre.class, CallbackInfo.class);
        method.setAccessible(true);
        method.invoke(handler, event, callback);
    }

    private static ClassNode readClass(InputStream input) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static final class CompatibleManager {
        void onRenderTooltipH(RenderTooltipEvent.Pre event) {}
        void onRenderTooltipL(RenderTooltipEvent.Pre event) {}
    }

    private static final class IncompleteManager {
        void onRenderTooltipH(RenderTooltipEvent.Pre event) {}
    }
}
