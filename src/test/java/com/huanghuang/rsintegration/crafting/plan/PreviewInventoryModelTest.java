package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PreviewInventoryModelTest extends BootstrapTest {
    static final StorageReference REFERENCE = new StorageReference(new StorageBackendId("test"), "preview");

    @Test
    void pipeChurnChangesOnlyTheAffectedRowAndRecoversWithoutPlanning() {
        var model = new PreviewInventoryModel(plan(List.of(initial(new ItemStack(Items.IRON_INGOT), 100)), List.of(), Map.of()));
        IngredientKey iron = IngredientKey.of(new ItemStack(Items.IRON_INGOT));
        model.update(iron, 120);
        assertEquals(PreviewInventoryModel.Status.READY, model.status());
        assertEquals(1, model.drain(true).size());
        model.update(IngredientKey.of(new ItemStack(Items.DIAMOND)), Long.MAX_VALUE);
        assertTrue(model.drain(false).isEmpty());
        model.update(iron, 90);
        assertEquals(PreviewInventoryModel.Status.MISSING, model.status());
        assertEquals(10, model.drain(false).get(0).missing());
        model.update(iron, 130);
        assertEquals(PreviewInventoryModel.Status.READY, model.status());
        assertEquals(0, model.drain(false).get(0).missing());
        model.update(iron, 130);
        assertTrue(model.drain(false).isEmpty());
    }

    @Test
    void sharedInitialMaterialIsCountedOnceAcrossAllConsumers() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        var model = new PreviewInventoryModel(plan(List.of(initial(iron, 60), initial(iron, 40)), List.of(), Map.of()));
        model.update(IngredientKey.of(iron), 70);
        assertEquals(30, model.drain(true).get(0).missing());
    }

    @Test
    void producedIntermediateNeverBecomesAnExternalShortage() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack block = new ItemStack(Items.IRON_BLOCK);
        var produced = new PlanGraphView.EdgeView(1, 0, PlanGraphView.SourceView.fromProducer(0, 0), block, 50_000_000);
        var model = new PreviewInventoryModel(plan(List.of(initial(iron, 100), produced), List.of(), Map.of()));
        model.update(IngredientKey.of(iron), 100);
        assertEquals(PreviewInventoryModel.Status.READY, model.status());
        assertTrue(model.drain(true).stream().allMatch(row -> row.missing() == 0));
    }

    @Test
    void exactNbtSelectionsDoNotBorrowEachOthersStock() {
        ItemStack plain = new ItemStack(Items.BOOK);
        ItemStack enchanted = new ItemStack(Items.BOOK);
        enchanted.getOrCreateTag().putString("variant", "specific");
        var model = new PreviewInventoryModel(plan(List.of(initial(plain, 5), initial(enchanted, 5)), List.of(), Map.of()));
        model.update(IngredientKey.of(enchanted), 10);
        var rows = model.drain(true);
        assertEquals(5, rows.get(0).missing());
        assertEquals(0, rows.get(1).missing());
        assertEquals(PreviewInventoryModel.Status.MISSING, model.status());
    }

    @Test
    void fillingAnUnresolvedDisplayHintNeverCertifiesAnIncompleteRoute() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        var unresolved = new PlanGraphView.UnresolvedView(0, 0, iron, 10);
        var model = new PreviewInventoryModel(plan(List.of(initial(iron, 5)), List.of(unresolved), Map.of()));
        model.update(IngredientKey.of(iron), 10);
        assertEquals(5, model.drain(true).get(0).missing());
        model.update(IngredientKey.of(iron), 15);
        assertEquals(PreviewInventoryModel.Status.REPLAN, model.status());
    }

    @Test
    void longAmountsAndSharedDemandDoNotOverflow() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        var model = new PreviewInventoryModel(plan(List.of(initial(iron, Long.MAX_VALUE - 1), initial(iron, 5)), List.of(), Map.of()));
        model.update(IngredientKey.of(iron), Long.MAX_VALUE - 10);
        assertEquals(10, model.drain(true).get(0).missing());
        model.update(IngredientKey.of(iron), Long.MAX_VALUE);
        assertEquals(PreviewInventoryModel.Status.REPLAN, model.status(), "总需求超出 long 时不能凭饱和值宣称足量");
    }

    static PlanGraphView.EdgeView initial(ItemStack stack, long count) {
        return new PlanGraphView.EdgeView(0, 0, PlanGraphView.SourceView.fromInitial(), stack, count);
    }

    static PlanResponse plan(List<PlanGraphView.EdgeView> edges, List<PlanGraphView.UnresolvedView> unresolved,
                             Map<IngredientKey, Long> stock) {
        Map<IngredientKey, PlanResponse.Availability> rows = new LinkedHashMap<>();
        for (var edge : edges) {
            IngredientKey key = IngredientKey.of(edge.material());
            rows.put(key, new PlanResponse.Availability(edge.quantity(), stock.getOrDefault(key, 0L), 0));
        }
        for (var missing : unresolved) {
            IngredientKey key = IngredientKey.of(missing.display());
            rows.put(key, new PlanResponse.Availability(missing.quantity(), stock.getOrDefault(key, 0L), missing.quantity()));
        }
        var graph = new PlanGraphView(1, List.of(), edges, List.of(), unresolved, List.of());
        return new PlanResponse(unresolved.isEmpty(), "target", new ItemStack(Items.DIAMOND), List.of(), rows,
                List.of(), "test:preview", null, "minecraft:overworld", 0, 0, 0, List.of(), 1,
                null, null, null, 0L, false, false, false, null, Set.of(), Map.of(), null,
                graph, false, List.of(), Map.of(), REFERENCE, List.of());
    }
}
