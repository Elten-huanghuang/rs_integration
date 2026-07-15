package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeOutputAccumulatorTest extends BootstrapTest {

    @Test
    void publishesOnlyNewlyConfirmedDeclaredUnits() {
        NodeId node = new NodeId(0);
        ItemStack template = new ItemStack(Items.DIAMOND);
        MaterialKey diamond = MaterialKey.of(template);
        OutputPortId port = new OutputPortId(node, 0);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(
                List.of(new OutputDeclaration(port, diamond, 3, OutputKind.PRIMARY)));

        List<NodeOutputAccumulator.Publication> first = accumulator.add(
                List.of(new ItemStack(Items.DIAMOND, 2)));
        List<NodeOutputAccumulator.Publication> second = accumulator.add(
                List.of(new ItemStack(Items.DIAMOND, 2)));

        assertEquals(2, first.get(0).stack().getCount());
        assertEquals(1, second.get(0).stack().getCount());
        assertTrue(accumulator.isComplete());
        assertEquals(1, accumulator.drainSurplus().get(0).getCount());
        assertTrue(accumulator.drainSurplus().isEmpty());
    }

    @Test
    void preservesNbtAndDoesNotUseWrongVariantForDeclaration() {
        ItemStack red = tagged("red", 1);
        ItemStack blue = tagged("blue", 1);
        MaterialKey redKey = MaterialKey.of(red);
        OutputPortId port = new OutputPortId(new NodeId(0), 0);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(
                List.of(new OutputDeclaration(port, redKey, 1, OutputKind.PRIMARY)));

        assertTrue(accumulator.add(List.of(blue)).isEmpty());
        assertFalse(accumulator.isComplete());
        NodeOutputAccumulator.Publication publication = accumulator.add(List.of(red)).get(0);

        assertEquals("red", publication.stack().getTag().getString("variant"));
        assertTrue(accumulator.isComplete());
        assertEquals("blue", accumulator.drainSurplus().get(0).getTag().getString("variant"));
    }

    private static ItemStack tagged(String variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return stack;
    }
}
