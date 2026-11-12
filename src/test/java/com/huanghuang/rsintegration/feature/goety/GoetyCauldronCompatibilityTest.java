package com.huanghuang.rsintegration.feature.goety;

import com.huanghuang.rsintegration.testutil.CompatibilityTestJars;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用实际模组字节码检查可选版本，避免在单测中初始化 Goety 注册表。 */
class GoetyCauldronCompatibilityTest {
    private static final String ENTITIES = "com/Polarice3/Goety/common/blocks/entities/";
    private static final String CAULDRON = ENTITIES + "BrewCauldronBlockEntity";
    private static final String CANDLE = ENTITIES + "SoulCandlestickBlockEntity";

    @ParameterizedTest
    @ValueSource(strings = {"2.5.36.1", "2.5.52.4", "2.5.60.1"})
    void nativeLadleAndSharedCandleHooksExistInSupportedVersions(String version) throws IOException {
        Path jar = CompatibilityTestJars.requireMatching(
                ".*goety-" + Pattern.quote(version) + "(?:[-+_].*)?\\.jar");
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            ClassNode cauldron = readClass(archive, CAULDRON);
            assertTrue(cauldron.fields.stream().anyMatch(field ->
                    field.name.equals("candlestickBlockEntityList") && field.desc.equals("Ljava/util/List;")
                            && (field.access & Opcodes.ACC_FINAL) != 0));
            for (String field : new String[]{"soulTime", "isBrewing"}) {
                assertTrue(cauldron.fields.stream().anyMatch(value -> value.name.equals(field)), field);
            }
            method(cauldron, "findCandlesticks", "()V");
            assertTrue(calls(method(cauldron, "tick", "()V"), CAULDRON, "getBrewCost", "()I"));
            ClassNode block = readClass(archive, "com/Polarice3/Goety/common/blocks/BrewCauldronBlock");
            MethodNode use = block.methods.stream().filter(candidate -> referencesLadle(candidate))
                    .findFirst().orElseThrow();
            String playerBrew = "(Lnet/minecraft/world/entity/player/Player;)V";
            String brewDescriptor = cauldron.methods.stream().anyMatch(candidate ->
                    candidate.name.equals("brew") && candidate.desc.equals(playerBrew)) ? playerBrew : "()V";
            assertTrue(calls(use, CAULDRON, "brew", brewDescriptor));
            method(cauldron, "brew", brewDescriptor);

            ClassNode candle = readClass(archive, CANDLE);
            method(candle, "getSouls", "()I");
            method(candle, "checkCage", "()Z");
            method(candle, "drainSouls", "(ILnet/minecraft/core/BlockPos;)V");
            if (cauldron.methods.stream().anyMatch(candidate -> candidate.name.equals("drainSouls"))) {
                ClassNode api = readClass(archive, "com/Polarice3/Goety/api/blocks/entities/ISoulCandle");
                assertTrue(calls(method(api, "drainSouls", "(Lnet/minecraft/core/BlockPos;)V"),
                        api.name, "drainSouls", "(ILnet/minecraft/core/BlockPos;)V"));
            } else {
                String candleOwner = archive.getEntry(
                        "com/Polarice3/Goety/api/blocks/entities/ISoulCandle.class") == null ? CANDLE
                        : "com/Polarice3/Goety/api/blocks/entities/ISoulCandle";
                assertTrue(calls(method(cauldron, "tick", "()V"),
                        candleOwner, "drainSouls", "(ILnet/minecraft/core/BlockPos;)V"));
            }
        }
    }

    private static boolean referencesLadle(MethodNode method) {
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("CAULDRON_LADLE")) return true;
        }
        return false;
    }

    private static boolean calls(MethodNode method, String owner, String name, String descriptor) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                .findFirst().orElseThrow();
        assertEquals(0, method.access & Opcodes.ACC_STATIC);
        return method;
    }

    private static ClassNode readClass(ZipFile archive, String name) throws IOException {
        var entry = archive.getEntry(name + ".class");
        assertNotNull(entry, name);
        try (var input = archive.getInputStream(entry)) {
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
