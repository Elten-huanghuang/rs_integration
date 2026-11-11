package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MonitorScopeStateTest {
    private final StorageReference network = new StorageReference(new StorageBackendId("refinedstorage"), "network-a");

    @Test void unchangedOrdersDoNotPublishAgainAndDisconnectRevokesActions() {
        var state = new MonitorScopeState();
        UUID craft = UUID.randomUUID();
        var initial = state.update(network, List.of(craft));
        assertNotNull(initial);
        assertNull(state.update(network, List.of(craft)));
        assertTrue(state.allows(initial.session(), craft, network));
        var offline = state.update(null, List.of());
        assertEquals(initial.session(), offline.session());
        assertEquals(initial.revision() + 1, offline.revision());
        assertFalse(state.allows(initial.session(), craft, network));
        assertFalse(state.allows(initial.session(), craft, null));
    }

    @Test void olderAndOtherMenuPacketsCannotRestoreRevokedOrders() {
        var server = new MonitorScopeState();
        var client = new MonitorScopeState();
        UUID craft = UUID.randomUUID();
        var first = server.update(network, List.of(craft));
        assertTrue(client.accept(first));
        var next = server.update(network, List.of());
        assertTrue(client.accept(next));
        assertFalse(client.accept(first));
        assertFalse(client.accept(next));
        assertFalse(client.accept(new MonitorScopeState.Scope(UUID.randomUUID(), 99, network, List.of(craft))));
        assertTrue(client.current().crafts().isEmpty());
    }

    @Test void anotherNetworkOrBackendCannotAuthorizeTheSameCraftId() {
        var state = new MonitorScopeState();
        UUID craft = UUID.randomUUID();
        var scope = state.update(network, List.of(craft));
        assertFalse(state.allows(scope.session(), UUID.randomUUID(), network));
        assertFalse(state.allows(UUID.randomUUID(), craft, network));
        assertFalse(state.allows(scope.session(), craft,
                new StorageReference(network.backendId(), "network-b")));
        assertFalse(state.allows(scope.session(), craft,
                new StorageReference(new StorageBackendId("beyonddimensions"), "network-a")));
    }

    @Test void scopeOwnsItsListAndRejectsInvalidMembership() {
        var ids = new ArrayList<UUID>();
        UUID craft = UUID.randomUUID();
        ids.add(craft);
        var scope = new MonitorScopeState.Scope(UUID.randomUUID(), 1, network, ids);
        ids.clear();
        assertEquals(List.of(craft), scope.crafts());
        assertThrows(IllegalArgumentException.class, () ->
                new MonitorScopeState.Scope(UUID.randomUUID(), 0, network, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new MonitorScopeState.Scope(UUID.randomUUID(), 1, null, List.of(craft)));
        assertThrows(IllegalArgumentException.class, () ->
                new MonitorScopeState.Scope(UUID.randomUUID(), 1, network, List.of(craft, craft)));
    }
}
