package com.huanghuang.rsintegration.recipe.viewer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/** Resolves the ID method used by vanilla and third-party recipe objects. */
public final class StandardRecipeIdResolver {

    private StandardRecipeIdResolver() {}

    @Nullable
    public static ResourceLocation resolve(Object recipe) {
        if (recipe == null) return null;

        // 原生配方走接口调用，避免反射扫描到附属模组注入的失效方法签名。
        if (recipe instanceof Recipe<?> nativeRecipe) {
            try {
                ResourceLocation id = nativeRecipe.getId();
                if (id != null) return id;
            } catch (RuntimeException | LinkageError ignored) {
                // 仍允许兼容包装对象通过下面的反射路径提供 ID。
            }
        }

        ResourceLocation direct = invokeNamed(recipe, "getId");
        if (direct != null) return direct;

        ResourceLocation srg = invokeNamed(recipe, "m_6423_");
        if (srg != null) return srg;

        try {
            Method mapped = ObfuscationReflectionHelper.findMethod(recipe.getClass(), "m_6423_");
            return invoke(recipe, mapped);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static ResourceLocation invokeNamed(Object target, String name) {
        Method method = findMethod(target.getClass(), name);
        return method == null ? null : invoke(target, method);
    }

    @Nullable
    private static ResourceLocation invoke(Object target, Method method) {
        try {
            Object value = method.invoke(target);
            return value instanceof ResourceLocation id ? id : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findMethod(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
            // 子类存在缺失的可选类型时，继续尝试父类中可用的 ID 方法。
        }

        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
            }
        }
        return null;
    }
}
