package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PanelDataModelTest extends BootstrapTest {
    private final PanelDataModel model = new PanelDataModel();
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private final PanelStack first = new PanelStack(a, new ItemStack(Items.STONE, 3), 30, true);
    private final PanelStack second = new PanelStack(b, new ItemStack(Items.DIRT, 6), 20, false);

    @Test void dragAcrossSortedSlotsKeepsIdsAndRestoresOnlyUnconfirmedItems() {
        model.fullSync(List.of(first, second), 10, true, "rs");
        var view = new DisplayListManager();
        var sorted = view.project(model, "", 0, 1, false, true);
        assertEquals(List.of(second, first), sorted);
        model.predictDistribution(7, sorted.stream().map(PanelStack::getId).toList(), 100);
        var filtered = view.project(model, "", 2, 1, false, true);
        assertEquals(List.of(a), filtered.stream().map(PanelStack::getId).toList());
        assertEquals(2, filtered.get(0).getCount());
        var delta = model.applyDelta(UUID.randomUUID(), new ItemStack(Items.STONE, 7), 200, true, 2, 200);
        assertEquals(new PanelDataModel.Change(a, 5), delta);
        assertEquals(3, first.getCount());
        assertTrue(model.tick(20, 301, 200));
        assertFalse(model.tick(40, 302, 200));
        assertEquals(6, model.getById(b).getCount());
        assertEquals(7, model.getById(a).getCount());
    }

    @Test void projectionsAreReadOnlyAndKnownIdsWinOverMatchingResourceKeys() {
        model.fullSync(List.of(first, new PanelStack(b, first.getStack(), 0, false)), 2, true, "rs");
        var list = model.entries();
        assertThrows(UnsupportedOperationException.class, list::clear);
        list.get(0).getStack().setCount(99);
        model.applyDelta(b, new ItemStack(Items.STONE, 8), 2, true, 2, 3);
        assertEquals(3, model.getById(a).getCount());
        assertEquals(8, model.getById(b).getCount());
        model.applyDelta(a, ItemStack.EMPTY, 3, false, 2, 4);
        assertNull(model.getById(a));
        assertEquals(1, model.getTotalSlotCount());
        assertEquals(b, model.entries().get(0).getId());
    }
}
