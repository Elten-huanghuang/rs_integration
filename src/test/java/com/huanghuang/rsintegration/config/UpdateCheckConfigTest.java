package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.client.UpdateCheckClient;
import net.minecraftforge.event.TickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckConfigTest {
    @Test
    void existingClientConfigGetsEnabledDefault() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(config);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(config);
        assertTrue(RSIntegrationConfig.ENABLE_UPDATE_CHECK.get());
    }

    @Test
    void disabledConfigIsPreservedAndTickNeedsNoClientOrModList() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("updates.enabled", false);
        RSIntegrationConfig.CLIENT_SPEC.correct(config);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(config);
        assertFalse(RSIntegrationConfig.ENABLE_UPDATE_CHECK.get());
        // 禁用时应在读取客户端和模组信息、启动网络请求之前返回。
        assertDoesNotThrow(() -> UpdateCheckClient.onClientTick(
                new TickEvent.ClientTickEvent(TickEvent.Phase.END)));
    }

    @AfterEach
    void clearConfig() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
    }
}
