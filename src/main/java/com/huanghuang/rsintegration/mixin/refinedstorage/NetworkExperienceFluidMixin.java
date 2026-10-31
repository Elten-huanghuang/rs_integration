package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.feature.experience.ExperienceFluidNormalization;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.network.Network;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 精妙背包通过导入器、管道等路径输入 RS 时统一液态经验身份。 */
@Mixin(value = Network.class, remap = false)
public abstract class NetworkExperienceFluidMixin {
    @Inject(method = "insertFluid(Lnet/minecraftforge/fluids/FluidStack;ILcom/refinedmods/refinedstorage/api/util/Action;)Lnet/minecraftforge/fluids/FluidStack;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$normalizeExperience(FluidStack stack, int size, Action action,
                                         CallbackInfoReturnable<FluidStack> callback) {
        if (size <= 0 || !ExperienceFluidNormalization.canNormalize(stack)) return;
        // 转换后的 RSI 流体不会再次命中；存储过滤、缓存和实际插入仍走原生 RS。
        callback.setReturnValue(ExperienceFluidNormalization.insertConverted(stack, size,
                converted -> ((INetwork) (Object) this).insertFluid(converted, size, action)));
    }
}
