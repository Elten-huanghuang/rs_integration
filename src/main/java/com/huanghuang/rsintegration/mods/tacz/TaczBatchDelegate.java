package com.huanghuang.rsintegration.mods.tacz;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/** Generic TACZ material transaction with a mandatory physical workbench check. */
public final class TaczBatchDelegate extends GenericBatchDelegate {

    @Nullable
    private BlockPos machinePos;
    @Nullable
    private ServerLevel machineLevel;
    @Nullable
    private ResourceLocation recipeId;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.machineLevel = null;
        this.recipeId = null;
        this.machinePos = null;
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || pos == null
                || AltarBindingRegistry.findBindingEntry(
                player, level.dimension().location(), pos) == null
                || !TaczWorkbenchCompatibility.accepts(level, pos, recipeId)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.unsupported_machine", recipeId));
            return false;
        }
        this.machineLevel = level;
        this.recipeId = recipeId;
        this.machinePos = pos.immutable();
        if (!super.validateAndInit(player, recipeId, level.dimension().location(), pos)) {
            this.machineLevel = null;
            this.recipeId = null;
            this.machinePos = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return super.validateExecutionContext(player)
                && machineLevel != null && machinePos != null && recipeId != null
                && TaczWorkbenchCompatibility.accepts(machineLevel, machinePos, recipeId);
    }

    @Override
    @Nullable
    public BlockPos getMachinePos() {
        return machinePos;
    }
}
