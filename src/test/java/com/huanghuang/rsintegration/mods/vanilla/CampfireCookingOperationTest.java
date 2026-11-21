package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CampfireCookingOperationTest extends BootstrapTest {
    @Test
    void receivesOnlyOwnedNativeOutputAndPreservesTags() {
        var campfire = new Campfire();
        var session = TaskOutputReception.open();
        var manual = new ItemStack(Items.BEEF);
        campfire.getItems().set(0, manual);
        var operation = CampfireCookingOperation.start(campfire, new ItemStack(Items.BEEF), 10, session.writer());
        assertNotNull(operation);
        var owned = campfire.getItems().get(1);
        var actual = new ItemStack(Items.COOKED_BEEF, 2);
        actual.getOrCreateTag().putString("variant", "marinated");

        assertFalse(CampfireCookingOperation.receive(campfire, 0, manual, actual));
        assertFalse(CampfireCookingOperation.receive(new Campfire(), 1, owned, actual));
        assertFalse(CampfireCookingOperation.receive(campfire, 1, owned.copy(), actual));
        assertFalse(operation.completed());
        assertTrue(CampfireCookingOperation.receive(campfire, 1, owned, actual));
        assertTrue(operation.completed());
        assertFalse(CampfireCookingOperation.receive(campfire, 1, owned, actual));
        assertSame(manual, campfire.getItems().get(0));

        var stacks = session.drainStacks();
        assertEquals(1, stacks.size());
        assertTrue(ItemStack.matches(actual, stacks.get(0)));
        assertTrue(session.drainStacks().isEmpty());
        var contract = CampfireCookingOperation.outputContract(new ItemStack(Items.COOKED_BEEF, 2));
        assertTrue(OutputAccounting.assess(contract, 1,
                OutputAccounting.attributeReceived(contract, 1, stacks)).complete());
    }

    @Test
    void cancelsOwnedInputWithoutRefundingEqualReplacement() {
        var campfire = new Campfire();
        var session = TaskOutputReception.open();
        var material = new ItemStack(Items.BEEF);
        var first = CampfireCookingOperation.start(campfire, material, 10, session.writer());
        var second = CampfireCookingOperation.start(campfire, material, 10, session.writer());
        var replacement = material.copy();
        campfire.getItems().set(1, replacement);
        var owned = campfire.getItems().get(0);
        assertSame(owned, first.cancel(campfire));
        assertTrue(campfire.getItems().get(0).isEmpty());
        assertEquals(0, campfire.rsi$cookingTime()[0]);
        assertTrue(second.cancel(campfire).isEmpty());
        assertSame(replacement, campfire.getItems().get(1));
        assertEquals(10, campfire.rsi$cookingTime()[1]);
        assertFalse(CampfireCookingOperation.receive(campfire, 0, owned, new ItemStack(Items.COOKED_BEEF)));
        assertTrue(session.drainStacks().isEmpty());
    }

    @Test
    void missingRecipeAndRemovedCampfireDoNotComplete() {
        var campfire = new Campfire();
        var session = TaskOutputReception.open();
        var operation = CampfireCookingOperation.start(campfire, new ItemStack(Items.BEEF), 10, session.writer());
        var owned = campfire.getItems().get(0);
        assertFalse(CampfireCookingOperation.receive(campfire, 0, owned, owned));
        campfire.setRemoved();
        assertTrue(operation.cancel(campfire).isEmpty());
        assertFalse(operation.completed());
        assertSame(owned, campfire.getItems().get(0));
        assertTrue(session.drainStacks().isEmpty());
    }

    @Test
    void closedSessionLeavesNativeDropUntouched() {
        var campfire = new Campfire();
        var session = TaskOutputReception.open();
        var operation = CampfireCookingOperation.start(campfire, new ItemStack(Items.BEEF), 10, session.writer());
        session.close();
        assertFalse(CampfireCookingOperation.receive(campfire, 0, campfire.getItems().get(0),
                new ItemStack(Items.COOKED_BEEF)));
        assertFalse(operation.completed());
        assertTrue(session.drainStacks().isEmpty());
    }

    private static final class Campfire extends CampfireBlockEntity implements CampfireCookingOperation.Access {
        private final int[] progress = new int[4];
        private final int[] time = new int[4];
        private final CampfireCookingOperation[] operations = new CampfireCookingOperation[4];

        Campfire() { super(BlockPos.ZERO, Blocks.CAMPFIRE.defaultBlockState()); }
        @Override public int[] rsi$cookingProgress() { return progress; }
        @Override public int[] rsi$cookingTime() { return time; }
        @Override public CampfireCookingOperation rsi$getCookingOperation(int slot) { return operations[slot]; }
        @Override public void rsi$setCookingOperation(int slot, CampfireCookingOperation operation) {
            operations[slot] = operation;
        }
    }
}
