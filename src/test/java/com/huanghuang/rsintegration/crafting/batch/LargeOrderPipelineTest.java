package com.huanghuang.rsintegration.crafting.batch;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator;
import com.huanghuang.rsintegration.crafting.planning.AsyncPurePlanningService;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PurePlanGraphAdapter;
import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StoragePermissionResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.eventbus.ListenerList;
import net.minecraftforge.eventbus.LockHelper;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 请求编解码→后台路由求解→执行图→真实链 tick/账本/派发/结算。
 * 世界、绑定、网络显示和机器/存储后端使用替身；不绕过请求上限或伪造账本返回值。
 */
@Timeout(60)
class LargeOrderPipelineTest extends BootstrapTest {
    private static final ResourceLocation RAW_RECIPE = new ResourceLocation("test", "bulk_intermediate");
    private static final ResourceLocation TARGET_RECIPE = new ResourceLocation("test", "bulk_target");
    // 验证用配方：64 铁→1 钻石；50000 钻石→1 绿宝石。
    private static final int RAW_PER_OPERATION = 64;
    private static final int INTERMEDIATES_PER_TARGET = 50_000;
    // 测试机器的原生批量能力，用于覆盖多个派发窗口；不修改生产配置。
    private static final int MACHINE_CAPACITY = 65_536;

