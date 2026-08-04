package com.huanghuang.rsintegration.mixin.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class FtbQuestMixinPluginTest {
    @Test
    void skipsClientRefreshMixinWhenFtbQuestsIsAbsent() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();

        assertFalse(plugin.shouldApplyMixin(
                "dev.ftb.mods.ftbquests.client.FTBQuestsNetClient",
                "com.huanghuang.rsintegration.mixin.ftbquests.FTBQuestsNetClientMixin"));
    }
}
