package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/** Resolves the ID method used by vanilla and third-party recipe objects. */
public final class StandardRecipeIdResolver {

    private StandardRecipeIdResolver() {}

    @Nullable
    public static ResourceLocation resolve(Object recipe) {
        if (recipe == null) return null;

        ResourceLocation direct = invokeNamed(recipe, "getId");
        if (direct != null) return direct;

        ResourceLocation srg = invokeNamed(recipe, "m_6423_");
        if (srg != null) return srg;

        try {
            Method mapped = ObfuscationReflectionHelper.findMethod(recipe.getClass(), "m_6423_");
            return invoke(recipe, mapped);
        } catch (RuntimeException ignored) {
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
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findMethod(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
        }

        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException ignored) {
            }
        }
        return null;
    }
}
