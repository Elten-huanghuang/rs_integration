package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.mods.botania.BotaniaResonantManaPoolBlock;
import org.junit.jupiter.api.Test;
import vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity;
import vazkii.botania.common.block.mana.ManaPoolBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotaniaResonantManaPoolContractTest {
    @Test
    void resonantPoolEntersBotaniaNativeTypeChain() {
        assertTrue(ManaPoolBlock.class.isAssignableFrom(
                BotaniaResonantManaPoolBlock.class));
        assertTrue(ManaPoolBlockEntity.class.isAssignableFrom(
                BotaniaResonantManaPoolBlockEntity.class));
    }

    @Test
    void nativeTypeChainReplacesFeatureSpecificPortalMixins() throws IOException {
        String mixins = Files.readString(
                Path.of("src/main/resources/rs_integration.mixins.json"));
        assertFalse(mixins.contains("AlfheimPortalBlockEntityMixin"));
        assertFalse(mixins.contains("PylonBlockEntityAccessor"));
    }

    @Test
    void signedManaCallsClampIntoRsInventory() {
        assertEquals(40, ManaPoolManaMath.insertRequest(40, 100, 1_000));
        assertEquals(60, ManaPoolManaMath.extractRequest(-60, 100));
        assertEquals(100, ManaPoolManaMath.extractRequest(Integer.MIN_VALUE, 100));
    }
}
