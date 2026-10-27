package com.huanghuang.rsintegration.feature.resonance.api;

import com.huanghuang.rsintegration.api.ApiVersion;
import com.huanghuang.rsintegration.api.resonance.ResonanceDiagnostic;
import com.huanghuang.rsintegration.api.resonance.ResonanceDiagnosticCode;
import com.huanghuang.rsintegration.api.resonance.ResonanceInventory;
import com.huanghuang.rsintegration.api.resonance.ResonanceResult;
import com.huanghuang.rsintegration.api.resonance.RSIResonanceApi;
import com.huanghuang.rsintegration.feature.resonance.bridge.ResonanceInventoryBridge;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

@ApiStatus.Internal
public final class DefaultRSIResonanceApi implements RSIResonanceApi {
    private static final ApiVersion VERSION =
            new ApiVersion(API_MAJOR, API_MINOR, 0);

    @Override
    public ApiVersion version() {
        return VERSION;
    }

    @Override
    public ResonanceResult<ResonanceInventory> openInventory(
            ServerPlayer player
    ) {
        if (player == null || player.isRemoved()) {
            return rejected(
                    ResonanceDiagnosticCode.PLAYER_UNAVAILABLE,
                    "player_is_unavailable",
                    false
            );
        }
        if (player.server == null || !player.server.isSameThread()) {
            return rejected(
                    ResonanceDiagnosticCode.MAIN_THREAD_REQUIRED,
                    "resonance_inventory_must_be_opened_on_the_server_thread",
                    true
            );
        }
        try {
            List<ResonanceStorageView> views =
                    ResonanceInventoryBridge.getViews(player);
            if (views.isEmpty()) {
                return rejected(
                        ResonanceDiagnosticCode.STORAGE_UNAVAILABLE,
                        "no_resonance_disk_is_available_for_player",
                        true
                );
            }
            return ResonanceResult.success(
                    new DefaultResonanceInventory(player, views));
        } catch (RuntimeException | LinkageError failure) {
            return rejected(
                    ResonanceDiagnosticCode.INTERNAL_ERROR,
                    "resonance_inventory_snapshot_failed",
                    true
            );
        }
    }

    private static <T> ResonanceResult<T> rejected(
            ResonanceDiagnosticCode code,
            String detail,
            boolean retryable
    ) {
        return ResonanceResult.rejected(
                ResonanceDiagnostic.error(code, detail, retryable));
    }
}
