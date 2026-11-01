package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import com.rekindled.embers.blockentity.StampBaseBlockEntity;
import com.rekindled.embers.blockentity.StamperBlockEntity;
import com.rekindled.embers.recipe.FluidIngredient;
import com.rekindled.embers.recipe.IStampingRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class EmbersStamperBatchDelegateTest extends BootstrapTest {
    @Test
    void replacesWrongStampWithAvailableCorrectStamp() {
        ItemStack original = new ItemStack(Items.IRON_INGOT);
        ItemStackHandler slot = new ItemStackHandler(1);
        slot.setStackInSlot(0, original.copy());

        assertTrue(EmbersStamperBatchDelegate.replaceStamp(slot, original,
                new ItemStack(Items.GOLD_INGOT), Ingredient.of(Items.GOLD_INGOT)));
        assertTrue(Ingredient.of(Items.GOLD_INGOT).test(slot.getStackInSlot(0)));
    }

    @Test
    void rejectsWrongReplacementAndConcurrentSlotChange() {
        ItemStack original = new ItemStack(Items.IRON_INGOT);
        ItemStackHandler slot = new ItemStackHandler(1);
        slot.setStackInSlot(0, original.copy());
        Ingredient required = Ingredient.of(Items.GOLD_INGOT);

        assertFalse(EmbersStamperBatchDelegate.replaceStamp(slot, original,
                new ItemStack(Items.COPPER_INGOT), required));
        assertTrue(ItemStack.isSameItemSameTags(original, slot.getStackInSlot(0)));

        slot.setStackInSlot(0, new ItemStack(Items.DIAMOND));
        assertFalse(EmbersStamperBatchDelegate.replaceStamp(slot, original,
                new ItemStack(Items.GOLD_INGOT), required));
        assertTrue(Ingredient.of(Items.DIAMOND).test(slot.getStackInSlot(0)));
    }

    @Test
    void graphRecoversOnlyTheActuallyInstalledOwnedStampAndOnlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        EmbersStamperBatchDelegate delegate = fixture.delegate(true);

        List<ItemStack> actual = delegate.collectReusableMaterials(fixture.player);

        assertEquals(1, actual.size());
        assertEquals("chain", actual.get(0).getTag().getString("owner"));
        assertTrue(fixture.slot.getStackInSlot(0).isEmpty());
        assertTrue(delegate.collectReusableMaterials(fixture.player).isEmpty());
    }

    @Test
    void installedPlayerStampAndForeignReplacementAreNeverPublished() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.delegate(false).collectReusableMaterials(fixture.player).isEmpty());
        assertEquals(1, fixture.slot.getStackInSlot(0).getCount());
        EmbersStamperBatchDelegate owned = fixture.delegate(true);
        fixture.slot.setStackInSlot(0, new ItemStack(Items.DIAMOND));
        assertTrue(owned.collectReusableMaterials(fixture.player).isEmpty());
        assertEquals(Items.DIAMOND, fixture.slot.getStackInSlot(0).getItem());
    }

    @Test
    void freshOperationInheritsStampOwnershipAndTheOriginalMaterialLayout() throws Exception {
        Fixture fixture = new Fixture();
        EmbersStamperBatchDelegate previous = fixture.delegate(true);
        EmbersStamperBatchDelegate next = fixture.delegate(false);
        set(next, "started", false);
        List<IngredientSpec> required = List.of(
                new IngredientSpec(Ingredient.of(Items.CLAY_BALL), 1),
                new IngredientSpec(Ingredient.of(Items.GOLD_INGOT), 1, DemandRole.CATALYST));
        set(previous, "required", required);
        set(next, "required", required.subList(0, 1));
        previous.onBatchFinished(fixture.player);

        assertTrue(previous.transferReusableMaterialsTo(next));
        assertEquals(required, next.getRequiredMaterials());
        assertTrue(previous.collectReusableMaterials(fixture.player).isEmpty());
        assertEquals(1, fixture.slot.getStackInSlot(0).getCount());
        // 实际启动继承者，模板中的印模不能作为第二枚未投放物品再次返回。
        IStampingRecipe recipe = mock(IStampingRecipe.class);
        FluidIngredient noFluid = mock(FluidIngredient.class);
        when(noFluid.getFluids()).thenReturn(List.of());
        when(recipe.getDisplayInput()).thenReturn(Ingredient.of(Items.CLAY_BALL));
        when(recipe.getDisplayInputFluid()).thenReturn(noFluid);
        when(recipe.matches(any(), eq(fixture.level))).thenReturn(true);
        set(next, "recipe", recipe);
        ItemStackHandler input = new ItemStackHandler(1);
        Field inventory = StampBaseBlockEntity.class.getField("inventory");
        inventory.setAccessible(true);
        inventory.set(fixture.base, input);
        next = spy(next);
        doReturn(true).when(next).validateExecutionContext(fixture.player);
        try (MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertTrue(next.startPreparedOperation(fixture.player,
                    List.of(new ItemStack(Items.CLAY_BALL), fixture.stamp.copy()), new ExtractionLedger()));
        }
        input.extractItem(0, 1, false);
        assertTrue(next.collectAllResults(fixture.player).isEmpty());
        assertEquals(1, next.collectReusableMaterials(fixture.player).size());
        assertTrue(fixture.slot.getStackInSlot(0).isEmpty());
    }

    @Test
    void finishedStampIsReturnedThroughItsOriginalStorageEndpointOnlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        EmbersStamperBatchDelegate delegate = fixture.delegate(true);
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        StorageOperationResult inserted = mock(StorageOperationResult.class);
        when(inserted.remainder()).thenReturn(Optional.of(ItemStack.EMPTY));
        when(endpoint.insert(eq(fixture.player), any(ItemStack.class), eq(false))).thenReturn(inserted);
        set(delegate, "stampReturnEndpoint", endpoint);
        delegate.onBatchFinished(fixture.player);

        delegate.releaseReusableMaterials(fixture.player);
        delegate.releaseReusableMaterials(fixture.player);

        verify(endpoint).insert(eq(fixture.player), any(ItemStack.class), eq(false));
        assertTrue(fixture.slot.getStackInSlot(0).isEmpty());
    }

    @Test
    void changedStructureRejectsHandoffAndLeavesTheStampInItsOriginalMachine() throws Exception {
        Fixture fixture = new Fixture();
        EmbersStamperBatchDelegate previous = fixture.delegate(true);
        EmbersStamperBatchDelegate next = fixture.delegate(false);
        set(next, "stamper", mock(StamperBlockEntity.class));

        assertFalse(previous.transferReusableMaterialsTo(next));
        assertEquals(1, previous.collectReusableMaterials(fixture.player).size());
        assertTrue(next.collectReusableMaterials(fixture.player).isEmpty());
    }

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final StamperBlockEntity stamper = mock(StamperBlockEntity.class);
        final StampBaseBlockEntity base = mock(StampBaseBlockEntity.class);
        final ItemStackHandler slot = new ItemStackHandler(1);
        final BlockPos pos = new BlockPos(0, 64, 0);
        final ItemStack stamp = new ItemStack(Items.GOLD_INGOT);

        Fixture() throws Exception {
            stamp.getOrCreateTag().putString("owner", "chain");
            slot.setStackInSlot(0, stamp.copy());
            Field field = StamperBlockEntity.class.getField("stamp");
            field.setAccessible(true);
            field.set(stamper, slot);
            when(level.hasChunkAt(pos)).thenReturn(true);
            when(level.hasChunkAt(pos.below(3))).thenReturn(true);
            when(level.getBlockEntity(pos)).thenReturn(stamper);
            when(level.getBlockEntity(pos.below(2))).thenReturn(base);
        }

        EmbersStamperBatchDelegate delegate(boolean ownsStamp) throws Exception {
            EmbersStamperBatchDelegate delegate = new EmbersStamperBatchDelegate();
            set(delegate, "level", level);
            set(delegate, "pos", pos);
            set(delegate, "stamper", stamper);
            set(delegate, "base", base);
            set(delegate, "started", true);
            if (ownsStamp) set(delegate, "placedStamp", stamp.copy());
            return delegate;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = EmbersStamperBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
