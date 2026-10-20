package com.huanghuang.rsintegration.api.magic;

import com.huanghuang.rsintegration.magic.GoetySoulStackKey;
import com.huanghuang.rsintegration.magic.MagicResourceEndpoint;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.StackKeyRegistry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PublicMagicApiContractTest {
    @Test
    void rsApiUsesTheCanonicalMagicResourceEndpoint() {
        INetwork network = mock(INetwork.class);
        MagicResourceNetworkPool reservation = mock(MagicResourceNetworkPool.class);

        try (MockedStatic<MagicResourceEndpoint> endpoint =
                     mockStatic(MagicResourceEndpoint.class)) {
            endpoint.when(() -> MagicResourceEndpoint.total(network, MagicResourceKind.SOUL))
                    .thenReturn(120L);
            endpoint.when(() -> MagicResourceEndpoint.available(network, MagicResourceKind.SOUL))
                    .thenReturn(80L);
            endpoint.when(() -> MagicResourceEndpoint.insert(
                            network, MagicResourceKind.SOUL, 40, false))
                    .thenReturn(40L);
            endpoint.when(() -> MagicResourceEndpoint.extract(
                            network, MagicResourceKind.SOUL, 20, true))
                    .thenReturn(20L);
            endpoint.when(() -> MagicResourceEndpoint.reserve(
                            network, MagicResourceKind.SOUL, 60))
                    .thenReturn(reservation);

            RsiMagicApi.Session session = RsiMagicApi.network(network);
            assertEquals(120, session.amount(MagicResourceKind.SOUL));
            assertEquals(80, session.available(MagicResourceKind.SOUL));
            assertEquals(40, session.insert(MagicResourceKind.SOUL, 40, true));
            assertEquals(20, session.extract(MagicResourceKind.SOUL, 20, false));
            assertSame(reservation, session.reserve(MagicResourceKind.SOUL, 60));
        }
    }

    @Test
    void bdApiCanonicalizesRegisteredAliasesWithoutMergingRsStorage() {
        try {
            StackKeyRegistry.getType(GoetySoulStackKey.ID);
        } catch (IllegalArgumentException absent) {
            StackKeyRegistry.registerType(GoetySoulStackKey.INSTANCE);
        }

        ResourceLocation alias = new ResourceLocation("examplemod", "soul");
        IStackKey<?> aliasKey = mock(IStackKey.class);
        when(aliasKey.getTypeId()).thenReturn(alias);
        BdMagicApi.registerAlias(alias, BdMagicApi.SOUL_KEY);

        assertSame(GoetySoulStackKey.INSTANCE, BdMagicApi.canonicalKey(aliasKey));
        assertEquals(BdMagicApi.SOUL_KEY, GoetySoulStackKey.INSTANCE.getTypeId());
    }
}
