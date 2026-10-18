package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkBottleFluidHandler;
import com.huanghuang.rsintegration.mods.ironsspellbooks.AlchemistBottleSupport;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;

import java.util.function.Function;

/** 容器先在副本上准备，网络实际返回量确认后才替换鼠标物品。 */
public final class FluidContainerTransfer {
    private FluidContainerTransfer() { }

    /** 后端只负责原生资源读写，容器结算由同一套逻辑处理。 */
    public interface Storage {
        ItemStack extractItem(ItemStack stack, int amount, boolean simulate);
        ItemStack insertItem(ItemStack stack, int amount, boolean simulate);
        FluidStack extractFluid(FluidStack fluid, int amount, boolean simulate);
        FluidStack insertFluid(FluidStack fluid, int amount, boolean simulate);
    }

    public static IFluidHandlerItem handler(ItemStack stack) {
        IFluidHandlerItem bottle = InkBottleFluidHandler.create(stack);
        return bottle != null ? bottle : FluidContainerBucketSupport.getHandler(stack);
    }

    public record Result(ItemStack cursor, ItemStack overflow, FluidStack recovery,
                         FluidStack resource, int transferred) { }

    public static Result fill(Storage network, ItemStack cursor, FluidStack fluid) {
        return fill(network, cursor, fluid, FluidContainerTransfer::handler);
    }

    public static Result fill(Storage network, ItemStack cursor, FluidStack fluid,
                       Function<ItemStack, IFluidHandlerItem> containers) {
        // 取尽库存会把缓存对象清成零，整次转移必须使用独立的流体身份。
        fluid = fluid.copy();
        boolean borrowed = cursor.isEmpty();
        ItemStack source = cursor;
        Item emptyContainer = AlchemistBottleSupport.canBottle(fluid)
                || WaterBottleFluidSupport.isPurityWater(fluid)
                ? Items.GLASS_BOTTLE : Items.BUCKET;
        if (borrowed) {
            // 空手从网络取匹配容器，墨水和药水使用玻璃瓶。
            if (emptyContainer == Items.BUCKET && fluid.getFluid().getBucket() == Items.AIR) return unchanged(cursor);
            ItemStack bucket = network.extractItem(new ItemStack(emptyContainer), 1, true);
            if (bucket == null || !bucket.is(emptyContainer) || bucket.getCount() != 1) return unchanged(cursor);
            source = bucket;
        }
        IFluidHandlerItem simulated = containers.apply(source.copyWithCount(1));
        if (simulated == null) return unchanged(cursor);
        int capacity = simulated.fill(withAmount(fluid, Integer.MAX_VALUE), IFluidHandler.FluidAction.SIMULATE);
        if (capacity <= 0) return unchanged(cursor);
        FluidStack available = network.extractFluid(fluid, capacity, true);
        if (!validExtract(available, fluid, capacity)) return unchanged(cursor);
        Prepared filled = prepareFill(source, available, containers);
        if (filled == null) return unchanged(cursor);
        if (borrowed) {
            ItemStack bucket = network.extractItem(new ItemStack(emptyContainer), 1, false);
            if (bucket == null || !bucket.is(emptyContainer) || bucket.getCount() != 1) {
                return new Result(bucket == null ? cursor : bucket, ItemStack.EMPTY,
                        FluidStack.EMPTY, FluidStack.EMPTY, 0);
            }
            source = bucket;
        }
        FluidStack extracted = network.extractFluid(fluid, filled.amount, false);
        if (!validExtract(extracted, fluid, filled.amount)) {
            // 已知的异常返回资源仍尝试归还；归还失败交由持久化恢复队列保管。
            FluidStack recovery = refund(network, extracted);
            return failedFill(network, cursor, source, borrowed, recovery);
        }
        if (extracted.getAmount() != filled.amount) {
            filled = prepareFill(source, extracted, containers);
            if (filled == null) return failedFill(network, cursor, source, borrowed, refund(network, extracted));
        }
        FluidStack recovery = refund(network, withAmount(extracted, extracted.getAmount() - filled.amount));
        ItemStack held = borrowed ? source : cursor;
        return replaceOne(held, filled.container, recovery, fluid, filled.amount);
    }

    public static Result empty(Storage network, ItemStack cursor) {
        return empty(network, cursor, FluidContainerTransfer::handler);
    }

