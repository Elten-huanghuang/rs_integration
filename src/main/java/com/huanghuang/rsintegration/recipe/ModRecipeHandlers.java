package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipe;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipeHandler;
import com.huanghuang.rsintegration.crafting.planning.PlanningThreadContext;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.mods.vanilla.VanillaMachineRecipeHandler;
import com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingRecipeHandler;
import com.huanghuang.rsintegration.util.Reflect;
import com.mojang.logging.LogUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 配方处理器注册表，统一读取配方主产物和副产物。 */
public final class ModRecipeHandlers {
    private static final Logger RESULT_LOGGER = LogUtils.getLogger();

    private static final Map<Class<?>, Method> RESULT_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, CraftTweakerOutputAccessor> CT_OUTPUT_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> NO_CT_OUTPUT_ACCESSOR_CACHE = ConcurrentHashMap.newKeySet();
    /** 按配方类缓存产物字段。 */
    private static final Map<Class<?>, Field> OUTPUT_FIELD_CACHE = new ConcurrentHashMap<>();
    /** 已扫描且没有可用产物字段的配方类。 */
    private static final Set<Class<?>> NO_OUTPUT_FIELD_CACHE = ConcurrentHashMap.newKeySet();
    /** 非网格配方的静态结果按配方 ID 缓存。 */
    private static final Map<ResourceLocation, ItemStack> GLOBAL_RESULT_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> CLIENT_UNSAFE_RESULT_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, Claim> CLAIM_CACHE = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> AMBIGUOUS_CLAIMS_LOGGED =
            ConcurrentHashMap.newKeySet();
    /** 防止处理器直接回调，或经其他配方类间接回调时重复分发。 */
    private static final ThreadLocal<Set<Class<?>>> ACTIVE_RESULT_HANDLERS = new ThreadLocal<>();

    private static final List<ModRecipeHandler> HANDLERS = new ArrayList<>();
    private static final List<ModRecipeHandler> CLASS_HANDLERS = new ArrayList<>();
    private static final List<ModRecipeHandler> RECIPE_SENSITIVE_HANDLERS = new ArrayList<>();

    static {
        HANDLERS.add(new GenericRecipeHandler());
        HANDLERS.add(new VanillaMachineRecipeHandler());
        HANDLERS.add(new SmithingRecipeHandler());
        HANDLERS.add(new VanillaBrewingRecipeHandler());
        HANDLERS.add(new FluidContainerRecipeHandler());
        for (ModRecipeHandler handler : HANDLERS) {
            if (handler.cacheByRecipeClass()) CLASS_HANDLERS.add(handler);
        }
    }

