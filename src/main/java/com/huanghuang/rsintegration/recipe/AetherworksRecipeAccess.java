package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.LogSampler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cached, defensive access to the recipe API variants shipped by Aetherworks add-ons. */
final class AetherworksRecipeAccess {
    private static final Map<Class<?>, AccessPlan> PLANS = new ConcurrentHashMap<>();
    private static final LogSampler FAILURE_LOGS = new LogSampler(60_000L);

    private AetherworksRecipeAccess() {}

    static ItemStack result(Object recipe, RegistryAccess access, List<String> methodOrder) {
        AccessPlan plan = plan(recipe);
        for (String name : methodOrder) {
            for (Method method : plan.methods(name)) {
                if (!ItemStack.class.isAssignableFrom(method.getReturnType())) continue;
                Object value = invoke(recipe, method, access);
                if (value instanceof ItemStack stack && !stack.isEmpty()) return stack.copy();
            }
        }
        for (Field field : plan.outputFields()) {
            try {
                Object value = field.get(recipe);
                if (value instanceof ItemStack stack && !stack.isEmpty()) return stack.copy();
            } catch (ReflectiveOperationException | RuntimeException e) {
                logFailure(recipe.getClass(), field.getName(), e);
            }
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    static Ingredient ingredient(Object recipe, String methodName) {
        Object value = invokeFirstNoArg(recipe, methodName);
        return value instanceof Ingredient ingredient && !ingredient.isEmpty() ? ingredient : null;
    }

    @Nullable
    static List<Ingredient> ingredients(Object recipe, String methodName) {
        Object value = invokeFirstNoArg(recipe, methodName);
        if (!(value instanceof List<?> list)) return null;
        List<Ingredient> ingredients = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Ingredient ingredient && !ingredient.isEmpty()) {
                ingredients.add(ingredient);
            }
        }
        return ingredients.isEmpty() ? null : List.copyOf(ingredients);
    }

    @Nullable
    static List<?> list(Object recipe, String methodName) {
        Object value = invokeFirstNoArg(recipe, methodName);
        return value instanceof List<?> list ? list : null;
    }

    private static Object invokeFirstNoArg(Object recipe, String methodName) {
        for (Method method : plan(recipe).methods(methodName)) {
            if (method.getParameterCount() != 0) continue;
            Object value = invoke(recipe, method, null);
            if (value != null) return value;
        }
        return null;
    }

    private static Object invoke(Object recipe, Method method, @Nullable RegistryAccess access) {
        try {
            if (method.getParameterCount() == 0) return method.invoke(recipe);
            Class<?> parameterType = method.getParameterTypes()[0];
            if (access == null || !parameterType.isInstance(access)) return null;
            return method.invoke(recipe, access);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailure(recipe.getClass(), method.getName(), e);
            return null;
        }
    }

    private static AccessPlan plan(Object recipe) {
        return PLANS.computeIfAbsent(recipe.getClass(), AetherworksRecipeAccess::probe);
    }

    private static AccessPlan probe(Class<?> recipeClass) {
        Map<String, List<Method>> methods = new LinkedHashMap<>();
        for (Method method : recipeClass.getMethods()) {
            if (method.getParameterCount() > 1) continue;
            try {
                method.setAccessible(true);
                methods.computeIfAbsent(method.getName(), ignored -> new ArrayList<>()).add(method);
            } catch (RuntimeException e) {
                logFailure(recipeClass, method.getName(), e);
            }
        }

        List<Field> outputFields = new ArrayList<>();
        for (Class<?> current = recipeClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                String name = field.getName();
                if (!("output".equals(name) || "result".equals(name))
                        || !ItemStack.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    outputFields.add(field);
                } catch (RuntimeException e) {
                    logFailure(recipeClass, name, e);
                }
            }
        }

        Map<String, List<Method>> immutableMethods = new LinkedHashMap<>();
        methods.forEach((name, candidates) -> {
            candidates.sort((left, right) -> Integer.compare(
                    right.getParameterCount(), left.getParameterCount()));
            immutableMethods.put(name, List.copyOf(candidates));
        });
        return new AccessPlan(Map.copyOf(immutableMethods), List.copyOf(outputFields));
    }

    static boolean hasNoArgMethod(Object recipe, String methodName) {
        for (Method method : plan(recipe).methods(methodName)) {
            if (method.getParameterCount() == 0) return true;
        }
        return false;
    }

    private static void logFailure(Class<?> recipeClass, String member, Throwable failure) {
        String key = recipeClass.getName() + '#' + member + ':' + failure.getClass().getName();
        if (FAILURE_LOGS.allow(key)) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Aetherworks] Cached recipe accessor failed for {}#{}",
                    recipeClass.getName(), member, failure);
        }
    }

    private record AccessPlan(Map<String, List<Method>> methods, List<Field> outputFields) {
        private List<Method> methods(String name) {
            return methods.getOrDefault(name, List.of());
        }
    }
}
