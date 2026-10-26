package com.huanghuang.rsintegration.api;

import com.example.rsiintegration.PublicIntegrationExample;
import com.example.rsiintegration.PublicIntegrationExample.RegisteredComponents;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PublicIntegrationExampleRuntimeTest extends BootstrapTest {

    @Test
    void publicExampleRegistersThroughTheForgeEvent() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        api.beginRegistration();
        IEventBus bus = BusBuilder.builder().build();
        AtomicReference<RegisteredComponents> registered =
                new AtomicReference<>();
        AtomicBoolean delivered = new AtomicBoolean();
        bus.addListener((RSIIntegrationRegistrationEvent event) -> {
            delivered.set(true);
            registered.set(PublicIntegrationExample.register(event));
        });

        bus.post(new RSIIntegrationRegistrationEvent(api));

        RegisteredComponents components = registered.get();
        assertTrue(delivered.get());
        assertTrue(components.recipeResult().accepted(),
                () -> components.recipeResult().diagnostics().toString());
        assertClaims(api, components);
    }

    private static void assertClaims(
            RSIIntegrationApi api,
            RegisteredComponents registered
    ) {
        Recipe<?> recipe = mock(Recipe.class);
        BlockEntity blockEntity = mock(BlockEntity.class);
        assertSame(registered.recipe(),
                api.recipes().claim(recipe).winner());
        assertSame(registered.machine(),
                api.machines().claim(blockEntity).winner());

        MachineContext machine = new MachineContext(
                registered.machine().id(),
                mock(ServerLevel.class),
                BlockPos.ZERO,
                null
        );
        assertSame(registered.execution(),
                api.executions().claim(
                        PublicIntegrationExample.exampleView(),
                        machine
                ).winner());
        assertEquals(PublicIntegrationExample.PROCESS,
                registered.machine()
                        .describe(machine, blockEntity)
                        .descriptor()
                        .acceptedProcesses()
                        .iterator()
                        .next());
    }
}
