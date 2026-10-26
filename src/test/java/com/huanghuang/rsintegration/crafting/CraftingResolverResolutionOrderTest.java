package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingResolverResolutionOrderTest extends BootstrapTest {

    @Test
    void machineRootsResolveConcreteInputsBeforeTagsAndPreserveOriginalSlotOrder() {
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(
                        Ingredient.of(Items.OAK_LOG, Items.BIRCH_LOG), 1,
                        DemandRole.CONSUMED),
                new IngredientSpec(
                        Ingredient.of(Items.DIAMOND), 1,
                        DemandRole.CONSUMED),
                new IngredientSpec(
                        Ingredient.of(Items.BUCKET), 1,
                        DemandRole.CATALYST));

        assertEquals(List.of(1, 0, 2),
                CraftingResolver.machineRootResolutionOrder(specs));
    }
}
