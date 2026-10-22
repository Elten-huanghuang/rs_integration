package com.huanghuang.rsintegration.crafting;

import com.blamejared.crafttweaker.api.ingredient.transform.type.TransformDamage;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftPacketUtilsCraftTweakerDamageTest extends BootstrapTest {

    @Test
    void transformDamageUsesActualDamageAmountAndDurabilityScaling() {
        InputConsumption consumption = CraftPacketUtils
                .craftTweakerTransformerConsumption(new TransformDamage(5));

        assertEquals(InputConsumption.Kind.DURABILITY, consumption.kind());
        assertEquals(5L, consumption.unitsPerOperation());
    }
}
