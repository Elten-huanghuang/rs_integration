package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.gui.BlockGuiRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Minimal backend-neutral GUI open request for a BD-bound machine. */
public final class BeyondDimensionsOpenBoundMachineGuiPacket {
    private final ResourceLocation dim;
    private final BlockPos pos;

    public BeyondDimensionsOpenBoundMachineGuiPacket(ResourceLocation dim, BlockPos pos) {
        this.dim = dim;
        this.pos = pos;
    }

    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
    }

    public static BeyondDimensionsOpenBoundMachineGuiPacket decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new BeyondDimensionsOpenBoundMachineGuiPacket(buf.readResourceLocation(), buf.readBlockPos());
    }

    public static void handle(BeyondDimensionsOpenBoundMachineGuiPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> open(player, packet));
        }
        context.setPacketHandled(true);
    }

    private static void open(ServerPlayer player, BeyondDimensionsOpenBoundMachineGuiPacket packet) {
        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                packet.dim);
        if (!AltarBindingRegistry.isBound(dimension, packet.pos, player)) return;
        var level = player.getServer().getLevel(dimension);
        if (level == null || !level.hasChunkAt(packet.pos)
                || !ProtectionChecker.canInteract(player, level, packet.pos)) return;
        BlockGuiRegistry.openGui(player, dimension, packet.pos);
    }
}
