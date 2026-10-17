package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.network.binding.AltarBinding;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.RSAltarBindingResolver;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** 仅在 RS 已加载时加载的 Arca 灵魂溢出写入器。 */
public final class GoetySoulOverflowRs {
    private GoetySoulOverflowRs() {}

    public static int insert(ServerPlayer player, ResourceKey<Level> dim,
                             BlockPos pos, int amount) {
        AltarBinding binding = AltarBindingRegistry
                .getBinding(player, dim, pos, AltarBinding.RS_NETWORK).orElse(null);
        if (binding == null) return 0;
        INetwork network = RSAltarBindingResolver.resolveNetworkForBinding(player, binding);
        if (network == null) return 0;
        UnifiedDiskManager manager = UnifiedDiskManager.existing(player.server);
        if (manager == null) return 0;

        int moved = MagicDiskStore.insert(MagicDiskStore.mountedDisks(manager),
                MagicResourceKind.SOUL, amount, true);
        if (moved < amount) {
            RSIntegrationMod.LOGGER.warn("[RSI] Arca 灵魂溢出未全部写入归墟盘：装入 {} / {}", moved, amount);
        }
        return moved;
    }
}
