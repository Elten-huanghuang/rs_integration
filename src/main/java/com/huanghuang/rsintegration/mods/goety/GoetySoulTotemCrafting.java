package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Models Goety's NBT-changing, soul-powered totem crafting remainders. */
public final class GoetySoulTotemCrafting {
    private static final ResourceLocation TOTEM_ID =
            new ResourceLocation("goety", "totem_of_souls");
    private static final String SOUL_TOTEM_CLASS =
            "com.Polarice3.Goety.common.items.magic.TotemOfSouls";
    private static final String SOULS_TAG = "Souls";
    private static final int DEFAULT_CRAFTING_SOUL_COST = 1;

    private GoetySoulTotemCrafting() {}

    public static boolean isSoulTotem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return TOTEM_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                || isSoulTotemType(stack.getItem().getClass());
    }

    static boolean isSoulTotemType(Class<?> type) {
        return hasNamedSuperclass(type, SOUL_TOTEM_CLASS);
    }

    public static boolean isSoulTotemIngredient(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return false;
        ItemStack[] candidates;
        try {
            candidates = ingredient.getItems();
        } catch (RuntimeException ignored) {
            return false;
        }
        boolean found = false;
        for (ItemStack candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) continue;
            found = true;
            if (!isSoulTotem(candidate)) return false;
        }
        return found;
    }

    public static int storedSouls(ItemStack stack) {
        return isSoulTotem(stack) && stack.getTag() != null
                ? Math.max(0, stack.getTag().getInt(SOULS_TAG)) : 0;
    }

    public static int craftingSoulCost() {
        try {
            Class<?> config = Class.forName("com.Polarice3.Goety.config.ItemConfig");
            Field field = config.getField("CraftingSouls");
            Object value = field.get(null);
            Method get = value.getClass().getMethod("get");
            Object raw = get.invoke(value);
            return raw instanceof Number number
                    ? Math.max(0, number.intValue()) : DEFAULT_CRAFTING_SOUL_COST;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return DEFAULT_CRAFTING_SOUL_COST;
        }
    }

    public static int soulCostPerCraft(CraftingRecipe recipe) {
        if (recipe == null) return 0;
        int slots = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (isSoulTotemIngredient(ingredient)) slots++;
        }
        if (slots == 0) return 0;
        long total = (long) slots * craftingSoulCost();
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public static int ritualSoulCost(@Nullable Object recipe) {
        if (recipe == null) return 0;
        if (!hasNamedSuperclass(recipe.getClass(),
                "com.Polarice3.Goety.common.crafting.RitualRecipe")
                && !hasNamedSuperclass(recipe.getClass(),
                "com.Polarice3.Goety.common.crafting.BrazierRecipe")) {
            return 0;
        }
        try {
            Object value = recipe.getClass().getMethod("getSoulCost").invoke(recipe);
            int soulCost = value instanceof Number number ? Math.max(0, number.intValue()) : 0;

            // Age of Mythology 接管塔罗觉醒仪式后，会在启动时一次性扣除
            // getSoulCost()，运行期间不再按持续时间扣费。该类是可选依赖，
            // 必须通过反射判断当前配方，避免把普通 Goety 配方误判为总量。
            if (isTotalSettlementRitual(recipe)) return soulCost;

            // 黑暗祭坛每秒都会消耗一次 getSoulCost()，持续时间的单位也是秒，必须计入
            // 能量需求；只读取单次消耗会低估长时间仪式的总消耗。
            if (hasNamedSuperclass(recipe.getClass(),
                    "com.Polarice3.Goety.common.crafting.RitualRecipe")) {
                try {
                    Object durationValue = recipe.getClass().getMethod("getDuration").invoke(recipe);
                    if (durationValue instanceof Number durationNumber) {
                        int duration = durationNumber.intValue();
                        if (duration > 0) return saturatingMultiply(soulCost, duration);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // 兼容没有暴露持续时间访问器的旧版 Goety。
                }
            }
            // BrazierRecipe 没有持续时间访问器，其 getSoulCost() 本身就是机器
            // 会抽取的灵魂总量。
            return soulCost;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean isTotalSettlementRitual(Object recipe) {
        try {
            ModList mods = ModList.get();
            if (mods == null || !mods.isLoaded("ageofmythology")) return false;
            Class<?> service = Class.forName(
                    "com.kurome.ageofmythology.tarot.awakening.upright.TarotRitualStartService");
            for (Class<?> type = recipe.getClass(); type != null; type = type.getSuperclass()) {
                if ("com.Polarice3.Goety.common.crafting.RitualRecipe".equals(type.getName())) {
                    Method manages = service.getMethod("manages", type);
                    return Boolean.TRUE.equals(manages.invoke(null, recipe));
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static int saturatingMultiply(int left, int right) {
        long result = (long) Math.max(0, left) * Math.max(0, right);
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static boolean hasNamedSuperclass(Class<?> type, String expectedName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (expectedName.equals(current.getName())) return true;
        }
        return false;
    }

    public static int maxAvailableSouls(
            Map<CraftingResolver.StackKey, Integer> available) {
        int max = 0;
        if (available == null) return max;
        for (Map.Entry<CraftingResolver.StackKey, Integer> entry : available.entrySet()) {
            if (entry.getValue() <= 0) continue;
            max = Math.max(max, storedSouls(entry.getKey().toStack()));
        }
        return max;
    }

    public static List<IngredientSpec> requireBatchCharge(
            List<IngredientSpec> specs, int executions) {
        if (specs == null || specs.isEmpty()) return specs;
        long required = (long) craftingSoulCost() * Math.max(1, executions);
        int minimum = required > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) required;
        List<IngredientSpec> result = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (!spec.isEmpty() && spec.role() == DemandRole.CATALYST
                    && isSoulTotemIngredient(spec.ingredient())) {
                result.add(new IngredientSpec(
                        new ChargedSoulTotemIngredient(spec.ingredient(), minimum),
                        spec.count(), spec.role()));
            } else {
                result.add(spec);
            }
        }
        return List.copyOf(result);
    }

    private static final class ChargedSoulTotemIngredient extends Ingredient {
        private final Ingredient delegate;
        private final int minimumSouls;

        private ChargedSoulTotemIngredient(Ingredient delegate, int minimumSouls) {
            super(Stream.of(delegate.getItems()).map(stack ->
                    new ItemValue(stack.copyWithCount(1))));
            this.delegate = delegate;
            this.minimumSouls = Math.max(0, minimumSouls);
        }

        @Override
        public boolean test(@Nullable ItemStack stack) {
            return stack != null && delegate.test(stack)
                    && storedSouls(stack) >= minimumSouls;
        }

        @Override
        public com.google.gson.JsonElement toJson() {
            return delegate.toJson();
        }
    }
}
