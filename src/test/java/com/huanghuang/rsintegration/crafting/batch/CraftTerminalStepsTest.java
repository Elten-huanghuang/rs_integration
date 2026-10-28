package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.ProductionTarget;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void stockedSmithingInputsStillComposeATerminalOnlyGraph() {
        // 材料齐全时解析器只给出根需求、没有中间节点；旧实现要求 interSteps 非空才追加
        // 锻造终端，导致“材料齐全的锻造”直接落到“不支持的机器类型”兜底上。
        CraftPlanGraph inputGraph = new CraftPlanGraph(1, List.of(), List.of(),
                List.of(
                        rootDemand(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                        rootDemand(Items.DIAMOND_SWORD),
                        rootDemand(Items.NETHERITE_INGOT)),
                List.of(), List.of());

        List<ResolutionStep> flatSteps = ExecutionEquivalence.projectFlatSteps(inputGraph);

        assertTrue(flatSteps.isEmpty(), "库存全部满足时不应产生中间步骤");
        assertTrue(CraftTerminalSteps.canComposeEquivalentTerminalGraph(
                inputGraph, flatSteps, List.of()));

        ResolutionStep terminal = CraftTerminalSteps.smithingTerminalStep(
                new ResourceLocation("minecraft", "netherite_sword_smithing"), 1);
        assertEquals(ModType.GENERIC, terminal.modType());
        assertEquals(new ResourceLocation("minecraft", "smithing"), terminal.recipeTypeId());
        assertEquals(1, terminal.executions());
    }

    private static RootDemand rootDemand(Item item) {
        ItemStack stack = new ItemStack(item);
        MaterialKey key = MaterialKey.of(stack);
        return new RootDemand(Ingredient.of(item), 1, 0, stack,
                List.of(new RootAllocation(new MaterialSource.InitialPool(key), key, 1)));
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
