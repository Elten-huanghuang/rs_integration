package com.huanghuang.rsintegration.mods.wizardsreborn;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** 避免通用配方和服务端加载路径直接依赖可选模组的类型。 */
public final class WRAlchemyAccess {
    private static final String POTION_UTIL = "mod.maxbogomol.wizards_reborn.api.alchemy.AlchemyPotionUtil";
    private WRAlchemyAccess() {}

    public static Object invoke(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法读取炼金接口 " + name, failure);
        }
    }

    public static int number(Object target, String name) { return ((Number) invoke(target, name)).intValue(); }

    public static Object field(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法读取炼金状态 " + name, failure);
        }
    }

    public static void setField(Object target, String name, Object value) {
        try {
            target.getClass().getField(name).set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法更新炼金状态 " + name, failure);
        }
    }

    public static IItemHandler items(Object machine, boolean output) {
        return (IItemHandler) field(machine, output ? "itemOutputHandler" : "itemHandler");
    }

    public static IFluidHandler tank(Object machine, int index) {
        try {
            return (IFluidHandler) machine.getClass().getMethod("getTank", int.class).invoke(machine, index);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法读取炼金输入罐", failure);
        }
    }

    public static FluidStack fluidOutput(Object recipe) {
        return ((FluidStack) invoke(recipe, "getFluidResult")).copy();
    }

    public static List<List<FluidStack>> fluidInputs(Object recipe) {
        List<List<FluidStack>> result = new ArrayList<>();
        for (Object ingredient : (List<?>) invoke(recipe, "getFluidInputs")) {
            List<FluidStack> alternatives = new ArrayList<>();
            for (Object fluid : (List<?>) invoke(ingredient, "getFluids")) {
                if (fluid instanceof FluidStack stack && !stack.isEmpty()) alternatives.add(stack.copy());
            }
            result.add(List.copyOf(alternatives));
        }
        return List.copyOf(result);
    }

    private static Object potion(Object recipe, boolean output) {
        return invoke(recipe, output ? "getAlchemyPotion" : "getAlchemyPotionIngredient");
    }

    private static boolean isPotion(Object potion) {
        if (potion == null) return false;
        try {
            for (Method method : Class.forName(POTION_UTIL).getMethods()) {
                if (method.getName().equals("isEmpty") && method.getParameterCount() == 1) {
                    return !(boolean) method.invoke(null, potion);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法读取炼金药剂", failure);
        }
        throw new IllegalStateException("缺少炼金药剂检查接口");
    }

    public static boolean hasPotionOutput(Object recipe) { return isPotion(potion(recipe, true)); }

    public static ItemStack potionOutput(Object recipe, ItemStack bottle) {
        return setPotion(bottle, potion(recipe, true));
    }

    private static ItemStack setPotion(ItemStack stack, Object potion) {
        ItemStack result = stack.copyWithCount(1);
        try {
            for (Method method : Class.forName(POTION_UTIL).getMethods()) {
                if (method.getName().equals("setPotion") && method.getParameterCount() == 2) {
                    method.invoke(null, result, potion);
                    return result;
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法构建炼金药剂", failure);
        }
        throw new IllegalStateException("缺少炼金药剂写入接口");
    }

    public static Ingredient potionIngredient(Object recipe, Ingredient ingredient) {
        Object potion = potion(recipe, false);
        if (!isPotion(potion)) return ingredient;
        List<Ingredient> alternatives = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.getItem().getClass().getName().endsWith("AlchemyPotionItem")) {
                alternatives.add(StrictNBTIngredient.of(setPotion(stack, potion)));
            } else {
                alternatives.add(Ingredient.of(stack));
            }
        }
        return CompoundIngredient.of(alternatives.toArray(Ingredient[]::new));
    }

    public static ItemStack itemOutput(Recipe<?> recipe, RegistryAccess access) {
        ItemStack output = recipe.getResultItem(access).copy();
        Object potion = potion(recipe, true);
        if (!isPotion(potion)) return output;
        try {
            Method bottle = recipe.getClass().getMethod("getAlchemyBottle", ItemStack.class);
            for (Ingredient ingredient : recipe.getIngredients()) {
                for (ItemStack input : ingredient.getItems()) {
                    ItemStack result = (ItemStack) bottle.invoke(null, input);
                    if (!result.isEmpty()) return setPotion(result, potion);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法构建炼金药剂产物", failure);
        }
        return ItemStack.EMPTY;
    }
}
