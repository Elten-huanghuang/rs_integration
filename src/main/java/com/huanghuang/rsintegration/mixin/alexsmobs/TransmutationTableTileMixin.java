package com.huanghuang.rsintegration.mixin.alexsmobs;

import com.github.alexthe666.alexsmobs.tileentity.TileEntityTransmutationTable;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableMenuAccess;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableStateAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(TileEntityTransmutationTable.class)
public abstract class TransmutationTableTileMixin implements TransmutationTableStateAccess {
    @Shadow(remap = false)
    private UUID rerollPlayerUUID;

    @Override
    public UUID rsIntegration$getRerollPlayer() {
        return rerollPlayerUUID;
    }

    @Inject(method = "randomizeResults", at = @At("RETURN"), remap = false)
    private void rsIntegration$confirmResults(Player player, CallbackInfo callback) {
        if (player.containerMenu instanceof TransmutationTableMenuAccess menu) {
            menu.rsIntegration$onResultsChanged(player, (BlockEntity) (Object) this);
        }
    }
}
