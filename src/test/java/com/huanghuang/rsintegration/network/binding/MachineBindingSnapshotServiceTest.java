package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.machine.client.BindingSyncChunkAccumulator;
import com.huanghuang.rsintegration.network.binding.BindingSyncCodec.Payload;
import com.huanghuang.rsintegration.network.binding.MachineBindingService.PlayerEndpoint;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.huanghuang.rsintegration.network.binding.MachineBindingSnapshotService.Scope.ALL;
import static com.huanghuang.rsintegration.network.binding.MachineBindingSnapshotService.Scope.BD_TERMINALS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MachineBindingSnapshotServiceTest extends BootstrapTest {
    private static final String RS_GRID = "refinedstorage:grid";
    private static final String BD_TERMINAL = "beyonddimensions:net_terminal_item";
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");

    private final MachineBindingSnapshotService snapshots = new MachineBindingSnapshotService();
    private final ServerPlayer player = mock(ServerPlayer.class);

    MachineBindingSnapshotServiceTest() {
        when(player.getUUID()).thenReturn(UUID.randomUUID());
    }

    @AfterEach
    void clearClientState() {
        BindingSyncChunkAccumulator.clear();
    }

    @Test
    void standaloneSnapshotIncludesOnlyBdTerminalsAndKeepsTheirIdentity() {
        PlayerEndpoint bd = endpoint(BD_TERMINAL, 2);
        List<Payload> sent = new ArrayList<>();
        try (var bindings = mockStatic(MachineBindingService.class)) {
            bindings.when(() -> MachineBindingService.collectPlayerEndpoints(player)).thenReturn(List.of(
                    endpoint(RS_GRID, 1), bd, endpoint("beyonddimensions:other_item", 3)));
            snapshots.send(player, BD_TERMINALS, true, sent::add);
        }

        assertEquals(1, sent.size());
        List<BindingInfo> entries = sent.get(0).bindings();
        assertEquals(List.of(BD_TERMINAL), entries.stream().map(BindingInfo::itemKey).toList());
        assertEquals(bd.bookId(), entries.get(0).bindingBookId());
        assertEquals(bd.endpointId(), entries.get(0).endpointId());
        assertEquals("机器 2", entries.get(0).customName());
    }

    @Test
    void mixedSnapshotReplacesFilteredViewOnlyAfterAllChunksArrive() {
        List<PlayerEndpoint> endpoints = new ArrayList<>();
        endpoints.add(endpoint(BD_TERMINAL, 0));
        for (int index = 1; index <= BindingSyncCodec.CHUNK_SIZE; index++) {
            endpoints.add(endpoint(RS_GRID, index));
        }
        List<Payload> filtered = new ArrayList<>();
        List<Payload> full = new ArrayList<>();
        try (var bindings = mockStatic(MachineBindingService.class)) {
            bindings.when(() -> MachineBindingService.collectPlayerEndpoints(player)).thenReturn(endpoints);
            snapshots.send(player, BD_TERMINALS, true, filtered::add);
            snapshots.send(player, ALL, false, full::add);
        }

        assertEquals(List.of(BD_TERMINAL), accept(filtered.get(0)).stream().map(BindingInfo::itemKey).toList());
        assertEquals(2, full.size());
        assertTrue(full.get(0).generation() > filtered.get(0).generation());
        assertEquals(full.get(0).generation(), full.get(1).generation());
        assertNull(accept(full.get(0)));
        var complete = accept(full.get(1));
        assertEquals(endpoints.stream().map(PlayerEndpoint::endpointId).toList(),
                complete.stream().map(BindingInfo::endpointId).toList());
        assertEquals(BD_TERMINAL, complete.get(0).itemKey());
        assertEquals(RS_GRID, complete.get(1).itemKey());
    }

    @Test
    void changingScopeSendsEmptySnapshotEvenWhenEndpointFingerprintIsUnchanged() {
        List<Payload> sent = new ArrayList<>();
        try (var bindings = mockStatic(MachineBindingService.class)) {
            bindings.when(() -> MachineBindingService.collectPlayerEndpoints(player)).thenReturn(List.of());
            snapshots.send(player, ALL, false, sent::add);
            snapshots.send(player, BD_TERMINALS, false, sent::add);
        }

        assertEquals(2, sent.size());
        assertTrue(sent.get(1).generation() > sent.get(0).generation());
        assertEquals(List.of(), accept(sent.get(1)));
    }

    @Test
    void routineRefreshDeduplicatesButForceRevisionAndLogoutSendAgain() {
        PlayerEndpoint original = endpoint(RS_GRID, 1);
        PlayerEndpoint revised = new PlayerEndpoint(original.bookId(), original.itemKey(),
                original.endpointId(), original.entry(), original.revision() + 1);
        List<Payload> sent = new ArrayList<>();
        try (var bindings = mockStatic(MachineBindingService.class)) {
            bindings.when(() -> MachineBindingService.collectPlayerEndpoints(player)).thenReturn(List.of(original));
            snapshots.send(player, ALL, false, sent::add);
            snapshots.send(player, ALL, false, sent::add);
            assertEquals(1, sent.size());

            snapshots.send(player, ALL, true, sent::add);
            assertEquals(2, sent.size());
            bindings.when(() -> MachineBindingService.collectPlayerEndpoints(player)).thenReturn(List.of(revised));
            snapshots.send(player, ALL, false, sent::add);
            assertEquals(3, sent.size());

            snapshots.forgetPlayer(player.getUUID());
            snapshots.send(player, ALL, false, sent::add);
            assertEquals(4, sent.size());
        }
    }

    @Test
    void failedSendDoesNotSuppressRetry() {
        List<Payload> sent = new ArrayList<>();
        try (var bindings = mockStatic(MachineBindingService.class)) {
            bindings.when(() -> MachineBindingService.collectPlayerEndpoints(player))
                    .thenReturn(List.of(endpoint(RS_GRID, 1)));
            assertThrows(IllegalStateException.class, () -> snapshots.send(player, ALL, false,
                    ignored -> { throw new IllegalStateException("模拟发送失败"); }));
            snapshots.send(player, ALL, false, sent::add);
        }
        assertEquals(1, sent.size());
    }

    private static List<BindingInfo> accept(Payload payload) {
        return BindingSyncChunkAccumulator.accept(
                payload.generation(), payload.chunkIndex(), payload.totalChunks(), payload.bindings());
    }

    private static PlayerEndpoint endpoint(String itemKey, int index) {
        return new PlayerEndpoint(UUID.randomUUID(), ResourceLocation.parse(itemKey), MachineEndpointId.create(),
                new BindingStorage.BindingEntry(OVERWORLD, new BlockPos(index, 64, 0),
                        "block.minecraft.furnace", null, ItemStack.EMPTY, "机器 " + index), 1L);
    }
}
