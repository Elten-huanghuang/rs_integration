package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanMaterialBillTest extends BootstrapTest {
    private static final ResourceLocation TARGET_RECIPE =
            new ResourceLocation("test", "target");

    @Test
    void keepsNetFeasibilitySeparateFromGrossTreeDemand() {
        ItemStack target = new ItemStack(Items.DIAMOND);
        PlanStep targetStep = new PlanStep(TARGET_RECIPE, target, 2,
                List.of(new ItemStack(Items.IRON_INGOT)));

        PlanMaterialBill.Result result = PlanMaterialBill.summarize(
                mapOf(Items.IRON_INGOT, 1, Items.DIAMOND, -2),
                Map.of(Items.IRON_INGOT, Ingredient.of(Items.IRON_INGOT)),
                Map.of(Items.IRON_INGOT, 1),
                Map.of(new StackKey(Items.IRON_INGOT, null), 1),
                target, List.of(targetStep), 2, null, false);

        assertTrue(result.feasible(), "one net ingot is available for execution");
        assertEquals(new PlanResponse.Availability(2, 1),
                result.materials().get(IngredientKey.of(new ItemStack(Items.IRON_INGOT))));
    }

    @Test
    void reusableCatalystIsNotMultipliedByBatchCount() {
        ItemStack target = new ItemStack(Items.EMERALD);
        PlanStep targetStep = new PlanStep(TARGET_RECIPE, target, 65,
                List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_INGOT)),
                List.of(), null, 0, false, 0, 0, List.of(),
                List.of(DemandRole.CATALYST, DemandRole.CONSUMED));

        PlanMaterialBill.Result result = PlanMaterialBill.summarize(
                mapOf(Items.DIAMOND, 1, Items.IRON_INGOT, 65),
                Map.of(Items.DIAMOND, Ingredient.of(Items.DIAMOND),
                        Items.IRON_INGOT, Ingredient.of(Items.IRON_INGOT)),
                Map.of(Items.DIAMOND, 1, Items.IRON_INGOT, 65),
                Map.of(new StackKey(Items.DIAMOND, null), 1,
                        new StackKey(Items.IRON_INGOT, null), 65),
                target, List.of(targetStep), 65, null, false);

        assertEquals(1, targetStep.totalInputCount(0, 65));
        assertEquals(65, targetStep.totalInputCount(1, 65));
        assertEquals(new PlanResponse.Availability(1, 1),
                result.materials().get(IngredientKey.of(new ItemStack(Items.DIAMOND))));
        assertEquals(new PlanResponse.Availability(65, 65),
                result.materials().get(IngredientKey.of(new ItemStack(Items.IRON_INGOT))));
    }

    @Test
    void selfAmplifyingTargetDisplaysOneSeedForRepeatedExecutions() {
        ItemStack templateOutput = new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2);
        PlanStep targetStep = new PlanStep(TARGET_RECIPE, templateOutput, 6,
                List.of(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                        new ItemStack(Items.DIAMOND, 7), new ItemStack(Items.NETHERRACK)));

        assertEquals(1, targetStep.totalInputCount(0, 6));
        assertEquals(42, targetStep.totalInputCount(1, 6));
        assertEquals(6, targetStep.totalInputCount(2, 6));
    }

    @Test
    void mergesMultiOptionDemandAndOffsetsProducedTagMembers() {
        Ingredient logs = Ingredient.of(Items.OAK_LOG, Items.BIRCH_LOG);
        PlanMaterialBill.Result result = PlanMaterialBill.summarize(
                mapOf(Items.OAK_LOG, 2, Items.BIRCH_LOG, -1),
                Map.of(Items.OAK_LOG, logs),
                Map.of(Items.OAK_LOG, 0, Items.BIRCH_LOG, 1),
                Map.of(), new ItemStack(Items.DIAMOND), List.of(), 1, null, false);

        assertTrue(result.feasible());
        assertEquals(new PlanResponse.Availability(1, 1),
                result.materials().get(IngredientKey.of(new ItemStack(Items.OAK_LOG))));
    }

    @Test
    void strictNbtDemandCountsOnlyMatchingStoredStacks() {
        ItemStack charged = new ItemStack(Items.DIAMOND);
        charged.getOrCreateTag().putInt("charge", 80_000);
        ItemStack other = new ItemStack(Items.DIAMOND);
        other.getOrCreateTag().putInt("charge", 1);

        PlanMaterialBill.Result result = PlanMaterialBill.summarize(
                Map.of(Items.DIAMOND, 2),
                Map.of(Items.DIAMOND, StrictNBTIngredient.of(charged)),
                Map.of(Items.DIAMOND, 10),
                Map.of(
                        new StackKey(Items.DIAMOND, charged.getTag().toString()), 1,
                        new StackKey(Items.DIAMOND, other.getTag().toString()), 9),
                new ItemStack(Items.EMERALD), List.of(), 1, null, false);

        IngredientKey chargedKey = IngredientKey.of(charged);
        assertFalse(result.feasible());
        assertEquals(new PlanResponse.Availability(2, 1), result.materials().get(chargedKey));
    }

    @Test
    void taggedVanillaIngredientCountsAllDamageAndNbtVariants() {
        ItemStack template = new ItemStack(Items.IRON_HELMET);
        template.setDamageValue(10);
        ItemStack other = new ItemStack(Items.IRON_HELMET);
        other.setDamageValue(80);
        other.getOrCreateTag().putString("modifier", "other");

        PlanMaterialBill.Result result = PlanMaterialBill.summarize(
                Map.of(Items.IRON_HELMET, 2),
                Map.of(Items.IRON_HELMET, Ingredient.of(template)),
                Map.of(Items.IRON_HELMET, 2),
                Map.of(
                        new StackKey(Items.IRON_HELMET, template.getTag().toString()), 1,
                        new StackKey(Items.IRON_HELMET, other.getTag().toString()), 1),
                new ItemStack(Items.EMERALD), List.of(), 1, null, false);

        assertTrue(result.feasible());
        assertEquals(new PlanResponse.Availability(2, 2),
                result.materials().get(IngredientKey.of(new ItemStack(Items.IRON_HELMET))));
    }

    @Test
    void grossTreeDemandDoesNotTurnTaggedDisplayStackIntoStrictMaterial() {
        ItemStack displayTemplate = new ItemStack(Items.IRON_HELMET);
        displayTemplate.setDamageValue(10);
        ItemStack stored = new ItemStack(Items.IRON_HELMET);
        stored.setDamageValue(80);
        stored.getOrCreateTag().putString("modifier", "other");
        PlanStep targetStep = new PlanStep(TARGET_RECIPE, new ItemStack(Items.EMERALD), 1,
                List.of(displayTemplate));

        PlanMaterialBill.Result result = PlanMaterialBill.summarize(
                Map.of(Items.IRON_HELMET, 1),
                Map.of(Items.IRON_HELMET, Ingredient.of(displayTemplate)),
                Map.of(Items.IRON_HELMET, 1),
                Map.of(new StackKey(Items.IRON_HELMET, stored.getTag().toString()), 1),
                new ItemStack(Items.EMERALD), List.of(targetStep), 1, null, false);

        IngredientKey plainKey = IngredientKey.of(new ItemStack(Items.IRON_HELMET));
        assertTrue(result.feasible());
        assertEquals(new PlanResponse.Availability(1, 1), result.materials().get(plainKey));
        assertEquals(1, result.materials().size());
    }

    @Test
    void reportsNonTargetOverproductionAsLeftovers() {
        PlanMaterialBill.Result result = PlanMaterialBill.summarize(
                mapOf(Items.DIAMOND, -1, Items.GOLD_INGOT, -3),
                Map.of(), Map.of(), Map.of(), new ItemStack(Items.DIAMOND),
                List.of(), 1, null, false);

        assertEquals(Map.of(IngredientKey.of(new ItemStack(Items.GOLD_INGOT)), 3),
                result.leftovers());
    }

    private static Map<Item, Integer> mapOf(Item firstItem, int firstCount,
                                             Item secondItem, int secondCount) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        result.put(firstItem, firstCount);
        result.put(secondItem, secondCount);
        return result;
    }
}
