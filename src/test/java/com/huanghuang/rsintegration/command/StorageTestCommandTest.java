package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageTestCommandTest extends BootstrapTest {
    @Test
    void insertsLongAmountsThroughNativeIntSizedStacks() {
        StorageSession session = mock(StorageSession.class);
        ServerPlayer player = mock(ServerPlayer.class);
        long requested = Integer.MAX_VALUE + 100L;
        List<Integer> chunks = new ArrayList<>();
        when(session.insert(eq(player), any(ItemStack.class), eq(false)))
                .thenAnswer(invocation -> {
                    ItemStack input = invocation.getArgument(1);
                    chunks.add(input.getCount());
                    return StorageOperationResult.inserted(
                            StorageOperationMode.PERFORM, input, ItemStack.EMPTY);
                });

        long accepted = StorageTestCommand.insertLong(
                session, player, new ItemStack(Items.STONE), requested);

        assertEquals(requested, accepted);
        assertEquals(List.of(Integer.MAX_VALUE, 100), chunks);
    }
}
