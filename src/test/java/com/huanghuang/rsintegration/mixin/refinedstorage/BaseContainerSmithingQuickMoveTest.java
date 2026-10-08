package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.craftingstation.CraftingStationInputSlot;
import com.huanghuang.rsintegration.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.craftingstation.CraftingStationResultSlot;
import com.huanghuang.rsintegration.craftingstation.CraftingStationState;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseContainerSmithingQuickMoveTest extends BootstrapTest {
    @ParameterizedTest
    @EnumSource(value = CraftingStationMode.class, names = "CRAFTING", mode = EnumSource.Mode.EXCLUDE)
    void fullSurvivalInventoryStopsVanillaShiftLoopAndPreservesInput(CraftingStationMode mode) {
        Fixture fixture = inputFixture(mode, new ItemStack(Items.BLACKSTONE, 64));
        fillInventory(fixture.inventory);
        ItemStack original = fixture.input.getItem();

        fixture.menu.clicked(0, 0, ClickType.QUICK_MOVE, fixture.player);

        assertEquals(1, fixture.menu.attempts);
        assertSame(original, fixture.input.getItem());
        assertEquals(64, fixture.input.getItem().getCount());
        assertEquals(0, fixture.inventory.countItem(Items.BLACKSTONE));
        verify(fixture.state, never()).recompute();
        verify(fixture.state, never()).setInput(anyInt(), any());
    }

    @ParameterizedTest
    @EnumSource(value = CraftingStationMode.class, names = "CRAFTING", mode = EnumSource.Mode.EXCLUDE)
    void partialSpaceMovesOnlyWhatFitsThenStops(CraftingStationMode mode) {
        Fixture fixture = inputFixture(mode, new ItemStack(Items.BLACKSTONE, 64));
        fillInventory(fixture.inventory);
        fixture.inventory.items.set(9, new ItemStack(Items.BLACKSTONE, 60));

        fixture.menu.clicked(0, 0, ClickType.QUICK_MOVE, fixture.player);

        assertEquals(2, fixture.menu.attempts);
        assertEquals(60, fixture.input.getItem().getCount());
        assertEquals(64, fixture.inventory.items.get(9).getCount());
        verify(fixture.state, times(1)).recompute();
    }

    @ParameterizedTest
    @EnumSource(value = CraftingStationMode.class, names = "CRAFTING", mode = EnumSource.Mode.EXCLUDE)
    void availableSpaceMovesWholeInput(CraftingStationMode mode) {
        Fixture fixture = inputFixture(mode, new ItemStack(Items.BLACKSTONE, 64));

        fixture.menu.clicked(0, 0, ClickType.QUICK_MOVE, fixture.player);

        assertEquals(1, fixture.menu.attempts);
        assertTrue(fixture.input.getItem().isEmpty());
        assertEquals(64, fixture.inventory.countItem(Items.BLACKSTONE));
        verify(fixture.state, times(1)).recompute();
    }

    @Test
    void fullInventoryPreservesDamagedItemAndItsNbt() {
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(10);
        tool.getOrCreateTag().putString("rsi_test", "preserved");
        Fixture fixture = inputFixture(CraftingStationMode.ANVIL, tool);
        fillInventory(fixture.inventory);

        fixture.menu.clicked(0, 0, ClickType.QUICK_MOVE, fixture.player);

        assertSame(tool, fixture.input.getItem());
        assertEquals(10, tool.getDamageValue());
        assertEquals("preserved", tool.getTag().getString("rsi_test"));
        verify(fixture.state, never()).recompute();
    }

    @Test
    void matchingOffhandStackCanReceivePartialInput() {
        Fixture fixture = inputFixture(CraftingStationMode.STONECUTTER, new ItemStack(Items.BLACKSTONE, 8));
        fillInventory(fixture.inventory);
        fixture.inventory.offhand.set(0, new ItemStack(Items.BLACKSTONE, 62));

        fixture.menu.clicked(0, 0, ClickType.QUICK_MOVE, fixture.player);

        assertEquals(2, fixture.menu.attempts);
        assertEquals(6, fixture.input.getItem().getCount());
        assertEquals(64, fixture.inventory.offhand.get(0).getCount());
    }

    @Test
    void creativeInventoryRetainsVanillaOverflowBehaviorWithoutLooping() {
        Fixture fixture = inputFixture(CraftingStationMode.STONECUTTER, new ItemStack(Items.BLACKSTONE, 64));
        fillInventory(fixture.inventory);
        fixture.player.getAbilities().instabuild = true;

        fixture.menu.clicked(0, 0, ClickType.QUICK_MOVE, fixture.player);

        assertEquals(1, fixture.menu.attempts);
        assertTrue(fixture.input.getItem().isEmpty());
    }

    @Test
    void fullInventoryDoesNotTakeOrConsumeResult() {
        Player player = player();
        fillInventory(player.getInventory());
        CraftingStationResultSlot result = resultSlot(new ItemStack(Items.BLACKSTONE, 2));
        ShiftMenu menu = new ShiftMenu(result);

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);

        assertEquals(1, menu.attempts);
        assertEquals(2, result.getItem().getCount());
        verify(result, never()).onTake(any(), any());
    }

    @Test
    void repeatedResultTransferRechecksPickupPermission() {
        Player player = player();
        CraftingStationResultSlot result = resultSlot(new ItemStack(Items.BLACKSTONE, 2));
        doAnswer(invocation -> {
            // 模拟铁砧第一次取出后经验不足，但仍显示下一份结果。
            when(result.mayPickup(player)).thenReturn(false);
            return null;
        }).when(result).onTake(any(), any());
        ShiftMenu menu = new ShiftMenu(result);

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);

        assertEquals(2, menu.attempts);
        assertEquals(2, player.getInventory().countItem(Items.BLACKSTONE));
        verify(result, times(1)).onTake(any(), any());
    }

    @Test
    void resultTransferStopsWhenInventoryFills() {
        Player player = player();
        fillInventory(player.getInventory());
        player.getInventory().items.set(9, new ItemStack(Items.BLACKSTONE, 60));
        CraftingStationResultSlot result = resultSlot(new ItemStack(Items.BLACKSTONE, 2));
        ShiftMenu menu = new ShiftMenu(result);

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);

        assertEquals(3, menu.attempts);
        assertEquals(64, player.getInventory().items.get(9).getCount());
        verify(result, times(2)).onTake(any(), any());
    }

    private static Fixture inputFixture(CraftingStationMode mode, ItemStack stack) {
        Player player = player();
        int count = switch (mode) {
            case STONECUTTER -> 1;
            case ANVIL -> 2;
            case SMITHING -> 3;
            default -> throw new IllegalArgumentException("不支持的工作站模式");
        };
        SimpleContainer inputs = new SimpleContainer(count);
        inputs.setItem(count - 1, stack);
        CraftingStationState state = mock(CraftingStationState.class);
        when(state.inputs()).thenReturn(inputs);
        CraftingStationInputSlot input = new CraftingStationInputSlot(state, count - 1, 0, 0, true);
        return new Fixture(player, player.getInventory(), state, input, new ShiftMenu(input));
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(new Inventory(player));
        when(player.getAbilities()).thenReturn(new Abilities());
        when(player.level()).thenReturn(mock(Level.class));
        return player;
    }

    private static void fillInventory(Inventory inventory) {
        for (int index = 0; index < inventory.items.size(); index++) {
            inventory.items.set(index, new ItemStack(Items.COBBLESTONE, 64));
        }
    }

    private static CraftingStationResultSlot resultSlot(ItemStack output) {
        CraftingStationResultSlot result = mock(CraftingStationResultSlot.class);
        when(result.getItem()).thenReturn(output);
        when(result.mayPickup(any())).thenReturn(true);
        return result;
    }

    private record Fixture(Player player, Inventory inventory, CraftingStationState state,
                           CraftingStationInputSlot input, ShiftMenu menu) {}

    /** 使用真实的原版点击循环，只代理未经过 Mixin 转换的转移入口。 */
    private static final class ShiftMenu extends AbstractContainerMenu {
        private int attempts;

        private ShiftMenu(Slot slot) {
            super(null, 0);
            addSlot(slot);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            assertTrue(++attempts <= 4, "Shift 转移没有停止");
            Slot slot = getSlot(index);
            String name = slot instanceof CraftingStationInputSlot ? "rsi$quickMoveInput" : "rsi$quickMoveResult";
            Class<?> slotType = slot instanceof CraftingStationInputSlot
                    ? CraftingStationInputSlot.class : CraftingStationResultSlot.class;
            try {
                Method method = BaseContainerSmithingQuickMoveMixin.class.getDeclaredMethod(name, Player.class, slotType);
                method.setAccessible(true);
                return (ItemStack) method.invoke(null, player, slot);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
