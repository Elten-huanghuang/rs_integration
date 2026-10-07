package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

/** 3.15 炼金锅的药水 NBT 格式；不链接旧版不存在的 PotionFluid 类。 */
public final class AlchemistPotionSupport {
    private static final ResourceLocation POTION_FLUID = new ResourceLocation("irons_spellbooks", "potion");
    private static final String BOTTLE_TYPE = "irons_spellbooks:bottle_type";

    private AlchemistPotionSupport() { }

    public static boolean isPotion(FluidStack fluid) {
        return POTION_FLUID.equals(ForgeRegistries.FLUIDS.getKey(fluid.getFluid()));
    }

    public static FluidStack fluid(ItemStack potion) {
        if (!(potion.is(Items.POTION) || potion.is(Items.SPLASH_POTION) || potion.is(Items.LINGERING_POTION))
                || PotionUtils.getPotion(potion) == Potions.EMPTY) return FluidStack.EMPTY;
        if (potion.is(Items.POTION) && PotionUtils.getPotion(potion) == Potions.WATER
                && potion.getTag() != null && potion.getTag().getAllKeys().size() == 1) {
            return new FluidStack(Fluids.WATER, InkFluidSupport.BOTTLE_AMOUNT);
        }
        var fluid = ForgeRegistries.FLUIDS.getValue(POTION_FLUID);
        if (fluid == null || fluid == Fluids.EMPTY) return FluidStack.EMPTY;
        CompoundTag tag = potion.getTag().copy();
        tag.putString(BOTTLE_TYPE, potion.is(Items.SPLASH_POTION) ? "SPLASH"
                : potion.is(Items.LINGERING_POTION) ? "LINGERING" : "REGULAR");
        return new FluidStack(fluid, InkFluidSupport.BOTTLE_AMOUNT, tag);
    }

    public static ItemStack bottle(FluidStack fluid) {
        if (!isPotion(fluid) || !fluid.hasTag() || PotionUtils.getPotion(fluid.getTag()) == Potions.EMPTY)
            return ItemStack.EMPTY;
        String type = fluid.getTag().contains(BOTTLE_TYPE) ? fluid.getTag().getString(BOTTLE_TYPE) : "REGULAR";
        Item item = switch (type) {
            case "REGULAR" -> Items.POTION;
            case "SPLASH" -> Items.SPLASH_POTION;
            case "LINGERING" -> Items.LINGERING_POTION;
            default -> Items.AIR;
        };
        if (item == Items.AIR) return ItemStack.EMPTY;
        ItemStack result = new ItemStack(item);
        CompoundTag tag = fluid.getTag().copy();
        tag.remove(BOTTLE_TYPE);
        result.setTag(tag);
        return result;
    }
}
