package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedDiskRecoveryCommandsTest extends BootstrapTest {
    private CommandDispatcher<CommandSourceStack> dispatcher() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        RegisterCommandsEvent event = mock(RegisterCommandsEvent.class);
        when(event.getDispatcher()).thenReturn(dispatcher); UnifiedDiskEvents.commands(event);
        return dispatcher;
    }

    @Test void listAndRecoveryRequireAdministratorPermission() throws Exception {
        CommandSourceStack player = mock(CommandSourceStack.class);
        var dispatcher = dispatcher();
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("rsi_unified_disk list", player));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("rsi_unified_disk recover " + UUID.randomUUID(), player));
        verify(player, never()).getLevel(); verify(player, never()).getPlayerOrException();
    }

    @Test void listUsesPaginationAndNeverLoadsInventoryOrOverflowsLargePageNumber() throws Exception {
        CommandSourceStack admin = mock(CommandSourceStack.class); when(admin.hasPermission(2)).thenReturn(true);
        ServerLevel level = mock(ServerLevel.class); when(admin.getLevel()).thenReturn(level);
        UnifiedDiskManager manager = mock(UnifiedDiskManager.class);
        List<UUID> ids = IntStream.range(0, 21).mapToObj(i -> new UUID(0, i)).toList();
        when(manager.savedDiskIds()).thenReturn(ids);
        try (var staticManager = mockStatic(UnifiedDiskManager.class)) {
            staticManager.when(() -> UnifiedDiskManager.get(level)).thenReturn(manager);
            var dispatcher = dispatcher();
            assertEquals(20, dispatcher.execute("rsi_unified_disk list", admin));
            assertEquals(1, dispatcher.execute("rsi_unified_disk list 2", admin));
            assertEquals(0, dispatcher.execute("rsi_unified_disk list 2147483647", admin));
            verify(manager, never()).entry(any()); verify(manager, never()).restoreIdentity(any(), any());
        }
    }
}