    public static Result empty(Storage network, ItemStack cursor, Function<ItemStack, IFluidHandlerItem> containers) {
        if (cursor.isEmpty()) return unchanged(cursor);
        IFluidHandlerItem simulated = containers.apply(cursor.copyWithCount(1));
        if (simulated == null) return unchanged(cursor);
        FluidStack content = simulated.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
        if (content.isEmpty()) return unchanged(cursor);
        int accepted = accepted(content, network.insertFluid(content, content.getAmount(), true));
        if (accepted <= 0) return unchanged(cursor);
        Prepared emptied = prepareEmpty(cursor, withAmount(content, accepted), containers);
        if (emptied == null) return unchanged(cursor);
        FluidStack moving = withAmount(content, emptied.amount);
        FluidStack remainder = network.insertFluid(moving, moving.getAmount(), false);
        int inserted = accepted(moving, remainder);
        if (inserted == 0) return unchanged(cursor);
        if (inserted != moving.getAmount()) {
            // 通用容器能部分排空时只扣实际量；桶不支持部分排空，剩余流体持久化等待归还。
            Prepared partial = prepareEmpty(cursor, withAmount(content, inserted), containers);
            if (partial != null && partial.amount == inserted)
                return replaceOne(cursor, partial.container, FluidStack.EMPTY, content, inserted);
        }
        return replaceOne(cursor, emptied.container, remainder.copy(), content, inserted);
    }

    private static Prepared prepareFill(ItemStack source, FluidStack fluid,
                                        Function<ItemStack, IFluidHandlerItem> containers) {
        IFluidHandlerItem handler = containers.apply(source.copyWithCount(1));
        if (handler == null) return null;
        // 普通桶无法保存流体标签，必须在实际扣液之前拒绝。
        if (source.is(Items.BUCKET) && handler instanceof FluidBucketWrapper
                && fluid.hasTag() && !fluid.getTag().isEmpty()) return null;
        int filled = handler.fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE);
        if (filled <= 0 || filled > fluid.getAmount()) return null;
        // 桶可能丢弃流体 NBT；只有转换后仍能还原同一身份与数量时才允许取液。
        if (handler instanceof FluidBucketWrapper && fluid.hasTag() && !fluid.getTag().isEmpty()) {
            IFluidHandlerItem result = containers.apply(handler.getContainer().copyWithCount(1));
            if (result == null || result.getTanks() != 1
                    || !result.getFluidInTank(0).isFluidEqual(fluid)
                    || result.getFluidInTank(0).getAmount() != filled) return null;
        }
        return new Prepared(handler.getContainer().copy(), filled);
    }

    private static Prepared prepareEmpty(ItemStack source, FluidStack fluid,
                                         Function<ItemStack, IFluidHandlerItem> containers) {
        IFluidHandlerItem handler = containers.apply(source.copyWithCount(1));
        if (handler == null) return null;
        FluidStack drained = handler.drain(fluid.copy(), IFluidHandler.FluidAction.EXECUTE);
        if (!validExtract(drained, fluid, fluid.getAmount())) return null;
        return new Prepared(handler.getContainer().copy(), drained.getAmount());
    }

    private static Result failedFill(Storage network, ItemStack cursor, ItemStack bucket, boolean borrowed,
                                      FluidStack recovery) {
        ItemStack held = cursor;
        if (borrowed) {
            ItemStack leftover = network.insertItem(bucket, bucket.getCount(), false);
            held = leftover == null ? bucket.copy() : leftover.copy();
        }
        return new Result(held, ItemStack.EMPTY, recovery, FluidStack.EMPTY, 0);
    }

    private static Result replaceOne(ItemStack cursor, ItemStack converted, FluidStack recovery,
                                      FluidStack resource, int amount) {
        ItemStack remainder = cursor.copy();
        remainder.shrink(1);
        return new Result(remainder.isEmpty() ? converted : remainder,
                remainder.isEmpty() ? ItemStack.EMPTY : converted, recovery, resource, amount);
    }

    private static FluidStack refund(Storage network, FluidStack fluid) {
        if (fluid == null || fluid.isEmpty()) return FluidStack.EMPTY;
        FluidStack remainder = network.insertFluid(fluid, fluid.getAmount(), false);
        accepted(fluid, remainder);
        return remainder.copy();
    }

    private static int accepted(FluidStack offered, FluidStack remainder) {
        if (remainder == null || (!remainder.isEmpty() && (!remainder.isFluidEqual(offered)
                || remainder.getAmount() > offered.getAmount())))
            throw new IllegalStateException("网络返回了无效流体余量");
        return offered.getAmount() - remainder.getAmount();
    }

    private static boolean validExtract(FluidStack actual, FluidStack requested, int amount) {
        return actual != null && !actual.isEmpty() && actual.isFluidEqual(requested) && actual.getAmount() <= amount;
    }

    private static FluidStack withAmount(FluidStack fluid, int amount) {
        FluidStack result = fluid.copy();
        result.setAmount(Math.max(0, amount));
        return result;
    }

    private static Result unchanged(ItemStack cursor) {
        return new Result(cursor, ItemStack.EMPTY, FluidStack.EMPTY, FluidStack.EMPTY, 0);
    }

    private record Prepared(ItemStack container, int amount) { }
}
