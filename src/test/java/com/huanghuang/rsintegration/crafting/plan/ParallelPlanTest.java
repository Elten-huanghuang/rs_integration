package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParallelPlanTest {

    @Test
    void shareSummaryUsesStableTwoPlusOneOrder() {
        ParallelPlan plan = new ParallelPlan(List.of(
                new ParallelPlan.WorkerShare(BlockPos.ZERO, "vanilla_furnace", 2, 2, 0),
                new ParallelPlan.WorkerShare(new BlockPos(1, 0, 0), "vanilla_furnace", 1, 1, 0)
        ), 3, 3, 0, null);

        assertEquals("2+1", plan.shareSummary());
        assertEquals(2, plan.workerCount());
        assertEquals(3, plan.totalOperations());
    }

    @Test
    void fuelAndCatalystTotalsComeFromTheWorkerSnapshot() {
        ParallelPlan plan = new ParallelPlan(List.of(
                new ParallelPlan.WorkerShare(BlockPos.ZERO, "vanilla_furnace", 2, 2, 0),
                new ParallelPlan.WorkerShare(new BlockPos(1, 0, 0), "vanilla_furnace", 1, 1, 0)
        ), 3, 3, 0, null);
        PlanStep step = new PlanStep(
                new ResourceLocation("minecraft", "iron_ingot"),
                new ItemStack(Items.IRON_INGOT),
                3,
                List.of(new ItemStack(Items.COAL)),
                List.of(),
                null,
                0,
                false,
                0,
                0,
                List.of(),
                List.of(DemandRole.FUEL),
                plan
        );

        assertEquals(3L, step.totalInputCount(0, 3));
    }

    @Test
    void catalystCountMultipliesByWorkerCountInsteadOfBatches() {
        ParallelPlan plan = new ParallelPlan(List.of(
                new ParallelPlan.WorkerShare(BlockPos.ZERO, "vanilla_furnace", 2, 2, 0),
                new ParallelPlan.WorkerShare(new BlockPos(1, 0, 0), "vanilla_furnace", 1, 1, 0)
        ), 3, 3, 1, null);
        PlanStep step = new PlanStep(
                new ResourceLocation("minecraft", "iron_ingot"),
                new ItemStack(Items.IRON_INGOT),
                3,
                List.of(new ItemStack(Items.COAL)),
                List.of(),
                null,
                0,
                false,
                0,
                0,
                List.of(),
                List.of(DemandRole.CATALYST),
                plan
        );

        assertEquals(2L, step.totalInputCount(0, 3));
    }

    @Test
    void durabilityCountMultipliesByWorkerCount() {
        ParallelPlan plan = new ParallelPlan(List.of(
                new ParallelPlan.WorkerShare(BlockPos.ZERO, "farmersdelight:cutting_board", 2, 0, 0, 1),
                new ParallelPlan.WorkerShare(new BlockPos(1, 0, 0), "farmersdelight:cutting_board", 1, 0, 0, 1)
        ), 3, 0L, 0L, 2L, null);
        PlanStep step = new PlanStep(
                new ResourceLocation("farmersdelight", "cutting_board"),
                new ItemStack(Items.IRON_SWORD),
                3,
                List.of(new ItemStack(Items.IRON_SWORD)),
                List.of(),
                null,
                0,
                false,
                0,
                0,
                List.of(),
                List.of(DemandRole.DURABILITY),
                plan
        );

        assertEquals(2L, step.totalInputCount(0, 3));
        assertEquals(2L, plan.reusableItems());
    }
}
