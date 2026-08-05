package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.util.ModIds;

/** Selects inference only until an Embers recipe code has been discovered. */
public final class EreAlchemyDelegateMode {

    private EreAlchemyDelegateMode() {}

    public static boolean shouldUseInference(String modTypeId,
                                             boolean inferenceRequested,
                                             boolean codeKnown) {
        if (!inferenceRequested) return false;
        return !ModIds.ID_EMBERS_ALCHEMY.equals(modTypeId) || !codeKnown;
    }
}
