package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.CraftLogContext;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 覆盖真实执行、按槽返还、图资产交接以及失败回收，不放宽普通 NBT 匹配。 */
class GraphCatalystReturnTest extends BootstrapTest {
    private static final NodeId NODE = new NodeId(0);
    private static final ResourceLocation RECIPE = new ResourceLocation("test", "charged_catalyst");
    private static final OutputPortId RETURN = new OutputPortId(NODE, 1);

    @BeforeAll
    static void initializeRuntime() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @Test
    void sixteenCraftsPublish64ItemsAndTheActualReducedCharge() throws Exception {
        Fixture fixture = new Fixture(100, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertTrue(fixture.publish());
        assertEquals(64, fixture.broker.available(fixture.primarySource(), fixture.primaryKey));
        ItemStack returned = fixture.takeReturn();
        assertEquals(84, returned.getTag().getInt("Souls"));
        assertEquals("selected", returned.getTag().getString("identity"));
        assertFalse(MaterialMatcher.matchesOutputDeclaration(fixture.catalystKey, returned));
        assertThrows(IllegalArgumentException.class, () -> fixture.broker.publishActual(
                fixture.returnSource(), fixture.catalystKey, returned));
        assertTrue(fixture.inventory.isEmpty());
    }

    @Test
    void slicesAcrossTicksKeepOnlyTheFinalCatalystState() throws Exception {
        Fixture fixture = new Fixture(100, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(8));
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(8));
        assertTrue(fixture.publish());
        assertEquals(84, fixture.takeReturn().getTag().getInt("Souls"));
        assertEquals(64, fixture.broker.available(fixture.primarySource(), fixture.primaryKey));
    }

    @Test
    void mergedShapedInputsTrackTheCenterCatalystAcross22Crafts() throws Exception {
        assertShapedCatalystReturn(false);
    }

    @Test
    void emptyShapedSlotsDoNotShiftTheGraphCatalystReturn() throws Exception {
        assertShapedCatalystReturn(true);
    }