    public record Claim(@Nullable ModRecipeHandler winner,
                        List<ModRecipeHandler> candidates) {
        public Claim {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public boolean ambiguous() {
            return candidates.size() > 1;
        }
    }

    /**
     * Register a mod recipe handler. Call during mod construction from
     * {@code IModIntegration.registerRecipeHandler()}.
     */
    public static void register(ModRecipeHandler handler) {
        HANDLERS.add(handler);
        if (handler.cacheByRecipeClass()) CLASS_HANDLERS.add(handler);
        else RECIPE_SENSITIVE_HANDLERS.add(handler);
        CLAIM_CACHE.clear();
        AMBIGUOUS_CLAIMS_LOGGED.clear();
    }

    private ModRecipeHandlers() {}

    // ── dispatch ──────────────────────────────────────────────────

    /** 返回同一配方在最高优先级上的唯一 winner，供 typed/pure 路径共享。 */
    public static Claim claimFor(Recipe<?> recipe) {
        Objects.requireNonNull(recipe, "recipe");
        Claim fixed = CLAIM_CACHE.computeIfAbsent(recipe.getClass(), ignored -> {
            List<ModRecipeHandler> classMatches = new ArrayList<>();
            for (ModRecipeHandler handler : CLASS_HANDLERS) {
                if (handler.canHandle(recipe)) classMatches.add(handler);
            }
            return selectClaim(classMatches);
        });
        // 实例相关处理器仍逐个检查；未匹配时无需复制列表并重新选择同一个处理器。
        List<ModRecipeHandler> matching = null;
        for (ModRecipeHandler handler : RECIPE_SENSITIVE_HANDLERS) {
            if (!handler.canHandle(recipe)) continue;
            if (matching == null) matching = new ArrayList<>(fixed.candidates());
            matching.add(handler);
        }
        Claim claim = matching == null ? fixed : selectClaim(matching);
        if (claim.ambiguous() && AMBIGUOUS_CLAIMS_LOGGED.add(recipe.getId())) {
            RESULT_LOGGER.warn("[RSI] Ambiguous recipe handlers for {}: {}",
                    recipe.getId(), claim.candidates().stream()
                            .map(handler -> handler.getClass().getName()
                                    + "#priority=" + handler.claimPriority())
                            .toList());
        }
        return claim;
    }

    static Claim selectClaim(List<ModRecipeHandler> matching) {
        if (matching == null || matching.isEmpty()) return new Claim(null, List.of());
        int highest = Integer.MIN_VALUE;
        for (ModRecipeHandler handler : matching) {
            highest = Math.max(highest, handler.claimPriority());
        }
        List<ModRecipeHandler> candidates = new ArrayList<>();
        for (ModRecipeHandler handler : matching) {
            if (handler.claimPriority() == highest) candidates.add(handler);
        }
        return new Claim(candidates.size() == 1 ? candidates.get(0) : null, candidates);
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
        CLIENT_UNSAFE_RESULT_LOGGED.clear();
        CLAIM_CACHE.clear();
        RecipeResultMethodSafety.clear();
    }

    // ── 主产物提取 ───────────────────────────────────────────────

    /** 读取声明的主产物；非网格配方使用结果缓存。 */
    public static ItemStack tryGetResultItem(@Nullable Recipe<?> recipe, RegistryAccess access) {
        if (recipe == null) return ItemStack.EMPTY;
        if (recipe instanceof CraftingRecipe cr) {
            return tryGetCraftingResultItem(cr, access, isDedicatedServer());
        }
        // 第三方处理器可能访问注册表或执行反射，后台规划只消费已投影的结果。
        PlanningThreadContext.requireMainThread("third-party recipe result extraction");
        if (isDispatching(recipe)) return probeNonGridResult(recipe, access);

        ResourceLocation id = recipe.getId();
        ItemStack cached = GLOBAL_RESULT_CACHE.get(id);
        if (cached != null) return cached.copy();

        ItemStack result = resolveNonGridResult(recipe, access);
        GLOBAL_RESULT_CACHE.put(id, result.copy());
        return result;
    }

    /** 读取当前配方实例的主产物，不读写按配方 ID 保存的结果缓存。 */
    public static ItemStack tryGetUncachedResultItem(@Nullable Recipe<?> recipe, RegistryAccess access) {
        PlanningThreadContext.requireMainThread("third-party recipe result extraction");
        if (recipe == null) return ItemStack.EMPTY;
        if (recipe instanceof CraftingRecipe cr) {
            return tryGetCraftingResultItem(cr, access, isDedicatedServer());
        }
        return resolveNonGridResult(recipe, access);
    }

    /** 处理器的默认产物探测，不再分发处理器，也不写结果缓存。 */
    public static ItemStack probeResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof CraftingRecipe cr) {
            ItemStack result = readCraftingResult(cr, access, isDedicatedServer());
            return result.isEmpty() ? probeCraftingFallback(cr) : result;
        }
        PlanningThreadContext.requireMainThread("third-party recipe result extraction");
        return probeNonGridResult(recipe, access);
    }

    private static boolean isDedicatedServer() {
        return FMLEnvironment.dist == Dist.DEDICATED_SERVER;
    }

    static ItemStack tryGetCraftingResultItem(CraftingRecipe recipe, RegistryAccess access,
                                               boolean dedicatedServer) {
        ItemStack result = readCraftingResult(recipe, access, dedicatedServer);
        if (!result.isEmpty()) return result;

        ModRecipeHandler handler = resultHandler(recipe);
        // 通用处理器只会重复调用接口；专服判定不安全时也不能绕过检查。
        if (handler != null && !(handler instanceof GenericRecipeHandler)) {
            ItemStack handlerResult = readHandlerResult(handler, recipe, access);
            if (!handlerResult.isEmpty()) return handlerResult;
        }
        return probeCraftingFallback(recipe);
    }

    private static ItemStack readCraftingResult(CraftingRecipe recipe, RegistryAccess access,
                                                boolean dedicatedServer) {
        if (!dedicatedServer
                || RecipeResultMethodSafety.mayInvokeOnDedicatedServer(recipe.getClass())) {
            try {
                ItemStack result = recipe.getResultItem(access);
                if (result != null && !result.isEmpty()) return result.copy();
            } catch (RuntimeException | LinkageError failure) {
                if (!dedicatedServer) throw failure;
                RecipeResultMethodSafety.markUnsafe(recipe.getClass());
                logClientUnsafeFallback(recipe.getClass(), failure);
            }
        } else {
            logClientUnsafeFallback(recipe.getClass(), null);
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack probeCraftingFallback(CraftingRecipe recipe) {
        ItemStack craftTweakerResult = tryGetCraftTweakerOutput(recipe);
        if (!craftTweakerResult.isEmpty()) return craftTweakerResult;
        return tryGetOutputField(recipe);
    }

    private static ItemStack resolveNonGridResult(Recipe<?> recipe, RegistryAccess access) {
        ModRecipeHandler handler = resultHandler(recipe);
        // 非网格配方的处理器明确返回空时，不再探测。
        return handler == null
                ? probeNonGridResult(recipe, access)
                : readHandlerResult(handler, recipe, access);
    }

    private static boolean isDispatching(Recipe<?> recipe) {
        Set<Class<?>> active = ACTIVE_RESULT_HANDLERS.get();
        return active != null && active.contains(recipe.getClass());
    }

    @Nullable
    private static ModRecipeHandler resultHandler(Recipe<?> recipe) {
        return isDispatching(recipe) ? null : claimFor(recipe).winner();
    }

    private static ItemStack readHandlerResult(ModRecipeHandler handler, Recipe<?> recipe, RegistryAccess access) {
        Set<Class<?>> active = ACTIVE_RESULT_HANDLERS.get();
        if (active == null) {
            active = new HashSet<>();
            ACTIVE_RESULT_HANDLERS.set(active);
        }
        active.add(recipe.getClass());
        try {
            ItemStack result = handler.getResultItem(recipe, access);
            return result == null ? ItemStack.EMPTY : result.copy();
        } finally {
            active.remove(recipe.getClass());
            if (active.isEmpty()) ACTIVE_RESULT_HANDLERS.remove();
        }
    }

    private static void logClientUnsafeFallback(Class<?> recipeClass, Throwable failure) {
        if (!CLIENT_UNSAFE_RESULT_LOGGED.add(recipeClass)) return;
        if (failure == null) {
            RESULT_LOGGER.debug(
                    "[RecipeCatalog] Avoiding client-unsafe getResultItem for {}; using structural output fallback",
                    recipeClass.getName());
        } else {
            RESULT_LOGGER.debug(
                    "[RecipeCatalog] getResultItem failed on dedicated server for {}; future calls use structural output fallback",
                    recipeClass.getName(), failure);
        }
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
                RESULT_LOGGER.debug("[RSI-Handler] CraftTweaker output accessor unavailable for {}",
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
            RESULT_LOGGER.debug("[RSI-Handler] CraftTweaker output read failed for {}",
                    clazz.getName(), e);
            return ItemStack.EMPTY;
        }
    }

    private record CraftTweakerOutputAccessor(Method getCtOutput, Method getInternal) {}

    private static ItemStack probeNonGridResult(Recipe<?> recipe, RegistryAccess access) {
        // 接口调用会被 ForgeGradle 重映射；开发环境里的方法名在生产环境不可靠。
        try {
            ItemStack result = recipe.getResultItem(access);
            if (result != null && !result.isEmpty()) return result.copy();
        } catch (RuntimeException | LinkageError e) {
            RESULT_LOGGER.debug("[RSI-Handler] Recipe result lookup failed", e);
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
                RESULT_LOGGER.debug("[RSI-Handler] cached getResultItem failed for {}", clazz.getName(), e);
            }
        }

        // 不探测已废弃的无参 getResultItem()，部分模组用它返回机器图标。
        for (String name : new String[]{"getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            // 同名方法优先使用接收 RegistryAccess 的重载。
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(RegistryAccess.class)) continue;
                try {
                    Object r = m.invoke(recipe, access);
                    if (r instanceof ItemStack s && !s.isEmpty()) {
                        RESULT_METHOD_CACHE.put(clazz, m);
                        return s.copy();
                    }
                } catch (Exception e) {
                    RESULT_LOGGER.debug("[RSI-Handler] probe {} on {} failed", name, clazz.getName(), e);
                }
            }
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
                    RESULT_LOGGER.debug("[RSI-Handler] probe {} on {} failed", name, clazz.getName(), e);
                }
            }
        }

        return tryGetOutputField(recipe);
    }

    private static ItemStack tryGetOutputField(Recipe<?> recipe) {
        Class<?> clazz = recipe.getClass();
        if (NO_OUTPUT_FIELD_CACHE.contains(clazz)) return ItemStack.EMPTY;
        Field cachedField = OUTPUT_FIELD_CACHE.get(clazz);
        if (cachedField != null) {
            try {
                ItemStack s = (ItemStack) cachedField.get(recipe);
                if (s != null && !s.isEmpty()) return s.copy();
            } catch (Exception e) {
                RESULT_LOGGER.debug("[RSI-Handler] cached output field read failed for {}", clazz.getName(), e);
            }
            return ItemStack.EMPTY;
        }
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (Field f : scan.getDeclaredFields()) {
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
                    RESULT_LOGGER.debug("[RSI-Handler] output field probe failed for {}", clazz.getName(), e);
                }
            }
            scan = scan.getSuperclass();
        }
        NO_OUTPUT_FIELD_CACHE.add(clazz);
        return ItemStack.EMPTY;
    }

    // ── 副产物提取 ───────────────────────────────────────────────

    public static List<ItemStack> tryGetSecondaryOutputs(@Nullable Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof FluidContainerRecipe conversion) return conversion.secondaryOutputs();
        List<ItemStack> results = new ArrayList<>();
        if (recipe == null) return results;

        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        // 网格配方要用实际合成网格计算剩余物。
        if (!(recipe instanceof CraftingRecipe)) {
            appendSecondaryStacks(readSecondaryGetter(recipe, "getRemainingItems", seen), ItemStack.EMPTY, results);
        }
        appendSecondaryStacks(readSecondaryGetter(recipe, "getByproducts", seen), ItemStack.EMPTY, results);
        appendSecondaryStacks(readSecondaryGetter(recipe, "getRollResults", seen), ItemStack.EMPTY, results);
        List<?> outputs = readSecondaryGetter(recipe, "getOutputs", seen);
        if (!outputs.isEmpty()) {
            // 全量产物可能包含主产物；调用方已单独计入主产物。
            try {
                ItemStack primary = CraftingResolver.resolveDeclaredOutput(
                        recipe, tryGetUncachedResultItem(recipe, access));
                appendSecondaryStacks(outputs, primary, results);
            } catch (RuntimeException failure) {
                RESULT_LOGGER.debug("[RSI-Handler] Could not resolve the primary output for {}", recipe.getClass(), failure);
            }
        }
        if (results.isEmpty()) trySecondaryOutputFields(recipe, results);
        return results;
    }

    private static List<?> readSecondaryGetter(Recipe<?> recipe, String name, Set<Object> seen) {
        try {
            Method method = Reflect.findMethod(recipe.getClass(), name, new Class<?>[0]);
            if (method == null) return List.of();
            Object value = method.invoke(recipe);
            if (!seen.add(value)) return List.of();
            if (value instanceof List<?> list) return list;
            if (value instanceof ItemStack[] stacks) return Arrays.asList(stacks);
        } catch (Exception failure) {
            RESULT_LOGGER.debug("[RSI-Handler] Secondary output getter {} failed for {}", name, recipe.getClass(), failure);
        }
        return List.of();
    }

    private static void appendSecondaryStacks(List<?> values, ItemStack primary, List<ItemStack> results) {
        boolean primaryDropped = false;
        for (Object value : values) {
            if (!(value instanceof ItemStack stack) || stack.isEmpty()) continue;
            if (!primaryDropped && !primary.isEmpty()
                    && (primary.hasTag() ? ItemStack.isSameItemSameTags(stack, primary)
                                         : ItemStack.isSameItem(stack, primary))) {
                primaryDropped = true;
                continue;
            }
            results.add(stack.copy());
        }
    }

    private static void trySecondaryOutputFields(Recipe<?> recipe, List<ItemStack> results) {
        for (Class<?> scan = recipe.getClass(); scan != null && scan != Object.class; scan = scan.getSuperclass()) {
            for (Field field : scan.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("byproduct") && !name.contains("secondary")
                        && !name.contains("extra") && !name.contains("bonus") && !name.contains("roll")) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(recipe);
                    if (value instanceof List<?> list) {
                        appendSecondaryStacks(list, ItemStack.EMPTY, results);
                    } else if (value instanceof ItemStack stack && !stack.isEmpty()) {
                        results.add(stack.copy());
                    }
                } catch (Exception failure) {
                    RESULT_LOGGER.debug("[RSI-Handler] Secondary output field {} failed for {}", field, scan, failure);
                }
            }
        }
    }

}
