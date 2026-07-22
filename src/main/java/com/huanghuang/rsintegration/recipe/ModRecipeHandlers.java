package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.RecipeIndex;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.vanilla.VanillaMachineRecipeHandler;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Map<Class<?>, CraftTweakerOutputAccessor> CT_OUTPUT_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> NO_CT_OUTPUT_ACCESSOR_CACHE = ConcurrentHashMap.newKeySet();
    /** Caches the ItemStack-producing field per recipe class. */
    private static final Map<Class<?>, java.lang.reflect.Field> OUTPUT_FIELD_CACHE = new ConcurrentHashMap<>();
    /** Classes that have been scanned and have no suitable output field. */
    private static final Set<Class<?>> NO_OUTPUT_FIELD_CACHE = ConcurrentHashMap.newKeySet();
    /** Global result cache: recipe ID → output ItemStack (or EMPTY sentinel). */
    private static final Map<ResourceLocation, ItemStack> GLOBAL_RESULT_CACHE = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> GLOBAL_EMPTY_CACHE = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, ModRecipeHandler> HANDLER_CACHE = new ConcurrentHashMap<>();
    /** Prevents infinite recursion when a handler's getResultItem() calls back into tryGetResultItem(). */
    private static final ThreadLocal<Class<?>> DISPATCH_GUARD = new ThreadLocal<>();
    /** Sentinel stored in HANDLER_CACHE for recipe classes that have no registered handler. */
    private static final ModRecipeHandler NO_HANDLER = new ModRecipeHandler() {
        @Override public ModType modType() { return ModType.byId("generic"); }
        @Override public boolean canHandle(Recipe<?> recipe) { return false; }
        @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) { return ItemStack.EMPTY; }
        @Nullable
        @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) { return null; }
    };

    private static final List<ModRecipeHandler> HANDLERS = new ArrayList<>();

    static {
        HANDLERS.add(new GenericRecipeHandler());
        HANDLERS.add(new VanillaMachineRecipeHandler());
        HANDLERS.add(new SmithingRecipeHandler());
        HANDLERS.add(new com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingRecipeHandler());
        HANDLERS.add(new MarketRecipeHandler());
    }

    /**
     * Register a mod recipe handler. Call during mod construction from
     * {@code IModIntegration.registerRecipeHandler()}.
     */
    public static void register(ModRecipeHandler handler) {
        HANDLERS.add(handler);
        HANDLER_CACHE.clear(); // Invalidate — new handler may cover previously-unknown classes
    }

    private ModRecipeHandlers() {}

    // ── dispatch ──────────────────────────────────────────────────

    @Nullable
    public static ModRecipeHandler handlerFor(Recipe<?> recipe) {
        Class<?> clazz = recipe.getClass();
        ModRecipeHandler cached = HANDLER_CACHE.get(clazz);
        if (cached != null) return cached == NO_HANDLER ? null : cached;
        // Iterate in reverse so mod-specific handlers (registered later)
        // take priority over GenericRecipeHandler registered first.
        for (int i = HANDLERS.size() - 1; i >= 0; i--) {
            ModRecipeHandler h = HANDLERS.get(i);
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

    /** Clear recipe-result caches. Called on /reload so new recipes take effect. */
    public static void clearResultCaches() {
        GLOBAL_RESULT_CACHE.clear();
        GLOBAL_EMPTY_CACHE.clear();
    }

    // ── shared result-item extraction ─────────────────────────────

    /**
     * Common {@code getResultItem} with global result cache.
     * Expensive reflection is done at most once per recipe instance.
     */
    public static ItemStack tryGetResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof CraftingRecipe cr) {
            ItemStack result = cr.getResultItem(access);
            if (!result.isEmpty()) return result.copy();
            return tryGetCraftTweakerOutput(recipe);
        }
        ResourceLocation id = recipe.getId();
        if (GLOBAL_EMPTY_CACHE.contains(id)) return ItemStack.EMPTY;
        ItemStack cached = GLOBAL_RESULT_CACHE.get(id);
        if (cached != null) return cached.copy();

        ItemStack result = internalTryGetResultItem(recipe, access);

        if (result.isEmpty()) {
            GLOBAL_EMPTY_CACHE.add(id);
        } else {
            GLOBAL_RESULT_CACHE.put(id, result.copy());
        }
        return result.copy();
    }

    private static ItemStack tryGetCraftTweakerOutput(Recipe<?> recipe) {
        Class<?> clazz = recipe.getClass();
        if (!clazz.getName().startsWith("com.blamejared.crafttweaker.api.recipe.type.CT")) {
            return ItemStack.EMPTY;
        }
        if (NO_CT_OUTPUT_ACCESSOR_CACHE.contains(clazz)) return ItemStack.EMPTY;

        CraftTweakerOutputAccessor accessor = CT_OUTPUT_ACCESSOR_CACHE.get(clazz);
        if (accessor == null) {
            try {
                Method getCtOutput = clazz.getMethod("getCtOutput");
                Class<?> outputType = getCtOutput.getReturnType();
                Method getInternal;
                try {
                    getInternal = outputType.getMethod("getImmutableInternal");
                } catch (NoSuchMethodException ignored) {
                    getInternal = outputType.getMethod("getInternal");
                }
                if (!ItemStack.class.isAssignableFrom(getInternal.getReturnType())) {
                    NO_CT_OUTPUT_ACCESSOR_CACHE.add(clazz);
                    return ItemStack.EMPTY;
                }
                accessor = new CraftTweakerOutputAccessor(getCtOutput, getInternal);
                CT_OUTPUT_ACCESSOR_CACHE.put(clazz, accessor);
            } catch (ReflectiveOperationException e) {
                NO_CT_OUTPUT_ACCESSOR_CACHE.add(clazz);
                RSIntegrationMod.LOGGER.debug("[RSI-Handler] CraftTweaker output accessor unavailable for {}",
                        clazz.getName(), e);
                return ItemStack.EMPTY;
            }
        }

        try {
            Object ctOutput = accessor.getCtOutput().invoke(recipe);
            if (ctOutput == null) return ItemStack.EMPTY;
            Object internal = accessor.getInternal().invoke(ctOutput);
            return internal instanceof ItemStack stack && !stack.isEmpty()
                    ? stack.copy() : ItemStack.EMPTY;
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Handler] CraftTweaker output read failed for {}",
                    clazz.getName(), e);
            return ItemStack.EMPTY;
        }
    }

    private record CraftTweakerOutputAccessor(Method getCtOutput, Method getInternal) {}

    /**
     * Internal reflection-based result extraction.  Only called on cache miss
     * for non-CraftingRecipe instances.
     */
    private static ItemStack internalTryGetResultItem(Recipe<?> recipe, RegistryAccess access) {
        // Re-entry guard: when a handler's getResultItem() calls back into
        // tryGetResultItem() for the same recipe class, skip handler dispatch
        // and fall through to reflection to avoid StackOverflowError.
        if (DISPATCH_GUARD.get() != recipe.getClass()) {
            ModRecipeHandler handler = handlerFor(recipe);
            if (handler != null) {
                Class<?> prev = DISPATCH_GUARD.get();
                DISPATCH_GUARD.set(recipe.getClass());
                try {
                    ItemStack result = handler.getResultItem(recipe, access);
                    if (!result.isEmpty()) return result;
                } finally {
                    if (prev != null) {
                        DISPATCH_GUARD.set(prev);
                    } else {
                        DISPATCH_GUARD.remove();
                    }
                }
                // Handler exists but returned EMPTY — don't fall through to the
                // reflection probe (it would hit the deprecated no-arg
                // getResultItem(); see the probe loop below).
                return ItemStack.EMPTY;
            }
        }
        Class<?> clazz = recipe.getClass();

        Method cached = RESULT_METHOD_CACHE.get(clazz);
        if (cached != null) {
            try {
                Object r = cached.getParameterCount() == 1
                        ? cached.invoke(recipe, access)
                        : cached.invoke(recipe);
                if (r instanceof ItemStack s && !s.isEmpty()) return s.copy();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Handler] cached getResultItem failed for {}", clazz.getName(), e);
            }
        }

        // Probe known method names, preferring 1-param (RegistryAccess)
        // overloads.  The deprecated no-arg getResultItem() is skipped entirely:
        // several mods (WR, Malum) override it to return a machine block icon
        // instead of the recipe output.  In 1.20.1 getResultItem(RegistryAccess)
        // always exists on Recipe, so the no-arg probe is never needed for it.
        for (String name : new String[]{"getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            boolean isResultItem = "getResultItem".equals(name);
            // Try 1-param methods first
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    Object r = m.invoke(recipe, access);
                    if (r instanceof ItemStack s && !s.isEmpty()) {
                        RESULT_METHOD_CACHE.put(clazz, m);
                        return s.copy();
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Handler] probe {} on {} failed", name, clazz.getName(), e);
                }
            }
            // No-arg getResultItem() is the abused overload — skip it (see above).
            if (isResultItem) continue;
            // Fall back to no-arg methods (other method names)
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 0) continue;
                try {
                    Object r = m.invoke(recipe);
                    if (r instanceof ItemStack s && !s.isEmpty()) {
                        RESULT_METHOD_CACHE.put(clazz, m);
                        return s.copy();
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Handler] probe {} on {} failed", name, clazz.getName(), e);
                }
            }
        }

        // Fallback: scan fields for an output ItemStack
        return tryGetOutputField(recipe);
    }

    private static ItemStack tryGetOutputField(Recipe<?> recipe) {
        Class<?> clazz = recipe.getClass();
        if (NO_OUTPUT_FIELD_CACHE.contains(clazz)) return ItemStack.EMPTY;
        java.lang.reflect.Field cachedField = OUTPUT_FIELD_CACHE.get(clazz);
        if (cachedField != null) {
            try {
                ItemStack s = (ItemStack) cachedField.get(recipe);
                if (s != null && !s.isEmpty()) return s.copy();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Handler] cached output field read failed for {}", clazz.getName(), e);
            }
            return ItemStack.EMPTY;
        }
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field f : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(f.getType())) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("output") && !name.contains("result") && !name.contains("assembled"))
                    continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(recipe);
                    if (val instanceof ItemStack s && !s.isEmpty()) {
                        OUTPUT_FIELD_CACHE.put(clazz, f);
                        return s.copy();
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Handler] output field probe failed for {}", clazz.getName(), e);
                }
            }
            scan = scan.getSuperclass();
        }
        NO_OUTPUT_FIELD_CACHE.add(clazz);
        return ItemStack.EMPTY;
    }

    // ── shared secondary-output extraction ────────────────────────

    public static List<ItemStack> tryGetSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        return RecipeIndex.tryGetSecondaryOutputs(recipe, access);
    }

}
