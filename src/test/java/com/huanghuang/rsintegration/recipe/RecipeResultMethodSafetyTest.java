package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeResultMethodSafetyTest extends BootstrapTest {
    @BeforeEach
    void clearSafetyCache() {
        RecipeResultMethodSafety.clear();
    }

    @Test
    void detectsClientReferenceInResultMethod() {
        assertFalse(RecipeResultMethodSafety.mayInvokeOnDedicatedServer(
                ClientUnsafeRecipe.class));
    }

    @Test
    void ordinaryCraftingResultMethodRemainsCallable() {
        assertTrue(RecipeResultMethodSafety.mayInvokeOnDedicatedServer(
                ShapelessRecipe.class));
    }

    @Test
    void unrelatedClientMethodSignatureDoesNotLoadClientClasses() throws Exception {
        ClientRejectingLoader loader = new ClientRejectingLoader(false);
        Class<?> recipeClass = loader.loadClass(ClientRejectingLoader.RECIPE_NAME);

        assertTrue(RecipeResultMethodSafety.mayInvokeOnDedicatedServer(recipeClass));
        assertEquals(0, loader.clientLoadAttempts);
    }

    @Test
    void unsafeResultIsDetectedWithoutLoadingItsClientClasses() throws Exception {
        ClientRejectingLoader loader = new ClientRejectingLoader(true);
        Class<?> recipeClass = loader.loadClass(ClientRejectingLoader.RECIPE_NAME);

        assertFalse(RecipeResultMethodSafety.mayInvokeOnDedicatedServer(recipeClass));
        assertEquals(0, loader.clientLoadAttempts);
    }

    @Test
    void inheritedUnsafeResultIsDetected() {
        assertFalse(RecipeResultMethodSafety.mayInvokeOnDedicatedServer(
                InheritedClientUnsafeRecipe.class));
    }

    // 模拟独立服务端：配方可以加载，但任何客户端类解析都会被拒绝。
    private static final class ClientRejectingLoader extends ClassLoader {
        static final String RECIPE_NAME = "test.ClientSignatureRecipe";
        private final byte[] recipeBytes;
        int clientLoadAttempts;

        ClientRejectingLoader(boolean unsafeResult) {
            super(RecipeResultMethodSafetyTest.class.getClassLoader());
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RECIPE_NAME.replace('.', '/'),
                    null, Type.getInternalName(ShapelessRecipe.class), null);
            var clientMethod = writer.visitMethod(Opcodes.ACC_PUBLIC, "clientPreview",
                    "(Lnet/minecraft/client/multiplayer/ClientLevel;)V", null, null);
            clientMethod.visitCode();
            clientMethod.visitInsn(Opcodes.RETURN);
            clientMethod.visitMaxs(0, 2);
            clientMethod.visitEnd();
            if (unsafeResult) {
                var resultMethod = writer.visitMethod(Opcodes.ACC_PUBLIC, "getResultItem",
                        Type.getMethodDescriptor(Type.getType(ItemStack.class),
                                Type.getType(RegistryAccess.class)), null, null);
                resultMethod.visitCode();
                resultMethod.visitLdcInsn(Type.getObjectType("net/minecraft/client/multiplayer/ClientLevel"));
                resultMethod.visitInsn(Opcodes.POP);
                resultMethod.visitInsn(Opcodes.ACONST_NULL);
                resultMethod.visitInsn(Opcodes.ARETURN);
                resultMethod.visitMaxs(1, 2);
                resultMethod.visitEnd();
            }
            writer.visitEnd();
            recipeBytes = writer.toByteArray();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("net.minecraft.client.")) {
                clientLoadAttempts++;
                throw new ClassNotFoundException("客户端类在独立服务端不可用: " + name);
            }
            if (!RECIPE_NAME.equals(name)) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) loaded = defineClass(name, recipeBytes, 0, recipeBytes.length);
            if (resolve) resolveClass(loaded);
            return loaded;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.equals(RECIPE_NAME.replace('.', '/') + ".class")) {
                return new ByteArrayInputStream(recipeBytes);
            }
            return super.getResourceAsStream(name);
        }
    }

    @Test
    void dedicatedServerUsesOutputFieldWithoutInvokingUnsafeMethod() {
        ClientUnsafeRecipe recipe = new ClientUnsafeRecipe();

        ItemStack output = ModRecipeHandlers.tryGetCraftingResultItem(
                recipe, RegistryAccess.EMPTY, true);

        assertEquals(Items.DIAMOND, output.getItem());
        assertEquals(0, recipe.invocations);
    }

    private static class ClientUnsafeRecipe implements CraftingRecipe {
        private final ItemStack result = new ItemStack(Items.DIAMOND);
        private int invocations;

        @Override
        public boolean matches(CraftingContainer container, Level level) {
            return false;
        }

        @Override
        public ItemStack assemble(CraftingContainer container, RegistryAccess access) {
            return result.copy();
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return true;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess access) {
            invocations++;
            return ClientLevel.class.getName().isEmpty() ? ItemStack.EMPTY : result.copy();
        }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            return NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT));
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("test", "client_unsafe_result");
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return RecipeSerializer.SHAPELESS_RECIPE;
        }

        @Override
        public CraftingBookCategory category() {
            return CraftingBookCategory.MISC;
        }
    }

    private static final class InheritedClientUnsafeRecipe extends ClientUnsafeRecipe {}
}