    private static void assertShapedCatalystReturn(boolean sparse) throws Exception {
        Fixture fixture = new Fixture(86, false, false);
        int catalystSlot = sparse ? 7 : 4;
        NonNullList<Ingredient> ingredients = NonNullList.withSize(9, Ingredient.EMPTY);
        List<IngredientSpec> specs = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            if (slot == catalystSlot) {
                ingredients.set(slot, Ingredient.of(Items.TOTEM_OF_UNDYING));
                specs.add(new IngredientSpec(ingredients.get(slot), 1, DemandRole.CATALYST));
            } else if (!sparse || slot == 1 || slot == 5) {
                ingredients.set(slot, Ingredient.of(slot % 2 == 0 ? Items.LAPIS_LAZULI : Items.REDSTONE));
                specs.add(new IngredientSpec(ingredients.get(slot), 1));
            } else specs.add(IngredientSpec.EMPTY);
        }
        fixture.specs = List.copyOf(specs);
        fixture.recipe = new ShapedRecipe(RECIPE, "", CraftingBookCategory.MISC,
                3, 3, ingredients, new ItemStack(Items.EMERALD, 4)) {
            @Override
            public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
                NonNullList<ItemStack> remainders = NonNullList.withSize(9, ItemStack.EMPTY);
                ItemStack totem = container.getItem(catalystSlot).copy();
                totem.getOrCreateTag().putInt("Souls", totem.getTag().getInt("Souls") - 1);
                remainders.set(catalystSlot, totem);
                return remainders;
            }
        };
        RecipeManager manager = fixture.level.getRecipeManager();
        doReturn(Optional.of(fixture.recipe)).when(manager).byKey(RECIPE);
        List<InputDemand> inputs = new ArrayList<>();
        List<MaterialAllocation> allocations = new ArrayList<>();
        Inventory physical = new Inventory(fixture.player);
        int inventorySlot = 0;
        InputPortId catalystPort = null;
        for (IngredientSpec spec : StepExecutor.coalesceSpecsForGraph(specs)) {
            InputPortId port = new InputPortId(NODE, inputs.size());
            ItemStack supplied = spec.role() == DemandRole.CATALYST
                    ? charged(86) : spec.ingredient().getItems()[0].copy();
            long quantity = spec.role() == DemandRole.CATALYST ? spec.count() : spec.count() * 22L;
            MaterialKey material = MaterialKey.of(supplied);
            inputs.add(new InputDemand(port, StrictNBTIngredient.of(supplied), quantity,
                    spec.role(), supplied));
            allocations.add(new MaterialAllocation(new AllocationId(allocations.size()), port,
                    new MaterialSource.InitialPool(material), material, quantity));
            physical.items.set(inventorySlot++, supplied.copyWithCount((int) quantity));
            if (spec.role() == DemandRole.CATALYST) catalystPort = port;
        }
        assertNotNull(catalystPort);
        assertNotEquals(catalystSlot, catalystPort.index());
        CraftNode node = new CraftNode(NODE, RECIPE, "generic", new ResourceLocation("minecraft", "crafting"),
                22, List.of(), List.of(), false, null, null, inputs,
                List.of(OutputDeclaration.item(new OutputPortId(NODE, 0), fixture.primaryKey, 88, OutputKind.PRIMARY),
                        OutputDeclaration.item(RETURN, fixture.catalystKey, 1, OutputKind.CATALYST_RETURN,
                                1, 1, OutputProbability.guaranteed(), false, catalystPort)));
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), allocations,
                List.of(new RootDemand(Ingredient.of(Items.EMERALD), 88, 0, new ItemStack(Items.EMERALD),
                        List.of(new RootAllocation(fixture.primarySource(), fixture.primaryKey, 88)))),
                List.of(), List.of(NODE));
        AsyncCraftChain chain = fixture.newChain(graph);
        MaterialBroker broker = field(chain, "graphMaterials", MaterialBroker.class);
        DagScheduler scheduler = field(chain, "graphScheduler", DagScheduler.class);
        NodeAdmissionCoordinator admissions = field(chain, "graphAdmissions", NodeAdmissionCoordinator.class);
        when(fixture.player.getInventory()).thenReturn(physical);
        try (var utils = mockStatic(CraftPacketUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> CraftPacketUtils.extractCraftingIngredientSpecs(fixture.recipe))
                    .thenAnswer(call -> fixture.specs);
            Object job = beginJob(chain, fixture, NODE, 22, broker, scheduler, admissions, graph);
            assertTrue(runJobSlice(chain, fixture.player, job, 8));
            assertTrue(runJobSlice(chain, fixture.player, job, 14));
        }
        assertEquals(DagScheduler.NodeState.SUCCEEDED, scheduler.state(NODE));
        List<ItemStack> results = broker.drainAvailableProducerAssets();
        assertEquals(88, count(results, Items.EMERALD));
        assertEquals(1, count(results, Items.TOTEM_OF_UNDYING));
        assertEquals(64, results.stream().filter(stack -> stack.is(Items.TOTEM_OF_UNDYING))
                .findFirst().orElseThrow().getTag().getInt("Souls"));
        assertTrue(physical.items.stream().allMatch(ItemStack::isEmpty));
        assertTrue(broker.drainAvailableProducerAssets().isEmpty());
    }

    @Test
    void twoCatalystSlotsPublishTheirOwnFinalNbt() throws Exception {
        Fixture fixture = new Fixture(new int[]{100, 60}, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertTrue(fixture.publish());
        ItemStack first = fixture.takeReturn();
        ItemStack second = fixture.broker.drainAvailable(
                new MaterialSource.ProducerOutput(new OutputPortId(NODE, 2)), fixture.catalystKeys.get(1), 1).get(0);
        assertEquals(84, first.getTag().getInt("Souls"));
        assertEquals("selected", first.getTag().getString("identity"));
        assertEquals(28, second.getTag().getInt("Souls"));
        assertEquals("selected-2", second.getTag().getString("identity"));
        assertEquals(0, count(fixture.broker.drainAvailableProducerAssets(), Items.TOTEM_OF_UNDYING));
    }

    @Test
    void changedNbtStillRequiresATrackedRecipeReturn() throws Exception {
        Fixture fixture = new Fixture(100, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertFalse(fixture.publish(new CraftingCatalystState()));
        assertEquals(84, fixture.virtualInventory().stream().filter(stack -> stack.is(Items.TOTEM_OF_UNDYING))
                .findFirst().orElseThrow().getTag().getInt("Souls"));
    }

    @Test
    void graphJobsCarryReducedNbtAcrossSlicesAndIntoTheNextNode() throws Exception {
        Fixture fixture = new Fixture(100, false, false);
        AsyncCraftChain chain = fixture.newChain(fixture.twoNodeGraph());
        MaterialBroker broker = field(chain, "graphMaterials", MaterialBroker.class);
        DagScheduler scheduler = field(chain, "graphScheduler", DagScheduler.class);
        NodeAdmissionCoordinator admissions = field(chain, "graphAdmissions", NodeAdmissionCoordinator.class);
        Inventory physical = new Inventory(fixture.player);
        physical.items.set(0, new ItemStack(Items.LAPIS_LAZULI, 17));
        physical.items.set(1, charged(100));
        when(fixture.player.getInventory()).thenReturn(physical);

        try (var utils = mockStatic(CraftPacketUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> CraftPacketUtils.extractCraftingIngredientSpecs(fixture.recipe))
                    .thenAnswer(call -> fixture.specs);
            Object first = beginJob(chain, fixture, NODE, 16, broker, scheduler, admissions);
            assertTrue(runJobSlice(chain, fixture.player, first, 8));
            assertEquals(DagScheduler.NodeState.RUNNING, scheduler.state(NODE));
            assertEquals(0, broker.available(fixture.primarySource(), fixture.primaryKey));
            assertTrue(runJobSlice(chain, fixture.player, first, 8));
            assertEquals(DagScheduler.NodeState.SUCCEEDED, scheduler.state(NODE));
            var returned = broker.reserve(new NodeId(1), List.of(
                    new MaterialBroker.Request(fixture.returnSource(), fixture.catalystKey, 1)));
            assertNotNull(returned);
            assertEquals(84, broker.checkout(returned).producerStacks().get(0)
                    .getTag().getInt("Souls"));
            broker.release(returned);

            NodeId next = new NodeId(1);
            Object second = beginJob(chain, fixture, next, 1, broker, scheduler, admissions);
            assertTrue(runJobSlice(chain, fixture.player, second, 1));
            assertEquals(DagScheduler.NodeState.SUCCEEDED, scheduler.state(next));
        }
        List<ItemStack> results = broker.drainAvailableProducerAssets();
        assertEquals(68, count(results, Items.EMERALD));
        assertEquals(1, count(results, Items.TOTEM_OF_UNDYING));
        assertEquals(83, results.stream().filter(stack -> stack.is(Items.TOTEM_OF_UNDYING))
                .findFirst().orElseThrow().getTag().getInt("Souls"));
        assertTrue(physical.items.stream().allMatch(ItemStack::isEmpty));
        assertTrue(broker.drainAvailableProducerAssets().isEmpty());
    }

    @Test
    void graphJobCannotPublishAnExhaustedCatalystNeededByTheNextNode() throws Exception {
        Fixture fixture = new Fixture(16, false, false);
        AsyncCraftChain chain = fixture.newChain(fixture.twoNodeGraph());
        MaterialBroker broker = field(chain, "graphMaterials", MaterialBroker.class);
        DagScheduler scheduler = field(chain, "graphScheduler", DagScheduler.class);
        NodeAdmissionCoordinator admissions = field(chain, "graphAdmissions", NodeAdmissionCoordinator.class);
        Inventory physical = new Inventory(fixture.player);
        physical.items.set(0, new ItemStack(Items.LAPIS_LAZULI, 17));
        physical.items.set(1, charged(16));
        when(fixture.player.getInventory()).thenReturn(physical);
        try (var utils = mockStatic(CraftPacketUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> CraftPacketUtils.extractCraftingIngredientSpecs(fixture.recipe))
                    .thenAnswer(call -> fixture.specs);
            Object job = beginJob(chain, fixture, NODE, 16, broker, scheduler, admissions);
            assertFalse(runJobSlice(chain, fixture.player, job, 16));
        }
        assertEquals(DagScheduler.NodeState.FAILED, scheduler.state(NODE));
        assertNotEquals(DagScheduler.NodeState.READY, scheduler.state(new NodeId(1)));
        assertEquals(0, broker.available(fixture.primarySource(), fixture.primaryKey));
        List<ItemStack> recovered = field(chain, "virtualInventory", List.class);
        assertEquals(64, count(recovered, Items.EMERALD));
        assertEquals(0, count(recovered, Items.TOTEM_OF_UNDYING));
        assertEquals(1, count(physical.items, Items.LAPIS_LAZULI));
    }

    private static Object beginJob(AsyncCraftChain chain, Fixture fixture, NodeId node, int executions,
                                   MaterialBroker broker, DagScheduler scheduler,
                                   NodeAdmissionCoordinator admissions) throws Exception {
        return beginJob(chain, fixture, node, executions, broker, scheduler, admissions, fixture.twoNodeGraph());
    }

    private static Object beginJob(AsyncCraftChain chain, Fixture fixture, NodeId node, int executions,
                                   MaterialBroker broker, DagScheduler scheduler,
                                   NodeAdmissionCoordinator admissions, CraftPlanGraph graph) throws Exception {
        scheduler.claim(node);
        List<MaterialBroker.Request> requests = graph.allocations().stream()
                .filter(allocation -> allocation.consumer().nodeId().equals(node))
                .map(allocation -> new MaterialBroker.Request(allocation.source(), allocation.material(), allocation.quantity()))
                .toList();
        var admission = admissions.tryAdmitClaimed(new NodeAdmissionCoordinator.Candidate(node, requests));
        assertNotNull(admission);
        Method begin = AsyncCraftChain.class.getDeclaredMethod("beginGraphVanillaJob", NodeId.class,
                CraftingResolver.ResolutionStep.class, NodeAdmissionCoordinator.Admission.class, int.class, ServerPlayer.class);
        begin.setAccessible(true);
        var step = new CraftingResolver.ResolutionStep(RECIPE, ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"), List.of(), List.of(), false, executions);
        Object job = begin.invoke(chain, node, step, admission, executions, fixture.player);
        assertNotNull(job);
        assertEquals(MaterialBroker.ReservationState.COMMITTED, broker.state(admission.materialToken()));
        return job;
    }

    private static boolean runJobSlice(AsyncCraftChain chain, ServerPlayer player, Object job, int executions)
            throws Exception {
        Method run = AsyncCraftChain.class.getDeclaredMethod("runGraphVanillaSlice", job.getClass(), int.class, ServerPlayer.class);
        run.setAccessible(true);
        return (boolean) run.invoke(chain, job, executions, player);
    }

    private static <T> T field(AsyncCraftChain chain, String name, Class<T> type) throws Exception {
        Field field = AsyncCraftChain.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(chain));
    }

    @Test
    void catalystCanDisappearOnTheLastCraftWhenNoConsumerNeedsIt() throws Exception {
        Fixture fixture = new Fixture(16, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertTrue(fixture.publish());
        assertEquals(0, fixture.broker.available(fixture.returnSource(), fixture.catalystKey));
        assertEquals(64, fixture.broker.available(fixture.primarySource(), fixture.primaryKey));
    }

    @Test
    void spentReplacementIsRecoveredAsTheRealItem() throws Exception {
        Fixture fixture = new Fixture(16, false, true);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertTrue(fixture.publish());
        assertEquals(0, fixture.broker.available(fixture.returnSource(), fixture.catalystKey));
        assertEquals(1, count(fixture.virtualInventory(), Items.STICK));
    }

    @Test
    void exhaustedCatalystCannotSatisfyARequiredReturn() throws Exception {
        Fixture fixture = new Fixture(16, true, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertFalse(fixture.publish());
        assertEquals(64, count(fixture.virtualInventory(), Items.EMERALD));
        assertEquals(0, fixture.broker.available(fixture.primarySource(), fixture.primaryKey));
    }

    @Test
    void insufficientChargeStopsBeforeFabricatingTheRestOfTheBatch() throws Exception {
        Fixture fixture = new Fixture(8, false, false);
        assertEquals(FlatCraftExecutor.Status.FAILURE, fixture.execute(16));
        assertEquals(32, count(fixture.inventory, Items.EMERALD));
        assertEquals(8, count(fixture.inventory, Items.LAPIS_LAZULI));
        assertEquals(0, count(fixture.inventory, Items.TOTEM_OF_UNDYING));
    }

    @Test
    void missingPrimaryOutputStillFailsAndRecoversTheChangedCatalyst() throws Exception {
        Fixture fixture = new Fixture(100, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        fixture.inventory.removeIf(stack -> stack.is(Items.EMERALD));
        assertFalse(fixture.publish());
        ItemStack recovered = fixture.virtualInventory().stream()
                .filter(stack -> stack.is(Items.TOTEM_OF_UNDYING)).findFirst().orElseThrow();
        assertEquals(84, recovered.getTag().getInt("Souls"));
    }

    @Test
    void unrelatedSameItemCannotReplaceTheTrackedReturn() throws Exception {
        Fixture fixture = new Fixture(100, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        fixture.inventory.removeIf(stack -> stack.is(Items.TOTEM_OF_UNDYING));
        ItemStack unrelated = charged(84);
        unrelated.getOrCreateTag().putString("identity", "other");
        fixture.inventory.add(unrelated);
        assertFalse(fixture.publish());
        assertEquals(1, count(fixture.virtualInventory(), Items.TOTEM_OF_UNDYING));
    }

    @Test
    void exactRootDemandDoesNotAcceptTheOldChargeAfterItHasChanged() throws Exception {
        Fixture fixture = new Fixture(100, true, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertFalse(fixture.publish());
    }

    @Test
    void cancellationAndReservationReleaseKeepTheReducedNbtExactlyOnce() throws Exception {
        Fixture fixture = new Fixture(100, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertTrue(fixture.publish());
        var requests = List.of(new MaterialBroker.Request(fixture.returnSource(), fixture.catalystKey, 1));
        var token = fixture.broker.reserve(new NodeId(1), requests);
        assertNotNull(token);
        assertEquals(84, fixture.broker.checkout(token).producerStacks().get(0).getTag().getInt("Souls"));
        fixture.broker.release(token);
        token = fixture.broker.reserve(new NodeId(1), requests);
        fixture.broker.commit(token);
        fixture.broker.refund(token);
        List<ItemStack> recovered = fixture.broker.drainAvailableProducerAssets();
        assertEquals(1, count(recovered, Items.TOTEM_OF_UNDYING));
        assertEquals(84, recovered.stream().filter(stack -> stack.is(Items.TOTEM_OF_UNDYING))
                .findFirst().orElseThrow().getTag().getInt("Souls"));
        assertTrue(fixture.broker.drainAvailableProducerAssets().isEmpty());
    }

    @Test
    void nextRecipeUsesTheReducedStateAndStillHonorsItsExactNbtRequirement() throws Exception {
        Fixture fixture = new Fixture(100, false, false);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, fixture.execute(16));
        assertTrue(fixture.publish());
        ItemStack returned = fixture.takeReturn();
        Fixture next = new Fixture(84, false, false);
        next.inventory.clear();
        next.inventory.add(new ItemStack(Items.LAPIS_LAZULI, 16));
        next.inventory.add(returned);
        assertEquals(FlatCraftExecutor.Status.SUCCESS, next.execute(1));
        assertEquals(83, next.inventory.stream().filter(stack -> stack.is(Items.TOTEM_OF_UNDYING))
                .findFirst().orElseThrow().getTag().getInt("Souls"));

        Fixture strict = new Fixture(100, false, false);
        strict.specs = List.of(strict.specs.get(0), new IngredientSpec(
                StrictNBTIngredient.of(charged(100)), 1, DemandRole.CATALYST));
        strict.inventory.clear();
        strict.inventory.add(new ItemStack(Items.LAPIS_LAZULI, 16));
        strict.inventory.add(returned);
        assertEquals(FlatCraftExecutor.Status.FAILURE, strict.execute(1));
        assertEquals(0, count(strict.inventory, Items.EMERALD));
        assertEquals(84, returned.getTag().getInt("Souls"));
    }

    private static ItemStack charged(int souls) {
        ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING);
        stack.getOrCreateTag().putInt("Souls", souls);
        stack.getOrCreateTag().putString("identity", "selected");
        return stack;
    }

    private static int count(List<ItemStack> stacks, Item item) {
        return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static final class Fixture {
        final MaterialKey catalystKey;
        final List<MaterialKey> catalystKeys = new ArrayList<>();
        final MaterialKey primaryKey = MaterialKey.of(new ItemStack(Items.EMERALD));
        final CraftingCatalystState state = new CraftingCatalystState();
        final List<ItemStack> inventory = new ArrayList<>();
        final AsyncCraftChain chain;
        final MaterialBroker broker;
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        CraftingRecipe recipe;
        List<IngredientSpec> specs;

        Fixture(int souls, boolean requireReturn, boolean replaceSpent) throws Exception {
            this(new int[]{souls}, requireReturn, replaceSpent);
        }

        Fixture(int[] charges, boolean requireReturn, boolean replaceSpent) throws Exception {
            inventory.add(new ItemStack(Items.LAPIS_LAZULI, 16));
            List<IngredientSpec> slots = new ArrayList<>();
            slots.add(new IngredientSpec(Ingredient.of(Items.LAPIS_LAZULI), 1));
            for (int index = 0; index < charges.length; index++) {
                ItemStack initial = charged(charges[index]);
                if (index > 0) initial.getOrCreateTag().putString("identity", "selected-" + (index + 1));
                catalystKeys.add(MaterialKey.of(initial));
                inventory.add(initial.copy());
                slots.add(new IngredientSpec(Ingredient.of(Items.TOTEM_OF_UNDYING), 1, DemandRole.CATALYST));
            }
            catalystKey = catalystKeys.get(0);
            specs = List.copyOf(slots);
            NonNullList<Ingredient> ingredients = NonNullList.create();
            specs.forEach(spec -> ingredients.add(spec.ingredient()));
            recipe = new ShapelessRecipe(RECIPE, "", CraftingBookCategory.MISC,
                    new ItemStack(Items.EMERALD, 4), ingredients) {
                @Override
                public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
                    NonNullList<ItemStack> remaining = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
                    for (int slot = 1; slot <= charges.length; slot++) {
                        ItemStack totem = container.getItem(slot).copy();
                        int next = totem.getOrCreateTag().getInt("Souls") - slot;
                        if (next > 0) {
                            totem.getOrCreateTag().putInt("Souls", next);
                            remaining.set(slot, totem);
                        } else if (replaceSpent) remaining.set(slot, new ItemStack(Items.STICK));
                    }
                    return remaining;
                }
            };
            RecipeManager manager = mock(RecipeManager.class);
            when(level.getRecipeManager()).thenReturn(manager);
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            doReturn(Optional.of(recipe)).when(manager).byKey(RECIPE);
            when(player.serverLevel()).thenReturn(level);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            Field menuField = Player.class.getField("inventoryMenu");
            menuField.setAccessible(true);
            menuField.set(player, mock(InventoryMenu.class));
            player.containerMenu = player.inventoryMenu;
            when(server.overworld()).thenReturn(level);
            chain = newChain(graph(requireReturn));
            Field brokerField = AsyncCraftChain.class.getDeclaredField("graphMaterials");
            brokerField.setAccessible(true);
            broker = (MaterialBroker) brokerField.get(chain);
        }

        FlatCraftExecutor.Status execute(int executions) {
            var step = new CraftingResolver.ResolutionStep(RECIPE, ModType.GENERIC,
                    new ResourceLocation("minecraft", "crafting"), List.of(), List.of(), false, executions);
            try (var utils = mockStatic(CraftPacketUtils.class, CALLS_REAL_METHODS);
                 ExtractionLedger ledger = new ExtractionLedger()) {
                utils.when(() -> CraftPacketUtils.extractCraftingIngredientSpecs(recipe)).thenAnswer(call -> specs);
                return FlatCraftExecutor.executeDetailed(List.of(step), player, inventory, ledger, false,
                        new FlatCraftExecutor.Context(level, CraftLogContext.create(player.getUUID(), RECIPE),
                                List.of(step), null, mock(FlatCraftExecutor.Host.class), Map.of(), state)).status();
            }
        }

        boolean publish() throws Exception {
            return publish(state);
        }

        boolean publish(CraftingCatalystState trackedState) throws Exception {
            Method publish = AsyncCraftChain.class.getDeclaredMethod("publishDeclaredNodeOutputs",
                    NodeId.class, List.class, CraftingCatalystState.class);
            publish.setAccessible(true);
            return (boolean) publish.invoke(chain, NODE, inventory, trackedState);
        }

        @SuppressWarnings("unchecked")
        List<ItemStack> virtualInventory() throws Exception {
            Field field = AsyncCraftChain.class.getDeclaredField("virtualInventory");
            field.setAccessible(true);
            return (List<ItemStack>) field.get(chain);
        }

        ItemStack takeReturn() {
            return broker.drainAvailable(returnSource(), catalystKey, 1).get(0);
        }

        MaterialSource.ProducerOutput primarySource() { return new MaterialSource.ProducerOutput(new OutputPortId(NODE, 0)); }
        MaterialSource.ProducerOutput returnSource() { return new MaterialSource.ProducerOutput(RETURN); }

        AsyncCraftChain newChain(CraftPlanGraph graph) {
            return new AsyncCraftChain(player.getUUID(), server, null, graph);
        }

        CraftPlanGraph twoNodeGraph() {
            CraftPlanGraph first = graph(false);
            NodeId next = new NodeId(1);
            MaterialKey lapis = MaterialKey.of(new ItemStack(Items.LAPIS_LAZULI));
            InputPortId raw = new InputPortId(next, 0);
            InputPortId tool = new InputPortId(next, 1);
            OutputPortId product = new OutputPortId(next, 0);
            CraftNode second = new CraftNode(next, RECIPE, "generic", new ResourceLocation("minecraft", "crafting"),
                    1, List.of(), List.of(), false, null, null,
                    List.of(new InputDemand(raw, Ingredient.of(Items.LAPIS_LAZULI), 1, DemandRole.CONSUMED, lapis.toStack(1)),
                            new InputDemand(tool, Ingredient.of(Items.TOTEM_OF_UNDYING), 1, DemandRole.CATALYST, catalystKey.toStack(1))),
                    List.of(OutputDeclaration.item(product, primaryKey, 4, OutputKind.PRIMARY),
                            OutputDeclaration.item(new OutputPortId(next, 1), catalystKey, 1, OutputKind.CATALYST_RETURN,
                                    1, 1, OutputProbability.guaranteed(), false, tool)), List.of());
            List<MaterialAllocation> allocations = new ArrayList<>(first.allocations());
            allocations.add(new MaterialAllocation(new AllocationId(2), raw, new MaterialSource.InitialPool(lapis), lapis, 1));
            allocations.add(new MaterialAllocation(new AllocationId(3), tool, returnSource(), catalystKey, 1));
            return new CraftPlanGraph(1, List.of(first.nodes().get(0), second), allocations,
                    List.of(new RootDemand(Ingredient.of(Items.EMERALD), 68, 0, primaryKey.toStack(1),
                            List.of(new RootAllocation(primarySource(), primaryKey, 64),
                                    new RootAllocation(new MaterialSource.ProducerOutput(product), primaryKey, 4)))),
                    List.of(), List.of(NODE, next));
        }

        CraftPlanGraph graph(boolean requireReturn) {
            InputPortId raw = new InputPortId(NODE, 0);
            MaterialKey lapis = MaterialKey.of(new ItemStack(Items.LAPIS_LAZULI));
            OutputDeclaration primary = OutputDeclaration.item(new OutputPortId(NODE, 0), primaryKey, 64, OutputKind.PRIMARY);
            List<InputDemand> inputs = new ArrayList<>(List.of(new InputDemand(raw, Ingredient.of(Items.LAPIS_LAZULI),
                    16, DemandRole.CONSUMED, lapis.toStack(1))));
            List<OutputDeclaration> outputs = new ArrayList<>(List.of(primary));
            List<MaterialAllocation> allocations = new ArrayList<>(List.of(new MaterialAllocation(new AllocationId(0),
                    raw, new MaterialSource.InitialPool(lapis), lapis, 16)));
            for (int index = 0; index < catalystKeys.size(); index++) {
                MaterialKey key = catalystKeys.get(index);
                InputPortId tool = new InputPortId(NODE, index + 1);
                inputs.add(new InputDemand(tool, StrictNBTIngredient.of(key.toStack(1)), 1, DemandRole.CATALYST, key.toStack(1)));
                outputs.add(OutputDeclaration.item(new OutputPortId(NODE, index + 1), key, 1, OutputKind.CATALYST_RETURN,
                        1, 1, OutputProbability.guaranteed(), false, tool));
                allocations.add(new MaterialAllocation(new AllocationId(index + 1), tool,
                        new MaterialSource.InitialPool(key), key, 1));
            }
            CraftNode node = new CraftNode(NODE, RECIPE, "generic", new ResourceLocation("minecraft", "crafting"),
                    16, List.of(), List.of(), false, null, null,
                    inputs, outputs, List.of());
            List<RootDemand> roots = new ArrayList<>(List.of(new RootDemand(Ingredient.of(Items.EMERALD), 64, 0,
                    primaryKey.toStack(1), List.of(new RootAllocation(primarySource(), primaryKey, 64)))));
            if (requireReturn) roots.add(new RootDemand(StrictNBTIngredient.of(catalystKey.toStack(1)), 1, 0,
                    catalystKey.toStack(1), List.of(new RootAllocation(returnSource(), catalystKey, 1))));
            return new CraftPlanGraph(1, List.of(node), allocations,
                    roots, List.of(), List.of(NODE));
        }
    }
}
