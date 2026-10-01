package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.mods.rs.RSGridSearchCache;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.unifiedgrid.client.UnifiedGridView;
import com.huanghuang.rsintegration.unifiedgrid.client.UnifiedGridJeiTracker;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.integration.jei.IngredientTracker;
import com.refinedmods.refinedstorage.util.ItemStackKey;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UnifiedGridViewTest extends BootstrapTest {
    @Test void bothKindsRenderAndQuantityDeltaKeepsObjectAndOrderingWithoutRebuilding() {
        try (MockedStatic<RSGridSearchCache> search = mockStatic(RSGridSearchCache.class)) {
            search.when(() -> RSGridSearchCache.beforeForceSort(any(), any())).thenReturn(true);
            Fixture f = new Fixture();
            f.view.apply(f.packet(GridResourceKind.ITEM, 1, 0, true, true, f.item(1, 5)));
            f.view.apply(f.packet(GridResourceKind.FLUID, 1, 0, true, true,
                    new UnifiedGridEntry(2, 1000, true, UUID.randomUUID(), null, new FluidStack(Fluids.WATER, 1), null)));
            f.view.forceSort();
            assertEquals(2, f.view.getStacks().size());
            IGridStack item = f.view.get(f.stored);
            assertTrue(item.getIngredient() instanceof ItemStack);
            assertTrue(f.view.getAllStacks().stream().anyMatch(stack -> stack.getIngredient() instanceof FluidStack));
            assertEquals(f.craftable, f.view.get(f.stored).getOtherId());
            assertTrue(f.view.get(f.craftable).isCraftable());
            assertEquals(3, f.view.getAllStacks().size());
            List<IGridStack> ordered = f.view.getStacks();
            f.view.apply(f.packet(GridResourceKind.ITEM, 1, 1, false, false,
                    new UnifiedGridEntry(1, Integer.MAX_VALUE, false, null, null, null, null)));
            assertSame(item, f.view.get(f.stored));
            assertSame(ordered, f.view.getStacks());
            assertEquals(Integer.MAX_VALUE, item.getQuantity());
            assertEquals(1, f.view.row(item).serial());
            f.view.cycleFilter();
            assertEquals(1, f.view.getStacks().size());
            assertTrue(f.view.getStacks().get(0).getIngredient() instanceof ItemStack);
            f.view.cycleFilter();
            assertEquals(1, f.view.getStacks().size());
            assertTrue(f.view.getStacks().get(0).getIngredient() instanceof FluidStack);
        }
    }

    @Test void jeiUsesAbsoluteUpdatesAndSaturatedVariantTotalsRecoverWithoutFullRebuild() {
        try (MockedStatic<IngredientTracker> tracker = mockStatic(IngredientTracker.class);
             MockedStatic<RSGridSearchCache> search = mockStatic(RSGridSearchCache.class)) {
            Fixture f = new Fixture();
            UUID variantId = UUID.randomUUID();
            ItemStack variant = new ItemStack(Items.DIAMOND);
            variant.getOrCreateTag().putString("variant", "second");
            f.view.apply(f.packet(GridResourceKind.ITEM, 1, 0, true, true,
                    f.item(1, Integer.MAX_VALUE),
                    new UnifiedGridEntry(2, 10, true, variantId, null, variant, null)));
            Map<ItemStackKey, Integer> storedItems = new HashMap<>();
            UnifiedGridJeiTracker.attach(f.view, storedItems);
            ItemStackKey key = new ItemStackKey(new ItemStack(Items.DIAMOND));
            assertEquals(Integer.MAX_VALUE, storedItems.get(key));
            tracker.clearInvocations();
            f.view.apply(f.packet(GridResourceKind.ITEM, 1, 1, false, false,
                    new UnifiedGridEntry(1, 20, false, null, null, null, null)));
            assertEquals(30, storedItems.get(key));
            f.view.apply(f.packet(GridResourceKind.ITEM, 1, 2, false, false,
                    new UnifiedGridEntry(1, 3, false, null, null, null, null)));
            assertEquals(13, storedItems.get(key));
            tracker.verifyNoInteractions();
            f.view.removed();
            tracker.verify(IngredientTracker::invalidate);
            f.view.apply(f.packet(GridResourceKind.ITEM, 1, 3, false, false,
                    new UnifiedGridEntry(1, 0, false, null, null, null, null)));
            assertEquals(13, storedItems.get(key), "子界面切换释放旧 tracker 的回调");
        }
    }

    @Test void openingCraftingSubscreenDoesNotStopUpdatesAndCraftOnlyTemplateStaysVisible() {
        try (MockedStatic<RSGridSearchCache> search = mockStatic(RSGridSearchCache.class)) {
            search.when(() -> RSGridSearchCache.beforeForceSort(any(), any())).thenReturn(true);
            Fixture f = new Fixture();
            f.view.apply(f.packet(GridResourceKind.ITEM, 1, 0, true, true, f.item(1, 5)));
            f.view.removed();
            f.view.apply(f.packet(GridResourceKind.ITEM, 1, 1, false, false,
                    new UnifiedGridEntry(1, 0, true, null, f.craftable, new ItemStack(Items.DIAMOND), null)));
            f.view.forceSort();
            assertEquals(1, f.view.getStacks().size());
            IGridStack craftOnly = f.view.getStacks().get(0);
            assertTrue(craftOnly.isCraftable());
            assertFalse(((ItemStack) craftOnly.getIngredient()).isEmpty());
            assertNull(f.view.get(f.stored));
            assertEquals(f.craftable, craftOnly.getId());
        }
    }

    private static final class Fixture {
        private final GridScreen screen = mock(GridScreen.class);
        private final IGrid grid = mock(IGrid.class);
        private final UnifiedGridView view;
        private final UUID session = UUID.randomUUID(), stored = UUID.randomUUID(), craftable = UUID.randomUUID();
        private Fixture() {
            when(screen.getGrid()).thenReturn(grid);
            when(screen.getSearchFieldText()).thenReturn("");
            when(grid.isGridActive()).thenReturn(true);
            when(grid.getFilters()).thenReturn(List.of());
            when(grid.getTabs()).thenReturn(List.of());
            when(grid.getTabSelected()).thenReturn(-1);
            when(grid.getSortingType()).thenReturn(IGrid.SORTING_TYPE_QUANTITY);
            view = new UnifiedGridView(screen);
        }
        private UnifiedGridEntry item(int serial, int amount) {
            return new UnifiedGridEntry(serial, amount, true, stored, craftable, new ItemStack(Items.DIAMOND), null);
        }
        private UnifiedGridUpdatePacket packet(GridResourceKind kind, int epoch, int sequence, boolean begin, boolean end,
                                               UnifiedGridEntry... updates) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                for (UnifiedGridEntry update : updates) update.write(buf, kind);
                byte[] payload = new byte[buf.readableBytes()];
                buf.readBytes(payload);
                return new UnifiedGridUpdatePacket(0, session, kind, epoch, sequence, begin, end, true, true, payload);
            } finally { buf.release(); }
        }
    }
}
