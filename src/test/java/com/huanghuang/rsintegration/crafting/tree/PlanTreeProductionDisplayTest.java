package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanTreeProductionDisplayTest extends BootstrapTest {
    @Test
    void producedNodeDisplaysRemainingProductionInsteadOfGrossDemand() {
        ResourceLocation targetRecipe = new ResourceLocation("test", "target");
        ResourceLocation ingotRecipe = new ResourceLocation("test", "iron_ingot");
        ItemStack target = new ItemStack(Items.DIAMOND);
        ItemStack ingot = new ItemStack(Items.IRON_INGOT);

        PlanStep producer = new PlanStep(ingotRecipe, ingot, 7,
                List.of(new ItemStack(Items.IRON_ORE)));
        PlanStep terminal = new PlanStep(targetRecipe, target, 84, List.of(ingot));
        Map<IngredientKey, PlanResponse.Availability> materials = Map.of(
                IngredientKey.of(ingot), new PlanResponse.Availability(84, 77, 0));

        PlanResponse plan = new PlanResponse(true, "", target,
                List.of(producer, terminal), materials, List.of(),
                targetRecipe.toString(), null, null, 0, 0, 0, List.of(), 84);
        PlanTreeNode produced = PlanTreeModel.from(plan).findByKey(IngredientKey.of(ingot));

        assertEquals(84, produced.amount);
        assertEquals(7, produced.step.batches());
        assertEquals(7, produced.productionAmount);
        assertEquals(7, produced.displayAmount());
    }
}
