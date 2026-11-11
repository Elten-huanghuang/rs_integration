package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmedPreviewExecutionTest extends BootstrapTest {
    @BeforeAll
    static void initialize() {
        GenericCraftPacketTest.loadServerConfig();
    }

    @Test
    void confirmationCodecPreservesTheOriginalPreviewIdentity() {
        var packet = new GenericCraftPacket(new ResourceLocation("test", "target"), false,
                Map.of(), null, null, 1, false, null, new ItemStack(Items.DIAMOND),
                200L, OutputDestination.RS_NETWORK).withConfirmedPreview(100L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            var decoded = GenericCraftPacket.decode(buffer);
            buffer.clear();
            decoded.encode(buffer);
            var wire = GenericCraftPacketCodec.read(buffer);
            assertEquals(100L, wire.confirmedPreviewRequestId());
            assertEquals(200L, wire.requestId());
            assertFalse(wire.preview());
            assertThrows(IllegalArgumentException.class, () ->
                    new GenericCraftPacket(wire.recipeId(), true).withConfirmedPreview(100L));
            buffer.clear();
            packet.encode(buffer);
            buffer.setByte(buffer.writerIndex() - 1, 0xFF);
            buffer.writeLong(-1L).writeByte(1);
            assertThrows(DecoderException.class, () -> GenericCraftPacket.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test
    void selectedOlderTaskIsValidatedAndConsumedWithoutUsingTheNewerTask() {
        Fixture fixture = new Fixture();
        try (var validator = mockStatic(PlanningStateValidator.class)) {
            fixture.retain(10, 1);
            PlanCache.Entry older = fixture.cache.peekForRequest(fixture.key, 10);
            fixture.retain(20, 2);
            PlanCache.Entry newer = fixture.cache.peekForRequest(fixture.key, 20);
            validator.when(() -> PlanningStateValidator.revalidateForExecution(fixture.player,
                    older.snapshot(), older.plan(), Level.OVERWORLD, BlockPos.ZERO, null)).thenReturn(true);
            var selected = fixture.confirm(10);
            assertFalse(selected.rejected());
            assertSame(older, selected.entry());
            assertSame(newer, fixture.cache.peekForRequest(fixture.key, 20));
            assertTrue(fixture.confirm(10).rejected(), "重复确认已消费任务不能回退到另一份计划或重新搜索");
            validator.verify(() -> PlanningStateValidator.revalidateForExecution(fixture.player,
                    older.snapshot(), older.plan(), Level.OVERWORLD, BlockPos.ZERO, null), times(1));
            validator.verifyNoMoreInteractions();
        } finally { fixture.cache.removePlayer(fixture.player.getUUID()); }
    }

    @Test
    void missingUnknownOrChangedStockCannotFallThroughToUnpreviewedPlanning() {
        Fixture fixture = new Fixture();
        try (var validator = mockStatic(PlanningStateValidator.class)) {
            fixture.retain(10, 1);
            PlanCache.Entry entry = fixture.cache.peekForRequest(fixture.key, 10);
            assertTrue(fixture.confirm(999).rejected());
            assertTrue(fixture.confirm(10).rejected(), "材料复验失败必须拒绝，并保留原计划等待恢复");
            assertSame(entry, fixture.cache.peekForRequest(fixture.key, 10));
            validator.when(() -> PlanningStateValidator.revalidateForExecution(fixture.player,
                    entry.snapshot(), entry.plan(), Level.OVERWORLD, BlockPos.ZERO, null)).thenReturn(true);
            assertSame(entry, fixture.confirm(10).entry());
        } finally { fixture.cache.removePlayer(fixture.player.getUUID()); }
    }

    private static final class Fixture {
        final ServerPlayer player = mock(ServerPlayer.class);
        final PlanCache cache = CraftPlanningRuntime.planCache();
        final ResourceLocation recipe = new ResourceLocation("test", "target");
        final PlanCache.Key key;

        Fixture() {
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            ServerLevel level = mock(ServerLevel.class);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(player.serverLevel()).thenReturn(level);
            when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            key = CraftPlanCacheKeys.key(player.getUUID(), recipe, Map.of(), 1, null, null);
        }

        void retain(long request, long generation) {
            var snapshot = new PlanningSnapshot(player.getUUID(), generation, key.recipeRevision(), recipe,
                    Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()), "", "", false);
            var plan = new PlanResponse(true, "target", new ItemStack(Items.DIAMOND),
                    List.of(), Map.of(), List.of(), recipe.toString());
            cache.put(key, plan, snapshot, System.nanoTime());
            cache.retainForRequest(player.getUUID(), request, snapshot);
        }

        GenericCraftPacket.ExecutionPlanLookup confirm(long request) {
            return GenericCraftPacket.findValidatedExecutionPlan(player, recipe, Map.of(), 1,
                    new ItemStack(Items.DIAMOND), null, null, null, null, Map.of(), request);
        }
    }
}
