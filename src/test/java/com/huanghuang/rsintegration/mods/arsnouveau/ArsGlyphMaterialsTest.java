package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArsGlyphMaterialsTest extends BootstrapTest {
    @Test
    void duplicateGlyphInputsRemainSeparateConsumedRequirements() {
        Ingredient redstone = Ingredient.of(Items.REDSTONE);
        var specs = ArsGlyphMaterials.build(List.of(
                redstone, Ingredient.of(Items.PISTON), redstone));

        assertEquals(3, specs.size());
        assertEquals(List.of(1, 1, 1), specs.stream().map(spec -> spec.count()).toList());
        assertEquals(List.of(DemandRole.CONSUMED, DemandRole.CONSUMED, DemandRole.CONSUMED),
                specs.stream().map(spec -> spec.role()).toList());
    }
}
