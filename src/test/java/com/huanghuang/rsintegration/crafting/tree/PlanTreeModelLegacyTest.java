package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.mods.embers.EmbersMachinesRSModule;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTreeModelLegacyTest extends BootstrapTest {

    @Test
    void sharedProducerExpandsItsActualExecutionsOnlyOnce() {
        PlanStep powder = new PlanStep(new ResourceLocation("test", "powder"),
                new ItemStack(Items.IRON_NUGGET, 9), 1, List.of(new ItemStack(Items.IRON_BLOCK)));
        PlanStep left = new PlanStep(new ResourceLocation("test", "left"),
                new ItemStack(Items.PAPER), 1, List.of(new ItemStack(Items.IRON_NUGGET, 4)));
        PlanStep right = new PlanStep(new ResourceLocation("test", "right"),
                new ItemStack(Items.EMERALD), 1, List.of(new ItemStack(Items.IRON_NUGGET, 4)));
        PlanStep target = new PlanStep(new ResourceLocation("test", "target"),
                new ItemStack(Items.DIAMOND), 1,
                List.of(new ItemStack(Items.PAPER), new ItemStack(Items.EMERALD)));
        PlanResponse plan = new PlanResponse(true, "", target.output(),
                List.of(powder, left, right, target), Map.of(), List.of(), target.recipeId().toString());

        PlanTreeModel tree = PlanTreeModel.from(plan);
        Map<IngredientKey, Integer> demand = PlanTreeModel.grossDemandByKey(tree);

        assertEquals(8, demand.get(IngredientKey.of(new ItemStack(Items.IRON_NUGGET))));
        assertEquals(1, demand.get(IngredientKey.of(new ItemStack(Items.IRON_BLOCK))));
        assertEquals(4, tree.root.children.get(0).children.get(0).amount);
        assertEquals(4, tree.root.children.get(1).children.get(0).amount);
    }

    @Test
    void rootKeepsTargetStepAndAlternativesWithoutGraphNodes() {
        ResourceLocation selected = new ResourceLocation("crafttweaker", "refined_mod.moon_slash");
        ResourceLocation alternative = new ResourceLocation(
                "rs_integration", "irons_spellbooks/scroll_forge/refined_mod/moon_slash/1");
        PlanStep targetStep = new PlanStep(selected, new ItemStack(Items.PAPER), 1,
                List.of(new ItemStack(Items.INK_SAC)), List.of(alternative),
                ModType.byId("generic"), 0, true, 0, 0,
                List.of("irons_spellbooks_scroll_forge"));
        PlanResponse plan = new PlanResponse(true, "Moon Slash", new ItemStack(Items.PAPER),
                List.of(targetStep), Map.of(), List.of(), selected.toString());

        PlanTreeNode root = PlanTreeModel.from(plan).root;

        assertNotNull(root.step);
        assertEquals(selected, root.step.recipeId());
        assertEquals(List.of(alternative), root.step.alternatives());
        assertTrue(root.hasAlternatives());
    }

    @Test
    void intermediateStepReceivesItsOwnPrerequisiteIssue() {
        ResourceLocation rootId = new ResourceLocation("test", "root");
        ResourceLocation altarId = new ResourceLocation("goety", "dark_altar_step");
        PlanStep altarStep = new PlanStep(altarId, new ItemStack(Items.EMERALD), 1,
                List.of(new ItemStack(Items.COAL)), List.of(), ModType.byId("goety"));
        PlanStep rootStep = new PlanStep(rootId, new ItemStack(Items.DIAMOND), 1,
                List.of(new ItemStack(Items.EMERALD)), List.of(), ModType.byId("generic"));
        PlanResponse plan = new PlanResponse(true, "Diamond", new ItemStack(Items.DIAMOND),
                List.of(altarStep, rootStep), Map.of(), List.of(), rootId.toString(),
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null,
                java.util.Set.of(), Map.of(), null, null, true, List.of(),
                Map.of(altarId, new PlanResponse.StepIssue(
                        List.of(Component.literal("Missing altar structure")), true)));

        PlanTreeNode altarNode = PlanTreeModel.from(plan).root.children.get(0);

        assertEquals(altarId, altarNode.step.recipeId());
        assertTrue(altarNode.prerequisiteBlocked);
        assertEquals("Missing altar structure", altarNode.warnings.get(0).getString());
    }

    @Test
    void embersStepsShowTheirOwnEnergyAndMachineHints() {
        EmbersMachinesRSModule.INSTANCE.registerModType();
        PlanStep melter = new PlanStep(new ResourceLocation("embers", "melting_test"),
                new ItemStack(Items.IRON_INGOT), 1, List.of(new ItemStack(Items.COAL)),
                List.of(), ModType.byId(ModIds.ID_EMBERS_MELTER));
        PlanStep mixer = new PlanStep(new ResourceLocation("embers", "mixing_test"),
                new ItemStack(Items.EMERALD), 1, List.of(new ItemStack(Items.IRON_INGOT)),
                List.of(), ModType.byId(ModIds.ID_EMBERS_MIXER));
        PlanStep stamper = new PlanStep(new ResourceLocation("embers", "stamping_test"),
                new ItemStack(Items.DIAMOND), 1, List.of(new ItemStack(Items.EMERALD)),
                List.of(), ModType.byId(ModIds.ID_EMBERS_STAMPER));
        PlanResponse plan = new PlanResponse(true, "Diamond", stamper.output(),
                List.of(melter, mixer, stamper), Map.of(), List.of(),
                stamper.recipeId().toString());

        PlanTreeNode root = PlanTreeModel.from(plan).root;
        PlanTreeNode mixerNode = root.children.get(0);
        PlanTreeNode melterNode = mixerNode.children.get(0);

        assertTrue(hintKeys(root).contains("rsi.embers_machine.hint.ember_stamper"));
        assertTrue(hintKeys(root).contains("rsi.embers_machine.hint.direct_capture"));
        assertTrue(hintKeys(mixerNode).contains("rsi.embers_machine.hint.ember_mixer"));
        assertTrue(hintKeys(melterNode).contains("rsi.embers_machine.hint.ember_melter"));
        assertTrue(root.warnings.isEmpty());
    }

    private static List<String> hintKeys(PlanTreeNode node) {
        return node.hints.stream().map(component ->
                ((TranslatableContents) component.getContents()).getKey()).toList();
    }

    @Test
    void treeRetainsAnExecutionStepThatCardViewCanShowButCannotBeLinked() {
        ResourceLocation rootId = new ResourceLocation("test", "root");
        ResourceLocation intermediateId = new ResourceLocation("minecraft", "kjs/malum_wicked_spirit");
        ItemStack taggedIntermediate = new ItemStack(Items.PAPER);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "wicked");
        taggedIntermediate.setTag(tag);
        PlanStep intermediate = new PlanStep(intermediateId, taggedIntermediate, 1,
                List.of(new ItemStack(Items.BLAZE_POWDER, 2)), List.of(),
                ModType.byId("generic"));
        PlanStep root = new PlanStep(rootId, new ItemStack(Items.DIAMOND), 1,
                List.of(new ItemStack(Items.PAPER)), List.of(), ModType.byId("generic"));
        PlanResponse plan = new PlanResponse(true, "Diamond", new ItemStack(Items.DIAMOND),
                List.of(intermediate, root), Map.of(), List.of(), rootId.toString());

        PlanTreeNode treeRoot = PlanTreeModel.from(plan).root;

        assertTrue(treeRoot.children.stream().anyMatch(node -> node.step != null
                && node.step.recipeId().equals(intermediateId)));
    }

    @Test
    void treeDoesNotAttachAnUnreachableLegacyManifestStep() {
        ResourceLocation rootId = new ResourceLocation("test", "root");
        ResourceLocation unrelatedId = new ResourceLocation("test", "unrelated");
        PlanStep unrelated = new PlanStep(unrelatedId, new ItemStack(Items.EMERALD), 1,
                List.of(new ItemStack(Items.COAL)), List.of(), ModType.byId("generic"));
        PlanStep root = new PlanStep(rootId, new ItemStack(Items.DIAMOND), 1,
                List.of(new ItemStack(Items.PAPER)), List.of(), ModType.byId("generic"));
        PlanResponse plan = new PlanResponse(true, "Diamond", new ItemStack(Items.DIAMOND),
                List.of(unrelated, root), Map.of(), List.of(), rootId.toString());

        PlanTreeNode treeRoot = PlanTreeModel.from(plan).root;

        assertTrue(treeRoot.children.stream().noneMatch(node -> node.step != null
                && node.step.recipeId().equals(unrelatedId)));
    }
}
