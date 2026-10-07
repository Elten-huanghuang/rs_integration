package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Function;

/** 原生 JEI 将同品质卷轴合并展示，执行时解析为一个具体卷轴的内部配方。 */
public final class IronAlchemistJeiBridge {
    public static final String RECIPE_CLASS = "io.redspace.ironsspellbooks.jei.AlchemistCauldronJeiRecipe";
    private IronAlchemistJeiBridge() { }

    public static boolean isNativeRecipe(Object recipe) {
        return recipe != null && recipe.getClass().getName().equals(RECIPE_CLASS);
    }

    @Nullable
    public static IronSpellBooksRecipe resolve(Object nativeRecipe, @Nullable ItemStack displayed) {
        if (!isNativeRecipe(nativeRecipe)) return null;
        try {
            Class<?> type = nativeRecipe.getClass();
            Object input = type.getMethod("itemIn").invoke(nativeRecipe);
            Object fluid = type.getMethod("fluidIn").invoke(nativeRecipe);
            Object outputs = type.getMethod("results").invoke(nativeRecipe);
            Object byproduct = type.getMethod("resultByproduct").invoke(nativeRecipe);
            if (!(input instanceof Ingredient ingredient) || !(fluid instanceof FluidStack water)
                    || !(outputs instanceof List<?> results) || !(byproduct instanceof ItemStack extra)) return null;
            IronSpellBooksRecipe brewed = IronAlchemistRecipeCatalog.find(ingredient, water, results, extra,
                    displayed, IronAlchemistRecipeCatalog.cachedRecipes());
            if (brewed != null) return brewed;
            return resolve(ingredient, water, results, extra, displayed, scroll -> {
                var key = IronSpellBooksRecipeCatalog.spellScrollKey(scroll);
                if (key == null) return null;
                return IronSpellBooksRecipeCatalog.byId(new ResourceLocation("rs_integration",
                        "irons_spellbooks/recycle/" + key.spellId().getNamespace() + "/"
                                + key.spellId().getPath() + "/" + key.level()));
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.debug("[RSI-IronSpells] 原生炼金锅 JEI 配方解析失败", failure);
            return null;
        }
    }

    @Nullable
    public static IronSpellBooksRecipe resolve(Object nativeRecipe, @Nullable ItemStack displayed, Level level) {
        IronAlchemistRecipeCatalog.allRecipes(level);
        return resolve(nativeRecipe, displayed);
    }

    @Nullable
    static IronSpellBooksRecipe resolve(Ingredient input, FluidStack water, List<?> results,
                                       ItemStack byproduct, @Nullable ItemStack displayed,
                                       Function<ItemStack, IronSpellBooksRecipe> lookup) {
        if (water.getFluid() != Fluids.WATER || water.getAmount() != InkFluidSupport.BOTTLE_AMOUNT
                || (water.hasTag() && !water.getTag().isEmpty()) || !byproduct.isEmpty()) return null;
        ItemStack[] candidates = displayed != null && !displayed.isEmpty()
                ? new ItemStack[]{displayed} : input.getItems();
        for (ItemStack scroll : candidates) {
            if (!input.test(scroll)) continue;
            IronSpellBooksRecipe recipe = lookup.apply(scroll);
            if (recipe == null || recipe.machine() != IronSpellBooksRecipe.Machine.ALCHEMIST_CAULDRON
                    || recipe.inputs().size() != 1 || !recipe.inputIngredients().get(0).test(scroll)) continue;
            ItemStack output = recipe.getResultItem(RegistryAccess.EMPTY);
            if (!InkFluidSupport.isToken(output)) continue;
            FluidStack ink = InkFluidSupport.fluid(output);
            if (results.stream().anyMatch(result -> result instanceof FluidStack fluid
                    && fluid.isFluidStackIdentical(ink))) return recipe;
        }
        return null;
    }
}
