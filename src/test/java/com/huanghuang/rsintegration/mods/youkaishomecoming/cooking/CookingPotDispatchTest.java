package com.huanghuang.rsintegration.mods.youkaishomecoming.cooking;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftOutputInterceptor;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import com.huanghuang.rsintegration.crafting.OperationResourceCoordinator;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationPoll;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.GraphConcurrencyEligibility;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.mods.youkaishomecoming.YoukaisHomecomingRSModule;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实执行组和资源租约，防止锅具的产物归属声明在投料前阻断订单。 */
class CookingPotDispatchTest extends BootstrapTest {
    @BeforeAll
    static void initializeRuntime() throws ClassNotFoundException {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
        YoukaisHomecomingRSModule.INSTANCE.registerModType();
    }

    @ParameterizedTest
    @ValueSource(strings = {"small", "short", "large"})
    void reservedFoodOrderStartsAndCollectsWithWorldCapture(String size) {
        String typeId = "youkaishomecoming_cooking_" + size;
        ModType type = ModType.byId(typeId);
        assertNotNull(type);
        CookingPotBatchDelegate pot = (CookingPotBatchDelegate) type.createDelegate();
        assertNotNull(pot);
        BlockPos position = new BlockPos(10, 64, 10);
        BoundMachine machine = new BoundMachine(Level.OVERWORLD.location(), position, type, typeId);
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.serverLevel()).thenReturn(level);
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(Ingredient.of(Items.BROWN_MUSHROOM), 1),
                new IngredientSpec(Ingredient.of(Items.BOWL), 4));
        List<ItemStack> supplied = List.of(
                new ItemStack(Items.BROWN_MUSHROOM), new ItemStack(Items.BOWL, 4));
        List<OperationStartContext> starts = new ArrayList<>();
        // 仅替代世界交互；规划资格取真实锅具声明，派发、材料交接与捕获租约均走正式代码。
        IBatchDelegate worker = mock(IBatchDelegate.class);
        when(worker.concurrencyCapabilities()).thenReturn(pot.concurrencyCapabilities());
        when(worker.materialPlan()).thenReturn(MaterialPlan.graphSlots(specs));
        when(worker.getMachinePos()).thenReturn(position);
        when(worker.getOperationMachinePos(position)).thenReturn(position);
        when(worker.getExpectedOutput()).thenReturn(new ItemStack(Items.MUSHROOM_STEW));
        when(worker.getOutputCaptureRegion()).thenReturn(new AABB(position).inflate(0.5));
        when(worker.canCollectResultWithoutWorldCapture()).thenReturn(pot.canCollectResultWithoutWorldCapture());
        when(worker.validateExecutionContext(player)).thenReturn(true);
        when(worker.startOperation(any())).thenAnswer(call -> {
            starts.add(call.getArgument(0));
            return true;
        });
        when(worker.observeCraft(level)).thenReturn(new IBatchDelegate.CraftObservation(IBatchDelegate.CraftPhase.DONE));
        when(worker.collectAllResults(player)).thenReturn(List.of(new ItemStack(Items.MUSHROOM_STEW, 4)));
        var planned = GraphConcurrencyEligibility.capabilities(
                new GraphConcurrencyEligibility.Context(typeId, "", false));
        ParallelCraftGroup group = new ParallelCraftGroup(List.of(machine), type,
                new ResourceLocation("test", "pot_food_order"), player, 1,
                false, planned, null, 1, ignored -> worker, worker);
        CountedOperationMaterials.Pool pool = new CountedOperationMaterials.Pool()
                .add(CountedOperationMaterials.Origin.INITIAL, supplied);
        CountedOperationMaterials.Builder materials = new CountedOperationMaterials.Builder(1, 1);
        worker.materialPlan().entries().forEach(entry -> materials.add(entry, pool));
        group.setCountedOperationMaterials(materials.build());
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationBudget global = new OperationBudget(1, 1);
        OperationBudget budget = new OperationBudget(1, 1);
        group.setOperationKernel(new OperationExecutionKernel(
                new OperationResourceCoordinator(machines, captures, global)),
                UUID.randomUUID(), new NodeId(0), budget);
        int initialZones = CraftOutputInterceptor.activeZoneCount();

        try (ExtractionLedger ledger = ExtractionLedger.external()) {
            assertTrue(ledger.commitToEndpoint(null, player));
            try {
                assertTrue(group.startOperation(OperationStartContext.chainReserved(
                        player, ledger, MaterialPlan.none(), List.of(), InputBufferPlan.none())));
                assertEquals(1, starts.size(), "订单应进入锅具投料，不能因产物归属冲突一直排队");
                assertEquals(1, machines.size());
                assertEquals(1, captures.size());
                assertEquals(1, budget.active());
                assertEquals(1, global.active());
                assertEquals(initialZones + 1, CraftOutputInterceptor.activeZoneCount());
                assertNotNull(starts.get(0).operationId());
                assertEquals(1, starts.get(0).inputSlots().get(0).getCount());
                assertEquals(4, starts.get(0).inputSlots().get(1).getCount());
                assertEquals(IBatchDelegate.CraftPhase.DONE, OperationPoll.poll(group, level).phase());
                assertEquals(1, group.getCompletedOperations());
                List<ItemStack> output = group.drainSettledResults();
                assertEquals(1, output.size());
                assertTrue(output.get(0).is(Items.MUSHROOM_STEW));
                assertEquals(4, output.get(0).getCount());
                assertTrue(group.drainSettledResults().isEmpty());
                verify(worker).collectAllResults(player);
                assertEquals(0, machines.size());
                assertEquals(0, captures.size());
                assertEquals(0, budget.active());
                assertEquals(0, global.active());
            } finally {
                group.onBatchFinished(player);
            }
        }
        assertEquals(initialZones, CraftOutputInterceptor.activeZoneCount());
    }
}
