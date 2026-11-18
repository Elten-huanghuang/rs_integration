package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.gui.MachineInteractionAccess;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraftforge.common.util.FakePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static com.huanghuang.rsintegration.machine.MachineManualActions.CollectionPolicy.BOUND_NETWORK;
import static com.huanghuang.rsintegration.machine.MachineManualActions.CollectionPolicy.CURRENT_NETWORK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MachineManualActionsTest extends BootstrapTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:the_nether");
    private static final BlockPos POSITION = new BlockPos(10, 64, 20);

    @Test
    void currentNetworkIsResolvedAfterExtractionAndMissingNetworkDropsOutput() {
        Fixture fixture = new Fixture();
        try (var access = fixture.allowAccess()) {
            fixture.collect(CURRENT_NETWORK, () -> {
                verify(fixture.furnace).setItem(FurnaceMachineAccess.OUTPUT_SLOT, ItemStack.EMPTY);
                return Optional.empty();
            });
        }

        verify(fixture.player).drop(argThat(stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 5), eq(false));
        verify(fixture.player).sendSystemMessage(argThat(message -> hasKey(message, "rsi.machine.collected")));
    }

    @Test
    void missingBoundNetworkDoesNotTouchTheFurnace() {
        Fixture fixture = new Fixture();
        try (var access = fixture.allowAccess()) {
            fixture.collect(BOUND_NETWORK, Optional::empty);
        }

        verifyNoInteractions(fixture.furnace);
        verify(fixture.player, never()).drop(any(), eq(false));
        verify(fixture.player).sendSystemMessage(argThat(message ->
                hasKey(message, "rsi.generic.error.network_unavailable")));
    }

    @ParameterizedTest
    @EnumSource(MachineManualActions.CollectionPolicy.class)
    void partialInsertionDropsOnlyTheExactRemainder(MachineManualActions.CollectionPolicy policy) {
        Fixture fixture = new Fixture();
        try (var access = fixture.allowAccess()) {
            fixture.collect(policy, () -> {
                if (policy == BOUND_NETWORK) verifyNoInteractions(fixture.furnace);
                return Optional.of(output -> {
                    verify(fixture.furnace).setItem(FurnaceMachineAccess.OUTPUT_SLOT, ItemStack.EMPTY);
                    assertEquals(5, output.getCount());
                    return output.copyWithCount(2);
                });
            });
        }

        verify(fixture.player).drop(argThat(stack -> stack.is(Items.IRON_INGOT)
                && stack.getCount() == 2 && stack.hasCustomHoverName()), eq(false));
    }

    @ParameterizedTest
    @EnumSource(MachineManualActions.CollectionPolicy.class)
    void inventoryCollectionNeverResolvesANetwork(MachineManualActions.CollectionPolicy policy) {
        Fixture fixture = new Fixture();
        Inventory inventory = mock(Inventory.class);
        when(fixture.player.getInventory()).thenReturn(inventory);
        when(inventory.add(any(ItemStack.class))).thenAnswer(call -> {
            ItemStack output = call.getArgument(0);
            output.shrink(4);
            return false;
        });

        try (var access = fixture.allowAccess()) {
            MachineManualActions.collect(fixture.player, DIMENSION, POSITION, false, policy,
                    () -> { throw new AssertionError("背包取货不应查找网络"); });
        }

        verify(fixture.player).drop(argThat(stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 1), eq(false));
    }

    @Test
    void rejectedAccessStopsBothActionsBeforeInventoryOrNetworkAccess() {
        Fixture fixture = new Fixture();
        try (var access = fixture.access(new MachineInteractionAccess.Result(
                null, MachineInteractionAccess.Error.PROTECTED))) {
            fixture.collect(BOUND_NETWORK, () -> { throw new AssertionError("被拒用后不应查找网络"); });
            MachineManualActions.insert(fixture.player, DIMENSION, POSITION, MachineSlotType.INPUT);
        }

        verifyNoInteractions(fixture.furnace);
        verify(fixture.player, never()).drop(any(), eq(false));
        verify(fixture.player, times(2)).sendSystemMessage(argThat(message ->
                hasKey(message, "rsi.error.protected_block")));
    }

    @Test
    void inputInsertionPreservesItemsThatDoNotFit() {
        Fixture fixture = new Fixture();
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        fixture.player.containerMenu = menu;
        when(menu.getCarried()).thenReturn(new ItemStack(Items.IRON_INGOT, 5));
        when(fixture.furnace.getItem(FurnaceMachineAccess.INPUT_SLOT))
                .thenReturn(new ItemStack(Items.IRON_INGOT, 62));
        when(fixture.furnace.getMaxStackSize()).thenReturn(64);

        try (var access = fixture.allowAccess()) {
            MachineManualActions.insert(fixture.player, DIMENSION, POSITION, MachineSlotType.INPUT);
        }

        verify(fixture.furnace).setItem(eq(FurnaceMachineAccess.INPUT_SLOT),
                argThat(inSlot -> inSlot.getCount() == 64));
        verify(menu).setCarried(argThat(remainder -> remainder.is(Items.IRON_INGOT) && remainder.getCount() == 3));
    }

    @Test
    void fakePlayerCannotCollectOrInsert() {
        FakePlayer player = mock(FakePlayer.class);
        try (var access = mockStatic(MachineInteractionAccess.class)) {
            MachineManualActions.collect(player, DIMENSION, POSITION, true, CURRENT_NETWORK,
                    () -> { throw new AssertionError("假玩家不应查找网络"); });
            MachineManualActions.insert(player, DIMENSION, POSITION, MachineSlotType.INPUT);
            access.verifyNoInteractions();
        }
        verifyNoInteractions(player);
    }

    private static boolean hasKey(Component message, String key) {
        return message != null && message.getContents() instanceof TranslatableContents text
                && key.equals(text.getKey());
    }

    private static final class Fixture {
        final ServerPlayer player = mock(ServerPlayer.class);
        final AbstractFurnaceBlockEntity furnace = mock(AbstractFurnaceBlockEntity.class);

        Fixture() {
            ItemStack output = new ItemStack(Items.IRON_INGOT, 5);
            output.setHoverName(Component.literal("output"));
            when(furnace.getItem(FurnaceMachineAccess.OUTPUT_SLOT)).thenReturn(output);
        }

        MockedStatic<MachineInteractionAccess> allowAccess() {
            return access(new MachineInteractionAccess.Result(new MachineInteractionAccess.Target(
                    mock(ServerLevel.class), furnace, null), null));
        }

        MockedStatic<MachineInteractionAccess> access(MachineInteractionAccess.Result result) {
            var access = mockStatic(MachineInteractionAccess.class);
            access.when(() -> MachineInteractionAccess.resolve(player, DIMENSION, POSITION, true, true))
                    .thenReturn(result);
            access.when(() -> MachineInteractionAccess.sendError(eq(player), any()))
                    .thenCallRealMethod();
            return access;
        }

        void collect(MachineManualActions.CollectionPolicy policy, Supplier<Optional<UnaryOperator<ItemStack>>> resolve) {
            MachineManualActions.collect(player, DIMENSION, POSITION, true, policy, resolve);
        }
    }
}
