package com.huanghuang.rsintegration.unifiedgrid;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.fluids.FluidStack;

import java.util.Iterator;
import java.util.UUID;

/** 异常情况下未能归还的已知流体保存在世界数据中，关终端、重启及死亡不丢弃。 */
public final class UnifiedGridFluidRecovery extends SavedData {
    private final ListTag pending;

    UnifiedGridFluidRecovery() { pending = new ListTag(); }
    UnifiedGridFluidRecovery(CompoundTag tag) { pending = tag.getList("pending", Tag.TAG_COMPOUND).copy(); }

    static UnifiedGridFluidRecovery get(ServerPlayer player) {
        return player.server.overworld().getDataStorage().computeIfAbsent(UnifiedGridFluidRecovery::new,
                UnifiedGridFluidRecovery::new, "rs_integration_unified_grid_fluid_recovery");
    }

    void retain(UUID player, FluidStack fluid) {
        if (fluid.isEmpty()) return;
        CompoundTag entry = new CompoundTag();
        entry.putUUID("owner", player);
        entry.put("fluid", fluid.writeToNBT(new CompoundTag()));
        pending.add(entry);
        setDirty();
    }

    void retry(UUID player, INetwork network) {
        Iterator<Tag> iterator = pending.iterator();
        int attempts = 0;
        while (iterator.hasNext() && attempts < 8) {
            CompoundTag entry = (CompoundTag) iterator.next();
            if (!entry.hasUUID("owner") || !player.equals(entry.getUUID("owner"))) continue;
            FluidStack fluid = FluidStack.loadFluidStackFromNBT(entry.getCompound("fluid"));
            // 缺失模组的注册名保留原始数据，待模组恢复后再处理。
            if (fluid.isEmpty()) continue;
            attempts++;
            FluidStack remainder = network.insertFluid(fluid, fluid.getAmount(), Action.PERFORM);
            if (remainder == null || (!remainder.isEmpty() && (!remainder.isFluidEqual(fluid)
                    || remainder.getAmount() > fluid.getAmount()))) continue;
            if (remainder.getAmount() == fluid.getAmount()) continue;
            if (remainder.isEmpty()) iterator.remove();
            else entry.put("fluid", remainder.writeToNBT(new CompoundTag()));
            setDirty();
        }
    }

    @Override public CompoundTag save(CompoundTag tag) {
        tag.put("pending", pending.copy());
        return tag;
    }
}
