package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 启动前归还旧内容；网络拒收时把剩余内容放回原槽位或原液罐。 */
public final class WRAlchemyRecovery {
    private WRAlchemyRecovery() {}

    public static boolean canRecover(List<IItemHandler> inventories, List<IFluidHandler> tanks,
                                     CraftStorageEndpoint endpoint, ServerPlayer player,
                                     Function<FluidStack, ItemStack> token) {
        if (endpoint == null || player == null) return false;
        List<ItemStack> contents = new ArrayList<>();
        for (IItemHandler inventory : inventories) {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (!stack.isEmpty()) contents.add(stack.copy());
            }
        }
        for (IFluidHandler tank : tanks) {
            FluidStack fluid = tank.getFluidInTank(0);
            if (!fluid.isEmpty()) contents.add(token.apply(fluid.copy()));
        }
        return contents.stream().allMatch(stack -> endpoint.insert(player, stack, true).status()
                == StorageOperationStatus.SUCCESS);
    }

    public static boolean recover(List<IItemHandler> inventories, List<IFluidHandler> tanks,
                                  CraftStorageEndpoint endpoint, ServerPlayer player,
                                  Function<FluidStack, ItemStack> token) {
        if (!canRecover(inventories, tanks, endpoint, player, token)) return false;
        for (IItemHandler inventory : inventories) {
            if (!(inventory instanceof IItemHandlerModifiable modifiable)) return false;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack original = inventory.getStackInSlot(slot).copy();
                if (original.isEmpty()) continue;
                StorageOperationResult stored = endpoint.insert(player, original.copy(), false);
                // 先存入，再按确认存入的数量清除。网络异常时不凭空重建未知结果。
                if (stored.transferredAmount().isEmpty()) throw new IllegalStateException("旧物品入库数量不确定");
                modifiable.setStackInSlot(slot, original.copyWithCount(
                        original.getCount() - Math.toIntExact(stored.transferredAmount().orElseThrow())));
                if (stored.status() != StorageOperationStatus.SUCCESS) return false;
            }
        }
        for (IFluidHandler tank : tanks) {
            FluidStack original = tank.getFluidInTank(0).copy();
            if (original.isEmpty()) continue;
            FluidStack removed = tank.drain(original.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (removed.isEmpty()) return false;
            ItemStack transfer = token.apply(removed);
            StorageOperationResult stored = endpoint.insert(player, transfer, false);
            if (stored.remainder().isEmpty()) throw new IllegalStateException("旧流体入库结果不确定");
            ItemStack remainder = stored.remainder().orElseThrow();
            if (!remainder.isEmpty()) {
                FluidStack fluid = InkFluidSupport.fluid(remainder);
                if (tank.fill(fluid, IFluidHandler.FluidAction.EXECUTE) != fluid.getAmount()) {
                    throw new IllegalStateException("无法归还炼金液体到原罐");
                }
            }
            if (stored.status() != StorageOperationStatus.SUCCESS) return false;
        }
        return true;
    }
}
