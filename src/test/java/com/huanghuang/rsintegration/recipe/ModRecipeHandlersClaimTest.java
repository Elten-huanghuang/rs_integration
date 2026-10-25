package com.huanghuang.rsintegration.recipe;

import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModRecipeHandlersClaimTest {
    @Test
    void higherPriorityWinsWithoutDependingOnRegistrationOrder() {
        ModRecipeHandler low = handler(0);
        ModRecipeHandler high = handler(100);

        ModRecipeHandlers.Claim claim = ModRecipeHandlers.selectClaim(List.of(low, high));

        assertFalse(claim.ambiguous());
        assertSame(high, claim.winner());
    }

    @Test
    void tiedHighestPriorityIsExplicitlyAmbiguous() {
        ModRecipeHandler first = handler(50);
        ModRecipeHandler second = handler(50);
        ModRecipeHandler lower = handler(10);

        ModRecipeHandlers.Claim claim =
                ModRecipeHandlers.selectClaim(List.of(first, second, lower));

        assertTrue(claim.ambiguous());
        assertNull(claim.winner());
        assertTrue(claim.candidates().containsAll(List.of(first, second)));
    }

    @Test
    void noMatchingHandlerHasNoWinner() {
        ModRecipeHandlers.Claim claim = ModRecipeHandlers.selectClaim(List.of());

        assertFalse(claim.ambiguous());
        assertNull(claim.winner());
    }

    private static ModRecipeHandler handler(int priority) {
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.claimPriority()).thenReturn(priority);
        when(handler.canHandle(ArgumentMatchers.any(Recipe.class))).thenReturn(true);
        return handler;
    }
}
