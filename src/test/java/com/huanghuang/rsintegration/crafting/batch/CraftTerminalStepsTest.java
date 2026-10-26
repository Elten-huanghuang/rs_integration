package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.ProductionTarget;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftTerminalStepsTest extends BootstrapTest {

    @Test
    void expectedTerminalAddsThePrimaryProductionTarget() {
        ResolutionStep terminal = terminalStep(3);
        ItemStack output = new ItemStack(Items.DIAMOND);
        TerminalGraphComposer.TerminalOutput primary =
                TerminalGraphComposer.TerminalOutput.item(
                        MaterialKey.of(output), 2, OutputKind.PRIMARY);

        ResolutionStep expected = CraftTerminalSteps.expectedTerminalStep(
                terminal, List.of(primary));

        assertEquals(
                IntegrationResourceKey.item(output),
                expected.productionTarget().resource()
        );
        assertEquals(6L, expected.productionTarget().quantity());
    }

    @Test
    void expectedTerminalKeepsAnExplicitProductionTarget() {
        ProductionTarget explicit = ProductionTarget.of(
                new ItemStack(Items.GOLD_INGOT), 4);
        ResolutionStep terminal = new ResolutionStep(
                new ResourceLocation("test", "terminal"),
                ModType.GENERIC,
                new ResourceLocation("test", "type"),
                List.of(),
                List.of(),
                false,
                3,
                null,
                null,
                explicit,
                List.of()
        );
        TerminalGraphComposer.TerminalOutput primary =
                TerminalGraphComposer.TerminalOutput.item(
                        MaterialKey.of(new ItemStack(Items.DIAMOND)),
                        1,
                        OutputKind.PRIMARY
                );

        ResolutionStep expected = CraftTerminalSteps.expectedTerminalStep(
                terminal, List.of(primary));

        assertEquals(explicit, expected.productionTarget());
    }

    private static ResolutionStep terminalStep(int executions) {
        return new ResolutionStep(
                new ResourceLocation("test", "terminal"),
                ModType.GENERIC,
                new ResourceLocation("test", "type"),
                List.of(),
                List.of(),
                false,
                executions
        );
    }
}
