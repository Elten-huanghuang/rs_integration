package com.huanghuang.rsintegration.api;

import com.example.rsiintegration.PublicIntegrationExample;
import com.example.rsiintegration.PublicIntegrationExample.RegisteredComponents;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PublicIntegrationExampleRuntimeTest extends BootstrapTest {

    @Test
    void publicExampleRegistersThroughApiEntryPoint() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        api.beginRegistration();
        RegisteredComponents components =
                PublicIntegrationExample.register(api);
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
