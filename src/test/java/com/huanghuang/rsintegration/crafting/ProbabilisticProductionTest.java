package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProbabilisticProductionTest extends BootstrapTest {
    @Test
    void inkTokenProgressCountsMillibucketsNotTokenItems() {
        ItemStack template = InkFluidSupport.token(
                InkFluidTestFixtures.tokenItem(),
                new FluidStack(Fluids.WATER, 250)
        );
        ProbabilisticProduction progress =
                new ProbabilisticProduction(
                        ProductionTarget.of(
                                template.copyWithCount(1), 750L),
                        8
                );

        progress.started();
        progress.settle(List.of(
                template.copyWithCount(250),
                template.copyWithCount(250)
        ));

        assertEquals(1, progress.attempts());
        assertEquals(500L, progress.produced());
        assertEquals(250L, progress.remaining());
    }

    @Test
    void fluidReportedOutputCanAdvanceProbabilisticProgress() {
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        IntegrationResourceKey resource =
                IntegrationResourceKey.fluid(water);
        ProbabilisticProduction progress =
                new ProbabilisticProduction(
                        ProductionTarget.of(resource, 750L),
                        8
                );

        progress.started();
        progress.settle(
                List.of(),
                List.of(new CollectedOutput(
                        resource,
                        500L,
                        OutputKind.PRIMARY,
                        new ResourceLocation("test", "fluid_output")
                ))
        );

        assertEquals(500L, progress.produced());
        assertEquals(250L, progress.remaining());
    }

    @Test
    void observedButNotYetDeliveredFluidCanAdvanceAttemptProgress() {
        IntegrationResourceKey water =
                IntegrationResourceKey.fluid(
                        new FluidStack(Fluids.WATER, 1));
        ProbabilisticProduction progress =
                new ProbabilisticProduction(
                        ProductionTarget.of(water, 750L),
                        8
                );

        progress.started();
        progress.settle(
                List.of(),
                List.of(),
                List.of(new CollectedOutput(
                        water,
                        250L,
                        OutputKind.PRIMARY,
                        new ResourceLocation(
                                "test", "observed_water")
                ))
        );

        assertEquals(250L, progress.produced());
        assertEquals(500L, progress.remaining());
    }
}
