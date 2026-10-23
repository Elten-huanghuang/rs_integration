package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRSModule;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.blade;
import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.ingredient;
import static org.junit.jupiter.api.Assertions.*;

class SlashBladePreviewLinkTest extends BootstrapTest {
    @Test
    void legacyTreeLinksNestedBladesDespiteDifferentTemplateAndOutputNbt() {
        SlashBladeRSModule.INSTANCE.registerModType();
        ModType type = ModType.byId("slashblade");
        Ingredient targetInput = ingredient("ak:rage", 1000, 10);
        Ingredient rageInput = ingredient("test:base", 800, 8);
        ItemStack rage = blade("ak:rage", 1200, 12);
        rage.getOrCreateTag().putString("uuid", "actual-output");
        ItemStack base = blade("test:base", 1200, 12);
        base.getOrCreateTag().putString("uuid", "different-output");
        ResourceLocation targetId = new ResourceLocation("prinegorerouse:chronosn_blade");
        ResourceLocation rageId = new ResourceLocation("ak:rage");
        ResourceLocation baseId = new ResourceLocation("test:base");
        ItemStack target = blade(targetId.toString(), 8000, 10);
        PlanStep root = new PlanStep(targetId, target, 1, List.of(targetInput.getItems()[0]), List.of(), type);
        PlanStep middle = new PlanStep(rageId, rage, 1, List.of(rageInput.getItems()[0]), List.of(), type);
        PlanStep leaf = new PlanStep(baseId, base, 1, List.of(new ItemStack(Items.STICK)), List.of(), type);
        Map<ResourceLocation, Recipe<?>> recipes = Map.of(targetId, recipe(targetId, targetInput, target),
                rageId, recipe(rageId, rageInput, rage), baseId, recipe(baseId, Ingredient.of(Items.STICK), base));

        var linked = CraftPreviewSteps.linkSlashBladeInputs(List.of(leaf, middle, root), recipes::get, RegistryAccess.EMPTY);
        PlanTreeModel tree = PlanTreeModel.from(new PlanResponse(false, "test", target, linked, Map.of(),
                List.of("raw"), targetId.toString(), List.of(), 1, PlanOutcome.MISSING_INPUTS, List.of()));
        assertEquals(1, tree.root.children.size());
        assertEquals(rageId, tree.root.children.get(0).step.recipeId());
        assertEquals(baseId, tree.root.children.get(0).children.get(0).step.recipeId());
        assertEquals(3, tree.root.children.get(0).children.get(0).children.get(0).depth);
        assertFalse(ItemStack.isSameItemSameTags(root.inputs().get(0), rage), "原展示栈不应被修改");
    }

    @Test
    void weakOrDifferentBladeCannotCreateAFalseTreeEdge() {
        SlashBladeRSModule.INSTANCE.registerModType();
        ModType type = ModType.byId("slashblade");
        ResourceLocation targetId = new ResourceLocation("test:target");
        Ingredient input = ingredient("ak:rage", 1000, 10);
        PlanStep target = new PlanStep(targetId, blade("test:target", 0, 0), 1,
                List.of(input.getItems()[0]), List.of(), type);
        PlanStep wrong = new PlanStep(new ResourceLocation("ak:greedy"), blade("ak:greedy", 1000, 10), 1, List.of(), List.of(), type);
        PlanStep weak = new PlanStep(new ResourceLocation("ak:rage"), blade("ak:rage", 999, 10), 1, List.of(), List.of(), type);
        var linked = CraftPreviewSteps.linkSlashBladeInputs(List.of(wrong, weak, target),
                id -> id.equals(targetId) ? recipe(id, input, target.output()) : null, RegistryAccess.EMPTY);
        assertSame(target, linked.get(2));
    }

    @Test
    void raisedOutputThresholdLinksOnlyToTheStrongEnoughProducer() {
        SlashBladeRSModule.INSTANCE.registerModType();
        ModType type = ModType.byId("slashblade");
        ResourceLocation targetId = new ResourceLocation("test:upgraded");
        Ingredient input = ingredient("test:base", 800, 8);
        PlanStep target = new PlanStep(targetId, blade("test:upgraded", 1000, 10), 1,
                List.of(input.getItems()[0]), List.of(), type);
        PlanStep weak = new PlanStep(new ResourceLocation("test:weak_base"), blade("test:base", 800, 8),
                1, List.of(), List.of(), type);
        PlanStep strong = new PlanStep(new ResourceLocation("test:strong_base"), blade("test:base", 1000, 10),
                1, List.of(), List.of(), type);
        var linked = CraftPreviewSteps.linkSlashBladeInputs(List.of(weak, strong, target),
                id -> id.equals(targetId) ? recipe(id, input, blade("test:upgraded", 0, 0)) : null,
                RegistryAccess.EMPTY);
        assertTrue(ItemStack.isSameItemSameTags(strong.output(), linked.get(2).inputs().get(0)));
        assertFalse(ItemStack.isSameItemSameTags(weak.output(), linked.get(2).inputs().get(0)));
    }

    private static ShapedRecipe recipe(ResourceLocation id, Ingredient input, ItemStack output) {
        return new ShapedRecipe(id, "", CraftingBookCategory.MISC, 1, 1,
                NonNullList.of(Ingredient.EMPTY, input), output);
    }
}
