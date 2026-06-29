package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.vanilla.VanillaMachineRecipeHandler;
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
    private static final Map<Class<?>, ModRecipeHandler> HANDLER_CACHE = new ConcurrentHashMap<>();
    /** Sentinel stored in HANDLER_CACHE for recipe classes that have no registered handler. */
    private static final ModRecipeHandler NO_HANDLER = new ModRecipeHandler() {
        @Override public ModType modType() { return ModType.GENERIC; }
        @Override public boolean canHandle(Recipe<?> recipe) { return false; }
        @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) { return ItemStack.EMPTY; }
        @Nullable
        @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) { return null; }
    };

    private static final List<ModRecipeHandler> HANDLERS = List.of(
            new GenericRecipeHandler(),
            new VanillaMachineRecipeHandler(),
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
        Class<?> clazz = recipe.getClass();
        ModRecipeHandler cached = HANDLER_CACHE.get(clazz);
        if (cached != null) return cached == NO_HANDLER ? null : cached;
        for (ModRecipeHandler h : HANDLERS) {
            if (h.canHandle(recipe)) {
                HANDLER_CACHE.put(clazz, h);
                return h;
            }
        }
        HANDLER_CACHE.put(clazz, NO_HANDLER);
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
        // Handler dispatch first — compile-time dispatch is always correct
        ModRecipeHandler handler = handlerFor(recipe);
        if (handler != null) {
            ItemStack result = handler.getResultItem(recipe, access);
            if (!result.isEmpty()) return result;
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

        // Probe known method names — prefer 1-param (RegistryAccess) overloads
        // to avoid no-arg getResultItem() that returns a machine block icon.
        for (String name : new String[]{"getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            // Try 1-param methods first
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    Object r = m.invoke(recipe, access);
                    if (r instanceof ItemStack s && !s.isEmpty()) {
                        RESULT_METHOD_CACHE.put(clazz, m);
                        return s;
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Handler] probe {} on {} failed: {}",
                            name, clazz.getName(), e.toString());
                }
            }
            // Fall back to no-arg methods
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 0) continue;
                try {
                    Object r = m.invoke(recipe);
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
