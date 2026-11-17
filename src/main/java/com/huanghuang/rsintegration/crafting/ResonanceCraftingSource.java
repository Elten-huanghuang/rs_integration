package com.huanghuang.rsintegration.crafting;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.feature.resonance.bridge.RSResonanceDiskAccess;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.server.level.ServerPlayer;
import com.refinedmods.refinedstorage.api.network.INetwork;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Selects resonance storage attached to the same backend used by a craft. */
public final class ResonanceCraftingSource {

    private ResonanceCraftingSource() {}

    public static List<ResonanceStorageView> viewsFor(
            @Nullable CraftStorageEndpoint endpoint, ServerPlayer player) {
        if (endpoint == null || player == null) return List.of();
        String backendId;
        try {
            StorageReference reference = endpoint.session().reference();
            backendId = reference.backendId().value();
            if ("refinedstorage".equals(backendId)) {
                INetwork network = RefinedStorageBackend.resolveNativeNetwork(reference, player);
                ResonanceStorageView view = RSResonanceDiskAccess.find(network);
                return view == null ? List.of() : List.of(view);
            }
        } catch (RuntimeException exception) {
            return List.of();
        }
        List<ResonanceStorageView> matches = new ArrayList<>();
        for (ResonanceStorageView view : ResonanceStorageResolvers.resolveAll(player)) {
            if (backendId.equals(view.backendId())) matches.add(view);
        }
        return List.copyOf(matches);
    }
}
