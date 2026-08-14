package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.ModType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManaPoolBindingRulesTest {

    private static final BlockPos BINDING = new BlockPos(4, 1, 14);

    @BeforeAll
    static void registerBotaniaType() {
        BotaniaRSModule.INSTANCE.registerModType();
    }

    @Test
    void catalystBindingsResolveToManaPoolType() {
        ModType manaPool = ModType.byId("botania_mana_pool");

        assertEquals(manaPool, ModType.fromBlockKey(
                "alchemy_catalyst||block.botania.alchemy_catalyst"));
        assertEquals(manaPool, ModType.fromBlockKey(
                "conjuration_catalyst||block.botania.conjuration_catalyst"));
        assertEquals(manaPool, ModType.fromBlockKey(
                "mana_pool||block.botania.creative_pool"));
    }

    @Test
    void plainRecipeUsesDirectlyBoundPool() {
        var result = ManaPoolBindingRules.assess(
                BINDING, true, false, false, false, false);

        assertEquals(ManaPoolBindingRules.State.READY, result.state());
        assertEquals(BINDING, result.poolPos());
    }

    @Test
    void catalystRecipeUsesPoolAboveMatchingCatalyst() {
        var result = ManaPoolBindingRules.assess(
                BINDING, false, true, true, true, false);

        assertEquals(ManaPoolBindingRules.State.READY, result.state());
        assertEquals(BINDING.above(), result.poolPos());
    }

    @Test
    void customCatalystRecipeUsesPoolBindingWhenBlockBelowMatches() {
        var result = ManaPoolBindingRules.assess(
                BINDING, true, false, true, false, true);

        assertEquals(ManaPoolBindingRules.State.READY, result.state());
        assertEquals(BINDING, result.poolPos());
    }

    @Test
    void catalystRecipeRejectsPoolBindingWhenBlockBelowDoesNotMatch() {
        var result = ManaPoolBindingRules.assess(
                BINDING, true, false, true, false, false);

        assertEquals(ManaPoolBindingRules.State.FATAL, result.state());
        assertNull(result.poolPos());
    }

    @Test
    void plainRecipeRejectsCatalystBindingPermanently() {
        var result = ManaPoolBindingRules.assess(
                BINDING, false, true, false, false, false);

        assertEquals(ManaPoolBindingRules.State.FATAL, result.state());
        assertNull(result.poolPos());
    }

    @Test
    void catalystRecipeRejectsWrongCatalystPermanently() {
        var result = ManaPoolBindingRules.assess(
                BINDING, false, true, true, false, false);

        assertEquals(ManaPoolBindingRules.State.FATAL, result.state());
        assertNull(result.poolPos());
    }

    @Test
    void missingPoolAboveCatalystRemainsRetryable() {
        var result = ManaPoolBindingRules.assess(
                BINDING, false, false, true, true, false);

        assertEquals(ManaPoolBindingRules.State.RETRY, result.state());
        assertNull(result.poolPos());
    }
}
