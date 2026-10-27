package com.huanghuang.rsintegration.mixin.jei;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JeiBookmarkCompatibilityTest {

    @Test
    void legacyTooltipCallbackIncludesRequiredCallbackInfo() throws Exception {
        String resource = RecipeBookmarkButtonMixin.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = RecipeBookmarkButtonMixin.class
                .getClassLoader().getResourceAsStream(resource)) {
            ClassShape mixin = shape(input);
            assertEquals(Set.of(
                            "(Lmezz/jei/common/gui/JeiTooltip;"
                                    + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"),
                    mixin.methodDescriptors().get(
                            "rsIntegration$extendBookmarkTooltip"));
        }
    }

    @Test
    void unbookmarkingClearsRecipeExclusionPreference() throws Exception {
        String resource = BookmarkListChangeMixin.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = BookmarkListChangeMixin.class
                .getClassLoader().getResourceAsStream(resource)) {
            byte[] bytes = input.readAllBytes();
            ClassShape mixin = shape(new ByteArrayInputStream(bytes));
            assertEquals(Set.of("(Lmezz/jei/gui/bookmarks/IBookmark;"
                            + "Lorg/spongepowered/asm/mixin/injection/callback/"
                            + "CallbackInfoReturnable;)V"),
                    mixin.methodDescriptors().get("rsIntegration$bookmarkRemoved"));
            // 回调体必须真的去清理递归禁用偏好，否则取消收藏后拉黑会残留
            String constants = new String(bytes, StandardCharsets.ISO_8859_1);
            assertTrue(constants.contains(
                    "com/huanghuang/rsintegration/crafting/plan/ClientRecipeExclusions"));
            assertTrue(constants.contains("mezz/jei/gui/bookmarks/RecipeBookmark"));
            assertTrue(constants.contains("clear"));
        }
    }

    @Test
    void compatibilityJarStillExposesBookmarkStateAndInputHooks() throws Exception {
        String configured = System.getProperty("rsintegration.jei.compatibilityJar");
        assumeTrue(configured != null && !configured.isBlank());
        Path jarPath = Path.of(configured);
        assumeTrue(Files.isRegularFile(jarPath), "JEI compatibility jar not found");

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.getJarEntry(
                    "mezz/jei/gui/recipes/RecipeBookmarkButton.class") != null) {
                ClassShape button = shape(jar,
                        "mezz/jei/gui/recipes/RecipeBookmarkButton.class");
                assertTrue(button.fields().containsAll(
                        Set.of("bookmarks", "recipeBookmark", "bookmarked")));
                assertTrue(button.methods().containsAll(
                        Set.of("tick", "onMouseClicked", "draw", "isBookmarked")));
                assertTrue(button.methodDescriptors().get("getTooltips").contains(
                        "(Lmezz/jei/common/gui/JeiTooltip;)V"));
                assertTrue(button.methodDescriptors().get("onMouseClicked").contains(
                        "(Lmezz/jei/gui/input/UserInput;)Z"));

                ClassShape toggle = shape(jar,
                        "mezz/jei/gui/elements/GuiIconToggleButton.class");
                assertTrue(toggle.methods().contains("createInputHandler"));

                ClassShape tooltip = shape(jar,
                        "mezz/jei/common/gui/JeiTooltip.class");
                assertTrue(tooltip.methods().contains("add"));
            } else {
                ClassShape controller = shape(jar,
                        "mezz/jei/gui/recipes/RecipeBookmarkButtonController.class");
                assertTrue(controller.fields().containsAll(
                        Set.of("bookmarks", "recipeBookmark", "bookmarked")));
                assertTrue(controller.methods().containsAll(
                        Set.of("getTooltips", "onPress", "drawExtras", "isBookmarked")));
                assertTrue(controller.methodDescriptors().get("getTooltips").contains(
                        "(Lmezz/jei/api/gui/builder/ITooltipBuilder;)V"));
                assertTrue(shape(jar,
                        "mezz/jei/api/gui/inputs/IJeiUserInput.class")
                        .methods().contains("getKey"));
                assertTrue(shape(jar,
                        "mezz/jei/api/gui/builder/ITooltipBuilder.class")
                        .methods().contains("add"));
            }

            ClassShape bookmark = shape(jar,
                    "mezz/jei/gui/bookmarks/RecipeBookmark.class");
            assertTrue(bookmark.methods().contains("getRecipeUid"));

            ClassShape bookmarkList = shape(jar,
                    "mezz/jei/gui/bookmarks/BookmarkList.class");
            assertTrue(bookmarkList.methods().containsAll(
                    Set.of("add", "remove", "toggleBookmark")));
        }
    }

    private static ClassShape shape(JarFile jar, String entryName) throws Exception {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) throw new IllegalStateException("missing " + entryName);
        try (InputStream input = jar.getInputStream(entry)) {
            return shape(input);
        }
    }

    private static ClassShape shape(InputStream input) throws Exception {
        Set<String> fields = new HashSet<>();
        Set<String> methods = new HashSet<>();
        Map<String, Set<String>> methodDescriptors = new HashMap<>();
        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                fields.add(name);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.add(name);
                methodDescriptors.computeIfAbsent(name, ignored -> new HashSet<>())
                        .add(descriptor);
                return null;
            }
        }, 0);
        return new ClassShape(fields, methods, methodDescriptors);
    }

    private record ClassShape(Set<String> fields, Set<String> methods,
                              Map<String, Set<String>> methodDescriptors) {}
}
