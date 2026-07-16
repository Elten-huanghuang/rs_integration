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

    @Test
    void reportsEveryUnmetDeclarationWithoutDrainingSurplus() {
        NodeId node = new NodeId(2);
        OutputDeclaration primary = new OutputDeclaration(new OutputPortId(node, 0),
                MaterialKey.of(new ItemStack(Items.DIAMOND)), 3, OutputKind.PRIMARY);
        OutputDeclaration remainder = new OutputDeclaration(new OutputPortId(node, 1),
                MaterialKey.of(new ItemStack(Items.BUCKET)), 1, OutputKind.REMAINDER);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(primary, remainder));

        accumulator.add(List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.EMERALD)));

        List<NodeOutputAccumulator.Shortage> shortages = accumulator.shortages();
        assertEquals(2, shortages.size());
        assertEquals(1, shortages.get(0).missing());
        assertEquals(OutputKind.PRIMARY, shortages.get(0).kind());
        assertEquals(1, shortages.get(1).missing());
        assertEquals(OutputKind.REMAINDER, shortages.get(1).kind());
        assertTrue(accumulator.describeShortages().contains("minecraft:bucket"));
        assertEquals(1, accumulator.drainSurplus().get(0).getCount());
    }

    @Test
    void completedDeclarationsHaveNoShortages() {
        OutputDeclaration declaration = new OutputDeclaration(
                new OutputPortId(new NodeId(3), 0),
                MaterialKey.of(new ItemStack(Items.DIAMOND)), 1, OutputKind.PRIMARY);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(declaration));

        accumulator.add(List.of(new ItemStack(Items.DIAMOND)));

        assertTrue(accumulator.shortages().isEmpty());
        assertTrue(accumulator.describeShortages().isEmpty());
    }

    private static ItemStack tagged(String variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return stack;
    }
}
