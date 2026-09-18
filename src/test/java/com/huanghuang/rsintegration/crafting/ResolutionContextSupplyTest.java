package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolutionContextSupplyTest extends BootstrapTest {

    @Test
    void strictPreviewDeadlineAbortsInsteadOfReturningMissingMaterial() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), Map.of(), null,
                false, null, System.nanoTime() - 1L, true);

        assertThrows(CraftingPlanningTimeoutException.class, context::timedOut);
    }

    @Test
    void detailedConsumptionPreservesInitialSourceAndQuantity() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 4);
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(iron), null);

        ResolutionContext.SupplyConsumption result = context.consumeMatchingDetailed(
                Ingredient.of(Items.IRON_INGOT), 3);

        assertTrue(result.complete());
        assertEquals(3, result.supplied());
        assertEquals(1, result.slices().size());
        assertTrue(result.slices().get(0).source() instanceof MaterialSource.InitialPool);
        assertEquals(3, result.slices().get(0).quantity());
        assertEquals(1, context.countMatching(Ingredient.of(Items.IRON_INGOT)));
    }

    @Test
    void producedSupplyCanBePartiallyConsumedWithoutLosingProvenance() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(), null);
        OutputPortId output = new OutputPortId(new com.huanghuang.rsintegration.crafting.graph.NodeId(0), 0);
        context.addProduced(new ItemStack(Items.DIAMOND, 5), new MaterialSource.ProducerOutput(output));

        ResolutionContext.SupplyConsumption result = context.consumeMatchingDetailed(
                Ingredient.of(Items.DIAMOND), 2);

        assertTrue(result.complete());
        MaterialSource.ProducerOutput source = (MaterialSource.ProducerOutput) result.slices().get(0).source();
        assertEquals(output, source.outputPort());
        assertEquals(MaterialKey.of(new ItemStack(Items.DIAMOND)), result.slices().get(0).material());
        assertEquals(3, context.countMatching(Ingredient.of(Items.DIAMOND)));
    }

    @Test
    void nestedRollbackRestoresSupplyAmounts() {
        ResolutionContext context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.GOLD_INGOT, 3)), null);
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(Ingredient.of(Items.GOLD_INGOT), 2).complete());
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(Ingredient.of(Items.GOLD_INGOT), 1).complete());
        context.rollback();
        context.rollback();

        ResolutionContext.SupplyConsumption afterRollback = context.consumeMatchingDetailed(
                Ingredient.of(Items.GOLD_INGOT), 3);
        assertTrue(afterRollback.complete());
        assertFalse(context.consumeMatchingDetailed(Ingredient.of(Items.GOLD_INGOT), 1).complete());
    }

    @Test
    void outerRollbackRestoresConsumptionCommittedByInnerTransaction() {
        ResolutionContext context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.GOLD_INGOT, 3)), null);
        context.beginUndo();
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(Ingredient.of(Items.GOLD_INGOT), 2).complete());
        context.commitUndo();
        context.rollback();

        assertTrue(context.consumeMatchingDetailed(Ingredient.of(Items.GOLD_INGOT), 3).complete());
    }

    @Test
    void exactConsumptionIgnoresLargeUnrelatedInventory() {
        List<ItemStack> inventory = new java.util.ArrayList<>();
        inventory.add(new ItemStack(Items.DIAMOND, 2));
        for (int i = 0; i < 2_000; i++) {
            ItemStack unrelated = new ItemStack(Items.COBBLESTONE, 1);
            CompoundTag tag = new CompoundTag();
            tag.putInt("variant", i);
            unrelated.setTag(tag);
            inventory.add(unrelated);
        }
        ResolutionContext context = new ResolutionContext(null, Map.of(), inventory, null);

        ResolutionContext.SupplyConsumption result = context.consumeMatchingDetailed(
                Ingredient.of(Items.DIAMOND), 2);

        assertTrue(result.complete());
        assertEquals(1, result.slices().size());
        assertEquals(MaterialKey.of(new ItemStack(Items.DIAMOND)), result.slices().get(0).material());
        assertEquals(2_000, context.countMatching(Ingredient.of(Items.COBBLESTONE)));
    }

    @Test
    void oneDemandCanConsumeInitialAndProducerSupplyWithoutLosingProvenance() {
        ResolutionContext context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.IRON_INGOT, 2)), null);
        OutputPortId output = new OutputPortId(new NodeId(0), 0);
        context.addProduced(new ItemStack(Items.IRON_INGOT, 3),
                new MaterialSource.ProducerOutput(output));

        ResolutionContext.SupplyConsumption result = context.consumeMatchingDetailed(
                Ingredient.of(Items.IRON_INGOT), 4);

        assertTrue(result.complete());
        assertEquals(4, result.supplied());
        assertEquals(2, result.slices().size());
        assertTrue(result.slices().get(0).source() instanceof MaterialSource.InitialPool);
        assertEquals(2, result.slices().get(0).quantity());
        assertEquals(new MaterialSource.ProducerOutput(output), result.slices().get(1).source());
        assertEquals(2, result.slices().get(1).quantity());
        assertEquals(1, context.countMatching(Ingredient.of(Items.IRON_INGOT)));
    }

    @Test
    void producedOnlyConsumptionPreservesMatchingInitialInventory() {
        ResolutionContext context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.IRON_INGOT)), null);
        OutputPortId output = new OutputPortId(new NodeId(3), 0);
        context.addProduced(new ItemStack(Items.IRON_INGOT),
                new MaterialSource.ProducerOutput(output));

        ResolutionContext.SupplyConsumption produced =
                context.consumeProducedMatchingDetailed(Ingredient.of(Items.IRON_INGOT), 1);

        assertTrue(produced.complete());
        assertEquals(new MaterialSource.ProducerOutput(output), produced.slices().get(0).source());
        assertEquals(1, context.countMatching(Ingredient.of(Items.IRON_INGOT)));

        ResolutionContext.SupplyConsumption initial = context.consumeMatchingDetailed(
                Ingredient.of(Items.IRON_INGOT), 1);
        assertTrue(initial.complete());
        assertTrue(initial.slices().get(0).source() instanceof MaterialSource.InitialPool);
    }

    @Test
    void forcedRecipeLookupPrefersExactTaggedOutputVariant() {
        ItemStack enchantedBook = new ItemStack(Items.ENCHANTED_BOOK);
        enchantedBook.getOrCreateTag().putString("test_enchantment", "minecraft:infinity");
        ResourceLocation exactKey = CraftingResolver.preferenceKey(enchantedBook);
        ResourceLocation exactRecipe = new ResourceLocation("enigmaticlegacy", "enchantment_transposing");
        ResourceLocation itemRecipe = new ResourceLocation("goety", "enchant/infinity");
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(), null,
                Map.of(exactKey, exactRecipe,
                        new ResourceLocation("minecraft", "enchanted_book"), itemRecipe));

        assertEquals(exactRecipe, context.forcedRecipeFor(Ingredient.of(enchantedBook)));
    }

    @Test
    void configuredPreferenceDoesNotForceProduction() {
        ResourceLocation itemKey = new ResourceLocation("minecraft", "iron_ingot");
        ResourceLocation preferredRecipe = new ResourceLocation("test", "preferred_iron");
        ResolutionContext context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.IRON_INGOT)), Map.of(itemKey, preferredRecipe));

        assertEquals(null, context.forcedRecipeFor(Ingredient.of(Items.IRON_INGOT)));
        assertTrue(context.consumeMatchingDetailed(Ingredient.of(Items.IRON_INGOT), 1).complete());
    }

    @Test
    void broadIngredientConsumesPreferredStoredVariantFirst() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(
                new ItemStack(Items.GREEN_WOOL), new ItemStack(Items.WHITE_WOOL)), null);

        ResolutionContext.SupplyConsumption consumed = context.consumeMatchingDetailed(
                Ingredient.of(Items.GREEN_WOOL, Items.WHITE_WOOL), 1);

        assertTrue(consumed.complete());
        assertEquals(Items.WHITE_WOOL, consumed.slices().get(0).material().item());
        assertEquals(1, context.countMatching(Ingredient.of(Items.GREEN_WOOL)));
    }

    @Test
    void taggedSupplyLotsRemainDistinct() {
        ItemStack red = taggedDiamond("red", 2);
        ItemStack blue = taggedDiamond("blue", 3);
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(red, blue), null);

        ResolutionContext.SupplyConsumption result = context.consumeMatchingDetailed(
                Ingredient.of(Items.DIAMOND), 5);

        assertTrue(result.complete());
        assertEquals(2, result.slices().size());
        assertEquals(MaterialKey.of(red), result.slices().get(0).material());
        assertEquals(MaterialKey.of(blue), result.slices().get(1).material());
        assertFalse(result.slices().get(0).material().equals(result.slices().get(1).material()));
        assertEquals(2, result.slices().get(0).quantity());
        assertEquals(3, result.slices().get(1).quantity());
    }

    @Test
    void rollbackRemovesGraphStateProducedSupplyAndRestoresIds() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(), null);
        context.beginUndo();
        NodeId speculativeId = context.allocateNodeId();
        InputPortId input = new InputPortId(speculativeId, 0);
        context.addGraphNode(node(speculativeId));
        OutputPortId output = new OutputPortId(speculativeId, 0);
        context.addProduced(new ItemStack(Items.IRON_INGOT), new MaterialSource.ProducerOutput(output));
        ResolutionContext.SupplySlice slice = context.consumeMatchingDetailed(
                Ingredient.of(Items.IRON_INGOT), 1).slices().get(0);
        context.addAllocation(input, slice);
        context.addUnresolved(input, Ingredient.of(Items.GOLD_INGOT), 1);

        context.rollback();

        assertTrue(context.graphNodes.isEmpty());
        assertTrue(context.graphAllocations.isEmpty());
        assertTrue(context.graphUnresolved.isEmpty());
        assertEquals(0, context.countMatching(Ingredient.of(Items.IRON_INGOT)));
        assertEquals(speculativeId, context.allocateNodeId());
        context.addProduced(new ItemStack(Items.IRON_INGOT), new MaterialSource.ProducerOutput(output));
        context.addAllocation(input, context.consumeMatchingDetailed(
                Ingredient.of(Items.IRON_INGOT), 1).slices().get(0));
        assertEquals(0L, context.graphAllocations.get(0).id().value());
    }

    @Test
    void outerRollbackAlsoRemovesCommittedInnerGraphState() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), List.of(), null);
        context.beginUndo();
        context.allocateNodeId();
        context.beginUndo();
        NodeId innerId = context.allocateNodeId();
        context.addGraphNode(node(innerId));
        context.addProduced(new ItemStack(Items.DIAMOND),
                new MaterialSource.ProducerOutput(new OutputPortId(innerId, 0)));
        context.commitUndo();

        context.rollback();

        assertTrue(context.graphNodes.isEmpty());
        assertEquals(0, context.countMatching(Ingredient.of(Items.DIAMOND)));
        assertEquals(new NodeId(0), context.allocateNodeId());
    }

    @Test
    void bestEffortMissingIngredientClosesConsumerPortWithoutMissingOutputList() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), Map.of(), null,
                true, null);
        InputPortId input = new InputPortId(new NodeId(0), 0);

        boolean resolved = CraftingResolver.recordBestEffortUnresolved(context, input,
                Ingredient.of(Items.IRON_INGOT), 1, "test");

        assertTrue(resolved);
        assertTrue(context.graphAllocations.isEmpty());
        assertEquals(1, context.graphUnresolved.size());
        assertEquals(input, context.graphUnresolved.get(0).consumer());
        assertEquals(1, context.graphUnresolved.get(0).quantity());
    }

    @Test
    void strictCandidatePassDoesNotAcceptBestEffortUnresolved() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), Map.of(), null,
                true, null);
        context.strictCandidatePass = true;
        InputPortId input = new InputPortId(new NodeId(0), 0);

        boolean resolved = CraftingResolver.recordBestEffortUnresolved(context, input,
                Ingredient.of(Items.IRON_INGOT), 1, "strict-candidate");

        assertFalse(resolved);
        assertTrue(context.graphUnresolved.isEmpty());
    }

    @Test
    void bestEffortClosesMultipleIndependentWrStylePorts() {
        ResolutionContext context = new ResolutionContext(null, Map.of(), Map.of(), null,
                true, null);
        List<Ingredient> ingredients = List.of(
                Ingredient.of(Items.IRON_INGOT),
                Ingredient.of(Items.GOLD_INGOT),
                Ingredient.of(Items.DIAMOND),
                Ingredient.of(Items.REDSTONE));

        for (int index = 0; index < ingredients.size(); index++) {
            InputPortId input = new InputPortId(new NodeId(6 + index), 0);
            assertTrue(CraftingResolver.recordBestEffortUnresolved(context, input,
                    ingredients.get(index), 1, "test"));
        }

        assertTrue(context.graphAllocations.isEmpty());
        assertEquals(4, context.graphUnresolved.size());
        for (int index = 0; index < context.graphUnresolved.size(); index++) {
            assertEquals(new InputPortId(new NodeId(6 + index), 0),
                    context.graphUnresolved.get(index).consumer());
            assertEquals(1, context.graphUnresolved.get(index).quantity());
        }
    }

    private static ItemStack taggedDiamond(String variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return stack;
    }

    private static CraftNode node(NodeId id) {
        return new CraftNode(id, new ResourceLocation("test", "same"), ModType.GENERIC.id(),
                new ResourceLocation("minecraft", "crafting"), 1, List.of(), List.of(),
                false, null, null, List.of(), List.of());
    }
}
