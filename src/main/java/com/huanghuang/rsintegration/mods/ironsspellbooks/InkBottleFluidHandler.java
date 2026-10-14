package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/** 终端与容器配方共用的局部装瓶能力，不给玻璃瓶全局添加能力。 */
public final class InkBottleFluidHandler implements IFluidHandlerItem {
    private final ItemStack bottle;
    private FluidStack content = FluidStack.EMPTY;
    private ItemStack filledBottle = ItemStack.EMPTY;

    private InkBottleFluidHandler(ItemStack bottle) {
        this.bottle = bottle.copy();
        if (!bottle.is(Items.GLASS_BOTTLE)) {
            content = bottledFluid(bottle);
            filledBottle = bottle.copy();
        }
    }

    @Nullable
    public static IFluidHandlerItem create(ItemStack stack) {
        if (stack.is(Items.GLASS_BOTTLE)) {
            return stack.hasTag() && !stack.getTag().isEmpty() ? null : new InkBottleFluidHandler(stack);
        }
        return bottledFluid(stack).isEmpty() ? null : new InkBottleFluidHandler(stack);
    }

    private static FluidStack bottledFluid(ItemStack stack) {
        FluidStack potion = AlchemistPotionSupport.fluid(stack);
        if (!potion.isEmpty()) return potion;
        // 墨水物品只能表示普通墨水；带自定义数据的物品保留原样。
        if (stack.hasTag() && !stack.getTag().isEmpty()) return FluidStack.EMPTY;
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return FluidStack.EMPTY;
        var fluid = ForgeRegistries.FLUIDS.getValue(id);
        if (fluid == null) return FluidStack.EMPTY;
        FluidStack result = new FluidStack(fluid, InkFluidSupport.BOTTLE_AMOUNT);
        return InkFluidSupport.isInk(result) ? result : FluidStack.EMPTY;
    }

    @Override public int getTanks() { return 1; }
    @Override public FluidStack getFluidInTank(int tank) { return content.copy(); }
    @Override public int getTankCapacity(int tank) { return InkFluidSupport.BOTTLE_AMOUNT; }
    @Override public boolean isFluidValid(int tank, FluidStack fluid) {
        return !bottledItem(fluid).isEmpty();
    }

    private static ItemStack bottledItem(FluidStack fluid) {
        if (fluid.getFluid() == Fluids.WATER && (!fluid.hasTag() || fluid.getTag().isEmpty())) {
            return PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        }
        Item ink = inkItem(fluid);
        return ink != Items.AIR ? new ItemStack(ink) : AlchemistBottleSupport.bottle(fluid);
    }

    private static Item inkItem(FluidStack fluid) {
        // 墨水物品无法保留自定义流体 NBT，不把带标签的流体静默转成普通墨水。
        if (fluid.isEmpty() || !InkFluidSupport.isInk(fluid)
                || (fluid.hasTag() && !fluid.getTag().isEmpty())) return Items.AIR;
        Item item = ForgeRegistries.ITEMS.getValue(ForgeRegistries.FLUIDS.getKey(fluid.getFluid()));
        return item == null ? Items.AIR : item;
    }

    @Override public int fill(FluidStack resource, FluidAction action) {
        if (!content.isEmpty() || resource.getAmount() < InkFluidSupport.BOTTLE_AMOUNT
                || !isFluidValid(0, resource)) return 0;
        if (action.execute()) {
            content = resource.copy();
            content.setAmount(InkFluidSupport.BOTTLE_AMOUNT);
            filledBottle = bottledItem(resource);
        }
        return InkFluidSupport.BOTTLE_AMOUNT;
    }

    @Override public FluidStack drain(FluidStack resource, FluidAction action) {
        return content.isFluidEqual(resource) ? drain(resource.getAmount(), action) : FluidStack.EMPTY;
    }
    @Override public FluidStack drain(int amount, FluidAction action) {
        if (content.isEmpty() || amount < InkFluidSupport.BOTTLE_AMOUNT) return FluidStack.EMPTY;
        FluidStack drained = content.copy();
        if (action.execute()) content = FluidStack.EMPTY;
        return drained;
    }
    @Override public ItemStack getContainer() {
        return content.isEmpty() ? (bottle.is(Items.GLASS_BOTTLE) ? bottle.copy() : new ItemStack(Items.GLASS_BOTTLE))
                : filledBottle.copy();
    }
}
