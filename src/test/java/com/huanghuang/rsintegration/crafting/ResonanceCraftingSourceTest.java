package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.feature.resonance.bridge.RSResonanceDiskAccess;
import com.huanghuang.rsintegration.feature.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ResonanceCraftingSourceTest extends BootstrapTest {
    @Test
    void selectedRsNetworkDoesNotExpandToAnotherNetwork() {
        StorageReference reference = new StorageReference(
                new StorageBackendId("refinedstorage"), "v1|minecraft:overworld@1,64,1");
        StorageSession session = mock(StorageSession.class);
        when(session.reference()).thenReturn(reference);
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        when(endpoint.session()).thenReturn(session);
        ServerPlayer player = mock(ServerPlayer.class);
        INetwork networkA = mock(INetwork.class);
        INetwork networkB = mock(INetwork.class);
        ResonanceDiskWrapper viewA = mock(ResonanceDiskWrapper.class);
        ResonanceDiskWrapper viewB = mock(ResonanceDiskWrapper.class);

        try (MockedStatic<RefinedStorageBackend> backend =
                     mockStatic(RefinedStorageBackend.class);
             MockedStatic<RSResonanceDiskAccess> disks =
                     mockStatic(RSResonanceDiskAccess.class)) {
            backend.when(() -> RefinedStorageBackend.resolveNativeNetwork(reference, player))
                    .thenReturn(networkA);
            disks.when(() -> RSResonanceDiskAccess.find(networkA)).thenReturn(viewA);
            disks.when(() -> RSResonanceDiskAccess.find(networkB)).thenReturn(viewB);

            assertEquals(List.of(viewA), ResonanceCraftingSource.viewsFor(endpoint, player));

            disks.verify(() -> RSResonanceDiskAccess.find(networkA));
            disks.verify(() -> RSResonanceDiskAccess.find(networkB), never());
        }
    }
}
