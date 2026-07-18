package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import com.huanghuang.rsintegration.util.ChunkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
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

            // Validate the player is bound to this machine (same gate as all
            // sibling packets to prevent remote container opening from a modified client).
            if (!AltarBindingRegistry.isBound(dimKey, packet.pos, player)) {
                player.sendSystemMessage(
                    Component.translatable("rsi.error.not_bound"));
                return;
            }

            PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                    player, InteractionHand.MAIN_HAND, packet.pos,
                    new BlockHitResult(new Vec3(packet.pos.getX() + 0.5,
                            packet.pos.getY() + 1.0, packet.pos.getZ() + 0.5),
                            Direction.UP, packet.pos, false));
            MinecraftForge.EVENT_BUS.post(event);
            if (event.isCanceled()) {
                player.sendSystemMessage(
                    Component.translatable("rsi.error.protected_block"));
                return;
            }

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
            if (!RemoteGuiAuth.bindOpenedMenu(player)) {
                player.closeContainer();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
