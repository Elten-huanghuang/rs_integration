package com.huanghuang.rsintegration.mixin.taxfreelevels;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxFreeLevelsCompatibilityTest {
    private static final String PLUGIN = RSIntegrationMixinPlugin.class.getName();
    private static final String ORIGINAL_RESOURCE =
            "io/github/fourmisain/taxfreelevels/TaxFreeLevels.class";
    private static final String MIXIN_PACKAGE = "com.huanghuang.rsintegration.mixin.";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";

    @Test
    void originalModDisablesEveryConfiguredCommonAndClientMixinBeforeClassesAreLoaded()
            throws Exception {
        IMixinConfigPlugin installed = isolatedPlugin(true);
        IMixinConfigPlugin absent = isolatedPlugin(false);
        int mixinCount = 0;
        for (String mixin : configuredMixins()) {
            for (String target : targets(readClass(mixin))) {
                assertFalse(installed.shouldApplyMixin(target, mixin), mixin + ": " + target);
                boolean targetPresent = getClass().getClassLoader()
                        .getResource(target.replace('.', '/') + ".class") != null;
                assertEquals(targetPresent, absent.shouldApplyMixin(target, mixin), mixin);
            }
            mixinCount++;
        }
        assertEquals(5, mixinCount, "公共与客户端注入必须全部受互斥开关控制");
        assertTrue(installed.shouldApplyMixin(PLAYER.replace('/', '.'),
                MIXIN_PACKAGE + "minecraft.EntityMixin"), "不能禁用无关功能");
    }

    @Test
    void missingOptionalModsAreSkippedEvenWithoutTaxFreeLevels() throws Exception {
        IMixinConfigPlugin plugin = isolatedPlugin(false);
        assertFalse(plugin.shouldApplyMixin("missing.optional.LevelConsumer",
                TaxFreeModLevelCostsMixin.class.getName()));
    }

    @Test
    void vanillaChargingAndAnvilLimitInjectionSitesExistInTheMappedForgeClasses()
            throws Exception {
        ClassNode anvil = readClass("net.minecraft.world.inventory.AnvilMenu");
        assertEquals(1, calls(method(anvil, "onTake"), PLAYER, "giveExperienceLevels", "(I)V"));
        MethodNode create = method(anvil, "createResult");
        assertEquals(2, calls(create, "net/minecraft/world/inventory/DataSlot", "get", "()I"));
        assertEquals(2, fields(create, "net/minecraft/world/entity/player/Abilities", "instabuild"));
        ClassNode player = readClass(PLAYER.replace('/', '.'));
        assertEquals(1, calls(method(player, "onEnchantmentPerformed"),
                PLAYER, "giveExperienceLevels", "(I)V"));
        ClassNode screen = readClass("net.minecraft.client.gui.screens.inventory.AnvilScreen");
        assertEquals(1, fields(method(screen, "renderLabels"),
                "net/minecraft/world/entity/player/Abilities", "instabuild"));
    }

    @Test
    void optionalWrapperSupportsBothStaticAndInstanceNativeConsumers() throws Exception {
        ClassNode mixin = readClass(TaxFreeModLevelCostsMixin.class.getName());
        MethodNode wrapper = method(mixin, "rsi$chargeFixedExperience");
        assertTrue((wrapper.access & Opcodes.ACC_STATIC) != 0,
                "旧版传送石碑使用静态扣费方法，不能注入实例处理器");
        assertTrue((wrapper.access & Opcodes.ACC_PRIVATE) != 0);
    }

    @Test
    void suppliedTetraAndInfuserJarsContainTheWrappedLevelCharge() throws Exception {
        assertNativeLevelCharge("libs/tetra-1.20.1-6.9.0.jar",
                "se/mickelus/tetra/blocks/workbench/WorkbenchTile.class");
        assertNativeLevelCharge("libs/EnchantingInfuser-v8.0.3-1.20.1-Forge.jar",
                "fuzs/enchantinginfuser/world/inventory/InfuserMenu.class");
    }

    private static void assertNativeLevelCharge(String jar, String classFile) throws Exception {
        try (ZipFile archive = new ZipFile(jar)) {
            assertNotNull(archive.getEntry(classFile));
            try (InputStream input = archive.getInputStream(archive.getEntry(classFile))) {
                ClassNode node = new ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                int count = node.methods.stream().mapToInt(method ->
                        calls(method, PLAYER, "m_6749_", "(I)V")
                                + calls(method, PLAYER, "giveExperienceLevels", "(I)V")).sum();
                assertTrue(count > 0, jar + " 的真实字节码必须存在等级扣费入口");
            }
        }
    }

    private static List<String> configuredMixins() throws Exception {
        String json = Files.readString(Path.of("src/main/resources/rs_integration.mixins.json"));
        var config = JsonParser.parseString(json).getAsJsonObject();
        List<String> result = new ArrayList<>();
        for (String side : List.of("mixins", "client")) {
            config.getAsJsonArray(side).forEach(entry -> {
                if (entry.getAsString().startsWith("taxfreelevels.")) {
                    result.add(MIXIN_PACKAGE + entry.getAsString());
                }
            });
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> targets(ClassNode node) {
        AnnotationNode annotation = node.invisibleAnnotations.stream().filter(value ->
                value.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i))) {
                for (Type type : (List<Type>) annotation.values.get(i + 1)) {
                    result.add(type.getClassName());
                }
            } else if ("targets".equals(annotation.values.get(i))) {
                result.addAll((List<String>) annotation.values.get(i + 1));
            }
        }
        assertFalse(result.isEmpty());
        return result;
    }

    private static ClassNode readClass(String name) throws Exception {
        try (InputStream input = TaxFreeLevelsCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input, name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> name.equals(method.name))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int fields(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }

    private static IMixinConfigPlugin isolatedPlugin(boolean originalInstalled) throws Exception {
        ClassLoader parent = TaxFreeLevelsCompatibilityTest.class.getClassLoader();
        String pluginResource = PLUGIN.replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = parent.getResourceAsStream(pluginResource)) {
            assertNotNull(input);
            bytecode = input.readAllBytes();
        }
        ClassLoader loader = new ClassLoader(parent) {
            @Override
            public URL getResource(String name) {
                if (ORIGINAL_RESOURCE.equals(name)) {
                    return originalInstalled ? parent.getResource(pluginResource) : null;
                }
                return super.getResource(name);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("io.github.fourmisain.taxfreelevels.")) {
                    throw new AssertionError("启动检测不能加载 Tax Free Levels 的类");
                }
                if (!PLUGIN.equals(name)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = defineClass(name, bytecode, 0, bytecode.length);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        return (IMixinConfigPlugin) loader.loadClass(PLUGIN).getDeclaredConstructor().newInstance();
    }
}
