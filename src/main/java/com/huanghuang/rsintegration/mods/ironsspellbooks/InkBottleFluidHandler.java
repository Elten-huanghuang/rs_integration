package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/** 仅供终端装填使用，不给原版玻璃瓶全局添加流体能力。 */
public final class InkBottleFluidHandler implements IFluidHandlerItem {
    private final ItemStack bottle;
    private FluidStack content = FluidStack.EMPTY;

    private InkBottleFluidHandler(ItemStack bottle) { this.bottle = bottle; }

    @Nullable
    public static IFluidHandlerItem create(ItemStack stack) {
        return stack.is(Items.GLASS_BOTTLE) ? new InkBottleFluidHandler(stack) : null;
    }

    @Override public int getTanks() { return 1; }
    @Override public FluidStack getFluidInTank(int tank) { return content.copy(); }
    @Override public int getTankCapacity(int tank) { return InkFluidSupport.BOTTLE_AMOUNT; }
    @Override public boolean isFluidValid(int tank, FluidStack fluid) {
        return inkItem(fluid) != Items.AIR;
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
        }
        return InkFluidSupport.BOTTLE_AMOUNT;
    }

    @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
    @Override public FluidStack drain(int amount, FluidAction action) { return FluidStack.EMPTY; }
    @Override public ItemStack getContainer() {
        return content.isEmpty() ? bottle.copy() : new ItemStack(inkItem(content));
    }
}
