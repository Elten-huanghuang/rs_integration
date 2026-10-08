package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CraftPacketUtilsIngredientWithCountProbeTest extends BootstrapTest {

    private static final String INGREDIENT_WITH_COUNT_CLASS =
            "team.lodestar.lodestone.systems.recipe.IngredientWithCount";

    @Test
    void missingOptionalLodestoneClassIsCached() throws ReflectiveOperationException {
        boolean classAvailable;
        try {
            Class.forName(INGREDIENT_WITH_COUNT_CLASS, false,
                    CraftPacketUtils.class.getClassLoader());
            classAvailable = true;
        } catch (ClassNotFoundException expected) {
            classAvailable = false;
        }
        assumeFalse(classAvailable, "This regression test requires Lodestone to be absent");

        assertNull(CraftPacketUtils.extractLodestoneIngredients(new Object()));
        Field probeField = CraftPacketUtils.class.getDeclaredField("ingredientWithCountProbe");
        probeField.setAccessible(true);
        Object firstProbe = probeField.get(null);
        assertNotNull(firstProbe);

        assertNull(CraftPacketUtils.extractLodestoneIngredients(new Object()));
        assertSame(firstProbe, probeField.get(null));
    }
}
