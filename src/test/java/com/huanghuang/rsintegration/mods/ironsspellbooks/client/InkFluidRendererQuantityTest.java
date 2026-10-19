package com.huanghuang.rsintegration.mods.ironsspellbooks.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InkFluidRendererQuantityTest {

    @Test
    void compactAmountKeepsTreeLabelsShortWithoutLosingScale() {
        assertEquals("9999", InkFluidRenderer.compactAmount(9_999));
        assertEquals("10.0K", InkFluidRenderer.compactAmount(10_000));
        assertEquals("12.3K", InkFluidRenderer.compactAmount(12_345));
        assertEquals("999K", InkFluidRenderer.compactAmount(999_000));
        assertEquals("1.2M", InkFluidRenderer.compactAmount(1_234_567));
        assertEquals("2.1B", InkFluidRenderer.compactAmount(Integer.MAX_VALUE));
    }
}
