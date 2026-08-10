package com.huanghuang.rsintegration.mods.goety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoetyRitualPolicyTest {
    @Test
    void classifiesAutomaticAndManualRituals() {
        assertEquals(GoetyRitualPolicy.Execution.AUTOMATIC,
                GoetyRitualPolicy.classify(new FakeRecipe(false), new CraftRitual()));
        assertEquals(GoetyRitualPolicy.Execution.MANUAL_CONFIRMATION,
                GoetyRitualPolicy.classify(new FakeRecipe(false), new SummonRitual()));
        assertEquals(GoetyRitualPolicy.Execution.MANUAL_CONFIRMATION,
                GoetyRitualPolicy.classify(new FakeRecipe(false), new ConvertRitual()));
        assertEquals(GoetyRitualPolicy.Execution.MANUAL_CONFIRMATION,
                GoetyRitualPolicy.classify(new FakeRecipe(true), new CraftRitual()));
    }

    @Test
    void keepsTeleportAndMissingRitualsUnsupported() {
        assertEquals(GoetyRitualPolicy.Execution.UNSUPPORTED,
                GoetyRitualPolicy.classify(new FakeRecipe(false), new TeleportRitual()));
        assertEquals(GoetyRitualPolicy.Execution.UNSUPPORTED,
                GoetyRitualPolicy.classify(new FakeRecipe(false), null));
    }

    @Test
    void unknownRecipeShapeRequiresManualConfirmation() {
        assertEquals(GoetyRitualPolicy.Execution.MANUAL_CONFIRMATION,
                GoetyRitualPolicy.classify(new Object(), new CraftRitual()));
    }

    public record FakeRecipe(boolean requiresSacrifice) {
        public boolean requiresSacrifice() {
            return requiresSacrifice;
        }
    }

    private static final class CraftRitual {}
    private static final class SummonRitual {}
    private static final class ConvertRitual {}
    private static final class TeleportRitual {}
}
