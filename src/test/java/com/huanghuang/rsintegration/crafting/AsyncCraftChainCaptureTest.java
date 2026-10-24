package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncCraftChainCaptureTest extends BootstrapTest {
    @BeforeAll
    static void initializeRuntime() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @Test
    void incrementalFlatCaptureRenewsTheIdleTimeoutUntilTheWholeBatchArrives() throws Exception {
        Fixture fixture = fixture();
        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        for (int count = 1; count < 3; count++) {
            set(fixture.chain, "waitTicks", timeoutTicks);
            fixture.captured.add(new ItemStack(Items.DIAMOND));

            assertFalse(fixture.chain.tick());
            assertEquals(AsyncCraftChain.State.EXECUTING, fixture.chain.state());
            assertEquals(0, field(fixture.chain, "waitTicks"));
            verify(fixture.delegate, never()).onBatchFinished(any());
            verify(fixture.capture, never()).drainAndClose();
        }
        assertFalse(fixture.chain.tick());
        assertEquals(1, field(fixture.chain, "waitTicks"));

        fixture.captured.add(new ItemStack(Items.DIAMOND));
        assertFalse(fixture.chain.tick());
        verify(fixture.delegate).onBatchFinished(fixture.player);
        verify(fixture.capture).drainAndClose();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void shutdownSettlesOnlyACompleteBatchAndPreservesPartialCapturedOutputs(
            int capturedCount) throws Exception {
        Fixture fixture = fixture();
        fixture.captured.add(new ItemStack(Items.DIAMOND, capturedCount));

        fixture.chain.abortForServerStop();
        fixture.chain.abortForServerStop();

        assertEquals(AsyncCraftChain.State.ABORTED, fixture.chain.state());
        if (capturedCount == 3) {
            verify(fixture.delegate).onBatchFinished(fixture.player);
            verify(fixture.delegate, never()).onBatchFailed(any(), anyString());
        } else {
            verify(fixture.delegate, never()).onBatchFinished(any());
            verify(fixture.delegate).onBatchFailed(isNull(), eq("Server stopping"));
        }
        assertEquals(capturedCount, fixture.returned.stream().mapToInt(ItemStack::getCount).sum());
        verify(fixture.capture).drainAndClose();
    }

    private static Fixture fixture() throws Exception {
        UUID owner = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(server.getPlayerList()).thenReturn(players);
        when(server.overworld()).thenReturn(level);
        when(players.getPlayer(owner)).thenReturn(player);
        when(player.serverLevel()).thenReturn(level);
        when(player.getServer()).thenReturn(server);

        List<ItemStack> returned = new ArrayList<>();
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        when(endpoint.isAvailable()).thenReturn(true);
        when(endpoint.insert(eq(player), any(), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            returned.add(stack.copy());
            return StorageOperationResult.inserted(
                    StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
        });

        List<ItemStack> captured = new ArrayList<>();
        CraftOutputInterceptor.CaptureHandle capture = mock(CraftOutputInterceptor.CaptureHandle.class);
        when(capture.hasCaptured()).thenAnswer(call -> !captured.isEmpty());
        when(capture.snapshot()).thenAnswer(call -> List.copyOf(captured));
        when(capture.drainAndClose()).thenAnswer(call -> {
            List<ItemStack> drained = List.copyOf(captured);
            captured.clear();
            return drained;
        });
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.observeCraft(level)).thenReturn(
                new IBatchDelegate.CraftObservation(IBatchDelegate.CraftPhase.WORKING));
        when(delegate.getExpectedOutput()).thenReturn(new ItemStack(Items.DIAMOND));
        when(delegate.getExpectedProduction()).thenReturn(
                new IBatchDelegate.ExpectedProduction(new ItemStack(Items.DIAMOND), 3));
        when(delegate.validateExecutionContext(player)).thenReturn(true);
        when(delegate.collectAllResults(player)).thenReturn(List.of());
        when(delegate.collectsPhysicalSecondaryOutputs()).thenReturn(true);
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "batch"), ModType.GENERIC,
                new ResourceLocation("test", "batch"), List.of(), List.of(), false, 3);
        AsyncCraftChain chain = new AsyncCraftChain(owner, server, endpoint, List.of(step));
        set(chain, "state", AsyncCraftChain.State.EXECUTING);
        set(chain, "stepRemaining", 3);
        set(chain, "currentDelegate", delegate);
        set(chain, "captureHandle", capture);
        return new Fixture(chain, delegate, player, capture, captured, returned);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(AsyncCraftChain chain, IBatchDelegate delegate, ServerPlayer player,
                           CraftOutputInterceptor.CaptureHandle capture,
                           List<ItemStack> captured, List<ItemStack> returned) {}
}
