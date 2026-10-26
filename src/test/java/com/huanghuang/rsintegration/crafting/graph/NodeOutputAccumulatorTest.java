package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidItem;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
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
                List.of(OutputDeclaration.item(port, diamond, 3, OutputKind.PRIMARY)));

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
                List.of(OutputDeclaration.item(port, redKey, 1, OutputKind.PRIMARY)));

        assertTrue(accumulator.add(List.of(blue)).isEmpty());
        assertFalse(accumulator.isComplete());
        NodeOutputAccumulator.Publication publication = accumulator.add(List.of(red)).get(0);

        assertEquals("red", publication.stack().getTag().getString("variant"));
        assertTrue(accumulator.isComplete());
        assertEquals("blue", accumulator.drainSurplus().get(0).getTag().getString("variant"));
    }

    @Test
    void taglessRecipeOutputAcceptsAndPreservesRuntimeNbt() {
        MaterialKey declared = MaterialKey.of(new ItemStack(Items.POTION));
        OutputPortId port = new OutputPortId(new NodeId(1), 0);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(
                List.of(OutputDeclaration.item(port, declared, 4, OutputKind.PRIMARY)));
        ItemStack actual = new ItemStack(Items.POTION, 4);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Purity", 3);
        actual.setTag(tag);

        NodeOutputAccumulator.Publication publication = accumulator.add(List.of(actual)).get(0);

        assertTrue(accumulator.isComplete());
        assertEquals(4, publication.stack().getCount());
        assertEquals(3, publication.stack().getTag().getInt("Purity"));
    }

    @Test
    void reportsEveryUnmetDeclarationWithoutDrainingSurplus() {
        NodeId node = new NodeId(2);
        OutputDeclaration primary = OutputDeclaration.item(new OutputPortId(node, 0),
                MaterialKey.of(new ItemStack(Items.DIAMOND)), 3, OutputKind.PRIMARY);
        OutputDeclaration remainder = OutputDeclaration.item(new OutputPortId(node, 1),
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
        OutputDeclaration declaration = OutputDeclaration.item(
                new OutputPortId(new NodeId(3), 0),
                MaterialKey.of(new ItemStack(Items.DIAMOND)), 1, OutputKind.PRIMARY);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(declaration));

        accumulator.add(List.of(new ItemStack(Items.DIAMOND)));

        assertTrue(accumulator.shortages().isEmpty());
        assertTrue(accumulator.describeShortages().isEmpty());
    }

    @Test
    void dynamicDeclarationAcceptsAndPreservesAnyRuntimeResult() {
        OutputDeclaration declaration = OutputDeclaration.item(
                new OutputPortId(new NodeId(4), 0),
                MaterialKey.of(new ItemStack(Items.SNIFFER_EGG)), 2, OutputKind.DYNAMIC);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(declaration));

        assertTrue(accumulator.add(List.of(new ItemStack(Items.PRISMARINE_SHARD))).isEmpty());

        assertTrue(accumulator.isComplete());
        assertTrue(accumulator.shortages().isEmpty());
        assertEquals(1, accumulator.drainSurplus().get(0).getCount());
    }

    @Test
    void prospectiveCaptureWaitsForTheEntireDeclaredBatchWithoutMutatingState() {
        OutputDeclaration declaration = OutputDeclaration.item(
                new OutputPortId(new NodeId(5), 0),
                MaterialKey.of(new ItemStack(Items.DIAMOND)), 2, OutputKind.PRIMARY);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(declaration));

        assertFalse(accumulator.canCompleteWith(List.of(new ItemStack(Items.DIAMOND))));
        assertEquals(2, accumulator.shortages().get(0).missing());
        assertTrue(accumulator.canCompleteWith(List.of(new ItemStack(Items.DIAMOND, 2))));
        assertEquals(2, accumulator.shortages().get(0).missing());
    }

    @Test
    void reportsLongExpectedQuantitiesWithoutOverflow() {
        long expected = (long) Integer.MAX_VALUE + 10L;
        OutputDeclaration declaration = OutputDeclaration.item(
                new OutputPortId(new NodeId(6), 0),
                MaterialKey.of(new ItemStack(Items.DIAMOND)), expected, OutputKind.PRIMARY);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(declaration));

        assertEquals(expected, accumulator.shortages().get(0).expected());
        assertEquals(expected, accumulator.shortages().get(0).missing());
    }

    @Test
    void publishesFluidTokenWhenOnlySerializedAmountMetadataDiffers() {
        InkFluidItem tokenItem = InkFluidTestFixtures.tokenItem();
        ItemStack declared = InkFluidSupport.token(tokenItem,
                new FluidStack(Fluids.WATER, 1000));
        ItemStack actual = declared.copy();
        actual.getOrCreateTag().putInt("Amount", 1000);
        OutputDeclaration declaration = OutputDeclaration.item(
                new OutputPortId(new NodeId(7), 0),
                MaterialKey.of(declared), declared.getCount(), OutputKind.PRIMARY);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(declaration));

        List<NodeOutputAccumulator.Publication> publications = accumulator.add(List.of(actual));

        assertEquals(1, publications.size());
        assertEquals(1000, publications.get(0).stack().getCount());
        assertTrue(accumulator.isComplete());
    }

    @Test
    void probabilisticOutputIsObservedWithoutBeingRequired() {
        OutputDeclaration declaration = OutputDeclaration.item(
                new OutputPortId(new NodeId(8), 0),
                MaterialKey.of(new ItemStack(Items.DIAMOND)),
                0, OutputKind.SECONDARY, 0, 2,
                OutputProbability.probabilistic(0.25D, 4, "diamond_bonus"),
                false, null);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(declaration));

        assertTrue(accumulator.isComplete());
        List<NodeOutputAccumulator.Publication> publications =
                accumulator.add(List.of(new ItemStack(Items.DIAMOND, 2)));

        assertEquals(1, publications.size());
        assertEquals(2, publications.get(0).stack().getCount());
        assertEquals(2, accumulator.publishedQuantity(declaration.id()));
        assertTrue(accumulator.isComplete());
        assertTrue(accumulator.drainSurplus().isEmpty());
    }

    @Test
    void probabilisticOutputPublishesNoMoreThanItsMaximumQuantity() {
        OutputDeclaration declaration = OutputDeclaration.item(
                new OutputPortId(new NodeId(9), 0),
                MaterialKey.of(new ItemStack(Items.DIAMOND)),
                0, OutputKind.SECONDARY, 0, 2,
                OutputProbability.probabilistic(0.25D, 4, "diamond_bonus"),
                false, null);
        NodeOutputAccumulator accumulator = new NodeOutputAccumulator(List.of(declaration));

        List<NodeOutputAccumulator.Publication> publications =
                accumulator.add(List.of(new ItemStack(Items.DIAMOND, 5)));

        assertEquals(1, publications.size());
        assertEquals(2, publications.get(0).stack().getCount());
        assertEquals(3, accumulator.drainSurplus().get(0).getCount());
        assertEquals(2, accumulator.publishedQuantity(declaration.id()));
    }

    private static ItemStack tagged(String variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return stack;
    }
}
