package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.IntegrationResourceKey.ResourceKind;
import com.huanghuang.rsintegration.crafting.NetworkOutputReception;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.feature.experience.ExperienceFluidNormalization;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.BiFunction;

/** RS 的 int/余量约定只留在此桥接层，公共收货层使用 long 接收量。 */
public final class RefinedStorageOutputReception {
    private RefinedStorageOutputReception() {}

    /** 保留 RS 1.12.4 默认实现的 track -> 普通 insert 顺序，只让它接收任务未认领的部分。 */
    public static ItemStack insertItemTracked(INetwork network, ItemStack stack, int amount) {
        return insertItem(network, stack, amount, Action.PERFORM, (remaining, size) -> {
            int left = network.getCraftingManager().track(remaining, size);
            return left == 0 ? ItemStack.EMPTY : network.insertItem(remaining, left, Action.PERFORM);
        });
    }

    public static FluidStack insertFluidTracked(INetwork network, FluidStack stack, int amount) {
        if (amount > 0 && ExperienceFluidNormalization.canNormalize(stack)) {
            return ExperienceFluidNormalization.insertConverted(stack, amount,
                    converted -> insertFluidTracked(network, converted, amount));
        }
        return insertFluid(network, stack, amount, Action.PERFORM, (remaining, size) -> {
            int left = network.getCraftingManager().track(remaining, size);
            return left == 0 ? FluidStack.EMPTY : network.insertFluid(remaining, left, Action.PERFORM);
        });
    }

    public static ItemStack insertItem(INetwork network, ItemStack stack, int amount, Action action,
                                       BiFunction<ItemStack, Integer, ItemStack> original) {
        if (stack.isEmpty() || amount <= 0 || !NetworkOutputReception.canReceive(network)
                || !TaskOutputReception.hasWaitingResourceType(network, ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
            return original.apply(stack, amount);
        }
        // 物品入口收到的令牌仍是物品；只有真实流体入口才能交付流体数量。
        return NetworkOutputReception.route(network, IntegrationResourceKey.item(stack), amount,
                action == Action.SIMULATE, left -> {
                    if (left == 0) return ItemStack.EMPTY;
                    if (left == amount) return original.apply(stack, amount);
                    return original.apply(stack.copyWithCount((int) left), (int) left);
                });
    }

    public static FluidStack insertFluid(INetwork network, FluidStack stack, int amount, Action action,
                                         BiFunction<FluidStack, Integer, FluidStack> original) {
        if (stack.isEmpty() || amount <= 0 || !NetworkOutputReception.canReceive(network)
                || !TaskOutputReception.hasWaitingResourceType(network, ResourceKind.FLUID,
                ForgeRegistries.FLUIDS.getKey(stack.getFluid()))) {
            return original.apply(stack, amount);
        }
        return NetworkOutputReception.route(network, TaskOutputReception.resource(stack), amount,
                action == Action.SIMULATE, left -> {
                    if (left == 0) return FluidStack.EMPTY;
                    if (left == amount) return original.apply(stack, amount);
                    FluidStack remainder = stack.copy();
                    remainder.setAmount((int) left);
                    return original.apply(remainder, (int) left);
                });
    }
}
