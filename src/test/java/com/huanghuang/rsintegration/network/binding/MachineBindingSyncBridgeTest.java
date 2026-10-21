package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class MachineBindingSyncBridgeTest extends BootstrapTest {
    @AfterEach
    void clearSender() {
        MachineBindingSyncBridge.install(null);
    }

    @Test
    void forwardsOnlyNonNullPlayersToTheInstalledSender() {
        AtomicReference<ServerPlayer> received = new AtomicReference<>();
        MachineBindingSyncBridge.install(received::set);
        ServerPlayer player = mock(ServerPlayer.class);

        MachineBindingSyncBridge.send(player);

        assertSame(player, received.get());
        assertNotNull(received.get());
    }

    @Test
    void nullSenderInstallBecomesANoOp() {
        MachineBindingSyncBridge.install(null);

        AtomicBoolean called = new AtomicBoolean();
        MachineBindingSyncBridge.install(ignored -> called.set(true));
        MachineBindingSyncBridge.send(null);

        assertFalse(called.get());
    }
}
