package com.huanghuang.rsintegration.testutil;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for layer-2 tests that need real {@link net.minecraft.world.item.ItemStack},
 * {@link net.minecraft.world.item.Item}, and NBT — but NOT a running server.
 *
 * <p>{@link Bootstrap#bootStrap()} initialises the vanilla registries (items,
 * blocks, etc.) in-process. It is idempotent and cheap after the first call,
 * so every layer-2 test can extend this safely.</p>
 *
 * <p><b>Scope limit:</b> this only wires vanilla content. It does NOT load Forge,
 * the RS API, or any mod module — anything reaching {@code RSIntegrationMod}
 * (whose RS-API deps are {@code compileOnly}) will fail with
 * {@code NoClassDefFoundError}. Keep layer-2 targets free of that dependency.</p>
 */
public abstract class BootstrapTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // Version metadata must be set before Bootstrap touches registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
