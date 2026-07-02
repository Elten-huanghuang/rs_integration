package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import com.huanghuang.rsintegration.util.ChunkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: request to re-open the RS Grid terminal after closing
 * a machine GUI.  This replaces the old (broken) Screen-caching approach
 * — instead of restoring a dead ContainerScreen, we ask the server to
 * legally open the grid through the normal Forge pipeline.
 */
public final class ReturnToRSPacket {

    private final ResourceLocation dim;
    private final BlockPos pos;

    public ReturnToRSPacket(ResourceLocation dim, BlockPos pos) {
        this.dim = dim;
        this.pos = pos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
    }

    public static ReturnToRSPacket decode(FriendlyByteBuf buf) {
        return new ReturnToRSPacket(buf.readResourceLocation(), buf.readBlockPos());
    }

    public static void handle(ReturnToRSPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) return;

            ResourceKey<Level> dimKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, packet.dim);

            var server = player.getServer();
            if (server == null) return;
            var level = server.getLevel(dimKey);
            if (level == null) return;

            if (!level.hasChunkAt(packet.pos)) {
                ChunkUtils.loadChunk(level, packet.pos);
                if (!level.hasChunkAt(packet.pos)) return;
            }

            BlockEntity be = level.getBlockEntity(packet.pos);
            if (!(be instanceof MenuProvider provider)) return;

            String blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getKey(level.getBlockState(packet.pos).getBlock()).toString();
            RemoteGuiAuth.authorize(player, dimKey, packet.pos, blockId);
            net.minecraftforge.network.NetworkHooks.openScreen(player, provider, packet.pos);
        });
        ctx.get().setPacketHandled(true);
    }
}
