package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.graph.SelfAmplifyingTerminalGraphComposer;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer.TerminalOutput;
import com.huanghuang.rsintegration.mods.malum.MalumRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class SelfAmplifyingTerminalGraphTest extends BootstrapTest {
    @ParameterizedTest
    @CsvSource({"1, 2, false", "2, 5, false", "3, 4, true"})
    void malumDefaultNbtInheritanceComposesThroughTheRealRecipeHandler(
            int consumed, int produced, boolean tagged) {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        if (tagged) seed.getOrCreateTag().putString("variant", "inherited");
        MalumCopyRecipe recipe = new MalumCopyRecipe(produced);
        MalumRecipeHandler handler = new MalumRecipeHandler();
        Level level = mock(Level.class);
        RecipeManager recipes = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(recipes);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        doReturn(Optional.of(recipe)).when(recipes).byKey(recipe.getId());
        ResolutionStep terminal = new ResolutionStep(recipe.getId(), ModType.byId("malum"),
                new ResourceLocation("malum", "spirit_infusion"), List.of(), List.of(), false, 11);
        assertTrue(handler.hasRuntimeDependentPrimaryNbt(recipe));
        assertEquals(produced, handler.getDeclaredOutputs(recipe, RegistryAccess.EMPTY).get(0).expectedPerOperation());

        CraftPlanGraph graph;
        try (MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            graph = CraftTerminalSteps.composeEquivalentTerminalGraph(input(seed, consumed, 11, false),
                    terminal, handler.getResultItem(recipe, RegistryAccess.EMPTY), level);
        }

        assertTrue(graph.nodes().stream().flatMap(node -> node.outputs().stream())
                .filter(output -> output.kind() == OutputKind.PRIMARY)
                .allMatch(output -> output.material().equals(MaterialKey.of(seed)) && !output.dynamicIdentity()));
        execute(graph, seed, consumed, produced, 11, consumed, false);
    }

    @Test
    void unknownDynamicNbtAndProbabilisticCopiesStillCannotPromiseSeeds() {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        CraftPlanGraph input = input(seed, 1, 11, false);
        ResolutionStep terminal = new ResolutionStep(new ResourceLocation("test", "unknown_copy"),
                ModType.byId("malum"), new ResourceLocation("test", "infusion"),
                List.of(), List.of(), false, 11);
        TerminalOutput dynamic = TerminalOutput.item(MaterialKey.of(seed), 2, OutputKind.PRIMARY,
                OutputProbability.guaranteed(), true);
        TerminalOutput random = TerminalOutput.item(MaterialKey.of(seed), 2, OutputKind.PRIMARY,
                OutputProbability.probabilistic(0.5, 16, "copy"), false);

        assertThrows(IllegalArgumentException.class, () -> SelfAmplifyingTerminalGraphComposer.tryCompose(
                input, terminal, List.of(dynamic), null));
        assertThrows(IllegalArgumentException.class, () -> SelfAmplifyingTerminalGraphComposer.tryCompose(
                input, terminal, List.of(random), 0));
        ItemStack taggedSeed = seed.copy();
        taggedSeed.getOrCreateTag().putString("variant", "unknown_output");
        assertThrows(IllegalArgumentException.class, () -> SelfAmplifyingTerminalGraphComposer.tryCompose(
                input(taggedSeed, 1, 11, false), terminal, List.of(dynamic), null));
    }

    @ParameterizedTest
    @CsvSource({"1, 2, 11, 1", "1, 2, 11, 59", "2, 5, 11, 2", "3, 4, 11, 3",
            "5, 12, 37, 5", "3, 4, 1, 3", "2, 3, 97, 59", "20, 21, 100, 20"})
    void machineCopyStagesCanReserveEveryOperationAndDeliverOnlyNetProduction(
            int consumed, int produced, int operations, int stock) {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        CraftPlanGraph input = input(seed, consumed, operations, false);
        CraftPlanGraph graph = compose(input, seed.copyWithCount(produced), operations, ModType.byId("malum"));

        assertEquals(operations, graph.nodes().stream().mapToInt(CraftNode::executions).sum());
        assertEquals(consumed + (long) operations * (produced - consumed),
                graph.rootDemands().get(0).quantity());
        if (consumed == 1 && operations == 11) {
            assertEquals(List.of(1, 2, 4, 4), graph.nodes().stream().map(CraftNode::executions).toList());
        }
        execute(graph, seed, consumed, produced, operations, stock, false);
    }

    @Test
    void genericCompactionKeepsFeedbackEdgesAndCatalystReturns() {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        CraftPlanGraph graph = compose(input(seed, 2, 11, true), seed.copyWithCount(5),
                11, ModType.GENERIC);

        assertTrue(graph.nodes().size() > 1, "有回流依赖的阶段不能压回一个整单节点");
        execute(graph, seed, 2, 5, 11, 2, true);
    }

    @Test
    void exactSeedNbtSurvivesFeedbackWithoutUsingAnotherVariant() {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "chosen");
        seed.setTag(tag);
        CraftPlanGraph graph = compose(input(seed, 2, 11, false), seed.copyWithCount(5),
                11, ModType.byId("malum"));

        assertTrue(graph.allocations().stream().filter(allocation -> allocation.material().item() == Items.SLIME_BALL)
                .allMatch(allocation -> allocation.material().equals(MaterialKey.of(seed))));
        execute(graph, seed, 2, 5, 11, 2, false);
    }

    @Test
    void recursivelyProducedSeedsRemainDependenciesOfTheFirstCopyStage() {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        CraftPlanGraph stocked = input(seed, 3, 11, false);
        NodeId producer = new NodeId(4);
        OutputPortId output = new OutputPortId(producer, 0);
        MaterialKey material = MaterialKey.of(seed);
        CraftNode node = new CraftNode(producer, new ResourceLocation("test", "seed_producer"),
                ModType.GENERIC.id(), new ResourceLocation("minecraft", "crafting"),
                1, List.of(), List.of(), false, null, null, List.of(),
                List.of(OutputDeclaration.item(output, material, 3L, OutputKind.PRIMARY)));
        List<RootDemand> roots = new ArrayList<>(stocked.rootDemands());
        roots.set(0, new RootDemand(StrictNBTIngredient.of(seed), 3L, 0L, seed,
                List.of(new RootAllocation(new MaterialSource.ProducerOutput(output), material, 3L))));
        CraftPlanGraph input = new CraftPlanGraph(1, List.of(node), List.of(), roots,
                List.of(), List.of(producer));

        CraftPlanGraph graph = compose(input, seed.copyWithCount(4), 11, ModType.byId("malum"));

        CraftPlanValidator.validate(graph);
        assertEquals(List.of(producer), graph.dependenciesOf(new NodeId(5)).stream().toList());
        assertEquals(14L, graph.rootDemands().get(0).quantity());
        assertFalse(graph.allocations().stream().anyMatch(allocation ->
                allocation.material().item() == Items.SLIME_BALL
                        && allocation.source() instanceof MaterialSource.InitialPool));
    }

    @Test
    void multipleSeedSlotsShareTheSameFeedbackSupply() {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        CraftPlanGraph stocked = input(seed, 3, 11, false);
        List<RootDemand> roots = new ArrayList<>(stocked.rootDemands());
        roots.set(0, root(seed, 2L, DemandRole.CONSUMED));
        roots.add(root(seed, 1L, DemandRole.CONSUMED));
        CraftPlanGraph input = new CraftPlanGraph(1, List.of(), List.of(), roots, List.of(), List.of());

        CraftPlanGraph graph = compose(input, seed.copyWithCount(4), 11, ModType.byId("malum"));

        execute(graph, seed, 3, 4, 11, 3, false);
        graph.nodes().forEach(node -> {
            assertEquals(2L * node.executions(), node.inputs().get(0).quantity());
            assertEquals(node.executions(), node.inputs().get(3).quantity());
        });
    }

    @Test
    void missingOrWrongSeedCannotStartTheFirstStage() {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        CraftPlanGraph graph = compose(input(seed, 3, 11, false), seed.copyWithCount(4),
                11, ModType.byId("malum"));
        MaterialBroker broker = new MaterialBroker();
        publish(broker, seed, 2);
        publish(broker, new ItemStack(Items.DIRT), 44);
        publish(broker, new ItemStack(Items.AMETHYST_SHARD), 704);

        assertFalse(broker.canReserve(requests(graph, graph.topologicalOrder().get(0))));
    }

    @Test
    void ordinaryTagAlternativeDoesNotBecomeASeed() {
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        RootDemand broad = new RootDemand(Ingredient.of(Items.SLIME_BALL, Items.CLAY_BALL), 1, 0,
                seed, List.of(new RootAllocation(new MaterialSource.InitialPool(MaterialKey.of(seed)),
                MaterialKey.of(seed), 1)));
        CraftPlanGraph input = new CraftPlanGraph(1, List.of(), List.of(), List.of(broad), List.of(), List.of());

        assertEquals(1, compose(input, seed.copyWithCount(2), 11, ModType.GENERIC).nodes().size());
    }

    @Test
    void oldSingleNodeNeedsElevenSeedsAndCannotReserveWithOne() {
        CountedOperationMaterials.Pool pool = new CountedOperationMaterials.Pool()
                .add(CountedOperationMaterials.Origin.INITIAL, new ItemStack(Items.SLIME_BALL), 1);
        assertThrows(IllegalArgumentException.class, () -> new CountedOperationMaterials.Builder(11, 1)
                .add(new IngredientSpec(Ingredient.of(Items.SLIME_BALL), 1), pool));
    }

    private static CraftPlanGraph compose(CraftPlanGraph input, ItemStack output, int operations, ModType type) {
        Level level = mock(Level.class);
        RecipeManager recipes = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(recipes);
        when(recipes.byKey(any())).thenReturn(Optional.empty());
        ResolutionStep terminal = new ResolutionStep(new ResourceLocation("test", "copy"), type,
                new ResourceLocation("test", "infusion"), List.of(), List.of(), false, operations);
        return CraftTerminalSteps.composeEquivalentTerminalGraph(input, terminal, output, level);
    }

    private static CraftPlanGraph input(ItemStack seed, int consumed, int operations, boolean catalyst) {
        List<RootDemand> roots = new ArrayList<>(List.of(root(seed, consumed, DemandRole.CONSUMED),
                root(new ItemStack(Items.DIRT), 4L * operations, DemandRole.CONSUMED),
                root(new ItemStack(Items.AMETHYST_SHARD), 64L * operations, DemandRole.CONSUMED)));
        if (catalyst) roots.add(root(new ItemStack(Items.BUCKET), 1L, DemandRole.CATALYST));
        return new CraftPlanGraph(1, List.of(), List.of(), roots, List.of(), List.of());
    }

    private static RootDemand root(ItemStack stack, long quantity, DemandRole role) {
        MaterialKey key = MaterialKey.of(stack);
        return new RootDemand(StrictNBTIngredient.of(stack), quantity, 0L, stack,
                List.of(new RootAllocation(new MaterialSource.InitialPool(key), key, quantity)), role);
    }

    private static void execute(CraftPlanGraph graph, ItemStack seed, int consumed, int produced,
                                int operations, int stock, boolean catalyst) {
        CraftPlanValidator.validate(graph);
        MaterialBroker broker = new MaterialBroker();
        publish(broker, seed, stock);
        publish(broker, new ItemStack(Items.DIRT), 4L * operations);
        publish(broker, new ItemStack(Items.AMETHYST_SHARD), 64L * operations);
        if (catalyst) publish(broker, new ItemStack(Items.BUCKET), 1L);
        DagScheduler scheduler = new DagScheduler(graph);
        for (int i = 0; i < graph.nodes().size(); i++) {
            List<NodeId> ready = scheduler.claimReady(1);
            assertEquals(1, ready.size());
            CraftNode node = graph.nodesById().get(ready.get(0));
            MaterialBroker.ReservationToken token = broker.reserve(node.id(), requests(graph, node.id()));
            assertNotNull(token, "前序种子实际回流后，每阶段都应有足够的图材料");
            MaterialBroker.Checkout checkout = broker.checkout(token);
            CountedOperationMaterials.Pool pool = new CountedOperationMaterials.Pool()
                    .add(CountedOperationMaterials.Origin.INITIAL, checkout.initialStacks())
                    .add(CountedOperationMaterials.Origin.PRODUCER, checkout.producerStacks());
            CountedOperationMaterials.Builder builder = new CountedOperationMaterials.Builder(node.executions(), 1);
            builder.add(new IngredientSpec(StrictNBTIngredient.of(seed), consumed), pool)
                    .add(new IngredientSpec(Ingredient.of(Items.DIRT), 4), pool)
                    .add(new IngredientSpec(Ingredient.of(Items.AMETHYST_SHARD), 64), pool);
            if (catalyst) builder.add(new IngredientSpec(Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST), pool);
            CountedOperationMaterials materials = builder.build();
            for (int operation = 0; operation < node.executions(); operation++) {
                List<ItemStack> batch = materials.batchMaterials(0, operation, 1);
                assertEquals(List.of(consumed, 4, 64), batch.stream().limit(3).map(ItemStack::getCount).toList());
            }
            broker.commit(token);
            broker.settle(token);
            node.outputs().forEach(output -> broker.publish(new MaterialSource.ProducerOutput(output.id()),
                    output.material(), output.quantity()));
            scheduler.succeed(node.id());
        }
        assertTrue(scheduler.allSucceeded());
        var delivery = graph.rootDemands().stream().flatMap(root -> root.allocations().stream())
                .map(allocation -> new MaterialBroker.Request(allocation.source(), allocation.material(), allocation.quantity()))
                .toList();
        assertTrue(broker.canReserve(delivery));
        assertEquals(stock + (long) operations * (produced - consumed),
                stock - consumed + graph.rootDemands().stream().mapToLong(RootDemand::quantity).sum());
        assertEquals(4L * operations, graph.allocations().stream()
                .filter(allocation -> allocation.material().item() == Items.DIRT)
                .mapToLong(allocation -> allocation.quantity()).sum());
        assertEquals(64L * operations, graph.allocations().stream()
                .filter(allocation -> allocation.material().item() == Items.AMETHYST_SHARD)
                .mapToLong(allocation -> allocation.quantity()).sum());
    }

    private static List<MaterialBroker.Request> requests(CraftPlanGraph graph, NodeId id) {
        return graph.allocations().stream().filter(allocation -> allocation.consumer().nodeId().equals(id))
                .map(allocation -> new MaterialBroker.Request(allocation.source(), allocation.material(), allocation.quantity()))
                .toList();
    }

    private static void publish(MaterialBroker broker, ItemStack stack, long quantity) {
        MaterialKey key = MaterialKey.of(stack);
        broker.publish(new MaterialSource.InitialPool(key), key, quantity);
    }

    /** 与 Malum 注魔配方相同的输出字段及默认 NBT 继承开关。 */
    private static final class MalumCopyRecipe extends ShapelessRecipe {
        public final boolean useNbtFromInput = true;
        public final ItemStack output;

        private MalumCopyRecipe(int produced) {
            super(new ResourceLocation("test", "malum_copy"), "", CraftingBookCategory.MISC,
                    new ItemStack(Items.SLIME_BALL, produced),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.SLIME_BALL)));
            output = new ItemStack(Items.SLIME_BALL, produced);
        }
    }
}
