package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkBottleFluidHandler;
import com.huanghuang.rsintegration.mods.ironsspellbooks.AlchemistBottleSupport;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

import java.util.function.Function;

/** 容器先在副本上准备，网络实际返回量确认后才替换鼠标物品。 */
public final class UnifiedGridFluidTransfer {
    private UnifiedGridFluidTransfer() { }

    public record Result(ItemStack cursor, ItemStack overflow, FluidStack recovery,
                         FluidStack resource, int transferred) { }

    public static Result fill(INetwork network, ItemStack cursor, FluidStack fluid) {
        return fill(network, cursor, fluid, stack -> {
            IFluidHandlerItem bottle = InkBottleFluidHandler.create(stack);
            return bottle != null ? bottle : FluidUtil.getFluidHandler(stack).orElse(null);
        });
    }

    static Result fill(INetwork network, ItemStack cursor, FluidStack fluid,
                       Function<ItemStack, IFluidHandlerItem> containers) {
        // 取尽库存会把缓存对象清成零，整次转移必须使用独立的流体身份。
        fluid = fluid.copy();
        boolean borrowed = cursor.isEmpty();
        ItemStack source = cursor;
        Item emptyContainer = AlchemistBottleSupport.canBottle(fluid) ? Items.GLASS_BOTTLE : Items.BUCKET;
        if (borrowed) {
            // 空手从网络取匹配容器，墨水和药水使用玻璃瓶。
            if (emptyContainer == Items.BUCKET && fluid.getFluid().getBucket() == Items.AIR) return unchanged(cursor);
            ItemStack bucket = network.extractItem(new ItemStack(emptyContainer), 1, Action.SIMULATE);
            if (bucket == null || !bucket.is(emptyContainer) || bucket.getCount() != 1) return unchanged(cursor);
            source = bucket;
        }
        IFluidHandlerItem simulated = containers.apply(source.copyWithCount(1));
        if (simulated == null) return unchanged(cursor);
        int capacity = simulated.fill(withAmount(fluid, Integer.MAX_VALUE), IFluidHandler.FluidAction.SIMULATE);
        if (capacity <= 0) return unchanged(cursor);
        FluidStack available = network.extractFluid(fluid, capacity, Action.SIMULATE);
        if (!validExtract(available, fluid, capacity)) return unchanged(cursor);
        Prepared filled = prepareFill(source, available, containers);
        if (filled == null) return unchanged(cursor);
        if (borrowed) {
            ItemStack bucket = network.extractItem(new ItemStack(emptyContainer), 1, Action.PERFORM);
            if (bucket == null || !bucket.is(emptyContainer) || bucket.getCount() != 1) {
                return new Result(bucket == null ? cursor : bucket, ItemStack.EMPTY,
                        FluidStack.EMPTY, FluidStack.EMPTY, 0);
            }
            source = bucket;
        }
        FluidStack extracted = network.extractFluid(fluid, filled.amount, Action.PERFORM);
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

    public static Result empty(INetwork network, ItemStack cursor) {
        return empty(network, cursor, stack -> FluidUtil.getFluidHandler(stack).orElse(null));
    }

    static Result empty(INetwork network, ItemStack cursor, Function<ItemStack, IFluidHandlerItem> containers) {
        if (cursor.isEmpty()) return unchanged(cursor);
        IFluidHandlerItem simulated = containers.apply(cursor.copyWithCount(1));
        if (simulated == null) return unchanged(cursor);
        FluidStack content = simulated.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
        if (content.isEmpty()) return unchanged(cursor);
        int accepted = accepted(content, network.insertFluid(content, content.getAmount(), Action.SIMULATE));
        if (accepted <= 0) return unchanged(cursor);
        Prepared emptied = prepareEmpty(cursor, withAmount(content, accepted), containers);
        if (emptied == null) return unchanged(cursor);
        FluidStack moving = withAmount(content, emptied.amount);
        FluidStack remainder = network.insertFluid(moving, moving.getAmount(), Action.PERFORM);
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
        int filled = handler.fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE);
        if (filled <= 0 || filled > fluid.getAmount()) return null;
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

    private static Result failedFill(INetwork network, ItemStack cursor, ItemStack bucket, boolean borrowed,
                                      FluidStack recovery) {
        ItemStack held = cursor;
        if (borrowed) {
            ItemStack leftover = network.insertItem(bucket, bucket.getCount(), Action.PERFORM);
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

    /** 混合终端与原生流体终端共用结算，异常余量进入持久化恢复队列。 */
    public static void apply(ServerPlayer player, INetwork network, Result result, boolean filling, boolean shift) {
        if (!result.recovery().isEmpty()) {
            UnifiedGridFluidRecovery.get(player).retain(player.getUUID(), result.recovery());
            RSIntegrationMod.LOGGER.warn("终端容器转移留下 {} mB 流体，已保存并等待归还网络", result.recovery().getAmount());
        }
        player.containerMenu.setCarried(result.cursor());
        if (!result.overflow().isEmpty()) player.getInventory().placeItemBackInInventory(result.overflow());
        else if (filling && shift && result.transferred() > 0 && !result.cursor().isEmpty()) {
            ItemStack copy = result.cursor().copy();
            player.getInventory().add(copy);
            player.containerMenu.setCarried(copy);
        }
        if (result.transferred() <= 0) return;
        FluidStack tracked = result.resource().copy();
        tracked.setAmount(result.transferred());
        network.getFluidStorageTracker().changed(player, tracked);
        if (network.getNetworkItemManager() != null) {
            var config = RS.SERVER_CONFIG.getWirelessFluidGrid();
            network.getNetworkItemManager().drainEnergy(player, filling ? config.getExtractUsage() : config.getInsertUsage());
        }
    }

    private static FluidStack refund(INetwork network, FluidStack fluid) {
        if (fluid == null || fluid.isEmpty()) return FluidStack.EMPTY;
        FluidStack remainder = network.insertFluid(fluid, fluid.getAmount(), Action.PERFORM);
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
