package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArsImbuementMaterialsTest extends BootstrapTest {
    @Test
    void centralInputIsConsumedButPedestalItemsAreReusableCatalysts() {
        var specs = ArsImbuementMaterials.build(
                Ingredient.of(Items.AMETHYST_SHARD),
                List.of(Ingredient.of(Items.FEATHER), Ingredient.of(Items.ARROW)));

        assertEquals(List.of(DemandRole.CONSUMED, DemandRole.CATALYST, DemandRole.CATALYST),
                specs.stream().map(spec -> spec.role()).toList());
    }

    @Test
    void timeoutAllowsArsNouveauFallbackSourceAccumulation() {
        assertEquals(1200, ArsImbuementBatchDelegate.timeoutTicksForSourceCost(500));
        assertEquals(300, ArsImbuementBatchDelegate.timeoutTicksForSourceCost(0));
    }

    @Test
    void pedestalRecipesRejectMachinesWithoutEnoughPedestals() {
        List<Ingredient> catalysts = List.of(
                Ingredient.of(Items.SUGAR),
                Ingredient.of(Items.MILK_BUCKET),
                Ingredient.of(Items.FERMENTED_SPIDER_EYE));

        assertEquals(3, ArsImbuementMaterials.pedestalItemCount(catalysts));
        assertFalse(ArsImbuementMaterials.hasPedestalCapacity(catalysts, 0));
        assertFalse(ArsImbuementMaterials.hasPedestalCapacity(catalysts, 2));
        assertTrue(ArsImbuementMaterials.hasPedestalCapacity(catalysts, 3));
        assertTrue(ArsImbuementMaterials.hasPedestalCapacity(List.of(), 0));
    }
}
