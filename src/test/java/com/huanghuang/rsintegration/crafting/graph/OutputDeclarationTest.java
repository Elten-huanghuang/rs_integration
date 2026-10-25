package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputDeclarationTest extends BootstrapTest {
    private static final NodeId NODE = new NodeId(1);

    @Test
    void legacyConstructorIsAnExactGuaranteedDeclaration() {
        OutputDeclaration output = new OutputDeclaration(
                new OutputPortId(NODE, 0), MaterialKey.of(new ItemStack(Items.DIAMOND)),
                4, OutputKind.PRIMARY);

        assertEquals(4, output.minimumQuantity());
        assertEquals(4, output.maximumQuantity());
        assertTrue(output.probability().isGuaranteed());
    }

    @Test
    void probabilisticRangeDoesNotPromisePlannedQuantity() {
        OutputProbability probability =
                OutputProbability.probabilistic(0.25D, 3, "diamond_bonus");
        OutputDeclaration output = new OutputDeclaration(
                new OutputPortId(NODE, 0), MaterialKey.of(new ItemStack(Items.DIAMOND)),
                0, OutputKind.SECONDARY, 0, 2, probability, false, null);

        assertFalse(output.probability().isGuaranteed());
        assertEquals(0, output.quantity());
        assertEquals(2, output.maximumQuantity());
    }

    @Test
    void returnedOutputRequiresASourceInput() {
        InputPortId source = new InputPortId(NODE, 0);
        MaterialKey bucket = MaterialKey.of(new ItemStack(Items.BUCKET));

        assertThrows(IllegalArgumentException.class, () -> new OutputDeclaration(
                new OutputPortId(NODE, 1), bucket, 1, OutputKind.CONTAINER,
                1, 1, OutputProbability.guaranteed(), false, null));
        new OutputDeclaration(new OutputPortId(NODE, 1), bucket, 1, OutputKind.CONTAINER,
                1, 1, OutputProbability.guaranteed(), false, source);
    }

    @Test
    void invalidProbabilityModelsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputProbability.probabilistic(0.5D, 0, "test"));
        assertThrows(IllegalArgumentException.class,
                () -> OutputProbability.probabilistic(Double.NaN, 1, "test"));
        assertThrows(IllegalArgumentException.class,
                () -> new OutputProbability(
                        OutputProbability.Mode.GUARANTEED, 0.5D, 1, "test"));
    }
}
