package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.crafting.graph.SelfAmplifyingTerminalGraphComposer;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer.TerminalOutput;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 按 ichor2 的 1+4+64→2 供料关系，覆盖真实确认包、租约和库存复验。 */
class MalumConfirmedPreviewTest extends BootstrapTest {
    @BeforeAll
    static void initialize() {
        GenericCraftPacketTest.loadServerConfig();
    }

    @ParameterizedTest
    @CsvSource({"1, false, rs", "1, true, bd", "11, true, rs", "11, true, bd"})
    void unchangedIchorCopyCanBeConfirmedButMissingSpiritsCannot(
            int operations, boolean terminalEmbedded, String backend) {
        ResourceLocation recipeId = new ResourceLocation("thaumcraft", "ichor2");
        BlockPos altar = new BlockPos(300, 64, 200);
        ModType malum = ModType.byId("malum");
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        StorageReference reference = new StorageReference(new StorageBackendId(backend), "test:network:1");
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        // 原版物品替代整合包注册物品；数量、配方类型和网络协议使用生产代码。
        ItemStack seed = new ItemStack(Items.SLIME_BALL);
        Map<StackKey, Long> available = Map.of(StackKey.of(seed, true), 1L,
                new StackKey(Items.GHAST_TEAR, null), 4L * operations,
                new StackKey(Items.AMETHYST_SHARD, null), 64L * operations);
        var specs = CraftIngredientPlanMath.scaleIngredientSpecs(List.of(
                new IngredientSpec(Ingredient.of(Items.SLIME_BALL), 1),
                new IngredientSpec(Ingredient.of(Items.GHAST_TEAR), 4 * operations),
                new IngredientSpec(Ingredient.of(Items.AMETHYST_SHARD), 64 * operations)), 1);
        CraftPlanGraph graph = CraftIngredientPlanMath.directTerminalInputGraph(specs,
                DirectMaterialAllocator.allocateScaled(specs, available));
        if (terminalEmbedded) {
            var terminal = new ResolutionStep(recipeId, malum,
                    new ResourceLocation("malum", "spirit_infusion"), List.of(), List.of(), false, operations);
            var outputs = List.of(TerminalOutput.item(MaterialKey.of(seed), 2,
                    OutputKind.PRIMARY, OutputProbability.guaranteed(), false));
            if (operations == 1) {
                graph = TerminalGraphComposer.compose(graph, terminal, outputs);
            } else {
                var composition = SelfAmplifyingTerminalGraphComposer.tryCompose(graph, terminal, outputs, null);
                assertNotNull(composition);
                graph = composition.graph();
            }
        }
        PlanCache cache = CraftPlanningRuntime.planCache();
        var key = CraftPlanCacheKeys.key(player.getUUID(), recipeId, Map.of(), operations, null, malum);
        var snapshot = new PlanningSnapshot(player.getUUID(), 1, key.recipeRevision(), recipeId,
                available, Map.of(), new ImmutableRecipeGraph(Map.of()),
                PlanningStateValidator.networkFingerprint(reference, available), "altar-binding", false);
        var plan = new PlanResponse(true, "ichor", seed.copyWithCount(2), List.of(), Map.of(), List.of(),
                recipeId.toString(), malum.id(), Level.OVERWORLD.location().toString(),
                altar.getX(), altar.getY(), altar.getZ(), List.of(), operations,
                null, null, null, 0L, false, false, false, null, Set.of(malum.id()), Map.of(),
                null, PlanGraphView.from(graph), false, List.of(), Map.of(), reference, List.of());
        cache.put(key, plan, snapshot, null, graph, terminalEmbedded, System.nanoTime());
        cache.retainForRequest(player.getUUID(), 10L, snapshot);
        var original = cache.peekForRequest(key, 10L);

        try (var endpoints = mockStatic(CraftStorageEndpoints.class);
             var materials = mockStatic(MaterialSources.class);
             var validator = mockStatic(PlanningStateValidator.class, CALLS_REAL_METHODS)) {
            endpoints.when(() -> CraftStorageEndpoints.resolve(reference, player)).thenReturn(Optional.of(endpoint));
            validator.when(() -> PlanningStateValidator.bindingFingerprint(player, Level.OVERWORLD, altar))
                    .thenReturn("altar-binding");
            Map<StackKey, Long> insufficient = new HashMap<>(available);
            insufficient.put(new StackKey(Items.AMETHYST_SHARD, null), 64L * operations - 1L);
            materials.when(() -> MaterialSources.listAvailableForTypes(player, endpoint,
                    Set.of(Items.SLIME_BALL, Items.GHAST_TEAR, Items.AMETHYST_SHARD)))
                    .thenReturn(insufficient, available);
            var confirmation = roundTrip(plan);
            assertTrue(confirm(player, malum, confirmation).rejected());
            assertSame(original, cache.peekForRequest(key, 10L), "缺料不能消费已展示计划");
            var accepted = confirm(player, malum, confirmation);
            assertFalse(accepted.rejected(), "补回精魂后原计划应能通过真实库存复验");
            assertSame(original, accepted.entry());
            assertNull(cache.peekForRequest(key, 10L));
        } finally {
            cache.removePlayer(player.getUUID());
        }
    }

    private static GenericCraftPacket.ExecutionPlanLookup confirm(
            ServerPlayer player, ModType type, GenericCraftPacketCodec.WireData packet) {
        return GenericCraftPacket.findValidatedExecutionPlan(player, packet.recipeId(), packet.forcedRecipes(),
                packet.repeatCount(), packet.targetOutput(), type, packet.dimension(), packet.position(),
                packet.storageReference(), packet.materialLocks(), packet.confirmedPreviewRequestId());
    }

    private static GenericCraftPacketCodec.WireData roundTrip(PlanResponse plan) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new PlanResponsePacket(plan, 10L).encode(buffer);
            var response = PlanResponsePacket.decode(buffer);
            PlanResponse displayed = response.plan();
            var packet = new GenericCraftPacket(new ResourceLocation(displayed.recipeId()), false, Map.of(),
                    new ResourceLocation(displayed.executionDim()),
                    new BlockPos(displayed.executionPosX(), displayed.executionPosY(), displayed.executionPosZ()),
                    displayed.repeatCount(), false, displayed.baseItem(), displayed.targetResult(),
                    20L, OutputDestination.RS_NETWORK).withStorageReference(displayed.storageReference())
                    .withConfirmedPreview(response.requestId());
            buffer.clear();
            packet.encode(buffer);
            return GenericCraftPacketCodec.read(buffer);
        } finally {
            buffer.release();
        }
    }
}
