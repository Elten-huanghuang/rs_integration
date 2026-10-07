package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.registries.ForgeRegistries;

/** 终端优先使用实际装瓶配方，包含流体名与物品名不同的药剂。 */
public final class AlchemistBottleSupport {
    private AlchemistBottleSupport() { }

    public static ItemStack bottle(FluidStack fluid) {
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
        if (id == null || !id.getNamespace().equals("irons_spellbooks")) return ItemStack.EMPTY;
        ItemStack potion = AlchemistPotionSupport.bottle(fluid);
        if (!potion.isEmpty()) return potion;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return ItemStack.EMPTY;
        for (IronSpellBooksRecipe recipe : IronAlchemistRecipeCatalog.allRecipes(server.overworld())) {
            if (!recipe.isBottling()) continue;
            FluidStack required = InkFluidSupport.fluid(recipe.inputs().get(0));
            if (required.getAmount() == InkFluidSupport.BOTTLE_AMOUNT && required.isFluidEqual(fluid))
                return recipe.getResultItem(RegistryAccess.EMPTY);
        }
        return ItemStack.EMPTY;
    }

    public static boolean canBottle(FluidStack fluid) {
        return InkFluidSupport.isInk(fluid) || !bottle(fluid).isEmpty();
    }
}
