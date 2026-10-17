package com.huanghuang.rsintegration.magic;

import com.Polarice3.Goety.utils.SEHelper;
import com.huanghuang.rsintegration.network.binding.AltarBinding;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.RSAltarBindingResolver;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** 仅在 RS 存在时加载的方舟反向补货实现。 */
public final class GoetySoulRefillRs {
    private GoetySoulRefillRs() {}

    public static int refill(ServerPlayer player, int requested) {
        if (player == null || requested <= 0 || !SEHelper.getSEActive(player)) return 0;
        int before = Math.max(0, SEHelper.getSESouls(player));
        int room = Math.max(0, GoetySoulOverflow.maxArcaSouls() - before);
        int wanted = Math.min(requested, room);
        if (wanted <= 0) return 0;

        BlockPos pos = SEHelper.getArcaBlock(player);
        ResourceKey<Level> dim = SEHelper.getArcaDimension(player);
        if (pos == null || dim == null) return 0;
        AltarBinding binding = AltarBindingRegistry
                .getBinding(player, dim, pos, AltarBinding.RS_NETWORK).orElse(null);
        if (binding == null) return 0;
        INetwork network = RSAltarBindingResolver.resolveNetworkForBinding(player, binding);
        if (network == null) return 0;

        int extracted = MagicResourceEndpoint.extract(
                network, MagicResourceKind.SOUL, wanted, true);
        if (extracted <= 0) return 0;

        int accepted = 0;
        if (SEHelper.increaseSESouls(player, extracted)) {
            accepted = Math.max(0, SEHelper.getSESouls(player) - before);
            if (accepted > 0) SEHelper.sendSEUpdatePacket(player);
        }
        if (accepted < extracted) {
            GoetySoulOverflowRs.insert(player, dim, pos, extracted - accepted);
        }
        return accepted;
    }
}
