package com.huanghuang.rsintegration.mods.distantworlds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class LithumAltarStateReader {
    private LithumAltarStateReader() {}

    public static Snapshot read(ServerLevel level, BlockPos corePos) {
        if (level == null || corePos == null || !level.isLoaded(corePos)) return null;
        BlockEntity core = level.getBlockEntity(corePos);
        if (!LithumAltarStructureHelper.isCore(core)) return null;
        var data = core.getPersistentData();
        List<ItemStack> pedestals = LithumAltarStructureHelper.pedestalPositions(corePos).stream()
                .map(level::getBlockEntity)
                .map(be -> LithumAltarStructureHelper.getSlot(be, 0))
                .toList();
        return new Snapshot(data.getString("CurrentRecipe"),
                data.getDouble("CurrentEnergy"), data.getDouble("MaxEnergy"),
                data.getDouble("Recovery"), data.getDouble("MaxRecovery"),
                LithumAltarStructureHelper.getSlot(core, 0), pedestals);
    }

    public record Snapshot(String currentRecipe, double currentEnergy, double maxEnergy,
                           double recovery, double maxRecovery, ItemStack coreStack,
                           List<ItemStack> pedestalStacks) {
        public Snapshot {
            coreStack = coreStack.copy();
            pedestalStacks = pedestalStacks.stream().map(ItemStack::copy).toList();
        }
    }
}
