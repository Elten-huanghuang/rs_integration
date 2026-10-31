package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.CraftTerminalSteps;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.mods.summoningrituals.SummoningRitualAltarBatchDelegate;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SummoningRitualGraphDispatchTest extends BootstrapTest {
    @BeforeAll
    static void initializeModContext() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @ParameterizedTest
    @CsvSource({"1,0,false", "1,0,true", "6,0,false", "6,0,true", "6,2,true", "6,6,true"})
    void emptyPlayerInventoryCanPrepareWholeBatchFromRsAndExistingAltarStock(
            int executions, int installedMaterials, boolean installedCatalyst) throws Exception {
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        BlockEntity altar = mock(BlockEntity.class);
        ItemStackHandler inventory = new ItemStackHandler(3);
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, installedMaterials));
        if (installedCatalyst) inventory.setStackInSlot(2, new ItemStack(Items.STICK));
        List<ItemStack> snapshot = List.of(inventory.getStackInSlot(0).copy(),
                ItemStack.EMPTY, inventory.getStackInSlot(2).copy());
        when(player.getInventory()).thenReturn(new Inventory(player));
        when(player.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(level);
        when(player.level()).thenReturn(level);
        when(server.overworld()).thenReturn(level);
        RecipeManager recipes = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(recipes);
        Field serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        when(level.isLoaded(BlockPos.ZERO)).thenReturn(true);
        when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(altar);
        when(altar.getCapability(ForgeCapabilities.ITEM_HANDLER))
                .thenReturn(LazyOptional.of(() -> inventory));
        when(altar.getLevel()).thenReturn(level);
        when(altar.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(altar.getBlockState()).thenReturn(Blocks.STONE.defaultBlockState());

        ModType type = ModType.findById("summoning_rituals");
        if (type == null) {
            type = ModType.register("summoning_rituals", new String[0], new String[0],
                    new String[0], SummoningRitualAltarBatchDelegate::new);
        }
        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(new ResourceLocation("test", "summoning_graph"));
        when(recipes.byKey(recipe.getId())).thenAnswer(call -> Optional.of(recipe));
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(recipe.getId(),
                type, recipe.getId(), List.of(), List.of(), false, executions);
        SummoningRitualAltarBatchDelegate delegate = new SummoningRitualAltarBatchDelegate();
        set(delegate, "altarPos", BlockPos.ZERO);
        set(delegate, "player", player);
        set(delegate, "recipe", recipe);
        set(delegate, "catalyst", Ingredient.of(Items.STICK));
        set(delegate, "recipeInputs", List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)));
        set(delegate, "preparedSnapshot", snapshot);

        Map<Item, Integer> stock = new LinkedHashMap<>();
        int missingMaterials = executions - installedMaterials;
        if (missingMaterials > 0) stock.put(Items.DIAMOND, missingMaterials);
        if (!installedCatalyst) stock.put(Items.STICK, 1);
        StorageBackendId backend = new StorageBackendId("refinedstorage");
        StorageSession session = mock(StorageSession.class, CALLS_REAL_METHODS);
        when(session.reference()).thenReturn(new StorageReference(backend, UUID.randomUUID().toString()));
        when(session.snapshotItems(player)).thenAnswer(call -> StorageSnapshotResult.success(
                new StorageSnapshot(backend, stock.entrySet().stream()
                        .filter(entry -> entry.getValue() > 0)
                        .map(entry -> new StoredItem(StorageItemKey.fromItemStack(backend,
                                new ItemStack(entry.getKey())), entry.getValue())).toList())));
        when(session.extractExact(eq(player), any(StorageItemKey.class), anyLong(), anyBoolean()))
                .thenAnswer(call -> {
                    ItemStack stack = ((StorageItemKey) call.getArgument(1)).displayStack();
                    int count = ((Long) call.getArgument(2)).intValue();
                    boolean simulate = call.getArgument(3);
                    assertTrue(stock.getOrDefault(stack.getItem(), 0) >= count);
                    if (!simulate) stock.merge(stack.getItem(), -count, Integer::sum);
                    return StorageOperationResult.extracted(
                            simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                            count, List.of(stack.copyWithCount(count)));
                });
        CraftStorageEndpoint endpoint = () -> session;
        delegate.setStorageEndpoint(endpoint);

        List<RootDemand> roots = new ArrayList<>();
        if (!installedCatalyst) roots.add(root(Items.STICK, 1, DemandRole.CATALYST));
        if (missingMaterials > 0) roots.add(root(Items.DIAMOND, missingMaterials, DemandRole.CONSUMED));
        CraftPlanGraph inputGraph = new CraftPlanGraph(1, List.of(), List.of(), roots, List.of(), List.of());
        CraftPlanGraph graph = CraftTerminalSteps.composeEquivalentTerminalGraph(
                inputGraph, step, ItemStack.EMPTY, level);
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(), server, endpoint, graph);
        NodeId nodeId = graph.nodes().get(0).id();
        DagScheduler scheduler = (DagScheduler) get(chain, "graphScheduler");
        scheduler.claim(nodeId);
        NodeAdmissionCoordinator admissions = (NodeAdmissionCoordinator) get(chain, "graphAdmissions");
        List<MaterialBroker.Request> requests = graph.allocations()
                .stream().map(allocation -> new MaterialBroker.Request(
                        allocation.source(), allocation.material(), allocation.quantity())).toList();
        NodeAdmissionCoordinator.Admission admission = admissions.tryAdmitClaimed(
                new NodeAdmissionCoordinator.Candidate(nodeId, requests));
        assertNotNull(admission);

        Class<?> preparedType = Class.forName(AsyncCraftChain.class.getName() + "$PreparedGraphNode");
        Constructor<?> constructor = preparedType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object prepared = constructor.newInstance(step, delegate,
                List.of(new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, null)), 1, false);
        Method dispatch = AsyncCraftChain.class.getDeclaredMethod("dispatchPreparedGraphNode",
                NodeId.class, preparedType, ServerPlayer.class, NodeAdmissionCoordinator.Admission.class);
        dispatch.setAccessible(true);
        Object result = dispatch.invoke(chain, nodeId, prepared, player, admission);
        Method workerAccessor = result.getClass().getDeclaredMethod("worker");
        workerAccessor.setAccessible(true);
        CraftNodeRuntime runtime = (CraftNodeRuntime) workerAccessor.invoke(result);
        assertNotNull(runtime, result.toString());
        try {
            assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, runtime.observe(), runtime.failureReason());
            assertEquals(executions, inventory.getStackInSlot(0).getCount());
            assertEquals(1, inventory.getStackInSlot(2).getCount());
            assertTrue(stock.values().stream().allMatch(count -> count == 0), stock.toString());
            assertTrue(player.getInventory().isEmpty());
        } finally {
            runtime.completeOperation(() -> true, () -> admissions.settleMaterialOnce(admission));
            runtime.markResourcesClosed();
        }
    }

    private static RootDemand root(Item item, int count, DemandRole role) {
        MaterialKey key = MaterialKey.of(new ItemStack(item));
        return new RootDemand(Ingredient.of(item), count, 0, new ItemStack(item),
                List.of(new RootAllocation(new MaterialSource.InitialPool(key), key, count)), role);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
