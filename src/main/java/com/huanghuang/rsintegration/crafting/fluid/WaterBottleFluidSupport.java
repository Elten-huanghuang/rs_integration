package com.huanghuang.rsintegration.crafting.fluid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

/**
 * 普通水瓶与 Thirst Was Taken 带纯度水瓶之间的局部流体语义。
 *
 * <p>Forge 没有给原版玻璃瓶注册物品流体能力，因此这里只供终端和液体容器
 * 配方探测使用，不改变全局物品能力。除 {@code Potion} 与 {@code Purity}
 * 外的 NBT 不会被静默丢弃。</p>
 */
public final class WaterBottleFluidSupport {
    public static final String PURITY_TAG = "Purity";

    private WaterBottleFluidSupport() {}

    public static boolean isPurityWater(FluidStack fluid) {
        if (fluid.getFluid() != Fluids.WATER) return false;
        CompoundTag tag = fluid.getTag();
        return tag != null && tag.size() == 1
                && tag.contains(PURITY_TAG, Tag.TAG_ANY_NUMERIC);
    }

    public static ItemStack bottle(FluidStack fluid) {
        if (fluid.getFluid() != Fluids.WATER) return ItemStack.EMPTY;
        CompoundTag tag = fluid.getTag();
        if (tag != null && !tag.isEmpty()
                && (tag.size() != 1 || !tag.contains(PURITY_TAG, Tag.TAG_ANY_NUMERIC))) {
            return ItemStack.EMPTY;
        }
        ItemStack bottle = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        if (tag != null && tag.contains(PURITY_TAG, Tag.TAG_ANY_NUMERIC)) {
            bottle.getOrCreateTag().put(PURITY_TAG, tag.get(PURITY_TAG).copy());
        }
        return bottle;
    }

    public static FluidStack fluid(ItemStack bottle, int amount) {
        if (amount <= 0 || !bottle.is(Items.POTION)
                || PotionUtils.getPotion(bottle) != Potions.WATER) return FluidStack.EMPTY;
        FluidStack result = new FluidStack(Fluids.WATER, amount);
        CompoundTag tag = bottle.getTag();
        if (tag == null) return result;
        for (String key : tag.getAllKeys()) {
            if ("Potion".equals(key)) continue;
            if (!PURITY_TAG.equals(key) || !tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
                return FluidStack.EMPTY;
            }
            result.getOrCreateTag().put(key, tag.get(key).copy());
        }
        return result;
    }
}
