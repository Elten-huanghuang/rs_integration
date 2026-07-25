package com.huanghuang.rsintegration;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards against the dedicated-server translation bug.
 *
 * <p>Forge's {@code LanguageHook.loadLanguagesOnServer} only loads
 * {@code data/<ns>/lang/en_us.json} plus Minecraft's and Forge's own tables. A mod's
 * {@code assets/rs_integration/lang/*.json} is a client resource, so a dedicated
 * server cannot resolve any {@code rsi.*} key. Calling {@code getString()} on such a
 * Component server-side therefore yields the raw key, and if that String is then
 * shipped to the client the player sees {@code rsi.plan.failure.missing_materials}
 * instead of a sentence. Singleplayer hides the bug because the integrated server
 * shares the client's in-process Language instance.
 *
 * <p>The rule enforced here: a class that is not client-only must not invoke
 * {@code Component.getString()} in a method that also loads an {@code rsi.} string
 * constant. Client classes are exempt — resolving on the client is the fix, not the
 * bug.
 */
class ServerSideTranslationBytecodeTest {

    private static final Path CLASS_ROOT = Path.of("build", "classes", "java", "main");

    /** Client-only packages: resolving translations here is correct by construction. */
    private static final List<String> CLIENT_ONLY_PATH_MARKERS = List.of(
            "/client/", "Screen", "Renderer", "Overlay", "Hud", "HUD", "Keybind",
            "/mixin/jei/", "/mixin/apotheosis/", "JeiPlugin", "JeiMarquee",
            "CraftProgressPresentation", "PlanRenderEngine", "BindingTooltipHandler",
            "MachineHub", "UIRenderer", "SearchController", "DisplayListManager",
            "PinyinUtil",
            // Tooltip builders: only ever invoked from appendHoverText on the client.
            "RSMagnetUpgradeItem");

    /**
     * Methods that resolve a Component only to feed a logger. Log output is
     * server-local and never shown to a player, so raw keys there are harmless.
     * Listed explicitly so a new offender in the same method still fails.
     */
    private static final List<String> LOG_ONLY_ALLOWLIST = List.of(
            "com/huanghuang/rsintegration/crafting/AsyncCraftChain#finish",
            "com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket#tryResolve",
            "com/huanghuang/rsintegration/crafting/CraftPacketUtils#tryResolveAndRunChain",
            "com/huanghuang/rsintegration/mods/crabbersdelight/CrabTrapBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/crockpot/CrockPotBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/eidolon/EidolonBatchDelegate#tryStartRitualCraft",
            "com/huanghuang/rsintegration/mods/embers/EreAlchemyBatchDelegate#recycleBlockingItems",
            "com/huanghuang/rsintegration/mods/embers/EreAlchemyBatchDelegate#validateAndInit",
            "com/huanghuang/rsintegration/mods/embers/EreAlchemyInferDelegate#validateAndInit",
            "com/huanghuang/rsintegration/mods/immortalersdelight/EnchantalCoolerBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/malum/MalumCraftPacket#tryCraft",
            "com/huanghuang/rsintegration/mods/wizardsreborn/WRBatchDelegate#startWissenWithMaterials",
            "com/huanghuang/rsintegration/mods/wizardsreborn/WRBatchDelegate#validateCrystalSetup",
            "com/huanghuang/rsintegration/mods/youkaishomecoming/cooking/CookingPotBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/youkaishomecoming/cuisine/CuisineBoardBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/youkaishomecoming/ferment/FermentationTankBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/youkaishomecoming/moka/MokaPotBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/aetherworks/AetherworksBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/farmersdelight/CookingPotBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/forbidden/FaBatchDelegate#tryStartSingleCraft",
            "com/huanghuang/rsintegration/mods/forbidden/FaBatchDelegate#tryStartWithMaterials",
            "com/huanghuang/rsintegration/mods/forbidden/FaCraftPacket#tryCraft",
            "com/huanghuang/rsintegration/mods/goety/GoetyBatchDelegate#validateAndInit",
            "com/huanghuang/rsintegration/mods/eidolon/EidolonCraftPacket#tryCraft",
            "com/huanghuang/rsintegration/mods/wizardsreborn/WRWandCraftPacket#handleWissenCrystallizer",
            "com/huanghuang/rsintegration/mods/wizardsreborn/WRWandCraftPacket#handleArcaneIterator",
            "com/huanghuang/rsintegration/mods/wizardsreborn/WRWandCraftPacket#handleArcaneWorkbench",
            "com/huanghuang/rsintegration/mods/wizardsreborn/WRWandCraftPacket#handleCrystalRitual",
            // targetName only; the client re-derives it from targetResult.
            "com/huanghuang/rsintegration/mods/apotheosis/ApothSpawnerUpgradeService#preview",
            "com/huanghuang/rsintegration/crafting/batch/GenericCraftPacket#tryBuildPlan");

    @Test
    void serverReachableClassesDoNotPreRenderModTranslations() throws IOException {
        assertTrue(Files.isDirectory(CLASS_ROOT),
                () -> "compile the mod before running this test: " + CLASS_ROOT.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> classes = Files.walk(CLASS_ROOT)) {
            classes.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !isClientOnly(p))
                    .forEach(p -> offenders.addAll(scan(p)));
        }

        if (!offenders.isEmpty()) {
            fail("Server-reachable code resolves rsi.* translation keys to String. "
                    + "A dedicated server has no rs_integration lang table, so these emit "
                    + "raw keys to players. Send the Component instead and resolve it "
                    + "client-side.\n  " + String.join("\n  ", offenders));
        }
    }

    private static boolean isClientOnly(Path classFile) {
        String normalized = classFile.toString().replace('\\', '/');
        for (String marker : CLIENT_ONLY_PATH_MARKERS) {
            if (normalized.contains(marker)) return true;
        }
        return isAnnotatedClientOnly(classFile);
    }

    /** True when the class carries {@code @OnlyIn(Dist.CLIENT)}. */
    private static boolean isAnnotatedClientOnly(Path classFile) {
        boolean[] clientOnly = {false};
        new ClassReader(read(classFile)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor,
                                                                      boolean visible) {
                if ("Lnet/minecraftforge/api/distmarker/OnlyIn;".equals(descriptor)) {
                    return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitEnum(String name, String desc, String value) {
                            if ("CLIENT".equals(value)) clientOnly[0] = true;
                        }
                    };
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return clientOnly[0];
    }

    /**
     * Flags methods that both load an {@code rsi.} constant and call
     * {@code Component.getString()}. Co-occurrence in one method body is the
     * signal: it is how every real instance of this bug was written.
     */
    private static List<String> scan(Path classFile) {
        List<String> hits = new ArrayList<>();
        new ClassReader(read(classFile)).accept(new ClassVisitor(Opcodes.ASM9) {
            String owner;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                owner = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean loadsModKey;
                    boolean resolvesToString;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String s && s.startsWith("rsi.")) loadsModKey = true;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String methodOwner, String name,
                                                String methodDescriptor, boolean isInterface) {
                        if ("getString".equals(name)
                                && methodOwner.startsWith("net/minecraft/network/chat/")) {
                            resolvesToString = true;
                        }
                    }

                    @Override
                    public void visitEnd() {
                        String id = owner + "#" + methodName;
                        if (loadsModKey && resolvesToString
                                && !LOG_ONLY_ALLOWLIST.contains(id)) {
                            hits.add(id);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hits;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
