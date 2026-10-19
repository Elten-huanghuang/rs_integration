package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EidolonWorktableRemainderTest extends BootstrapTest {

    @Test
    void worktableRemainderIsOwnedUntilResultCollection() throws Exception {
        EidolonBatchDelegate delegate = worktableDelegate();
        ServerPlayer player = player();

        try (MockedStatic<PlayerUtils> delivery = mockStatic(PlayerUtils.class)) {
            assertTrue(delegate.tryStartWithMaterials(player,
                    List.of(new ItemStack(Items.WATER_BUCKET)), new ExtractionLedger()));
            delivery.verifyNoInteractions();

            List<ItemStack> results = delegate.collectAllResults(player);

            assertEquals(2, results.size());
            assertTrue(results.get(0).is(Items.DIAMOND));
            assertTrue(results.get(1).is(Items.BUCKET));
            delivery.verifyNoInteractions();
        }
    }

    @Test
    void abortBeforeCollectionDoesNotDeliverRemainderBeforeLedgerRefund() throws Exception {
        EidolonBatchDelegate delegate = worktableDelegate();
        ServerPlayer player = player();

        try (MockedStatic<PlayerUtils> delivery = mockStatic(PlayerUtils.class)) {
            assertTrue(delegate.tryStartWithMaterials(player,
                    List.of(new ItemStack(Items.WATER_BUCKET)), new ExtractionLedger()));
            delegate.onBatchFailed(player, "cancelled before collection");

            delivery.verifyNoInteractions();
            assertTrue(readField(delegate, "pendingResult", ItemStack.class).isEmpty());
            assertTrue(readField(delegate, "pendingRemainders", List.class).isEmpty());
            assertFalse(readField(delegate, "craftCompleted", Boolean.class));
        }
    }

    private static EidolonBatchDelegate worktableDelegate() throws Exception {
        EidolonBatchDelegate delegate = new EidolonBatchDelegate();
        setField(delegate, "isWorktable", true);
        setField(delegate, "recipe", new ShapelessRecipe(
                new ResourceLocation("test", "worktable_remainder"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.WATER_BUCKET))));
        return delegate;
    }

    private static ServerPlayer player() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.serverLevel()).thenReturn(level);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        return player;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T readField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
