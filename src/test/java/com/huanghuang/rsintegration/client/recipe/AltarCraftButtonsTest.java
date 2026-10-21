package com.huanghuang.rsintegration.client.recipe;

import com.huanghuang.rsintegration.ModType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AltarCraftButtonsTest {
    private static final ResourceLocation RECIPE = id("recipe");
    private static final ResourceLocation UPDATED = id("updated");

    @BeforeEach
    void reset() {
        AltarCraftButtons.clear();
    }

    @Test
    void craftButtonMetadataAndBoundsStayTogether() {
        AtomicInteger clicks = new AtomicInteger();
        AltarCraftButtons.add(10, 20, 8, 9, clicks::incrementAndGet,
                "tooltip", RECIPE, null, BlockPos.ZERO, ModType.GENERIC);

        AltarCraftButtons.updateBounds(0, 30, 40, 11, 12);
        AltarCraftButtons.setRecipeId(0, UPDATED);

        assertEquals(0, AltarCraftButtons.hitTest(35, 45));
        assertEquals(-1, AltarCraftButtons.hitTest(15, 25));
        assertEquals(UPDATED, AltarCraftButtons.getButtonData(0).recipeId());
        assertEquals("tooltip", AltarCraftButtons.getButtonData(0).tooltip());
    }

    @Test
    void machineButtonsUseTheSameBoundsSemantics() {
        AltarCraftButtons.addMachineGui(
                2, 3, 8, 9, () -> {});
        AltarCraftButtons.updateMachineGuiBounds(0, 50, 60, 10, 11);

        assertEquals(1, AltarCraftButtons.getMachineGuiCount());
        assertEquals(0, AltarCraftButtons.hitTestMachineGui(55, 65));
        assertEquals(-1, AltarCraftButtons.hitTestMachineGui(5, 6));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("rs_integration", path);
    }
}
