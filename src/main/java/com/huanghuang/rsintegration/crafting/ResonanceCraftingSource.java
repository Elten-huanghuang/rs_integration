package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.resonance.bridge.RSResonanceDiskAccess;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Selects resonance storage attached to the same backend used by a craft. */
public final class ResonanceCraftingSource {

    private ResonanceCraftingSource() {}

    public static List<ResonanceStorageView> viewsFor(
            @Nullable CraftStorageEndpoint endpoint, ServerPlayer player) {
        if (endpoint == null || player == null) return List.of();
        if (endpoint instanceof LegacyRsCraftStorageEndpoint legacy) {
            ResonanceStorageView view = RSResonanceDiskAccess.find(legacy.network());
            return view == null ? List.of() : List.of(view);
        }
        String backendId;
        try {
            backendId = endpoint.session().reference().backendId().value();
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
