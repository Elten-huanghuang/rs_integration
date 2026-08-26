package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.network.gui.GuiOpenRateLimiter;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

public final class OpenResonanceBackpackPacket {

    private final boolean preferBeyondDimensions;

    public OpenResonanceBackpackPacket() {
        this(false);
    }

    public OpenResonanceBackpackPacket(boolean preferBeyondDimensions) {
        this.preferBeyondDimensions = preferBeyondDimensions;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(preferBeyondDimensions);
    }

    public static OpenResonanceBackpackPacket decode(FriendlyByteBuf buf) {
        return new OpenResonanceBackpackPacket(buf.readBoolean());
    }

    public static void handle(OpenResonanceBackpackPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) return;

            if (GuiOpenRateLimiter.isRateLimited(player.getUUID())) return;

            ResonanceStorageView view = selectView(player, packet.preferBeyondDimensions);
            if (view != null) {
                open(player, view);
                return;
            }
            player.displayClientMessage(Component.translatable(
                    packet.preferBeyondDimensions
                            ? "rsi.resonance.bd.no_disk"
                            : "rsi.resonance_backpack.no_disk"), true);
        });
        ctx.get().setPacketHandled(true);
    }

    private static ResonanceStorageView selectView(ServerPlayer player,
                                                   boolean preferBeyondDimensions) {
        var views = ResonanceStorageResolvers.resolveAll(player);
        String preferred = preferBeyondDimensions ? "beyonddimensions" : "refinedstorage";
        for (ResonanceStorageView view : views) {
            if (preferred.equals(view.backendId())) return view;
        }
        return preferBeyondDimensions || views.isEmpty() ? null : views.get(0);
    }

    private static void open(ServerPlayer player, ResonanceStorageView view) {
        NetworkHooks.openScreen(player,
                new SimpleMenuProvider(
                        (containerId, inv, p) -> new ResonanceBackpackContainer(
                                containerId, inv, view),
                        Component.translatable("rsi.resonance_backpack.title")),
                buf -> {});
    }
}
