package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArsApparatusMaterialsTest extends BootstrapTest {
    @Test
    void reagentAndPedestalItemsAreConsumedByTheTransformation() {
        var specs = ArsApparatusMaterials.build(
                Ingredient.of(Items.DIAMOND_HELMET),
                List.of(Ingredient.of(Items.STRING), Ingredient.of(Items.STRING)));

        assertEquals(List.of(DemandRole.CONSUMED, DemandRole.CONSUMED, DemandRole.CONSUMED),
                specs.stream().map(spec -> spec.role()).toList());
    }
}
