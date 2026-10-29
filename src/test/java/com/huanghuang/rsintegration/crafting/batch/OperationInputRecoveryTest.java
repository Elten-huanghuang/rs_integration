package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.batch.OperationInputRecovery.Input;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationInputRecoveryTest extends BootstrapTest {
    @Test
    void unplacedInputsRemainRecoverableWithoutAWorldAndReceiptsAreDefensive() {
        OperationInputRecovery recovery = new OperationInputRecovery();
        ItemStack held = tagged(3);
        recovery.hold(held);
        held.setCount(1);

        List<ItemStack> result = recovery.recover(null);
        assertEquals(3, result.get(0).getCount());
        result.get(0).setCount(1);
        assertEquals(3, recovery.recover(null).get(0).getCount());
        assertThrows(IllegalStateException.class, () -> recovery.hold(tagged(1)));
    }

    @Test
    void partiallyConsumedInputsReturnOnlyActualSurvivors() {
        Fixture fixture = new Fixture();
        fixture.place(tagged(5));
        fixture.inventory.setStackInSlot(0, tagged(2));

        assertEquals(2, fixture.recovery.recover(fixture.level).get(0).getCount());
        assertTrue(fixture.inventory.getStackInSlot(0).isEmpty());
    }

    @Test
    void extractionIsCappedByTheOwnedQuantityAndRepeatedRecoveryDoesNotExtractAgain() {
        Fixture fixture = new Fixture();
        fixture.place(tagged(2));
        fixture.inventory.setStackInSlot(0, tagged(7));

        assertEquals(2, fixture.recovery.recover(fixture.level).get(0).getCount());
        assertEquals(2, fixture.recovery.recover(fixture.level).get(0).getCount());
        assertEquals(5, fixture.inventory.getStackInSlot(0).getCount());
    }

    @Test
    void consumedInputsAreNotRecreated() {
        Fixture fixture = new Fixture();
        fixture.place(tagged(2));
        fixture.inventory.setStackInSlot(0, ItemStack.EMPTY);

        assertTrue(fixture.recovery.recover(fixture.level).isEmpty());
    }

    @Test
    void sameItemWithDifferentNbtIsNotClaimed() {
        Fixture fixture = new Fixture();
        fixture.place(tagged(2));
        ItemStack foreign = tagged(2);
        foreign.getOrCreateTag().putString("owner", "another");
        fixture.inventory.setStackInSlot(0, foreign);

        assertTrue(fixture.recovery.recover(fixture.level).isEmpty());
        assertEquals(2, fixture.inventory.getStackInSlot(0).getCount());
    }

    @Test
    void enchantedOutputIsNotClaimedAsTheOriginalUnbreakableTool() {
        Fixture fixture = new Fixture();
        ItemStack input = new ItemStack(Items.IRON_SWORD);
        input.getOrCreateTag().putBoolean("Unbreakable", true);
        fixture.place(input);
        ItemStack output = input.copy();
        output.enchant(Enchantments.SHARPNESS, 1);
        fixture.inventory.setStackInSlot(0, output);

        assertTrue(fixture.recovery.recover(fixture.level).isEmpty());
        assertTrue(ItemStack.isSameItemSameTags(output, fixture.inventory.getStackInSlot(0)));
    }

    @Test
    void equivalentPristineToolNbtRepresentationRemainsRecoverable() {
        Fixture fixture = new Fixture();
        ItemStack input = new ItemStack(Items.IRON_SWORD);
        input.getOrCreateTag().putBoolean("Unbreakable", true);
        fixture.place(input);
        ItemStack normalized = input.copy();
        normalized.getOrCreateTag().putInt("Unbreakable", 1);
        normalized.getOrCreateTag().putInt("Damage", 0);
        fixture.inventory.setStackInSlot(0, normalized);

        List<ItemStack> result = fixture.recovery.recover(fixture.level);

        assertEquals(1, result.size());
        assertTrue(ItemStack.isSameItemSameTags(normalized, result.get(0)));
        assertTrue(fixture.inventory.getStackInSlot(0).isEmpty());
    }

    @Test
    void replacedBlockEntityIsNotReadOrModified() {
        Fixture fixture = new Fixture();
        fixture.place(tagged(2));
        when(fixture.level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));

        assertTrue(fixture.recovery.recover(fixture.level).isEmpty());
        assertEquals(2, fixture.inventory.getStackInSlot(0).getCount());
    }

    @Test
    void unloadedBlockIsNotLoadedForRecoveryButHeldInputsStillReturn() {
        Fixture fixture = new Fixture();
        fixture.place(tagged(2));
        fixture.recovery.hold(new ItemStack(Items.GOLD_INGOT));
        when(fixture.level.hasChunkAt(BlockPos.ZERO)).thenReturn(false);
        clearInvocations(fixture.level);

        List<ItemStack> result = fixture.recovery.recover(fixture.level);

        assertEquals(1, result.size());
        assertEquals(Items.GOLD_INGOT, result.get(0).getItem());
        verify(fixture.level, never()).getBlockEntity(any());
        assertEquals(2, fixture.inventory.getStackInSlot(0).getCount());
    }

    @Test
    void observedStackIsNotAReceiptWhenExtractionRefusesIt() {
        Fixture fixture = new Fixture();
        IItemHandler refusing = mock(IItemHandler.class);
        when(refusing.getStackInSlot(0)).thenReturn(tagged(5));
        when(refusing.extractItem(0, 3, false)).thenReturn(ItemStack.EMPTY);
        fixture.recovery.hold(tagged(3)).place(fixture.owner, refusing, 0);

        assertTrue(fixture.recovery.recover(fixture.level).isEmpty());
        verify(refusing).extractItem(0, 3, false);
    }

    @Test
    void partialExtractorReceiptWinsOverObservedQuantity() {
        Fixture fixture = new Fixture();
        fixture.recovery.hold(tagged(4)).place(fixture.owner,
                () -> tagged(4), amount -> tagged(1));

        assertEquals(1, fixture.recovery.recover(fixture.level).get(0).getCount());
    }

    @Test
    void reusableInputAlreadyReturnedIsExcludedFromFailureRecovery() {
        Fixture fixture = new Fixture();
        Input catalyst = fixture.place(tagged(2));

        assertEquals(2, catalyst.take(fixture.level).getCount());
        assertTrue(catalyst.take(fixture.level).isEmpty());
        assertTrue(fixture.recovery.recover(fixture.level).isEmpty());
    }

    @Test
    void successfulOwnershipTransferDoesNotClearTheMachine() {
        Fixture fixture = new Fixture();
        fixture.place(tagged(2));
        fixture.recovery.release();

        assertTrue(fixture.recovery.recover(fixture.level).isEmpty());
        assertEquals(2, fixture.inventory.getStackInSlot(0).getCount());
    }

    @Test
    void unreadableSlotDoesNotLoseOtherRecoveryReceipts() {
        Fixture fixture = new Fixture();
        fixture.recovery.hold(new ItemStack(Items.GOLD_INGOT));
        fixture.recovery.hold(tagged(1)).place(fixture.owner,
                () -> { throw new IllegalStateException("unreadable"); },
                amount -> ItemStack.EMPTY);
        fixture.recovery.hold(new ItemStack(Items.DIAMOND));

        assertEquals(List.of(Items.GOLD_INGOT, Items.DIAMOND),
                fixture.recovery.recover(fixture.level).stream().map(ItemStack::getItem).toList());
    }

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class);
        final BlockEntity owner = mock(BlockEntity.class);
        final ItemStackHandler inventory = new ItemStackHandler(1);
        final OperationInputRecovery recovery = new OperationInputRecovery();

        Fixture() {
            when(owner.getBlockPos()).thenReturn(BlockPos.ZERO);
            when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(owner);
        }

        Input place(ItemStack stack) {
            Input input = recovery.hold(stack);
            input.place(owner, inventory, 0);
            inventory.setStackInSlot(0, stack.copy());
            return input;
        }
    }

    private static ItemStack tagged(int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putString("owner", "operation");
        return stack;
    }
}
