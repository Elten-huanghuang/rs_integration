package com.huanghuang.rsintegration.feature.sidepanel.model;

import com.huanghuang.rsintegration.feature.sidepanel.PanelStack;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelDataModelTest extends BootstrapTest {
    @Test
    void fullSyncRebuildsPanelsAndIndex() {
        PanelDataModel model = new PanelDataModel();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        model.updatePanels(List.of(
                stack(first, Items.STONE, 4),
                stack(second, Items.DIRT, 7)), 12);

        assertEquals(2, model.size());
        assertEquals(12, model.getTotalSlotCount());
        assertEquals(first, model.getByIndex(0).getId());
        assertEquals(second, model.getById(second).getId());
        assertTrue(model.isDirty());
    }

    @Test
    void deltaUpdatesExistingEntryAndBatchesDirtyState() {
        PanelDataModel model = new PanelDataModel();
        UUID id = UUID.randomUUID();
        model.updatePanels(
                List.of(stack(id, Items.STONE, 2)), 1);
        model.setDisplayList(model.getPanels());

        UUID animated = model.applyDelta(
                id, new ItemStack(Items.STONE, 9), 42L, true);

        assertEquals(id, animated);
        assertEquals(9, model.getById(id).getCount());
        assertTrue(model.isDeltaBatchDirty());
        assertTrue(model.isDirty());
        assertTrue(model.getDeltaBatch().contains(id));
    }

    @Test
    void deltaCanAddAndRemoveEntriesWithoutBreakingIndexes() {
        PanelDataModel model = new PanelDataModel();
        model.updatePanels(List.of(), 0);
        UUID id = UUID.randomUUID();

        assertEquals(id, model.applyDelta(
                id, new ItemStack(Items.IRON_INGOT, 3), 7L, false));
        assertEquals(1, model.size());
        assertEquals(1, model.getTotalSlotCount());
        assertNotNull(model.getById(id));

        model.applyDelta(
                id, new ItemStack(Items.IRON_INGOT, 0), 8L, false);
        assertEquals(0, model.size());
        assertNull(model.getById(id));
        assertFalse(model.getIdToIndex().containsKey(id));
    }

    @Test
    void removePanelReindexesFollowingEntries() {
        PanelDataModel model = new PanelDataModel();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        model.updatePanels(List.of(
                stack(first, Items.STONE, 1),
                stack(second, Items.DIRT, 1)), 2);

        model.removePanel(first);

        assertEquals(second, model.getByIndex(0).getId());
        assertEquals(0, model.getIdToIndex().get(second));
        assertEquals(1, model.getTotalSlotCount());
    }

    private static PanelStack stack(UUID id, Item item, int count) {
        return new PanelStack(id, new ItemStack(item, count), 1L, false);
    }
}
