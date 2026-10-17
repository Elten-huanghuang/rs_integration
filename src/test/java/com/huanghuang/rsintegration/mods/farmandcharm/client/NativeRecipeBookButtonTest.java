package com.huanghuang.rsintegration.mods.farmandcharm.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeRecipeBookButtonTest {

    @Test
    void buttonIsCenteredBelowRecipeBookAndClampedToScreen() {
        NativeRecipeBookButton.Layout layout =
                NativeRecipeBookButton.layout(854, 480, 157, 166, 250);

        assertEquals(267, layout.x());
        assertEquals(327, layout.y());
    }

    @Test
    void narrowScreenKeepsButtonInsideViewport() {
        NativeRecipeBookButton.Layout layout =
                NativeRecipeBookButton.layout(100, 240, 37, 166, 80);

        assertEquals(2, layout.x());
        assertEquals(207, layout.y());
    }
}
