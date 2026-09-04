package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.crafting.plan.PlanWarnings;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.mixin.enigmaticaddons.ArtificialFlowerMixin;
import com.huanghuang.rsintegration.mixin.jei.RecipeGuiLayoutsMixin;
import com.huanghuang.rsintegration.mixin.wizardterracurios.BuffItemMixin;
import com.huanghuang.rsintegration.mods.jei.JeiMarqueeSelector;
import com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageAccess;
import com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageJeiBridge;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageCatalog;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageRuntime;
import com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.huanghuang.rsintegration.network.packet.ResonanceNetworkHandler;
import com.huanghuang.rsintegration.resonance.backpack.OpenResonanceBackpackPacket;
import com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackContainer;
import com.huanghuang.rsintegration.resonance.backpack.ResonanceDiskInventory;
import com.huanghuang.rsintegration.resonance.backpack.ResonanceSlot;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalDependencyBytecodeTest {
    private static final String RS_NETWORK_TYPE =
            "com/refinedmods/refinedstorage/api/network/INetwork";
    private static final String RS_PACKAGE = "com/refinedmods/refinedstorage";
    private static final Set<String> RS_ISOLATED_CALLBACK_CLASSES = Set.of(
            "com/huanghuang/rsintegration/compat/emi/RSEmiOptionalHooks",
            "com/huanghuang/rsintegration/mixin/sophisticatedbackpacks/InventoryInteractionHelperMixin",
            "com/huanghuang/rsintegration/network/binding/RSAltarBindingResolver",
            "com/huanghuang/rsintegration/sidepanel/RSSidePanelNetworkHandler",
            "com/huanghuang/rsintegration/storage/rs/NativeRefinedStorageDriver",
            "com/huanghuang/rsintegration/util/TrackedNetworkInsertion");

    @Test
    void commonCallbacksDoNotCaptureRefinedStorageNetwork() throws IOException {
        Path classRoot = Path.of("build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classRoot),
                () -> "compile the mod before running this test: " + classRoot.toAbsolutePath());
        TreeSet<String> offenders = new TreeSet<>();

        try (Stream<Path> classes = Files.walk(classRoot)) {
            classes.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                String className = classRoot.relativize(path).toString()
                        .replace('\\', '/').replace(".class", "");
                if (RS_ISOLATED_CALLBACK_CLASSES.stream()
                        .anyMatch(className::startsWith)) return;
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitInvokeDynamicInsn(String dynamicName,
                                                               String dynamicDescriptor,
                                                               Handle bootstrapMethodHandle,
                                                               Object... bootstrapMethodArguments) {
                                if (!"java/lang/invoke/LambdaMetafactory".equals(
                                        bootstrapMethodHandle.getOwner())) return;
                                if (dynamicDescriptor.contains(RS_PACKAGE)) {
                                    offenders.add(className + "#" + name
                                            + " dynamic " + dynamicDescriptor);
                                }
                                for (Object argument : bootstrapMethodArguments) {
                                    if (argument instanceof Handle handle
                                            && handle.getDesc().contains(RS_PACKAGE)) {
                                        offenders.add(className + "#" + name
                                            + " target " + handle.getName() + handle.getDesc());
                                    }
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            });
        }

        assertEquals(List.of(), new ArrayList<>(offenders),
                "callbacks that capture optional RS types:\n"
                        + String.join("\n", offenders));
    }

    @Test
    void deferredEndpointPlannerCallbacksDoNotCaptureRefinedStorageNetwork() throws IOException {
        AtomicBoolean foundPurePlanner = new AtomicBoolean();
        AtomicBoolean foundPhysicalPlanner = new AtomicBoolean();
        List<String> offenders = new ArrayList<>();

        new ClassReader(classBytes(GenericCraftPacket.class)).accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        boolean planner = "queuePureExecutionPlan".equals(name)
                                || "queuePureExecutionPlanForPhysicalRecipe".equals(name);
                        if (!planner) return null;
                        if ("queuePureExecutionPlan".equals(name)) foundPurePlanner.set(true);
                        else foundPhysicalPlanner.set(true);
                        if (descriptor.contains(RS_NETWORK_TYPE)) {
                            offenders.add(name + " method descriptor");
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitInvokeDynamicInsn(String dynamicName,
                                                               String dynamicDescriptor,
                                                               Handle bootstrapMethodHandle,
                                                               Object... bootstrapMethodArguments) {
                                if (dynamicDescriptor.contains(RS_NETWORK_TYPE)) {
                                    offenders.add(name + " invokedynamic descriptor");
                                }
                                for (Object argument : bootstrapMethodArguments) {
                                    if (argument instanceof Handle handle
                                            && handle.getDesc().contains(RS_NETWORK_TYPE)) {
                                        offenders.add(name + " callback implementation descriptor");
                                    }
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(foundPurePlanner.get());
        assertTrue(foundPhysicalPlanner.get());
        assertEquals(List.of(), offenders,
                "deferred storage-endpoint callbacks must not resolve optional RS types");
    }

    @Test
    void sharedResonanceRuntimeDoesNotLinkRefinedStorageTypes() throws IOException {
        assertNoTypeReference(PassiveEffectEngine.class,
                "com/refinedmods/refinedstorage", "Refined Storage");
        assertNoTypeReference(PassiveEffectEngine.class,
                "com/huanghuang/rsintegration/resonance/disk", "RS resonance disk");
        assertNoTypeReference(PassiveEffectEngine.class,
                "com/huanghuang/rsintegration/resonance/backpack", "RS resonance backpack");
        assertNoTypeReference(LycheeVirtualCatalysts.class,
                "com/refinedmods/refinedstorage", "Refined Storage");
        assertNoTypeReference(LycheeVirtualCatalysts.class,
                "com/huanghuang/rsintegration/resonance/disk", "RS resonance disk");
    }

    @Test
    void sharedResonanceMenuPathDoesNotLinkOptionalBackendImplementations() throws IOException {
        Class<?>[] sharedTypes = {
                ResonanceNetworkHandler.class,
                OpenResonanceBackpackPacket.class,
                ResonanceBackpackContainer.class,
                ResonanceDiskInventory.class,
                ResonanceSlot.class
        };
        for (Class<?> type : sharedTypes) {
            assertNoTypeReference(type, "com/refinedmods/refinedstorage", "Refined Storage");
            assertNoTypeReference(type,
                    "com/huanghuang/rsintegration/resonance/disk", "RS resonance disk");
            assertNoTypeReference(type,
                    "com/huanghuang/rsintegration/resonance/bd", "Beyond Dimensions backend");
        }
    }

    @Test
    void sharedJeiAndPlanningClassesDoNotLinkBotaniaTypes() throws IOException {
        assertNoBotaniaTypeReference(RecipeGuiLayoutsMixin.class);
        assertNoBotaniaTypeReference(PlanWarnings.class);
    }

    @Test
    void sharedJeiMixinDoesNotLinkApotheosisTypes() throws IOException {
        assertNoTypeReference(RecipeGuiLayoutsMixin.class, "dev/shadowsoffire/apotheosis",
                "Apotheosis");
    }

    @Test
    void sharedJeiMixinDoesNotLinkLycheeTypes() throws IOException {
        assertNoTypeReference(RecipeGuiLayoutsMixin.class, "snownee/lychee", "Lychee");
    }

    @Test
    void sharedJeiMixinDoesNotLinkOtherOptionalRecipeModTypes() throws IOException {
        String[][] dependencies = {
                {"alabaster/crabbersdelight", "Crabber's Delight"},
                {"com/Polarice3/Goety", "Goety"},
                {"com/aetherteam/aether", "The Aether"},
                {"com/github/tartaricacid/touhoulittlemaid", "Touhou Little Maid"},
                {"com/hollingsworth/arsnouveau", "Ars Nouveau"},
                {"com/sammy/malum", "Malum"},
                {"com/stal111/forbidden_arcanus", "Forbidden and Arcanus"},
                {"committee/nova/mods/avaritia", "Avaritia"},
                {"dev/ftb/mods/ftbquests", "FTB Quests"},
                {"dev/xkmc/youkaishomecoming", "Youkai's Homecoming"},
                {"elucent/eidolon", "Eidolon"},
                {"mod/maxbogomol/wizards_reborn", "Wizard's Reborn"},
                {"net/blay09/mods/farmingforblockheads", "Farming for Blockheads"},
                {"team/lodestar/embers", "Embers Rekindled"},
                {"vectorwing/farmersdelight", "Farmer's Delight"}
        };
        for (String[] dependency : dependencies) {
            assertNoTypeReference(RecipeGuiLayoutsMixin.class, dependency[0], dependency[1]);
        }
    }

    @Test
    void jeiMarqueeDoesNotLinkVersionSpecificJeiImplementations() throws IOException {
        assertNoTypeReference(JeiMarqueeSelector.class, "mezz/jei/gui", "JEI GUI internals");
        assertNoTypeReference(JeiMarqueeSelector.class, "mezz/jei/common", "JEI common internals");
    }

    @Test
    void pmmoJeiCompatibilityHasNoHardPmmoTypeLinks() throws IOException {
        assertNoTypeReference(PmmoSalvageAccess.class, "harmonised/pmmo", "Project MMO");
        assertNoTypeReference(PmmoSalvageJeiBridge.class, "harmonised/pmmo", "Project MMO");
        assertNoTypeReference(PmmoSalvageCatalog.class, "harmonised/pmmo", "Project MMO");
        assertNoTypeReference(PmmoSalvageRuntime.class, "harmonised/pmmo", "Project MMO");
    }

    @Test
    void wizardTerraCuriosMixinSoftFailsAcrossApiVersions() throws IOException {
        byte[] bytecode = classBytes(BuffItemMixin.class);
        AtomicBoolean hasShadow = new AtomicBoolean();
        AtomicReference<Integer> injectRequire = new AtomicReference<>();

        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor,
                                                             boolean visible) {
                        if ("Lorg/spongepowered/asm/mixin/Shadow;".equals(annotationDescriptor)) {
                            hasShadow.set(true);
                        }
                        AnnotationVisitor delegate = super.visitAnnotation(annotationDescriptor, visible);
                        if (!name.equals("rsi$applyDiskBuffs")
                                || !"Lorg/spongepowered/asm/mixin/injection/Inject;"
                                .equals(annotationDescriptor)) {
                            return delegate;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visit(String key, Object value) {
                                if ("require".equals(key)) injectRequire.set((Integer) value);
                                super.visit(key, value);
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertFalse(hasShadow.get(), "optional-mod mixin must not require target members via @Shadow");
        assertEquals(0, injectRequire.get(), "optional target method must use require = 0");
    }

    @Test
    void artificialFlowerCompatibilityOnlyExtendsNativeDebuffLookup() throws IOException {
        byte[] bytecode = classBytes(ArtificialFlowerMixin.class);
        AtomicReference<String> targetMethod = new AtomicReference<>();
        AtomicReference<Integer> redirectRequire = new AtomicReference<>();

        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor,
                                                             boolean visible) {
                        AnnotationVisitor delegate = super.visitAnnotation(annotationDescriptor, visible);
                        if (!name.equals("rsi$includeDiskFlowers")
                                || !"Lorg/spongepowered/asm/mixin/injection/Redirect;"
                                .equals(annotationDescriptor)) {
                            return delegate;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visit(String key, Object value) {
                                if ("require".equals(key)) redirectRequire.set((Integer) value);
                                super.visit(key, value);
                            }

                            @Override
                            public AnnotationVisitor visitArray(String key) {
                                AnnotationVisitor arrayDelegate = super.visitArray(key);
                                if (!"method".equals(key)) return arrayDelegate;
                                return new AnnotationVisitor(Opcodes.ASM9, arrayDelegate) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        targetMethod.set((String) value);
                                        super.visit(ignored, value);
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertEquals("onEffectApply", targetMethod.get());
        assertEquals(0, redirectRequire.get());
    }

    private static void assertNoBotaniaTypeReference(Class<?> type) throws IOException {
        assertNoTypeReference(type, "vazkii/botania", "Botania");
    }

    private static void assertNoTypeReference(Class<?> type, String internalPackage,
                                              String dependencyName) throws IOException {
        String constantPool = new String(classBytes(type), StandardCharsets.ISO_8859_1);
        assertFalse(constantPool.contains(internalPackage),
                () -> type.getName() + " directly links optional " + dependencyName + " bytecode");
    }

    /**
     * Curios is {@code mandatory = false}. A direct reference compiles a hard
     * {@code invokestatic} to {@code CuriosApi}, and on a server without Curios the
     * JVM throws {@link NoClassDefFoundError} — an {@link Error}, which the
     * {@code catch (Exception)} these call sites used does NOT intercept. All access
     * must go through {@code util.CuriosAccess}, which gates on ModList and reflects.
     */
    @Test
    void curiosIsOnlyReachedThroughTheReflectiveHelper() throws IOException {
        Path classRoot = Path.of("build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classRoot),
                () -> "compile the mod before running this test: " + classRoot.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> classes = Files.walk(classRoot)) {
            classes.filter(p -> p.toString().endsWith(".class"))
                    // The helper itself resolves the class by name at runtime.
                    .filter(p -> !p.toString().replace('\\', '/').contains("/util/CuriosAccess"))
                    // Mixins whose @Mixin target itself requires Curios: if Curios is
                    // absent the target is too, so the mixin is never applied and its
                    // Curios references are never linked. RSIntegrationMixinPlugin
                    // gates this one on SlotContext being present.
                    .filter(p -> !p.toString().replace('\\', '/')
                            .contains("/mixin/moonstone/NineSwordBooksMixin"))
                    .forEach(p -> {
                        byte[] bytes;
                        try {
                            bytes = Files.readAllBytes(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        // A reflective Class.forName("top.theillusivec4...") stores the
                        // name dotted; a hard link stores it slash-separated.
                        if (new String(bytes, StandardCharsets.ISO_8859_1)
                                .contains("top/theillusivec4/curios")) {
                            offenders.add(classRoot.relativize(p).toString());
                        }
                    });
        }

        assertEquals(List.of(), offenders,
                "these classes hard-link Curios types; route the access through util.CuriosAccess");
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing class resource " + resource);
            return input.readAllBytes();
        }
    }
}
