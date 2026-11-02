package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.mockito.MockedStatic;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

/** 单测和基准共用真实准备入口；仅世界、绑定查询和保护服务使用替身。 */
final class GraphPreparationFixture implements AutoCloseable {
    private static final ThreadLocal<GraphPreparationFixture> CURRENT = new ThreadLocal<>();
    static final NodeId NODE = new NodeId(0);
    static final ResourceLocation RECIPE = new ResourceLocation("test", "graph_preparation");
    private static ModType type;

    static synchronized void initializeRuntime() throws Exception {
        if (type != null) return;
        Method bootstrap = BootstrapTest.class.getDeclaredMethod("bootstrapMinecraft");
        bootstrap.setAccessible(true);
        bootstrap.invoke(null);
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class,
                call -> call.getMethod().getName().equals("getModEventBus")
                        ? mock(IEventBus.class) : RETURNS_DEFAULTS.answer(call));
        try (MockedStatic<FMLJavaModLoadingContext> loading =
                     mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
        type = ModType.register("graph_preparation_fixture", new String[0],
                new String[0], new String[0], () -> CURRENT.get().newDelegate());
    }

    final List<BoundMachine> machines = new ArrayList<>();
    final Set<Integer> denied = new HashSet<>();
    final Set<Integer> unavailable = new HashSet<>();
    final List<Integer> probes = new ArrayList<>();
    final List<TrackingDelegate> delegates = new ArrayList<>();
    final AsyncCraftChain chain;
    final CraftingResolver.ResolutionStep step;
    IntFunction<IBatchDelegate.PreparationResult> resultAt =
            index -> IBatchDelegate.PreparationResult.ready();
    private final boolean tracking;
    private final ServerPlayer player;
    private final MethodHandle prepare;
    private final MethodHandle prepared;
    private final MethodHandle delegate;
    private final MockedStatic<AltarBindingRegistry> bindings;
    private final MockedStatic<ProtectionChecker> protection;

    GraphPreparationFixture(int count, int executions, boolean tracking) throws Exception {
        initializeRuntime();
        if (CURRENT.get() != null) throw new IllegalStateException("准备夹具不能嵌套");
        this.tracking = tracking;
        BlockEntity block = mock(BlockEntity.class, withSettings().stubOnly());
        RecipeManager recipes = mock(RecipeManager.class, withSettings().stubOnly()
                .defaultAnswer(call -> call.getMethod().getName().equals("byKey")
                        ? Optional.empty() : RETURNS_DEFAULTS.answer(call)));
        ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly().defaultAnswer(call -> {
            return switch (call.getMethod().getName()) {
                case "dimension" -> Level.OVERWORLD;
                case "isLoaded" -> !unavailable.contains(((BlockPos) call.getArgument(0)).getX());
                case "getBlockEntity" -> block;
                case "getRecipeManager" -> recipes;
                default -> RETURNS_DEFAULTS.answer(call);
            };
        }));
        MinecraftServer server = mock(MinecraftServer.class,
                withSettings().stubOnly().defaultAnswer(call -> {
                    return switch (call.getMethod().getName()) {
                        case "getLevel", "overworld" -> level;
                        default -> RETURNS_DEFAULTS.answer(call);
                    };
                }));
        player = mock(ServerPlayer.class, withSettings().stubOnly().defaultAnswer(call -> {
            return switch (call.getMethod().getName()) {
                case "level", "serverLevel" -> level;
                default -> RETURNS_DEFAULTS.answer(call);
            };
        }));
        for (int i = 0; i < count; i++) {
            machines.add(new BoundMachine(Level.OVERWORLD.location(),
                    new BlockPos(i, 64, 0), type, "test_machine"));
        }
        step = new CraftingResolver.ResolutionStep(RECIPE, type, null,
                List.of(), List.of(), false, executions);
        chain = new AsyncCraftChain(UUID.randomUUID(), server, null, List.of(step));
        Method method = AsyncCraftChain.class.getDeclaredMethod("prepareGraphNode",
                NodeId.class, CraftingResolver.ResolutionStep.class, ServerPlayer.class);
        method.setAccessible(true);
        prepare = MethodHandles.lookup().unreflect(method).bindTo(chain).asType(
                MethodType.methodType(Object.class, NodeId.class,
                        CraftingResolver.ResolutionStep.class, ServerPlayer.class));
        Method preparedMethod = method.getReturnType().getDeclaredMethod("prepared");
        preparedMethod.setAccessible(true);
        prepared = MethodHandles.lookup().unreflect(preparedMethod).asType(
                MethodType.methodType(Object.class, Object.class));
        Method delegateMethod = preparedMethod.getReturnType().getDeclaredMethod("delegate");
        delegateMethod.setAccessible(true);
        delegate = MethodHandles.lookup().unreflect(delegateMethod).asType(
                MethodType.methodType(IBatchDelegate.class, Object.class));
        bindings = mockStatic(AltarBindingRegistry.class, withSettings().stubOnly()
                .defaultAnswer(call -> call.getMethod().getName().equals("getBoundMachinesForRecipe")
                        ? machines : RETURNS_DEFAULTS.answer(call)));
        protection = mockStatic(ProtectionChecker.class, withSettings().stubOnly()
                .defaultAnswer(call -> call.getMethod().getName().equals("canInteract")
                        ? !denied.contains(((BlockPos) call.getArgument(2)).getX())
                        : RETURNS_DEFAULTS.answer(call)));
        CURRENT.set(this);
    }

    Object prepare() throws Throwable {
        return (Object) prepare.invokeExact(NODE, step, player);
    }

    void release(Object result) throws Throwable {
        Object node = (Object) prepared.invokeExact(result);
        if (node != null) {
            IBatchDelegate selected = (IBatchDelegate) delegate.invokeExact(node);
            selected.releasePreparationResources();
        }
    }

    static Object component(Object record, String name) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private IBatchDelegate newDelegate() {
        if (!tracking) return new ProbeDelegate();
        TrackingDelegate instance = new TrackingDelegate();
        delegates.add(instance);
        return instance;
    }

    @Override
    public void close() {
        CURRENT.remove();
        protection.close();
        bindings.close();
    }

    private class ProbeDelegate implements IBatchDelegate {
        BlockPos position = BlockPos.ZERO;

        @Override
        public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                         ResourceLocation dim, BlockPos pos) {
            position = pos;
            return resultAt.apply(pos.getX());
        }

        @Override public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                                 ResourceLocation dim, BlockPos pos) { return true; }
        @Override public boolean startOperation(OperationStartContext context) { return false; }
        @Override public boolean isCraftComplete(ServerLevel level) { return false; }
        @Override public ItemStack collectResult(ServerPlayer player) { return ItemStack.EMPTY; }
        @Override public void onBatchFailed(ServerPlayer player, String reason) {}
        @Override public void onBatchFinished(ServerPlayer player) {}
        @Override public BlockPos getMachinePos() { return position; }
    }

    final class TrackingDelegate extends ProbeDelegate {
        int releases;

        @Override
        public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                         ResourceLocation dim, BlockPos pos) {
            probes.add(pos.getX());
            return super.prepare(player, recipeId, dim, pos);
        }

        @Override
        public void releasePreparationResources() {
            releases++;
        }
    }
}
