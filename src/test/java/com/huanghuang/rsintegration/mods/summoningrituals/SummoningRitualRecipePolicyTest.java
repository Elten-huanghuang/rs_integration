package com.huanghuang.rsintegration.mods.summoningrituals;

import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SummoningRitualRecipePolicyTest {
    @Test
    void ritualOutputsNeverBecomeRecursiveIntermediateProducts() {
        SummoningRitualRecipeHandler handler = new SummoningRitualRecipeHandler();
        Recipe<?> unrelated = org.mockito.Mockito.mock(Recipe.class);
        assertFalse(handler.indexPrimaryOutput(unrelated));
        assertFalse(handler.hasDeterministicPrimaryOutput(unrelated));
    }

    @Test
    void altarDelegateDoesNotPublishWorldOutputs() {
        assertFalse(new SummoningRitualAltarBatchDelegate().publishesDeclaredGraphOutputs());
    }
}
