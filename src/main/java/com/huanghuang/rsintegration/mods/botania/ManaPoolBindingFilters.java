package com.huanghuang.rsintegration.mods.botania;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Botania 魔力池布局的公共分类边界。
 *
 * <p>JEI、EMI 与执行器共用同一套识别规则，避免配方视图把原版池、炼金
 * 催化剂和转化催化剂当成互不相干的机器。</p>
 */
public final class ManaPoolBindingFilters {
    public static final String MANA_POOL = "mana_pool";
    public static final String ALCHEMY_CATALYST = "alchemy_catalyst";
    public static final String CONJURATION_CATALYST = "conjuration_catalyst";

    private ManaPoolBindingFilters() {}

    public static boolean isManaPoolFilter(@Nullable String filter) {
        return MANA_POOL.equals(filter)
                || ALCHEMY_CATALYST.equals(filter)
                || CONJURATION_CATALYST.equals(filter);
    }

    public static boolean matches(String blockKey, String filter) {
        if (blockKey == null || filter == null) return false;
        if (!isManaPoolFilter(filter)) return false;
        return blockKey.contains(MANA_POOL)
                || blockKey.contains(ALCHEMY_CATALYST)
                || blockKey.contains(CONJURATION_CATALYST);
    }

    /**
     * 读取原生 ManaInfusionRecipe 的催化剂，并把普通池与两种内置催化剂归入
     * 同一机器家族。未知催化剂仍按普通池处理，由执行器检查池底方块。
     */
    @Nullable
    public static String filterForRecipe(Object recipe) {
        if (!implementsNamedType(recipe, "vazkii.botania.api.recipe.ManaInfusionRecipe")) {
            return null;
        }
        Object catalyst = invokeNoArgs(recipe, "getRecipeCatalyst");
        if (catalyst == null) return MANA_POOL;

        Object displayed = invokeNoArgs(catalyst, "getDisplayed");
        if (displayed instanceof Iterable<?> states) {
            for (Object value : states) {
                if (!(value instanceof BlockState state)) continue;
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                if (id == null || !"botania".equals(id.getNamespace())) continue;
                if (CONJURATION_CATALYST.equals(id.getPath())) return CONJURATION_CATALYST;
                if (ALCHEMY_CATALYST.equals(id.getPath())) return ALCHEMY_CATALYST;
            }
        }
        return MANA_POOL;
    }

    private static boolean implementsNamedType(Object value, String typeName) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (typeName.equals(type.getName())) return true;
            for (Class<?> contract : type.getInterfaces()) {
                if (namedInterface(contract, typeName)) return true;
            }
        }
        return false;
    }

    private static boolean namedInterface(Class<?> type, String typeName) {
        if (typeName.equals(type.getName())) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (namedInterface(parent, typeName)) return true;
        }
        return false;
    }

    @Nullable
    private static Object invokeNoArgs(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
