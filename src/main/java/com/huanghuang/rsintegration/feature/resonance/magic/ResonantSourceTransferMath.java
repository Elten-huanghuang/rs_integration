package com.huanghuang.rsintegration.feature.resonance.magic;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;

/** 把 Ars 的 int 传输速率与共振魔源罐的真实 long 余量合并。 */
public final class ResonantSourceTransferMath {
    private ResonantSourceTransferMath() {}

    public static int transferRate(int sourceRate, int sourceAmount, long targetAmount,
                                   long targetCapacity, int requested) {
        int availableSource = Math.min(Math.max(0, sourceRate),
                Math.max(0, sourceAmount));
        long targetSpace = targetCapacity > targetAmount
                ? targetCapacity - Math.max(0, targetAmount) : 0;
        return Math.min(availableSource, Math.min(Math.max(0, requested),
                SaturatingLongMath.clampToInt(targetSpace)));
    }
}
