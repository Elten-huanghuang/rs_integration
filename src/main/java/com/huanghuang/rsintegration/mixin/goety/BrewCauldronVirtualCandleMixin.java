package com.huanghuang.rsintegration.mixin.goety;

import com.huanghuang.rsintegration.mods.goety.magic.GoetyCandleSoulPort;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** 仅在 RSI 一键炼制期间接入共用烛台与灵魂账本，兼容新旧 Goety。 */
@Pseudo
@Mixin(targets = "com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity", remap = false)
public abstract class BrewCauldronVirtualCandleMixin {
    @Shadow @Final private List<Object> candlestickBlockEntityList;
    @Shadow public int soulTime;
    @Shadow public boolean isBrewing;
    @Shadow public abstract int getBrewCost();
    @Unique private long rsi$advancedAt = Long.MIN_VALUE;

    @Inject(method = "findCandlesticks", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$useManagedCandles(CallbackInfo callback) {
        if (!GoetyCandleSoulPort.isActive(this)) return;
        rsi$refreshCandles();
        callback.cancel();
    }

    @Inject(method = {"brew()V", "brew(Lnet/minecraft/world/entity/player/Player;)V"},
            at = @At("HEAD"), require = 0)
    private void rsi$prepareManagedCandles(CallbackInfo callback) {
        if (GoetyCandleSoulPort.isActive(this)) rsi$refreshCandles();
    }

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lcom/Polarice3/Goety/common/blocks/entities/BrewCauldronBlockEntity;getBrewCost()I"), require = 0)
    private void rsi$advanceManagedBrew(CallbackInfo callback) {
        var level = ((BlockEntity) (Object) this).getLevel();
        if (!isBrewing || level == null || level.isClientSide || rsi$advancedAt == level.getGameTime()) return;
        rsi$advancedAt = level.getGameTime();
        int extra = Math.min(GoetyCandleSoulPort.virtualCandleSpeed(this) - 1,
                Math.max(0, getBrewCost() - soulTime - 1));
        soulTime += GoetyCandleSoulPort.advance(this, extra);
    }

    @Unique
    private void rsi$refreshCandles() {
        candlestickBlockEntityList.clear();
        candlestickBlockEntityList.addAll(GoetyCandleSoulPort.candles(this));
    }
}
