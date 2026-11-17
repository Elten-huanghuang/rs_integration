package com.huanghuang.rsintegration.feature.transmutation;

import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll.Outcome;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransmutationTableRerollTest extends BootstrapTest {
    @Test
    void anyOfTheThreeOptionsStopsBeforeTransmuting() {
        for (int index = 0; index < 3; index++) {
            List<ItemStack> options = options();
            options.set(index, new ItemStack(Items.DIAMOND, 64));
            assertEquals(Outcome.MATCHED, evaluate(options, 10, 3, false));
        }
    }

    @Test
    void emptyInputOrAnyMissingOptionStops() {
        assertEquals(Outcome.UNAVAILABLE, TransmutationTableReroll.evaluate(
                ItemStack.EMPTY, options(), 10, 3, false, stack -> false));
        for (int index = 0; index < 3; index++) {
            List<ItemStack> options = options();
            options.set(index, ItemStack.EMPTY);
            assertEquals(Outcome.UNAVAILABLE, evaluate(options, 10, 3, false));
        }
    }

    @Test
    void respectsNativeLevelAndCreativeConditions() {
        assertEquals(Outcome.UNAVAILABLE, evaluate(options(), 2, 3, false));
        assertEquals(Outcome.READY, evaluate(options(), 3, 3, false));
        assertEquals(Outcome.READY, evaluate(options(), 0, 3, true));
        assertEquals(Outcome.READY, evaluate(options(), 0, 0, false));
    }

    @Test
    void matchingResultTakesPriorityOverInsufficientLevels() {
        List<ItemStack> options = options();
        options.set(2, new ItemStack(Items.DIAMOND));
        assertEquals(Outcome.MATCHED, evaluate(options, 0, 3, false));
    }

    @Test
    void everyResponseChangesStateEvenWhenAllRollsAreIdentical() {
        int state = 0;
        for (int index = 0; index < 20000; index++) {
            Outcome outcome = Outcome.values()[index % 3];
            int next = TransmutationTableReroll.nextState(state, outcome);
            assertNotEquals(state, next);
            assertTrue(next >= 0 && next <= Short.MAX_VALUE);
            assertEquals(outcome, TransmutationTableReroll.outcome(next));
            state = next;
        }
    }

    private static Outcome evaluate(List<ItemStack> options, int levels, int cost, boolean creative) {
        return TransmutationTableReroll.evaluate(new ItemStack(Items.COBBLESTONE),
                options, levels, cost, creative, stack -> stack.is(Items.DIAMOND));
    }

    private static List<ItemStack> options() {
        return new ArrayList<>(List.of(new ItemStack(Items.APPLE),
                new ItemStack(Items.STICK), new ItemStack(Items.EMERALD)));
    }
}
