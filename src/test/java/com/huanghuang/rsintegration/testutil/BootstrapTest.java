package com.huanghuang.rsintegration.testutil;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

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
    private static boolean forgeCapabilitiesInitialized;

    @BeforeAll
    static void bootstrapMinecraft() {
        // Version metadata must be set before Bootstrap touches registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        initializeForgeCapabilities();
    }

    /**
     * 单测没有 Forge 转换器，用模拟令牌注册一次 {@link ForgeCapabilities}。
     *
     * <p>该类的静态初始化失败后会被 JVM 永久缓存，因此测试必须在加载
     * {@code RSIntegrationMod} 或其他会间接接触能力令牌的生产类之前初始化它。</p>
     */
    protected static synchronized void initializeForgeCapabilities() {
        if (forgeCapabilitiesInitialized) return;
        try (MockedStatic<CapabilityManager> manager = mockStatic(CapabilityManager.class)) {
            manager.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(Capability.class));
            Class.forName(ForgeCapabilities.class.getName());
            forgeCapabilitiesInitialized = true;
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("无法初始化测试用 ForgeCapabilities", exception);
        }
    }
}
