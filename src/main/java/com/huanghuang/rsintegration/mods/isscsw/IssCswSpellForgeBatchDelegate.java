package com.huanghuang.rsintegration.mods.isscsw;

import com.huanghuang.rsintegration.mods.common.ReflectiveMenuBatchDelegate;

public final class IssCswSpellForgeBatchDelegate extends ReflectiveMenuBatchDelegate {
    public IssCswSpellForgeBatchDelegate() {
        super(IssCswRSModule.RECIPE_CLASS,
                "org.xszb.interlace_spellweaves.gui.spell_forge.SpellForgeMenu",
                "iss_csw:spell_forge", 3, 3, true);
    }

    /** SpellForgeMenu builds its recipe container as slot1, slot0, slot2. */
    @Override
    protected int menuInputSlotIndex(int recipeInputIndex) {
        return recipeInputIndex == 0 ? 1 : recipeInputIndex == 1 ? 0 : recipeInputIndex;
    }
}
