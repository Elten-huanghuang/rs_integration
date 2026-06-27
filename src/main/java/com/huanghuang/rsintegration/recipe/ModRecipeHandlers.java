package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link ModRecipeHandler} instances, keyed by {@link ModType}.
 *
 * <p>Call {@link #handlerFor(Recipe)} to get the handler for any recipe.
 * Handlers are queried in registration order; the first whose
 * {@link ModRecipeHandler#canHandle} returns true wins.</p>
 */
public final class ModRecipeHandlers {

    private static final Map<Class<?>, Method> RESULT_METHOD_CACHE = new ConcurrentHashMap<>();

    private static final List<ModRecipeHandler> HANDLERS = List.of(
            new GenericRecipeHandler(),
            new MalumRecipeHandler(),
            new EidolonRecipeHandler(),
            new FaRecipeHandler(),
            new GoetyRecipeHandler(),
            new WRRecipeHandler(),
            new TlmAltarRecipeHandler(),
            new EreAlchemyRecipeHandler()
    );

    private ModRecipeHandlers() {}

    // ── dispatch ──────────────────────────────────────────────────

    @Nullable
    public static ModRecipeHandler handlerFor(Recipe<?> recipe) {
        for (ModRecipeHandler h : HANDLERS) {
            if (h.canHandle(recipe)) return h;
        }
        return null;
    }

    @Nullable
    public static ModRecipeHandler handlerFor(ModType type) {
        for (ModRecipeHandler h : HANDLERS) {
            if (h.modType() == type) return h;
        }
        return null;
    }

    // ── shared result-item extraction ─────────────────────────────

    /**
     * Common {@code getResultItem} implementation via reflection.
     * Tries known method names and caches the resolved {@link Method} per class.
     */
    public static ItemStack tryGetResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof CraftingRecipe cr) {
            return cr.getResultItem(access);
        }
        Class<?> clazz = recipe.getClass();

        // Check cache
        Method cached = RESULT_METHOD_CACHE.get(clazz);
        if (cached != null) {
            try {
                Object r = cached.getParameterCount() == 1
                        ? cached.invoke(recipe, access)
                        : cached.invoke(recipe);
                if (r instanceof ItemStack s && !s.isEmpty()) return s;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Handler] cached getResultItem failed for {}: {}",
                        clazz.getName(), e.toString());
            }
        }

        // Probe known method names
        for (String name : new String[]{"getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() > 1) continue;
                try {
                    Object r = m.getParameterCount() == 1
                            ? m.invoke(recipe, access)
                            : m.invoke(recipe);
                    if (r instanceof ItemStack s && !s.isEmpty()) {
                        RESULT_METHOD_CACHE.put(clazz, m);
                        return s;
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Handler] probe {} on {} failed: {}",
                            name, clazz.getName(), e.toString());
                }
            }
        }

        // Fallback: scan fields for an output ItemStack
        return tryGetOutputField(recipe);
    }

    private static ItemStack tryGetOutputField(Recipe<?> recipe) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field f : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    ItemStack s = (ItemStack) f.get(recipe);
                    if (s != null && !s.isEmpty()) return s.copy();
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            scan = scan.getSuperclass();
        }
        return ItemStack.EMPTY;
    }

    // ── shared secondary-output extraction ────────────────────────

    public static List<ItemStack> tryGetSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        return com.huanghuang.rsintegration.crafting.ModRecipeIndex.tryGetSecondaryOutputs(recipe, access);
    }
}
