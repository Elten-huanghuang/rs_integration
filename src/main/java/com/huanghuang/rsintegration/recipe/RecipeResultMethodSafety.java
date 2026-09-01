package com.huanghuang.rsintegration.recipe;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Prevents dedicated-server recipe indexing from entering client-only result methods. */
final class RecipeResultMethodSafety {
    private static final String CLIENT_PREFIX = "net/minecraft/client/";
    private static final Map<Class<?>, Boolean> SAFE_CACHE = new ConcurrentHashMap<>();

    private RecipeResultMethodSafety() {}

    static boolean mayInvokeOnDedicatedServer(Class<?> recipeClass) {
        return SAFE_CACHE.computeIfAbsent(recipeClass,
                RecipeResultMethodSafety::inspectResultMethod);
    }

    static void markUnsafe(Class<?> recipeClass) {
        SAFE_CACHE.put(recipeClass, false);
    }

    static void clear() {
        SAFE_CACHE.clear();
    }

    private static boolean inspectResultMethod(Class<?> recipeClass) {
        Method contract = findRecipeContractMethod();
        if (contract == null) return false;
        final Method implementation;
        try {
            implementation = recipeClass.getMethod(contract.getName(), RegistryAccess.class);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return false;
        }

        Class<?> owner = implementation.getDeclaringClass();
        if (owner == Recipe.class) return true;
        String internalName = owner.getName().replace('.', '/');
        String resource = "/" + internalName + ".class";
        try (InputStream input = owner.getResourceAsStream(resource)) {
            if (input == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return !methodClosureReferencesClient(node, implementation.getName(),
                    Type.getMethodDescriptor(implementation));
        } catch (Exception | LinkageError failure) {
            // Failing closed is preferable to asking DistCleaner to load a client class.
            return false;
        }
    }

    private static Method findRecipeContractMethod() {
        for (Method method : Recipe.class.getMethods()) {
            if (method.getReturnType() == ItemStack.class
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == RegistryAccess.class) {
                return method;
            }
        }
        return null;
    }

    private static boolean methodClosureReferencesClient(
            ClassNode owner, String methodName, String descriptor) {
        Map<String, MethodNode> methods = new HashMap<>();
        for (MethodNode method : owner.methods) {
            methods.put(method.name + method.desc, method);
        }
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(methodName + descriptor);
        while (!pending.isEmpty()) {
            String key = pending.removeFirst();
            if (!visited.add(key)) continue;
            MethodNode method = methods.get(key);
            if (method == null) return true;
            if (referencesClient(method.desc)) return true;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instructionReferencesClient(instruction, owner.name, pending)) return true;
            }
        }
        return false;
    }

    private static boolean instructionReferencesClient(
            AbstractInsnNode instruction, String owner, ArrayDeque<String> pending) {
        if (instruction instanceof TypeInsnNode type) {
            return referencesClient(type.desc);
        }
        if (instruction instanceof FieldInsnNode field) {
            return referencesClient(field.owner) || referencesClient(field.desc);
        }
        if (instruction instanceof MethodInsnNode method) {
            if (referencesClient(method.owner) || referencesClient(method.desc)) return true;
            if (owner.equals(method.owner)) pending.add(method.name + method.desc);
            return false;
        }
        if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            if (referencesClient(dynamic.desc) || handleReferencesClient(dynamic.bsm)) return true;
            for (Object argument : dynamic.bsmArgs) {
                if (constantReferencesClient(argument)) return true;
                if (argument instanceof Handle handle && owner.equals(handle.getOwner())) {
                    pending.add(handle.getName() + handle.getDesc());
                }
            }
            return false;
        }
        if (instruction instanceof LdcInsnNode constant) {
            return constantReferencesClient(constant.cst);
        }
        return instruction instanceof MultiANewArrayInsnNode array
                && referencesClient(array.desc);
    }

    private static boolean constantReferencesClient(Object constant) {
        if (constant instanceof Type type) return referencesClient(type.getDescriptor());
        if (constant instanceof Handle handle) return handleReferencesClient(handle);
        if (constant instanceof ConstantDynamic dynamic) {
            if (referencesClient(dynamic.getDescriptor())
                    || handleReferencesClient(dynamic.getBootstrapMethod())) return true;
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                if (constantReferencesClient(dynamic.getBootstrapMethodArgument(index))) return true;
            }
            return false;
        }
        return constant instanceof String text && referencesClient(text.replace('.', '/'));
    }

    private static boolean handleReferencesClient(Handle handle) {
        return referencesClient(handle.getOwner()) || referencesClient(handle.getDesc());
    }

    private static boolean referencesClient(String value) {
        return value != null && value.contains(CLIENT_PREFIX);
    }
}
