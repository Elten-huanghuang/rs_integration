package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.recipe.viewer.StandardRecipeIdResolver;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandardRecipeIdResolverTest {

    @Test
    void resolvesThirdPartyGetIdMethod() {
        ResourceLocation id = new ResourceLocation("malum", "spirit_focusing/test");

        assertEquals(id, StandardRecipeIdResolver.resolve(new DirectRecipe(id)));
    }

    @Test
    void resolvesSrgRecipeIdMethod() {
        ResourceLocation id = new ResourceLocation("minecraft", "campfire/test");

        assertEquals(id, StandardRecipeIdResolver.resolve(new SrgRecipe(id)));
    }

    @Test
    void resolvesInheritedNonPublicGetIdMethod() {
        ResourceLocation id = new ResourceLocation("test", "inherited");

        assertEquals(id, StandardRecipeIdResolver.resolve(new InheritedRecipe(id)));
    }

    @Test
    void resolvesNativeRecipeWithUnavailableMethodSignature() throws ReflectiveOperationException {
        Object recipe = recipeWithMissingSignature(RecipeWithId.class, true);

        assertThrows(NoClassDefFoundError.class, () -> recipe.getClass().getDeclaredMethods());
        assertEquals(new ResourceLocation("test", "missing_signature"), ((Recipe<?>) recipe).getId());
        assertEquals(new ResourceLocation("test", "missing_signature"), StandardRecipeIdResolver.resolve(recipe));
    }

    @Test
    void resolvesInheritedIdDespiteUnavailableSubclassMethodSignature() throws ReflectiveOperationException {
        Object recipe = recipeWithMissingSignature(RecipeWithId.class, false);

        assertThrows(NoClassDefFoundError.class, () -> recipe.getClass().getDeclaredMethods());
        assertEquals(new ResourceLocation("test", "missing_signature"), StandardRecipeIdResolver.resolve(recipe));
    }

    @Test
    void ignoresNonRecipeWithUnavailableMethodSignature() throws ReflectiveOperationException {
        Object recipe = recipeWithMissingSignature(Object.class, false);

        assertThrows(NoClassDefFoundError.class, () -> recipe.getClass().getDeclaredMethods());
        assertNull(StandardRecipeIdResolver.resolve(recipe));
    }

    /** 模拟附属模组注入的方法仍引用当前 Goety 已不存在的类型。 */
    private static Object recipeWithMissingSignature(Class<?> parent, boolean nativeRecipe)
            throws ReflectiveOperationException {
        String name = "test/MissingSignatureRecipe";
        String parentName = Type.getInternalName(parent);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parentName,
                nativeRecipe ? new String[]{Type.getInternalName(Recipe.class)} : null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parentName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "getRitualType",
                "()Lcom/Polarice3/Goety/common/crafting/RitualRecipe$RitualType;", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();

        return new ClassLoader(StandardRecipeIdResolverTest.class.getClassLoader()) {
            Object instantiate(byte[] bytes) throws ReflectiveOperationException {
                return defineClass(null, bytes, 0, bytes.length).getConstructor().newInstance();
            }
        }.instantiate(writer.toByteArray());
    }

    public static class RecipeWithId {
        public ResourceLocation getId() {
            return new ResourceLocation("test", "missing_signature");
        }
    }

    private record DirectRecipe(ResourceLocation id) {
        public ResourceLocation getId() {
            return id;
        }
    }

    private record SrgRecipe(ResourceLocation id) {
        public ResourceLocation m_6423_() {
            return id;
        }
    }

    private static class RecipeBase {
        private final ResourceLocation id;

        private RecipeBase(ResourceLocation id) {
            this.id = id;
        }

        protected ResourceLocation getId() {
            return id;
        }
    }

    private static final class InheritedRecipe extends RecipeBase {
        private InheritedRecipe(ResourceLocation id) {
            super(id);
        }
    }
}
