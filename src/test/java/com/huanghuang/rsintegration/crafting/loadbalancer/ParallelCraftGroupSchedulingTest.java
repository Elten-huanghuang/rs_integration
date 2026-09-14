package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelCraftGroupSchedulingTest extends BootstrapTest {

    @Test
    void waitingDiagnosticsRequireNoProgressAndAreRateLimited() {
        assertFalse(ParallelCraftGroup.shouldReportWait(99, 0, -1));
        assertTrue(ParallelCraftGroup.shouldReportWait(100, 0, -1));
        assertFalse(ParallelCraftGroup.shouldReportWait(699, 0, 100));
        assertTrue(ParallelCraftGroup.shouldReportWait(700, 0, 100));
        assertFalse(ParallelCraftGroup.shouldReportWait(700, 650, 100));
        assertFalse(ParallelCraftGroup.shouldReportWait(700, -1, -1));
    }

    @Test
    void rejectedEarlierCandidatesDoNotConsumeTheSmallOrderWorkerLimit() {
        int ready = 0;
        int inspected = 0;
        for (boolean valid : List.of(false, false, true, true, true)) {
            if (!ParallelCraftGroup.needsMoreWorkers(ready, 2, 2)) break;
            inspected++;
            if (valid) ready++;
        }
        assertEquals(2, ready);
        assertEquals(4, inspected);
        assertFalse(ParallelCraftGroup.needsMoreWorkers(1, 8, 1));
        assertFalse(ParallelCraftGroup.needsMoreWorkers(0, 0, 100));
    }

    @Test
    void supplementalContainerIsRepeatedAndMergedIntoEveryOperation() {
        IngredientSpec bowl = new IngredientSpec(Ingredient.of(Items.BOWL), 1);
        assertEquals(6, ParallelCraftGroup.repeatSpecs(List.of(bowl), 6).size());

        List<ItemStack> graph = new ArrayList<>();
        List<ItemStack> supplemental = new ArrayList<>();
        for (int operation = 0; operation < 6; operation++) {
            graph.add(new ItemStack(Items.MILK_BUCKET));
            graph.add(new ItemStack(Items.SUGAR));
            graph.add(new ItemStack(Items.COCOA_BEANS));
            graph.add(new ItemStack(Items.COCOA_BEANS));
            supplemental.add(new ItemStack(Items.BOWL));
        }

        List<ItemStack> merged = ParallelCraftGroup.mergeOperationSlices(
                graph, supplemental, 6, 4, 1, (inputs, containers) -> {
                    List<ItemStack> operation = new ArrayList<>(inputs);
                    operation.addAll(containers);
                    return operation;
                });

        assertEquals(30, merged.size());
        for (int operation = 0; operation < 6; operation++) {
            assertTrue(merged.get(operation * 5 + 4).is(Items.BOWL));
        }
    }

    @Test
    void repeatedSpecsKeepOperationMajorOrderWithoutMaterializingAnArray() {
        IngredientSpec bowl = new IngredientSpec(Ingredient.of(Items.BOWL), 1);
        IngredientSpec sugar = new IngredientSpec(Ingredient.of(Items.SUGAR), 1);

        List<IngredientSpec> repeated = ParallelCraftGroup.repeatSpecs(List.of(bowl, sugar), 3);

        assertEquals(6, repeated.size());
        assertEquals(List.of(bowl, sugar, bowl, sugar, bowl, sugar), repeated);
        assertEquals(List.of(bowl, sugar, bowl), repeated.subList(0, 3));
        assertThrows(UnsupportedOperationException.class, () -> repeated.set(0, sugar));
    }

    @Test
    void repeatedSpecsFailClearlyWhenTheLegacyListContractCannotRepresentTheSize() {
        IngredientSpec bowl = new IngredientSpec(Ingredient.of(Items.BOWL), 1);
        IngredientSpec sugar = new IngredientSpec(Ingredient.of(Items.SUGAR), 1);

        assertThrows(ArithmeticException.class,
                () -> ParallelCraftGroup.repeatSpecs(List.of(bowl, sugar), Integer.MAX_VALUE));
    }

    @Test
    void exclusiveDelegateCanDrainOperationsThroughOneSerialWorker() {
        assertTrue(ParallelCraftGroup.operationGroupAcceptsChild(true, 1));
    }

    @Test
    void exclusiveDelegateCannotJoinMultiWorkerGroup() {
        assertFalse(ParallelCraftGroup.operationGroupAcceptsChild(true, 2));
    }

    @Test
    void concurrencySafeDelegateCanJoinMultiWorkerGroup() {
        assertTrue(ParallelCraftGroup.operationGroupAcceptsChild(false, 2));
    }

    @Test
    void privateLedgerCapabilityIsReadFromChildDelegate() {
        IBatchDelegate privateLedger = (IBatchDelegate) Proxy.newProxyInstance(
                IBatchDelegate.class.getClassLoader(),
                new Class<?>[]{IBatchDelegate.class},
                (proxy, method, args) -> method.getName()
                        .equals("requiresPrivateLedgerGraphDispatch"));

        assertTrue(ParallelCraftGroup.requiresPrivateLedgerGraphDispatch(privateLedger));
        assertFalse(ParallelCraftGroup.requiresPrivateLedgerGraphDispatch(null));
    }

    @Test
    void preparedButUnstartedWorkerReleasesOnlyPreparationResources() {
        TrackingDelegate delegate = new TrackingDelegate();

        ParallelCraftGroup.cleanupPreparedDelegate(delegate, false, null, "start rejected");

        assertEquals(1, delegate.preparationReleases);
        assertEquals(0, delegate.failureCleanups);
    }

    @Test
    void startedWorkerUsesFullFailureCleanup() {
        TrackingDelegate delegate = new TrackingDelegate();

        ParallelCraftGroup.cleanupPreparedDelegate(delegate, true, null, "start rejected");

        assertEquals(0, delegate.preparationReleases);
        assertEquals(1, delegate.failureCleanups);
    }

    private static final class TrackingDelegate implements IBatchDelegate {
        int preparationReleases;
        int failureCleanups;

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dim, BlockPos pos) {
            return true;
        }

        @Override public boolean tryStartSingleCraft(ServerPlayer player) { return false; }
        @Override public boolean isCraftComplete(ServerLevel level) { return false; }
        @Override public ItemStack collectResult(ServerPlayer player) { return ItemStack.EMPTY; }
        @Override public void releasePreparationResources() { preparationReleases++; }
        @Override public void onBatchFailed(ServerPlayer player, String reason) { failureCleanups++; }
        @Override public void onBatchFinished(ServerPlayer player) {}
        @Override public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }
}
