package com.Polarice3.Goety.common.crafting;

/** 可选 Goety 配方接口的测试替身，不进入发布包。 */
public class RitualRecipe {
    private final int soulCost;
    private final int duration;
    private final boolean totalSettlement;

    public RitualRecipe(int soulCost, int duration, boolean totalSettlement) {
        this.soulCost = soulCost;
        this.duration = duration;
        this.totalSettlement = totalSettlement;
    }

    public int getSoulCost() { return soulCost; }
    public int getDuration() { return duration; }
    public boolean usesTotalSettlement() { return totalSettlement; }
}
