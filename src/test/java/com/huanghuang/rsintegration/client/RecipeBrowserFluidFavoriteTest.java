package com.huanghuang.rsintegration.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeBrowserFluidFavoriteTest {
    @Test
    void jeiFavoriteConvertsInternalTokenToForgeFluidIngredient() throws IOException {
        Set<String> calls = references("client/RecipeBrowserBridge", "addJeiFavorite");
        assertTrue(calls.contains("InkFluidSupport.isToken"));
        assertTrue(calls.contains("InkFluidSupport.fluid"));
        assertTrue(calls.contains("ForgeTypes.FLUID_STACK"));
        assertTrue(calls.contains("VanillaTypes.ITEM_STACK"));
        assertTrue(calls.contains("IIngredientManager.createTypedIngredient"));
        assertTrue(calls.contains("IngredientBookmark.create"));
    }

    @Test
    void emiFavoritesAndRecipeLookupUseFluidIdentityNbtAndAmount() throws IOException {
        String bridge = "compat/emi/EmiClientBridge";
        Set<String> conversion = references(bridge, "browserIngredient");
        assertTrue(conversion.contains("InkFluidSupport.isToken"));
        assertTrue(conversion.contains("InkFluidSupport.fluid"));
        assertTrue(conversion.contains("FluidStack.getFluid"));
        assertTrue(conversion.contains("FluidStack.getTag"));
        assertTrue(conversion.contains("FluidStack.getAmount"));
        assertTrue(conversion.contains("EmiStack.of(Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/nbt/CompoundTag;J)Ldev/emi/emi/api/stack/EmiStack;"));
        assertTrue(references(bridge, "addFavorite").contains("EmiClientBridge.browserIngredient"));
        assertTrue(references(bridge, "showRecipesOrUses").contains("EmiClientBridge.browserIngredient"));
    }

    // 测试环境没有浏览器客户端实现，检查实际入口的字节码以避免加载可选依赖。
    private static Set<String> references(String className, String methodName) throws IOException {
        Path path = Path.of("build/classes/java/main/com/huanghuang/rsintegration", className + ".class");
        Set<String> result = new HashSet<>();
        new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (!name.equals(methodName)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                boolean isInterface) {
                        String call = owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
                        result.add(call);
                        result.add(call + descriptor);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        result.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + name);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }
}
