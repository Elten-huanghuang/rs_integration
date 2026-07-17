package com.huanghuang.rsintegration.mods.apotheosis.network;

import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryService;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryService.LevelAction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Requests one explicit adjacent Apotheosis library level. */
public final class ApotheosisLibraryLevelPacket {
    private final ResourceLocation dimension;
    private final BlockPos pos;
    private final ResourceLocation enchantmentId;
    private final LevelAction action;

    public ApotheosisLibraryLevelPacket(ResourceLocation dimension, BlockPos pos,
                                        ResourceLocation enchantmentId, LevelAction action) {
        this.dimension = dimension;
        this.pos = pos.immutable();
        this.enchantmentId = enchantmentId;
        this.action = action;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        buf.writeResourceLocation(enchantmentId);
        buf.writeEnum(action);
    }

    public static ApotheosisLibraryLevelPacket decode(FriendlyByteBuf buf) {
        return new ApotheosisLibraryLevelPacket(buf.readResourceLocation(), buf.readBlockPos(),
                buf.readResourceLocation(), buf.readEnum(LevelAction.class));
    }

    public static void handle(ApotheosisLibraryLevelPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player != null) {
                ApotheosisLibraryService.changeLevel(player, packet.dimension, packet.pos,
                        packet.enchantmentId, packet.action);
            }
        });
        context.setPacketHandled(true);
    }
}