    @BeforeAll
    static void initialize() throws Exception {
        resetConfig();
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
        initializeNetworkListeners();
        // 保留真实状态包编码，仅使用不发送到客户端的连接替身。
        var channel = BatchCraftNetworkHandler.CHANNEL;
        channel.registerMessage(NetworkPacketIds.CRAFT_STARTED, CraftStartedPacket.class,
                CraftStartedPacket::encode, CraftStartedPacket::decode, CraftStartedPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(NetworkPacketIds.CRAFT_PROGRESS, CraftProgressPacket.class,
                CraftProgressPacket::encode, CraftProgressPacket::decode, CraftProgressPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(NetworkPacketIds.CRAFT_PROGRESS_DELTA, CraftProgressDeltaPacket.class,
                CraftProgressDeltaPacket::encode, CraftProgressDeltaPacket::decode, CraftProgressDeltaPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    @SuppressWarnings("unchecked")
    private static void initializeNetworkListeners() throws Exception {
        // 普通 JVM 未经过 Forge 事件类变换，按已有后端测试的方式补齐监听器列表。
        Field listeners = EventListenerHelper.class.getDeclaredField("listeners");
        listeners.setAccessible(true);
        var cache = (LockHelper<Class<?>, ListenerList>) listeners.get(null);
        cache.computeIfAbsent(NetworkEvent.class, ListenerList::new);
        cache.computeIfAbsent(NetworkEvent.GatherLoginPayloadsEvent.class, ListenerList::new);
    }

    @BeforeEach
    @AfterEach
    void defaults() { resetConfig(); }

    private static void resetConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @ParameterizedTest
    @ValueSource(ints = {1025, 1_000_000, 10_000_000, 50_000_000})
    void oversizedOrdersAreClampedByConstructorAndRejectedOnServerWire(int requested) {
        GenericCraftPacket packet = new GenericCraftPacket(TARGET_RECIPE, true, Map.of(),
                null, null, requested, false, null);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            assertEquals(1024, GenericCraftPacketCodec.read(buffer).repeatCount(),
                    "当前生产入口会截断数量；不能把底层容量测试描述为已支持千万下单");
            buffer.clear();
            GenericCraftPacketCodec.write(buffer, wire(requested));
            assertThrows(IllegalArgumentException.class, () -> GenericCraftPacket.decode(buffer));
        } finally { buffer.release(); }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 1000})
    void admittedRequestRunsToCompletionWithLongMaterialsAndExactFinalDelivery(int repeats) throws Exception {
        try (Fixture fixture = new Fixture(repeats)) {
            long before = System.nanoTime();
            fixture.start();
            fixture.finish();
            long operations = (long) repeats * INTERMEDIATES_PER_TARGET;
            assertEquals(AsyncCraftChain.State.COMPLETED, fixture.chain.state(), fixture.chain.abortReason());
            assertEquals(operations, fixture.completed.getOrDefault(RAW_RECIPE, 0L));
            assertEquals((long) repeats, fixture.completed.getOrDefault(TARGET_RECIPE, 0L));
            assertEquals(0L, fixture.store.amount(Items.IRON_INGOT));
            assertEquals(0L, fixture.store.amount(Items.DIAMOND));
            assertEquals((long) repeats, fixture.store.amount(Items.EMERALD));
            assertEquals(operations * RAW_PER_OPERATION, fixture.store.extracted);
            assertTrue(fixture.store.extractCalls <= 2, "32 亿同质材料仅按 int API 边界拆片，不按加工次数提取");
            int expectedStarts = (int) ((operations + MACHINE_CAPACITY - 1L) / MACHINE_CAPACITY) + 1;
            assertEquals(expectedStarts, fixture.starts, "派发次数应由机器批量能力决定，不能按总加工次数展开");
            assertEquals((long) repeats, fixture.chain.outputProgress().completed());
            Map<Item, Long> settled = Map.copyOf(fixture.store.stock);
            fixture.chain.cancel("late cancel", Component.literal("late cancel"));
            fixture.chain.tick();
            assertEquals(settled, fixture.store.stock, "完成后取消不能退回已消耗投入或再次交付");
            assertEquals(expectedStarts, fixture.starts, "终态后不能重新派发");
            System.out.printf("LARGE_ORDER repeats=%d intermediateOperations=%d raw=%d extracts=%d starts=%d ticks=%d elapsedMs=%d%n",
                    repeats, operations, operations * RAW_PER_OPERATION, fixture.store.extractCalls,
                    fixture.starts, fixture.tick.get(), (System.nanoTime() - before) / 1_000_000L);
        }
    }

    @Test
    void monitorReadsFiftyMillionOperationsWithoutExtractingOrChangingSettlement() throws Exception {
        try (Fixture fixture = new Fixture(1000)) {
            fixture.start();
            var initial = fixture.chain.monitorMaterialView();
            assertEquals(fixture.initialRaw, material(initial, Items.IRON_INGOT).toExtract());
            assertEquals(0, material(initial, Items.IRON_INGOT).available());
            fixture.advanceUntil(() -> fixture.starts > 0);
            long extracted = fixture.store.extracted;
            int starts = fixture.starts;
            var view = fixture.chain.monitorMaterialView();
            var iron = material(view, Items.IRON_INGOT);
            assertEquals(fixture.initialRaw, iron.available() + iron.processing());
            assertEquals(0, iron.toExtract());
            assertTrue(iron.processing() > 0);
            assertEquals(3, view.rows().size());
            for (int i = 0; i < 10; i++) assertEquals(view, fixture.chain.monitorMaterialView());
            assertEquals(extracted, fixture.store.extracted);
            assertEquals(starts, fixture.starts);
            fixture.chain.cancel("monitor cancellation", Component.literal("cancel"));
            fixture.finish();
            assertEquals(fixture.initialRaw, fixture.store.amount(Items.IRON_INGOT)
                    + fixture.completed.getOrDefault(RAW_RECIPE, 0L) * RAW_PER_OPERATION);
        }
    }

    private static CraftMaterialView.Row material(CraftMaterialView view, Item item) {
        return view.rows().stream().filter(row -> row.material().item() == item).findFirst().orElseThrow();
    }

    @Test
    void cancellationAfterFirstDeliveryRefundsOnlyUnprocessedLongOrder() throws Exception {
        try (Fixture fixture = new Fixture(1000)) {
            fixture.start();
            fixture.advanceUntil(() -> fixture.completed.getOrDefault(RAW_RECIPE, 0L) > 0L);
            long completed = fixture.completed.get(RAW_RECIPE);
            fixture.chain.cancel("cancel after delivery", Component.literal("cancel"));
            Map<Item, Long> firstSettlement = Map.copyOf(fixture.store.stock);
            fixture.chain.cancel("duplicate cancel", Component.literal("cancel"));
            fixture.chain.tick();
            assertEquals(AsyncCraftChain.State.ABORTED, fixture.chain.state());
            assertEquals(firstSettlement, fixture.store.stock);
            assertEquals(fixture.initialRaw - completed * RAW_PER_OPERATION, fixture.store.amount(Items.IRON_INGOT));
            assertEquals(completed, fixture.store.amount(Items.DIAMOND));
            assertEquals(0L, fixture.store.amount(Items.EMERALD));
        }
    }

    @Test
    void failedStartAfterPartialConsumptionDoesNotRefundConsumedInputsOrRetry() throws Exception {
        try (Fixture fixture = new Fixture(1000)) {
            fixture.failFirstStart = true;
            fixture.start();
            fixture.finish();
            assertEquals(AsyncCraftChain.State.ABORTED, fixture.chain.state());
            assertEquals(1, fixture.starts);
            assertEquals(fixture.initialRaw - RAW_PER_OPERATION, fixture.store.amount(Items.IRON_INGOT));
            assertEquals(0L, fixture.store.amount(Items.DIAMOND));
            Map<Item, Long> settled = Map.copyOf(fixture.store.stock);
            fixture.chain.cancel("duplicate cancel", Component.literal("cancel"));
            assertEquals(settled, fixture.store.stock);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialMachineInsertionRecoversAcceptedAndRejectedMaterialsExactlyOnce(boolean throwAfterInsert) throws Exception {
        try (Fixture fixture = new Fixture(1000)) {
            fixture.rejectPartialInput = true;
            fixture.throwOnPartialInput = throwAfterInsert;
            fixture.start();
            fixture.finish();
            assertEquals(AsyncCraftChain.State.ABORTED, fixture.chain.state());
            assertEquals(1, fixture.starts, "部分插入失败后不能整批再次投料");
            assertEquals(Items.IRON_INGOT.getMaxStackSize(), fixture.acceptedBeforeFailure);
            assertEquals((long) MACHINE_CAPACITY * RAW_PER_OPERATION - fixture.acceptedBeforeFailure,
                    fixture.rejectedBeforeFailure);
            assertEquals(fixture.initialRaw, fixture.store.amount(Items.IRON_INGOT),
                    "实际取回的机器内材料和未插入余料都必须退回，且不能重复退款");
            assertEquals(0L, fixture.store.amount(Items.DIAMOND));
            assertEquals(0L, fixture.store.amount(Items.EMERALD));
            Map<Item, Long> settled = Map.copyOf(fixture.store.stock);
            fixture.chain.cancel("duplicate cancel", Component.literal("cancel"));
            fixture.chain.tick();
            assertEquals(settled, fixture.store.stock);
            assertEquals(1, fixture.starts);
        }
    }

    private static GenericCraftPacketCodec.WireData wire(int repeat) {
        return new GenericCraftPacketCodec.WireData(TARGET_RECIPE, true, Map.of(), null, null,
                repeat, false, null, null, 1L, OutputDestination.RS_NETWORK, null, false,
                MachineSelectionMode.AUTO, Map.of(), false, PlanningPreferences.empty());
    }

    private static MaterialRef material(Item item) {
        return new MaterialRef(BuiltInRegistries.ITEM.getKey(item), "");
    }

    private static final class Fixture implements AutoCloseable {
        final int repeats;
        final long initialRaw;
        final CountingStorage store = new CountingStorage();
        final AtomicInteger tick = new AtomicInteger();
        final Map<ResourceLocation, Long> completed = new HashMap<>();
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final UUID owner = UUID.randomUUID();
        final ModType type;
        final MockedStatic<AltarBindingRegistry> bindings;
        final MockedStatic<ProtectionChecker> protection;
        AsyncCraftChain chain;
        boolean failFirstStart;
        boolean rejectPartialInput;
        boolean throwOnPartialInput;
        long acceptedBeforeFailure;
        long rejectedBeforeFailure;
        int starts;

        Fixture(int repeats) throws Exception {
            this.repeats = repeats;
            initialRaw = (long) repeats * INTERMEDIATES_PER_TARGET * RAW_PER_OPERATION;
            store.stock.put(Items.IRON_INGOT, initialRaw);
            when(player.getUUID()).thenReturn(owner);
            when(player.getServer()).thenReturn(server);
            when(player.serverLevel()).thenReturn(level);
            when(player.level()).thenReturn(level);
            when(player.getInventory()).thenReturn(new Inventory(player));
            when(player.getName()).thenReturn(Component.literal("capacity-test"));
            Field playerServer = ServerPlayer.class.getField("server");
            playerServer.setAccessible(true);
            playerServer.set(player, server);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            Field connection = ServerGamePacketListenerImpl.class.getField("connection");
            connection.setAccessible(true);
            connection.set(player.connection, mock(Connection.class));
            PlayerList players = mock(PlayerList.class);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayer(owner)).thenReturn(player);
            when(server.overworld()).thenReturn(level);
            when(server.getLevel(any())).thenReturn(level);
            when(server.getTickCount()).thenAnswer(call -> tick.get());
            when(level.getGameTime()).thenAnswer(call -> (long) tick.get());
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.isLoaded(any())).thenReturn(true);
            when(level.getBlockEntity(any())).thenReturn(mock(BlockEntity.class));
            RecipeManager recipes = mock(RecipeManager.class);
            when(recipes.byKey(any())).thenReturn(Optional.empty());
            when(level.getRecipeManager()).thenReturn(recipes);
            when(server.getRecipeManager()).thenReturn(recipes);
            type = ModType.register("large_order_pipeline", new String[0], new String[0], new String[0],
                    () -> new CountingMachine(this));
            bindings = mockStatic(AltarBindingRegistry.class);
            bindings.when(() -> AltarBindingRegistry.getBoundMachinesForRecipe(any(), any(), any()))
                    .thenReturn(List.of(new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test")));
            protection = mockStatic(ProtectionChecker.class);
            protection.when(() -> ProtectionChecker.canInteract(any(), any(), any())).thenReturn(true);
        }

        void start() throws Exception {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            GenericCraftPacketCodec.WireData decoded;
            try {
                new GenericCraftPacket(TARGET_RECIPE, true, Map.of(), null, null, repeats, false, null).encode(buffer);
                GenericCraftPacket request = GenericCraftPacket.decode(buffer);
                assertEquals(0, buffer.readableBytes());
                buffer.clear();
                request.encode(buffer);
                decoded = GenericCraftPacketCodec.read(buffer);
                assertEquals(repeats, decoded.repeatCount());
            } finally { buffer.release(); }
            Map<StackKey, Long> available = Map.of(new StackKey(Items.IRON_INGOT, null), initialRaw);
            RecipeNode raw = new RecipeNode(RAW_RECIPE, material(Items.DIAMOND), 1L,
                    List.of(new IngredientRef(List.of(material(Items.IRON_INGOT)), RAW_PER_OPERATION)), type.id(), RAW_RECIPE);
            RecipeNode target = new RecipeNode(TARGET_RECIPE, material(Items.EMERALD), 1L,
                    List.of(new IngredientRef(List.of(material(Items.DIAMOND)), INTERMEDIATES_PER_TARGET)), type.id(), TARGET_RECIPE);
            ImmutableRecipeGraph recipes = new ImmutableRecipeGraph(Map.of(raw.output(), List.of(raw), target.output(), List.of(target)));
            PlanningSnapshot snapshot = new PlanningSnapshot(owner, 1L, 1L, decoded.recipeId(), available,
                    Map.of(), recipes, "capacity-storage", "capacity-machine", false);
            CompletableFuture<AsyncPurePlanningService.RoutedPlan> answer = new CompletableFuture<>();
            try (var coordinator = new AsyncPlanningCoordinator(1, 8)) {
                new AsyncPurePlanningService(coordinator).submitRouted(snapshot,
                        AsyncPurePlanningService.RouteInputs.fromItems(snapshot.availableItems(), 512, Set.of(), Set.of(), Set.of()),
                        decoded.repeatCount(), Runnable::run, RSIntegrationConfig.CRAFTING_MAX_STEPS.get(),
                        RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_STATES.get(),
                        RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES.get(),
                        RSIntegrationConfig.CRAFTING_PURE_PLANNING_TIMEOUT_MS.get(), answer::complete, answer::completeExceptionally);
                var routed = answer.get(10, TimeUnit.SECONDS);
                assertNotNull(routed.plan(), "本场景必须实际经过后台纯图路线");
                assertTrue(routed.plan().feasible());
                var input = PurePlanGraphAdapter.toInputGraph(routed.plan(), available,
                        List.of(new ScaledIngredientSpec(Ingredient.of(Items.DIAMOND),
                                (long) repeats * INTERMEDIATES_PER_TARGET, InputConsumption.consumed())));
                CraftPlanGraph graph = TerminalGraphComposer.compose(input,
                        new ResolutionStep(TARGET_RECIPE, type, TARGET_RECIPE, List.of(), List.of(), false, repeats),
                        List.of(TerminalGraphComposer.TerminalOutput.item(MaterialKey.of(new ItemStack(Items.EMERALD)),
                                1, OutputKind.PRIMARY)));
                CraftPlanValidator.validate(graph);
                assertEquals(2, graph.nodes().size());
                assertEquals(initialRaw, graph.nodes().get(0).inputs().get(0).quantity());
                CraftStorageEndpoint endpoint = () -> store;
                chain = new AsyncCraftChain(owner, server, endpoint, graph);
                chain.setTargetOutput(new ItemStack(Items.EMERALD));
                assertTrue(chain.isGraphExecution());
                assertEquals(AsyncCraftChain.State.PENDING, chain.state());
            }
        }

        void advanceUntil(BooleanSupplier condition) {
            for (int i = 0; i < 2000 && !condition.getAsBoolean() && !chain.isDone(); i++) {
                tick.incrementAndGet();
                chain.tick();
            }
            assertTrue(condition.getAsBoolean(), () -> "state=" + chain.state() + " reason=" + chain.abortReason());
        }

        void finish() { advanceUntil(chain::isDone); }

        @Override public void close() {
            try {
                if (chain != null && !chain.isDone()) chain.cancel("fixture cleanup", Component.literal("cleanup"));
            } finally { protection.close(); bindings.close(); }
        }
    }

    private static final class CountingMachine implements IBatchDelegate {
        final Fixture fixture;
        ResourceLocation recipe;
        int batch;
        int rawPerOperation;
        Item input;
        Item output;
        long held;
        boolean ready;
        final ItemStackHandler inputSlot = new ItemStackHandler(1);
        final List<ItemStack> rejectedInputs = new ArrayList<>();

        CountingMachine(Fixture fixture) { this.fixture = fixture; }
        @Override public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                                 ResourceLocation dim, BlockPos pos) {
            recipe = recipeId;
            input = RAW_RECIPE.equals(recipeId) ? Items.IRON_INGOT : Items.DIAMOND;
            output = RAW_RECIPE.equals(recipeId) ? Items.DIAMOND : Items.EMERALD;
            rawPerOperation = RAW_RECIPE.equals(recipeId) ? RAW_PER_OPERATION : INTERMEDIATES_PER_TARGET;
            return true;
        }
        @Override public List<IngredientSpec> getRequiredMaterials() {
            return List.of(new IngredientSpec(Ingredient.of(input), rawPerOperation));
        }
        @Override public int preferredParallelBatchSize(int total, int workers) { return MACHINE_CAPACITY; }
        @Override public void prepareGraphBatch(int executions) { batch = executions; }
        @Override public boolean startOperation(OperationStartContext context) {
            held = context.legacyMaterials().stream().mapToLong(ItemStack::getCount).sum();
            assertEquals((long) batch * rawPerOperation, held, "每次派发实际材料必须与机器接收数量一致");
            assertTrue(context.legacyMaterials().stream().allMatch(stack -> stack.is(input)));
            fixture.starts++;
            if (fixture.rejectPartialInput) {
                // 使用真实 Forge 槽位容量限制，只接收一叠；余料仍由适配器持有。
                held = 0;
                for (ItemStack stack : context.legacyMaterials()) {
                    ItemStack rejected = inputSlot.insertItem(0, stack, false);
                    if (!rejected.isEmpty()) rejectedInputs.add(rejected);
                }
                fixture.acceptedBeforeFailure = inputSlot.getStackInSlot(0).getCount();
                fixture.rejectedBeforeFailure = rejectedInputs.stream().mapToLong(ItemStack::getCount).sum();
                if (fixture.throwOnPartialInput) throw new IllegalStateException("partial insertion");
                return false;
            }
            if (fixture.failFirstStart) {
                held -= rawPerOperation; // 接口返回失败前实际消耗了一次；只能退回剩余实物。
                return false;
            }
            ready = true;
            return true;
        }
        @Override public boolean isCraftComplete(ServerLevel level) { return ready; }
        @Override public ItemStack collectResult(ServerPlayer player) {
            if (!ready) return ItemStack.EMPTY;
            ready = false;
            held = 0;
            fixture.completed.merge(recipe, (long) batch, Long::sum);
            return new ItemStack(output, batch);
        }
        @Override public ExpectedProduction getExpectedProduction() {
            return new ExpectedProduction(new ItemStack(output), batch);
        }
        @Override public List<ItemStack> failureRecoveredInputs() {
            List<ItemStack> recovery = split(input, held);
            held = 0;
            recovery.addAll(rejectedInputs);
            rejectedInputs.clear();
            ItemStack recovered = inputSlot.extractItem(0, Integer.MAX_VALUE, false);
            if (!recovered.isEmpty()) recovery.add(recovered);
            return recovery;
        }
        @Override public void onBatchFailed(ServerPlayer player, String reason) { ready = false; }
        @Override public void onBatchFinished(ServerPlayer player) { assertEquals(0L, held); }
        @Override public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }

    private static final class CountingStorage implements StorageSession {
        final StorageBackendId backend = new StorageBackendId("capacity-test");
        final Map<Item, Long> stock = new HashMap<>();
        long extracted;
        int extractCalls;
        long amount(Item item) { return stock.getOrDefault(item, 0L); }
        @Override public StorageReference reference() { return new StorageReference(backend, "network"); }
        @Override public StoragePermissionResult checkPermission(ServerPlayer player, StoragePermission permission) {
            return StoragePermissionResult.allowed();
        }
        @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) {
            return StorageSnapshotResult.success(new StorageSnapshot(backend, stock.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0L)
                    .map(entry -> new StoredItem(itemKey(new ItemStack(entry.getKey())), entry.getValue())).toList()));
        }
        @Override public StorageOperationResult extractExact(ServerPlayer player, StorageItemKey key, long amount, boolean simulate) {
            Item item = key.displayStack().getItem();
            long taken = Math.min(amount, amount(item));
            if (!simulate) {
                stock.put(item, amount(item) - taken);
                extracted += taken;
                extractCalls++;
            }
            return StorageOperationResult.extracted(simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                    amount, split(item, taken));
        }
        @Override public StorageOperationResult extractMatching(ServerPlayer player, Ingredient ingredient, long amount, boolean simulate) {
            throw new AssertionError("图已选定身份，必须精确提取");
        }
        @Override public StorageOperationResult insert(ServerPlayer player, ItemStack stack, boolean simulate) {
            if (!simulate) stock.merge(stack.getItem(), (long) stack.getCount(), Math::addExact);
            return StorageOperationResult.inserted(simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                    stack, ItemStack.EMPTY);
        }
    }

    private static List<ItemStack> split(Item item, long count) {
        List<ItemStack> result = new ArrayList<>();
        while (count > 0L) {
            int part = (int) Math.min(Integer.MAX_VALUE, count);
            result.add(new ItemStack(item, part));
            count -= part;
        }
        return result;
    }
}
