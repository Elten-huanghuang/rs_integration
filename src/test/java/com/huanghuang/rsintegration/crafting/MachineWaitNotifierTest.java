package com.huanghuang.rsintegration.crafting;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MachineWaitNotifierTest {
    @Test
    void wholeCraftSendsOnlyTheFirstActionableWaitMessage() {
        MachineWaitNotifier notifier = new MachineWaitNotifier();
        ServerPlayer player = mock(ServerPlayer.class);
        Component reason = Component.translatable(
                "rsi.wr.info.waiting_wissen", "615", "1,000");

        notifier.notifyWait(player, null);
        notifier.notifyWait(player, reason);
        notifier.notifyWait(player, reason);
        notifier.notifyWait(player, Component.translatable(
                "rsi.tlm.info.waiting_power", "1", "2"));

        verify(player, times(1)).sendSystemMessage(any(Component.class));
    }
}
