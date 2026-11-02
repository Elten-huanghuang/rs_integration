package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.fluid.WaterBottleFluidSupport;
import com.huanghuang.rsintegration.feature.experience.ExperienceBottleSupport;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        FluidStack experience = ExperienceBottleSupport.fluid(stack);
        if (!experience.isEmpty()) return experience;
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
    @Override public int getTankCapacity(int tank) { return bottleAmount(content); }
    @Override public boolean isFluidValid(int tank, FluidStack fluid) {
        return !bottledItem(fluid).isEmpty();
    }

    private static ItemStack bottledItem(FluidStack fluid) {
        ItemStack experienceBottle = ExperienceBottleSupport.bottle(fluid);
        if (!experienceBottle.isEmpty()) return experienceBottle;
        ItemStack waterBottle = WaterBottleFluidSupport.bottle(fluid);
        if (!waterBottle.isEmpty()) return waterBottle;
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
        int amount = bottleAmount(resource);
        if (!content.isEmpty() || resource.getAmount() < amount
                || !isFluidValid(0, resource)) return 0;
        if (action.execute()) {
            content = resource.copy();
            content.setAmount(amount);
            filledBottle = bottledItem(resource);
        }
        return amount;
    }

    private static int bottleAmount(FluidStack fluid) {
        return ExperienceBottleSupport.canBottle(fluid)
                ? ExperienceBottleSupport.BOTTLE_AMOUNT : InkFluidSupport.BOTTLE_AMOUNT;
    }

    @Override public FluidStack drain(FluidStack resource, FluidAction action) {
        return content.isFluidEqual(resource) ? drain(resource.getAmount(), action) : FluidStack.EMPTY;
    }
    @Override public FluidStack drain(int amount, FluidAction action) {
        // 附魔之瓶的实际经验仍由原版随机掉落，不将均值反向当作确定的液体产出。
        if (content.isEmpty() || ExperienceBottleSupport.canBottle(content)
                || amount < bottleAmount(content)) return FluidStack.EMPTY;
        FluidStack drained = content.copy();
        if (action.execute()) content = FluidStack.EMPTY;
        return drained;
    }
    @Override public ItemStack getContainer() {
        return content.isEmpty() ? (bottle.is(Items.GLASS_BOTTLE) ? bottle.copy() : new ItemStack(Items.GLASS_BOTTLE))
                : filledBottle.copy();
    }
}
