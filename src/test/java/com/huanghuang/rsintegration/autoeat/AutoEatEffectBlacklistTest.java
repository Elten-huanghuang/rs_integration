package com.huanghuang.rsintegration.autoeat;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoEatEffectBlacklistTest extends BootstrapTest {

    private static final ResourceLocation NAUSEA = new ResourceLocation("minecraft", "nausea");
    private static final ResourceLocation POISON = new ResourceLocation("minecraft", "poison");

    @Test
    void blocksDeclaredEffectEvenWhenChanceIsPartial() {
        FoodProperties food = foodWithNausea(0.2F);

        assertTrue(FoodEffectBlacklist.matches(food, Set.of(NAUSEA)));
    }

    @Test
    void allowsFoodWhenNoDeclaredEffectMatches() {
        FoodProperties food = foodWithNausea(1.0F);

        assertFalse(FoodEffectBlacklist.matches(food, Set.of(POISON)));
        assertFalse(FoodEffectBlacklist.matches(food, Set.of()));
    }

    private static FoodProperties foodWithNausea(float chance) {
        return new FoodProperties.Builder()
                .nutrition(4)
                .saturationMod(0.4F)
                .effect(() -> new MobEffectInstance(MobEffects.CONFUSION, 200), chance)
                .build();
    }
}
