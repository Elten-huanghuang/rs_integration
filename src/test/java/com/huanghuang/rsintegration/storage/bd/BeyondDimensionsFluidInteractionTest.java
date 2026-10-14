package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerBucketTestFixtures;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerTransfer;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkBottleFluidHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.UnorderedStackHandlerRemoveZero;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.FluidStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import com.wintercogs.beyonddimensions.common.menu.widget.slot.DisorderedStackTypedSlot;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BeyondDimensionsFluidInteractionTest extends BootstrapTest {
    private MockedStatic<FluidUtil> capabilities;
    private MockedStatic<MaterialSources> materials;
    private MockedConstruction<ItemStackKey> itemKeys;
    private DimensionsNetMenu menu;
    private ServerPlayer player;
    private UnorderedStackHandlerRemoveZero storage;
    private ItemStack cursor;
    private int water, buckets, bottles;

    @BeforeEach void setup() throws Exception {
        capabilities = FluidContainerBucketTestFixtures.capabilities();
        capabilities.when(() -> FluidUtil.getFilledBucket(any(FluidStack.class))).thenCallRealMethod();
        materials = mockStatic(MaterialSources.class);
        // BD 的 ItemStackKey 构造器依赖游戏中的 AT；在单测里只替换这条字段读取边界。
        itemKeys = mockConstruction(ItemStackKey.class, (key, context) -> {
            ItemStack stack = ((ItemStack) context.arguments().get(0)).copyWithCount(1);
            when(key.getReadOnlyStack()).thenReturn(stack);
            when(key.copyStackWithCount(anyLong())).thenAnswer(call -> stack.copyWithCount(((Long) call.getArgument(0)).intValue()));
            when(key.isEmpty()).thenReturn(false);
        });
        player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        menu = mock(DimensionsNetMenu.class);
        storage = mock(UnorderedStackHandlerRemoveZero.class);
        set(menu, "player", player);
        set(menu, "storage", storage);
        player.containerMenu = menu;
        when(menu.stillValid(player)).thenReturn(true);
        DisorderedStackTypedSlot slot = mock(DisorderedStackTypedSlot.class);
        when(slot.isActive()).thenReturn(true);
        when(slot.getStorage()).thenReturn(storage);
        when(slot.mayPickup(player)).thenReturn(true);
        when(slot.mayPlace(any())).thenReturn(true);
        set(menu, "slots", NonNullList.<Slot>of(slot, slot));
        cursor = ItemStack.EMPTY;
        when(menu.getCarried()).thenAnswer(call -> cursor);
        doAnswer(call -> { cursor = call.getArgument(0); return null; }).when(menu).setCarried(any());
        when(storage.extract(any(), anyLong(), anyBoolean(), eq(false))).thenAnswer(call -> {
            IStackKey<?> key = call.getArgument(0);
            long request = call.getArgument(1);
            boolean simulate = call.getArgument(2);
            int available = key instanceof FluidStackKey ? water : itemCount((ItemStackKey) key);
            long moved = Math.min(request, available);
            if (!simulate) {
                if (key instanceof FluidStackKey) water -= (int) moved;
                else if (((ItemStackKey) key).getReadOnlyStack().is(Items.BUCKET)) buckets -= (int) moved;
                else bottles -= (int) moved;
            }
            return new KeyAmount(key, moved);
        });
        when(storage.insert(any(), anyLong(), anyBoolean())).thenAnswer(call -> {
            IStackKey<?> key = call.getArgument(0);
            long amount = call.getArgument(1);
            if (!(boolean) call.getArgument(2)) {
                if (key instanceof FluidStackKey) water += (int) amount;
                else if (((ItemStackKey) key).getReadOnlyStack().is(Items.BUCKET)) buckets += (int) amount;
                else bottles += (int) amount;
            }
            return new KeyAmount(key, 0);
        });
    }

    @AfterEach void release() { itemKeys.close(); materials.close(); capabilities.close(); }

    @Test void emptyCursorBorrowsOneNetworkBucketAndIgnoresClientQuantity() {
        water = 1000;
        buckets = 1;
        assertTrue(click(waterKey(1), 0));
        assertTrue(cursor.is(Items.WATER_BUCKET));
        assertEquals(0, water);
        assertEquals(0, buckets);
    }

    @Test void stackedGlassBottlesFillOneWaterBottleAndLeaveTheRestOnCursor() {
        cursor = new ItemStack(Items.GLASS_BOTTLE, 3);
        water = 250;
        assertTrue(click(waterKey(Long.MAX_VALUE), 1));
        assertTrue(cursor.is(Items.GLASS_BOTTLE));
        assertEquals(2, cursor.getCount());
        verify(player.getInventory()).placeItemBackInInventory(argThat(stack -> stack.is(Items.POTION)
                && PotionUtils.getPotion(stack) == Potions.WATER));
        assertEquals(0, water);
    }

    @Test void waterBottleEmptiesToNetworkWithoutLosingItsEmptyBottle() {
        cursor = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        assertTrue(click(new KeyAmount(FluidStackKey.EMPTY, 0), 1));
        assertTrue(cursor.is(Items.GLASS_BOTTLE));
        assertEquals(250, water);
    }

    @Test void insufficientFluidKeepsHeldBottleAndDoesNotInsertItAsAnItem() {
        cursor = new ItemStack(Items.GLASS_BOTTLE);
        water = 249;
        assertTrue(click(waterKey(1000), 1));
        assertTrue(cursor.is(Items.GLASS_BOTTLE));
        assertEquals(249, water);
        verify(storage, never()).insert(any(), anyLong(), eq(false));
    }

    @Test void ordinarySingleBucketAndOtherResourceClicksStayOnBdNativePath() {
        cursor = new ItemStack(Items.BUCKET);
        water = 1000;
        assertFalse(click(waterKey(1000), 1));
        cursor = new ItemStack(Items.STICK, 3);
        assertFalse(click(waterKey(1000), 1));
        assertEquals(1000, water);
        verify(storage, never()).extract(any(), anyLong(), eq(false), anyBoolean());
    }

    @Test void staleMenusAndInvalidSlotsCannotTransferResources() {
        water = 1000;
        buckets = 1;
        player.containerMenu = null;
        assertFalse(click(waterKey(1000), 0));
        player.containerMenu = menu;
        assertFalse(BeyondDimensionsFluidInteraction.handle(menu, -1, waterKey(1000), 0, false));
        assertFalse(BeyondDimensionsFluidInteraction.handle(menu, 5, waterKey(1000), 0, false));
        assertEquals(1000, water);
    }

    @Test void taggedFluidCannotLoseItsNbtThroughNormalOrShiftBucketFilling() {
        water = 2000;
        buckets = 2;
        FluidStack fluid = new FluidStack(Fluids.WATER, 1);
        fluid.getOrCreateTag().putString("variant", "keep");
        KeyAmount selected = new KeyAmount(new FluidStackKey(fluid), 2000);
        assertTrue(BeyondDimensionsFluidInteraction.handle(menu, 0, selected, 0, true));
        assertTrue(cursor.isEmpty());
        cursor = new ItemStack(Items.BUCKET);
        assertTrue(click(selected, 1));
        assertTrue(cursor.is(Items.BUCKET));
        assertEquals(2000, water);
        assertEquals(2, buckets);
        verify(storage, never()).extract(any(), anyLong(), eq(false), anyBoolean());
    }

    @Test void adapterUsesCopiedFluidIdentityAndPreservesSimulation() {
        water = 1000;
        FluidStack fluid = new FluidStack(Fluids.WATER, 1);
        BeyondDimensionsFluidStorage adapter = new BeyondDimensionsFluidStorage(storage);
        assertEquals(750, adapter.extractFluid(fluid, 750, true).getAmount());
        assertEquals(1000, water);
        assertEquals(1, fluid.getAmount());
        assertEquals(750, adapter.extractFluid(fluid, 750, false).getAmount());
        assertEquals(250, water);
    }

    private int itemCount(ItemStackKey key) { return key.getReadOnlyStack().is(Items.BUCKET) ? buckets : bottles; }
    private boolean click(KeyAmount key, int button) {
        return BeyondDimensionsFluidInteraction.handle(menu, 0, key, button, false);
    }
    private KeyAmount waterKey(long amount) { return new KeyAmount(new FluidStackKey(new FluidStack(Fluids.WATER, 1)), amount); }
    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(field);
    }
}
