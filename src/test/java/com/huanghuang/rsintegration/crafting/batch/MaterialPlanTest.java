package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialPlanTest extends BootstrapTest {

    @Test
    void separatesGraphAndSupplementalEntriesWithoutLosingSlots() {
        MaterialPlan plan = new MaterialPlan(List.of(
                new MaterialPlan.Entry("ore", new IngredientSpec(
                        Ingredient.of(Items.IRON_INGOT), 2, DemandRole.CONSUMED),
                        MaterialPlan.Allocation.GRAPH, false, 0),
                new MaterialPlan.Entry("catalyst", new IngredientSpec(
                        Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST),
                        MaterialPlan.Allocation.SUPPLEMENTAL, true, 5)));

        assertEquals(List.of("ore"), plan.graphEntries().stream()
                .map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of("catalyst"), plan.supplementalEntries().stream()
                .map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of("catalyst"), plan.reusableEntries().stream()
                .map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of(2, 1), plan.legacySpecs().stream()
                .map(IngredientSpec::count).toList());
    }

    @Test
    void legacyProjectionUsesStableCompatibilityIds() {
        MaterialPlan plan = MaterialPlan.fromLegacy(List.of(
                new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1),
                new IngredientSpec(Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST)),
                List.of(IBatchDelegate.MaterialReservationScope.PER_OPERATION,
                        IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE));

        assertEquals(List.of("legacy:material:0", "legacy:material:1"),
                plan.entries().stream().map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of(false, true), plan.entries().stream()
                .map(MaterialPlan.Entry::reusable).toList());
        assertEquals(List.of(0, 1), plan.entries().stream()
                .map(MaterialPlan.Entry::inputSlot).toList());
    }

    @Test
    void rejectsDuplicateMaterialIdentity() {
        IngredientSpec spec = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1);
        assertThrows(IllegalArgumentException.class, () -> new MaterialPlan(List.of(
                new MaterialPlan.Entry("same", spec, MaterialPlan.Allocation.GRAPH, false, 0),
                new MaterialPlan.Entry("same", spec, MaterialPlan.Allocation.GRAPH, false, 1))));
    }

    @Test
    void legacyPartitionsRetainPlacementOrderAndReservationOwnership() {
        IngredientSpec aspect = new IngredientSpec(Ingredient.of(Items.BLAZE_POWDER), 1);
        IngredientSpec input = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 2);
        IngredientSpec tablet = new IngredientSpec(Ingredient.of(Items.BOOK), 1,
                DemandRole.CATALYST);

        MaterialPlan plan = MaterialPlan.fromLegacyPartitions(
                List.of(tablet, aspect, input),
                List.of(tablet, input),
                List.of(aspect),
                List.of(IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE,
                        IBatchDelegate.MaterialReservationScope.PER_OPERATION,
                        IBatchDelegate.MaterialReservationScope.PER_OPERATION));

        assertEquals(List.of(tablet, aspect, input), plan.legacySpecs());
        assertEquals(List.of(tablet, input), plan.legacyGraphSpecs());
        assertEquals(List.of(aspect), plan.legacySupplementalSpecs());
        assertEquals(List.of("legacy:material:0"), plan.reusableEntries().stream()
                .map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of(Items.BOOK, Items.BLAZE_POWDER, Items.IRON_INGOT),
                plan.mergeLegacyReservations(
                                List.of(new ItemStack(Items.BOOK), new ItemStack(Items.IRON_INGOT)),
                                List.of(new ItemStack(Items.BLAZE_POWDER)))
                        .stream().map(ItemStack::getItem).toList());
    }

    @Test
    void legacyPartitionsRejectUnmappedReservationEntries() {
        IngredientSpec ordered = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1);
        IngredientSpec extra = new IngredientSpec(Ingredient.of(Items.COAL), 1);

        assertThrows(IllegalArgumentException.class, () -> MaterialPlan.fromLegacyPartitions(
                List.of(ordered), List.of(ordered, extra), List.of(), List.of()));
    }

    @Test
    void legacyDelegateGetsStructuredPartitionWithoutChangingItsMethods() {
        IngredientSpec aspect = new IngredientSpec(Ingredient.of(Items.BLAZE_POWDER), 1);
        IngredientSpec input = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1);
        IBatchDelegate delegate = new LegacySplitDelegate(
                List.of(aspect, input), List.of(input), List.of(aspect));

        MaterialPlan plan = delegate.materialPlan();

        assertEquals(List.of(aspect, input), plan.legacySpecs());
        assertEquals(List.of(input), plan.legacyGraphSpecs());
        assertEquals(List.of(aspect), plan.legacySupplementalSpecs());
    }

    private record LegacySplitDelegate(List<IngredientSpec> required, List<IngredientSpec> graph,
                                       List<IngredientSpec> supplemental) implements IBatchDelegate {
        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dim, BlockPos pos) {
            return true;
        }

        @Override
        public boolean tryStartSingleCraft(ServerPlayer player) {
            return false;
        }

        @Override
        public List<IngredientSpec> getRequiredMaterials() {
            return required;
        }

        @Override
        public List<IngredientSpec> getGraphSpecs() {
            return graph;
        }

        @Override
        public List<IngredientSpec> getSupplementalSpecs() {
            return supplemental;
        }

        @Override
        public boolean isCraftComplete(ServerLevel level) {
            return false;
        }

        @Override
        public ItemStack collectResult(ServerPlayer player) {
            return ItemStack.EMPTY;
        }

        @Override
        public void onBatchFailed(ServerPlayer player, String reason) {
        }

        @Override
        public void onBatchFinished(ServerPlayer player) {
        }

        @Override
        public BlockPos getMachinePos() {
            return BlockPos.ZERO;
        }
    }
}
