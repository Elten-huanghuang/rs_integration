package com.huanghuang.rsintegration.network.machine.bd;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.gui.BlockGuiRegistry;
import com.huanghuang.rsintegration.network.gui.GuiOpenRateLimiter;
import com.huanghuang.rsintegration.network.gui.MachineInteractionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

/** Minimal backend-neutral GUI open request for a BD-bound machine. */
public final class BeyondDimensionsOpenBoundMachineGuiPacket {
    private final ResourceLocation dim;
    private final BlockPos pos;

    public BeyondDimensionsOpenBoundMachineGuiPacket(ResourceLocation dim, BlockPos pos) {
        this.dim = dim;
        this.pos = pos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
    }

    public static BeyondDimensionsOpenBoundMachineGuiPacket decode(FriendlyByteBuf buf) {
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
        if (!RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()
                || GuiOpenRateLimiter.isRateLimited(player.getUUID())) return;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                packet.dim);
        MachineInteractionAccess.Result access =
                MachineInteractionAccess.resolve(
                        player, packet.dim, packet.pos,
                        RSIntegrationConfig.REQUIRE_MACHINE_BINDING_FOR_GUI.get(),
                        true);
        if (!access.success()) {
            MachineInteractionAccess.sendError(player, access.error());
            return;
        }
        BlockGuiRegistry.openGui(player, dimension, packet.pos);
    }
}
