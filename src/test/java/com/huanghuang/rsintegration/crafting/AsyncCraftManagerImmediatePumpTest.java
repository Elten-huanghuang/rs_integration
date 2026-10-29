package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncCraftManagerImmediatePumpTest extends BootstrapTest {

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
    void submitAndAdvanceTicksNewChainBeforeReturning() {
        AsyncCraftManager manager = AsyncCraftManager.getInstance();
        AsyncCraftChain chain = mock(AsyncCraftChain.class);
        MinecraftServer server = mock(MinecraftServer.class);
        UUID craftId = UUID.randomUUID();
        AtomicBoolean done = new AtomicBoolean();

        when(server.getTickCount()).thenReturn(123456);
        when(chain.getCraftId()).thenReturn(craftId);
        when(chain.getPlayerId()).thenReturn(UUID.randomUUID());
        when(chain.stepsCount()).thenReturn(2);
        when(chain.isDone()).thenAnswer(ignored -> done.get());
        when(chain.canAdvanceImmediately(anyBoolean()))
                .thenAnswer(ignored -> !done.get());
        doAnswer(ignored -> {
            done.set(true);
            return true;
        }).when(chain).tick(any(VanillaCraftingTickBudget.ChainAllowance.class),
                any(BooleanSupplier.class));

        assertTrue(manager.submitAndAdvance(chain, server));
        verify(chain).tick(any(VanillaCraftingTickBudget.ChainAllowance.class),
                any(BooleanSupplier.class));
        assertNull(manager.getCraft(craftId));
    }
}
