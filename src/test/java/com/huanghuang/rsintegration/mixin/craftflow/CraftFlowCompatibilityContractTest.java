package com.huanghuang.rsintegration.mixin.craftflow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftFlowCompatibilityContractTest {
    private static final Path CONFIG = Path.of(
            "src/main/resources/rs_integration.craftflow_compat.mixins.json");
    private static final Path JAVA_ROOT = Path.of(
            "src/main/java/com/huanghuang/rsintegration/mixin/craftflow");
    private static final Path CRAFTFLOW_JAR = Path.of("libs/craftflow-1.0.0.jar");

    @Test
    void compatibilityConfigIsOptionalAndRunsAfterCraftFlowMixins() throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject();
        assertFalse(config.get("required").getAsBoolean());
        assertTrue(config.get("priority").getAsInt() < 1000);
        assertEquals("com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin",
                config.get("plugin").getAsString());

        Set<String> common = config.getAsJsonArray("mixins").asList().stream()
                .map(entry -> entry.getAsString()).collect(java.util.stream.Collectors.toSet());
        Set<String> client = config.getAsJsonArray("client").asList().stream()
                .map(entry -> entry.getAsString()).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                "craftflow.CraftFlowConfigMixin",
                "craftflow.CraftFlowFoodEatingIntegrationMixin",
                "craftflow.CraftFlowServerHandlerMixin",
                "craftflow.CraftFlowWirelessBindingMixin"), common);
        assertEquals(Set.of(
                "craftflow.CraftFlowBlueButtonPositionsMixin",
                "craftflow.CraftFlowFoodEatingButtonsMixin",
                "craftflow.CraftFlowJeiRecipeGuiLayoutsMixin",
                "craftflow.CraftFlowEmiRecipeScreenMixin"), client);
    }

    @Test
    void optionalGuardUsesClassResourcesWithoutLoadingCraftFlow() throws Exception {
        String plugin = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/"
                + "mixin/plugin/RSIntegrationMixinPlugin.java"));
        assertTrue(plugin.contains("mixinClassName.contains(\".craftflow.\")"));
        assertTrue(plugin.contains("isClassPresent(\"com.ybm.craftflow.CraftFlow\")"));
        assertTrue(plugin.contains("isClassPresent(targetClassName)"));

        try (var sources = Files.list(JAVA_ROOT)) {
            sources.filter(path -> path.toString().endsWith("Mixin.java")).forEach(path -> {
                try {
                    String source = Files.readString(path);
                    assertTrue(source.contains("@Pseudo"), path + " must tolerate an absent CraftFlow");
                    assertTrue(source.contains("@Mixin(targets = \""),
                            path + " must not hard-link a CraftFlow target type");
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }
    }

    @Test
    void bundledCraftFlowEntrypointsStillMatchTheIsolationHooks() throws Exception {
        try (ZipFile zip = new ZipFile(CRAFTFLOW_JAR.toFile())) {
            ClassNode handler = read(zip, "com/ybm/craftflow/server/ServerPseudoCraftHandler.class");
            assertMethod(handler, "register", "()V");
            assertMethod(handler, "execute",
                    "(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Ljava/util/List;Z"
                            + "Ljava/util/List;Ljava/util/List;ZI)V");
            assertMethod(handler, "handlePreviewRequest",
                    "(Lnet/minecraft/server/level/ServerPlayer;"
                            + "Lnet/minecraft/resources/ResourceLocation;ZI)V");
            assertMethod(handler, "handleMaxCraftableRequest",
                    "(Lnet/minecraft/server/level/ServerPlayer;"
                            + "Lnet/minecraft/resources/ResourceLocation;Z)V");

            ClassNode binding = read(zip, "com/ybm/craftflow/server/WirelessMachineBinding.class");
            assertMethod(binding, "onRightClickBlock",
                    "(Lnet/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickBlock;)V");
            assertMethod(binding, "onRegisterCommands",
                    "(Lnet/minecraftforge/event/RegisterCommandsEvent;)V");
            assertMethod(binding, "onServerTick",
                    "(Lnet/minecraftforge/event/TickEvent$ServerTickEvent;)V");

            ClassNode food = read(zip, "com/ybm/craftflow/server/FoodEatingIntegration.class");
            assertMethod(food, "eatAllFoods", "(Lnet/minecraft/server/level/ServerPlayer;I)V");
            assertMethod(food, "handleConfirm", "(Lnet/minecraft/server/level/ServerPlayer;JZ)V");

            ClassNode jeiMixin = read(zip, "com/ybm/craftflow/mixin/RecipeGuiLayoutsMixin.class");
            assertTrue(jeiMixin.methods.stream().anyMatch(method ->
                    method.name.equals("craftflow$drawSpecialButtons")));
            ClassNode emiMixin = read(zip, "com/ybm/craftflow/mixin/EmiRecipeScreenMixin.class");
            assertTrue(emiMixin.methods.stream().anyMatch(method -> method.name.equals("craftflow$draw")));
        }
    }

    private static ClassNode read(ZipFile zip, String entryName) throws Exception {
        var entry = zip.getEntry(entryName);
        assertNotNull(entry, entryName);
        try (InputStream input = zip.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    private static void assertMethod(ClassNode owner, String name, String descriptor) {
        assertTrue(owner.methods.stream().anyMatch(method ->
                        method.name.equals(name) && method.desc.equals(descriptor)),
                owner.name + "." + name + descriptor);
    }
}
