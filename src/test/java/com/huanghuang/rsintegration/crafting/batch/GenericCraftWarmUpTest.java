package com.huanghuang.rsintegration.crafting.batch;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenericCraftWarmUpTest extends BootstrapTest {
    @BeforeAll
    static void configureEnvironment() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @Test
    void executionRequestDuringLoadingQueuesAndReturnsRetryMessageWithoutBlockingBuild() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.serverLevel()).thenReturn(level);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        NetworkEvent.Context context = mock(NetworkEvent.Context.class);
        when(context.getSender()).thenReturn(player);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
        DeferredCraftRequestQueue<Consumer<ServerPlayer>> queue = warmUpQueue();
        queue.clear();
        try (var index = mockStatic(RecipeIndex.class)) {
            GenericCraftPacket packet = new GenericCraftPacket(new ResourceLocation("test", "craft"), false);
            GenericCraftPacket.handle(packet, () -> context);
            assertEquals(1, queue.size());
            index.verify(() -> RecipeIndex.isReady(level));
            index.verify(() -> RecipeIndex.warmUpBlocking(level), never());
            index.verify(() -> RecipeIndex.warmUp(level), never());
            verifyNoInteractions(level);
            ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
            verify(player).sendSystemMessage(message.capture());
            assertEquals("rsi.plan.failure.catalog_loading",
                    ((TranslatableContents) message.getValue().getContents()).getKey());
            verify(context).setPacketHandled(true);
        } finally {
            queue.clear();
        }
    }

    @Test
    void tickCancelsExpiredLoadingExecutionWithoutRunningIt() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        PlayerList players = mock(PlayerList.class);
        UUID playerId = UUID.randomUUID();
        when(server.overworld()).thenReturn(level);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(playerId)).thenReturn(player);
        Consumer<ServerPlayer> action = mock(Consumer.class);
        DeferredCraftRequestQueue<Consumer<ServerPlayer>> queue = warmUpQueue();
        queue.clear();
        queue.offer(new DeferredCraftRequestQueue.Entry<>(playerId, false, 0L, action,
                System.nanoTime() - 31_000_000_000L));
        try (var index = mockStatic(RecipeIndex.class);
             var progress = mockStatic(PlanningProgressServer.class)) {
            GenericCraftPacket.tickWarmUpRequests(server);
            assertEquals(0, queue.size());
            verifyNoInteractions(action);
            ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
            verify(player).sendSystemMessage(message.capture());
            assertEquals("rsi.plan.failure.catalog_wait_timeout",
                    ((TranslatableContents) message.getValue().getContents()).getKey());
            index.verify(() -> RecipeIndex.warmUpBlocking(level), never());
        } finally {
            queue.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static DeferredCraftRequestQueue<Consumer<ServerPlayer>> warmUpQueue() throws Exception {
        Field field = GenericCraftPacket.class.getDeclaredField("WARM_UP_REQUESTS");
        field.setAccessible(true);
        return (DeferredCraftRequestQueue<Consumer<ServerPlayer>>) field.get(null);
    }
}
