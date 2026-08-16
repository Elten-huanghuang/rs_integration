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

class ArsApparatusMaterialsTest extends BootstrapTest {
    @Test
    void pendingNativeStartUsesBoundedRetryWindow() {
        assertFalse(ArsApparatusBatchDelegate.retryWindowExpired(119, 120));
        assertTrue(ArsApparatusBatchDelegate.retryWindowExpired(120, 120));
    }

    @Test
    void sharedLedgerOwnsRejectedStartRefund() {
        assertFalse(ArsApparatusBatchDelegate.ownsRejectedStartRefund(true));
        assertTrue(ArsApparatusBatchDelegate.ownsRejectedStartRefund(false));
    }

    @Test
    void reagentAndPedestalItemsAreConsumedByTheTransformation() {
        var specs = ArsApparatusMaterials.build(
                Ingredient.of(Items.DIAMOND_HELMET),
                List.of(Ingredient.of(Items.STRING), Ingredient.of(Items.STRING)));

        assertEquals(List.of(DemandRole.CONSUMED, DemandRole.CONSUMED, DemandRole.CONSUMED),
                specs.stream().map(spec -> spec.role()).toList());
    }
}
