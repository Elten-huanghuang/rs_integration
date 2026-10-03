package com.kurome.ageofmythology.tarot.awakening.upright;

import com.Polarice3.Goety.common.crafting.RitualRecipe;

/** 模拟补丁按配方判定一次性扣费；普通仪式仍按秒扣费。 */
public final class TarotRitualStartService {
    private TarotRitualStartService() {}

    public static boolean manages(RitualRecipe recipe) {
        return recipe.usesTotalSettlement();
    }
}
