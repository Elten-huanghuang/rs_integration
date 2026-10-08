package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.compat.jei.JeiRecipeButtonPlacement;
import com.huanghuang.rsintegration.mixin.jei.GuiIconToggleButtonAccessor;
import com.huanghuang.rsintegration.mixin.jei.RecipeGuiLayoutsMixin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JeiRecipeButtonCompatibilityTest {
    private static final String WRAPPER = "mezz/jei/gui/recipes/RecipeLayoutWithButtons.class";
    private static final String DRAWABLE = "mezz/jei/api/gui/IRecipeLayoutDrawable.class";
    private static final String TOGGLE_BUTTON = "mezz/jei/gui/elements/GuiIconToggleButton.class";

    @Test
    void supportedJeiVersionsExposeTheRealTransferButtonArea() throws IOException {
        List<Path> jars = new ArrayList<>(List.of(
                Path.of("libs", "jei-1.20.1-forge-15.20.0.129.jar"),
                Path.of("libs", "[JEI物品管理器] jei-1.20.1-forge-15.49.0.191.jar")));
        String additionalJar = System.getProperty("rsintegration.jei.compatibilityJar");
        if (additionalJar != null) jars.add(Path.of(additionalJar));

        for (Path jar : jars) {
            assertTrue(Files.isRegularFile(jar), () -> "Missing JEI compatibility fixture: " + jar);
            Set<String> wrapperMethods = methods(jar, WRAPPER);
            assertTrue(wrapperMethods.contains(
                    "transferButton()Lmezz/jei/gui/recipes/RecipeTransferButton;"),
                    () -> jar + " no longer exposes the authoritative transfer button");
            assertTrue(wrapperMethods.stream().anyMatch(method ->
                            method.startsWith("recipeLayout()")
                                    || method.startsWith("getRecipeLayout()")),
                    () -> jar + " no longer exposes a supported recipe-layout accessor");

            Set<String> drawableMethods = methods(jar, DRAWABLE);
            assertTrue(drawableMethods.contains("getRect()Lnet/minecraft/client/renderer/Rect2i;"),
                    () -> jar + " no longer exposes the current recipe rectangle");
            assertTrue(drawableMethods.contains(
                            "getRecipeTransferButtonArea()Lnet/minecraft/client/renderer/Rect2i;"),
                    () -> jar + " no longer exposes the relative transfer-button area");

            assertTrue(fields(jar, TOGGLE_BUTTON).contains(
                    "button:Lmezz/jei/gui/elements/GuiIconButton;"),
                    () -> jar + " no longer exposes the button field used by the mixin accessor");
        }
    }

    @Test
    void mixinPrefersCurrentRecipeAreaAndKeepsCachedButtonFallback() throws IOException {
        Set<String> calls = methodCalls(RecipeGuiLayoutsMixin.class, "rsi$getTransferButtonArea");

        assertTrue(calls.contains(owner(GuiIconToggleButtonAccessor.class) + ".getButton"));
        assertTrue(calls.contains(owner(JeiRecipeButtonPlacement.class) + ".resolveTransferArea"));
    }

    @Test
    void staticMixinHelpersRemainPrivate() throws IOException {
        new ClassReader(classBytes(RecipeGuiLayoutsMixin.class)).accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (name.startsWith("rsi$")
                                && (access & Opcodes.ACC_STATIC) != 0
                                && (access & Opcodes.ACC_PRIVATE) == 0) {
                            fail("Mixin static helper must be private: " + name + descriptor);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static Set<String> methods(Path jar, String entryName) throws IOException {
        Set<String> result = new HashSet<>();
        visit(jar, entryName, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                result.add(name + descriptor);
                return null;
            }
        });
        return result;
    }

    private static Set<String> fields(Path jar, String entryName) throws IOException {
        Set<String> result = new HashSet<>();
        visit(jar, entryName, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                result.add(name + ":" + descriptor);
                return null;
            }
        });
        return result;
    }

    private static void visit(Path jar, String entryName, ClassVisitor visitor) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);
            assertTrue(entry != null, () -> jar + " is missing " + entryName);
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input).accept(visitor,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
    }

    private static Set<String> methodCalls(Class<?> type, String targetMethod) throws IOException {
        Set<String> calls = new HashSet<>();
        new ClassReader(classBytes(type)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(targetMethod)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        calls.add(owner + "." + name);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + owner(type) + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing class resource " + resource);
            return input.readAllBytes();
        }
    }

    private static String owner(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
