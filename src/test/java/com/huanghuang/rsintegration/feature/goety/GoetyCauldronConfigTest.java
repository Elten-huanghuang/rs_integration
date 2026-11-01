package com.huanghuang.rsintegration.feature.goety;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.ConfigEditorModel;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyCauldronConfigTest {
    private static final String CONFIG = "com/huanghuang/rsintegration/config/RSIntegrationConfig";

    @AfterEach
    void unloadCommonConfig() {
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
    }

    @Test
    void defaultsKeepExistingCauldronFeaturesEnabledBeforeConfigLoads() {
        assertFalse(RSIntegrationConfig.COMMON_SPEC.isLoaded());
        assertTrue(RSIntegrationConfig.ENABLE_GOETY_CAULDRON.getDefault());
        assertTrue(RSIntegrationConfig.isGoetyCauldronEnabled());
    }

    @Test
    void cauldronRequiresBothTheMasterSwitchAndItsOwnSwitch() {
        loadConfig(CommentedConfig.inMemory());
        for (boolean goety : new boolean[]{false, true}) {
            for (boolean cauldron : new boolean[]{false, true}) {
                RSIntegrationConfig.ENABLE_GOETY.set(goety);
                RSIntegrationConfig.ENABLE_GOETY_CAULDRON.set(cauldron);
                assertEquals(goety && cauldron, RSIntegrationConfig.isGoetyCauldronEnabled());
            }
        }
    }

    @Test
    void disablingCauldronDoesNotDisableOtherGoetyFeatures() {
        loadConfig(CommentedConfig.inMemory());
        RSIntegrationConfig.ENABLE_GOETY_CAULDRON.set(false);
        assertFalse(RSIntegrationConfig.isGoetyCauldronEnabled());
        assertTrue(RSIntegrationConfig.ENABLE_GOETY.get());
        assertFalse(RSIntegrationConfig.ENABLE_GOETY_SOUL_REVERSE_REFILL.get());
        RSIntegrationConfig.ENABLE_GOETY_CAULDRON.set(true);
        assertTrue(RSIntegrationConfig.isGoetyCauldronEnabled());
    }

    @Test
    void existingConfigsGainTheOptionWithoutChangingTheMasterSwitch() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("integrations.enableGoety", false);
        loadConfig(config);
        assertEquals(Boolean.TRUE, config.get("integrations.enableGoetyCauldron"));
        assertFalse(RSIntegrationConfig.ENABLE_GOETY.get());
        assertFalse(RSIntegrationConfig.isGoetyCauldronEnabled());
    }

    @Test
    void existingConfigEditorExposesTheOptionAndItsRestartRequirement() {
        loadConfig(CommentedConfig.inMemory());
        var model = new ConfigEditorModel(ConfigEditorModel.defaultFiles());
        var option = model.entries().stream()
                .filter(entry -> entry.file().id().equals("common")
                        && entry.path().equals("integrations.enableGoetyCauldron"))
                .findFirst().orElseThrow();
        assertTrue(option.isBoolean());
        assertEquals(true, option.parsed());
        assertTrue(option.requiresRestart());
        assertFalse(option.description().isBlank());
        assertTrue(RSIntegrationConfig.COMMON_SPEC.getSpec()
                .<ForgeConfigSpec.ValueSpec>get(
                        RSIntegrationConfig.ENABLE_GOETY_CAULDRON.getPath()).needsWorldRestart());
        option.setText("false");
        model.save(file -> {});
        assertFalse(RSIntegrationConfig.isGoetyCauldronEnabled());
        assertTrue(RSIntegrationConfig.ENABLE_GOETY.get());
    }

    @Test
    void registrationAndInteractionEntrypointsUseTheCombinedSwitch() throws IOException {
        // Goety 是可选依赖，检查入口字节码而不在无游戏单测中加载其注册表。
        ClassNode feature = readFeatureClass("GoetyCauldronFeature");
        assertTrue(feature.methods.stream().anyMatch(method ->
                method.name.startsWith("lambda$commonSetup$")
                        && checksSwitch(method)
                        && calls(method, "com/huanghuang/rsintegration/feature/goety/GoetyRsiRegistrations", "register")));
        ClassNode interaction = readFeatureClass("brewing/CauldronInteraction");
        assertTrue(checksSwitch(method(interaction, "open")));
        assertTrue(checksSwitch(method(interaction, "rightClick")));
        ClassNode menu = readFeatureClass("menu/WitchCauldronMenu");
        assertTrue(checksSwitch(method(menu, "stillValid")));
        MethodNode broadcast = method(menu, "broadcastChanges");
        assertTrue(checksSwitch(broadcast));
        assertTrue(calls(broadcast, "net/minecraft/server/level/ServerPlayer", "closeContainer"));
        assertTrue(calls(method(menu, "removed"),
                "com/huanghuang/rsintegration/feature/goety/integration/MissingMaterialOrders", "close"));
        assertTrue(calls(method(menu, "handle"),
                "com/huanghuang/rsintegration/feature/goety/menu/WitchCauldronMenu", "stillValid"));
    }

    @Test
    void stirringCallsTheNativeFunctionWithoutChangingHeldItems() throws IOException {
        var stir = method(readFeatureClass("brewing/NativeCauldronActions"), "stir");
        assertTrue(calls(stir,
                "com/huanghuang/rsintegration/feature/goety/integration/GoetyCauldronCompat", "brew"));
        for (var instruction : stir.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(call.name.equals("setItemInHand"));
                assertFalse(call.name.equals("getMainHandItem"));
                assertFalse(call.name.equals("use"));
            }
        }
        var menu = readFeatureClass("menu/WitchCauldronMenu");
        assertTrue(calls(method(menu, "readyToBrew"), menu.name, "hasLadle"));
    }

    @Test
    void displayUsesAnIsolatedCauldronForNativeOutputPreview() throws IOException {
        var preview = method(readFeatureClass("brewing/BrewPlan"), "previewCurrentOutput");
        assertTrue(calls(preview, "com/huanghuang/rsintegration/feature/goety/brewing/BrewPlan$PreviewCauldron", "<init>"));
        assertTrue(calls(preview, "com/huanghuang/rsintegration/feature/goety/brewing/BrewPreviewLevel", "<init>"));
        assertTrue(calls(preview, "com/Polarice3/Goety/common/blocks/entities/BrewCauldronBlockEntity", "saveWithoutMetadata"));
        var update = method(readFeatureClass("brewing/BrewPlan$PreviewCauldron"), "markUpdated");
        for (var instruction : update.instructions) assertFalse(instruction instanceof MethodInsnNode);
    }

    private static void loadConfig(CommentedConfig config) {
        RSIntegrationConfig.COMMON_SPEC.correct(config);
        RSIntegrationConfig.COMMON_SPEC.setConfig(config);
    }

    private static boolean checksSwitch(MethodNode method) {
        return calls(method, CONFIG, "isGoetyCauldronEnabled");
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return true;
        }
        return false;
    }

    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode readFeatureClass(String name) throws IOException {
        String resource = "/com/huanghuang/rsintegration/feature/goety/" + name + ".class";
        try (InputStream input = GoetyCauldronConfigTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            ClassNode type = new ClassNode();
            new ClassReader(input).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return type;
        }
    }
}
