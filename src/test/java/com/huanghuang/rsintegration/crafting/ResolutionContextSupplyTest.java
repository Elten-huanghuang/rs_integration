package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolutionContextSupplyTest extends BootstrapTest {

    @Test
    void detailedConsumptionPreservesInitialSourceAndQuantity() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 4);
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(iron), null);

        ResolutionContext.SupplyConsumption result = context.consumeMatchingDetailed(
                Ingredient.of(Items.IRON_INGOT), 3);

        assertTrue(result.complete());
        assertEquals(3, result.supplied());
        assertEquals(1, result.slices().size());
        assertTrue(result.slices().get(0).source() instanceof MaterialSource.InitialPool);
        assertEquals(3, result.slices().get(0).quantity());
        assertEquals(1, context.countMatching(Ingredient.of(Items.IRON_INGOT)));
    }

    @Test
    void producedSupplyCanBePartiallyConsumedWithoutLosingProvenance() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(), null);
        OutputPortId output = new OutputPortId(new com.huanghuang.rsintegration.crafting.graph.NodeId(0), 0);
        context.addProduced(new ItemStack(Items.DIAMOND, 5), new MaterialSource.ProducerOutput(output));

        ResolutionContext.SupplyConsumption result = context.consumeMatchingDetailed(
                Ingredient.of(Items.DIAMOND), 2);

        assertTrue(result.complete());
        MaterialSource.ProducerOutput source = (MaterialSource.ProducerOutput) result.slices().get(0).source();
        assertEquals(output, source.outputPort());
        assertEquals(MaterialKey.of(new ItemStack(Items.DIAMOND)), result.slices().get(0).material());
        assertEquals(3, context.countMatching(Ingredient.of(Items.DIAMOND)));
    }

    @Test
    void nestedRollbackRestoresSupplyAmounts() {
        ResolutionContext context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.GOLD_INGOT, 3)), null);
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(Ingredient.of(Items.GOLD_INGOT), 2).complete());
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(Ingredient.of(Items.GOLD_INGOT), 1).complete());
        context.rollback();
        context.rollback();

        ResolutionContext.SupplyConsumption afterRollback = context.consumeMatchingDetailed(
                Ingredient.of(Items.GOLD_INGOT), 3);
        assertTrue(afterRollback.complete());
        assertFalse(context.consumeMatchingDetailed(Ingredient.of(Items.GOLD_INGOT), 1).complete());
    }
}
