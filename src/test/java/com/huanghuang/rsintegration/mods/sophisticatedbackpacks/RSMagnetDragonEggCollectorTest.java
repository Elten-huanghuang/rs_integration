package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RSMagnetDragonEggCollectorTest extends BootstrapTest {
    private static final BlockPos POS = new BlockPos(3, 64, -2);
    private ServerLevel level;
    private ServerPlayer player;
    private ContentsFilterLogic filter;

    @BeforeEach
    void setUp() {
        level = mock(ServerLevel.class);
        player = mock(ServerPlayer.class);
        filter = mock(ContentsFilterLogic.class);
        when(player.serverLevel()).thenReturn(level);
        when(player.mayBuild()).thenReturn(true);
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(filter.matchesFilter(argThat(stack -> stack.is(Items.DRAGON_EGG)))).thenReturn(true);
        when(level.getGameRules()).thenReturn(new GameRules());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.hasChunkAt(POS)).thenReturn(true);
        when(level.getBlockState(POS)).thenReturn(Blocks.DRAGON_EGG.defaultBlockState());
        when(level.mayInteract(player, POS)).thenReturn(true);
    }

    @Test
    void dragonEggSwitchIsRegisteredInServerConfig() {
        var value = RSIntegrationConfig.ENABLE_MAGNET_DRAGON_EGG;
        assertEquals(List.of("integrations", "enableMagnetDragonEgg"), value.getPath());
        assertTrue(value.getDefault());
    }

    @Test
    void successfulConversionDropsThroughVanillaExactlyOnce() {
        var egg = Blocks.DRAGON_EGG.defaultBlockState();
        when(level.destroyBlock(POS, false, player)).thenReturn(true);
        var collector = new RSMagnetDragonEggCollector(event -> {
            assertEquals(POS, event.getPos());
            assertSame(player, event.getPlayer());
            return false;
        });
        try (var block = mockStatic(Block.class)) {
            collector.collect(level, POS, 0, player, filter);
            verify(level).destroyBlock(POS, false, player);
            block.verify(() -> Block.dropResources(egg, level, POS, null, player, ItemStack.EMPTY));
        }
    }

    @Test
    void protectionCanCancelBeforeWorldChanges() {
        new RSMagnetDragonEggCollector(event -> true).collect(level, POS, 0, player, filter);
        verify(level, never()).destroyBlock(any(), anyBoolean(), any());
    }

    @Test
    void failedRemovalDoesNotDropAnEgg() {
        try (var block = mockStatic(Block.class)) {
            new RSMagnetDragonEggCollector(event -> false).collect(level, POS, 0, player, filter);
            verify(level).destroyBlock(POS, false, player);
            block.verifyNoInteractions();
        }
    }

    @Test
    void rejectedFilterDoesNotScanBlocks() {
        filter = mock(ContentsFilterLogic.class);
        new RSMagnetDragonEggCollector(event -> fail("过滤器拒绝龙蛋时不应发送破坏事件"))
                .collect(level, POS, 2, player, filter);
        verify(level, never()).getBlockState(any());
    }
}
