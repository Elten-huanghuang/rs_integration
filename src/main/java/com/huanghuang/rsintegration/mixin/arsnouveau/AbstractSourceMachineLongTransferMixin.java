package com.huanghuang.rsintegration.mixin.arsnouveau;

import com.hollingsworth.arsnouveau.api.source.AbstractSourceMachine;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import com.huanghuang.rsintegration.feature.resonance.magic.ResonantMagicStorage;
import com.huanghuang.rsintegration.feature.resonance.magic.ResonantSourceTransferMath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ars 的原生传输速率由两个 int 相减得到；共振罐库存超过 int 上限后会
 * 饱和成相同数值并被误判为零余量。这里只在目标是共振罐时按 long 余量重算。
 */
@Mixin(value = AbstractSourceMachine.class, remap = false)
public abstract class AbstractSourceMachineLongTransferMixin {
    @Inject(method = "getTransferRate(Lcom/hollingsworth/arsnouveau/api/source/ISourceTile;"
            + "Lcom/hollingsworth/arsnouveau/api/source/ISourceTile;)I",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void rsi$useLongTargetSpace(ISourceTile source, ISourceTile target,
                                        CallbackInfoReturnable<Integer> callback) {
        if (!(target instanceof ResonantMagicStorage storage)
                || !storage.isBound() || !storage.reachable()) {
            return;
        }
        ISourceTile self = (ISourceTile) (Object) this;
        callback.setReturnValue(ResonantSourceTransferMath.transferRate(
                self.getTransferRate(), source.getSource(), storage.currentAmount(),
                storage.resourceCapacity(), Integer.MAX_VALUE));
    }

    @Inject(method = "getTransferRate(Lcom/hollingsworth/arsnouveau/api/source/ISourceTile;"
            + "Lcom/hollingsworth/arsnouveau/api/source/ISourceTile;I)I",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void rsi$useLongTargetSpaceWithRequestedAmount(
            ISourceTile source, ISourceTile target, int requested,
            CallbackInfoReturnable<Integer> callback) {
        if (!(target instanceof ResonantMagicStorage storage)
                || !storage.isBound() || !storage.reachable()) {
            return;
        }
        ISourceTile self = (ISourceTile) (Object) this;
        callback.setReturnValue(ResonantSourceTransferMath.transferRate(
                self.getTransferRate(), source.getSource(), storage.currentAmount(),
                storage.resourceCapacity(), requested));
    }
}
