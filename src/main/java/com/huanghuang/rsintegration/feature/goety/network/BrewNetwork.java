package com.huanghuang.rsintegration.feature.goety.network;

import com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;

public final class BrewNetwork {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("rs_integration", "brewing"), () -> "3", "3"::equals, "3"::equals);
    private BrewNetwork() {}
    public static void register() {
        CHANNEL.messageBuilder(Edit.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Edit::write).decoder(Edit::read).consumerMainThread((msg, context) -> {
                    var player = context.get().getSender();
                    if (player != null && player.containerMenu instanceof WitchCauldronMenu menu
                            && menu.containerId == msg.menuId && menu.stillValid(player)) menu.handle(player, msg);
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(State.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> { buf.writeVarInt(msg.menuId); buf.writeNbt(msg.data); })
                .decoder(buf -> new State(buf.readVarInt(), buf.readNbt()))
                .consumerMainThread((msg, context) -> {
                    com.huanghuang.rsintegration.feature.goety.client.GoetyRsiClient.accept(msg);
                    context.get().setPacketHandled(true);
                }).add();
    }
    public static void sync(ServerPlayer player, int menuId, CompoundTag data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new State(menuId, data));
    }
    public record State(int menuId, CompoundTag data) {}
    public record Edit(int menuId, int revision, int capacity, List<ResourceLocation> selected, String action) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeVarInt(revision); buf.writeVarInt(capacity);
            buf.writeVarInt(selected.size()); selected.forEach(buf::writeResourceLocation); buf.writeUtf(action, 24);
        }
        static Edit read(FriendlyByteBuf buf) {
            int menu = buf.readVarInt(), revision = buf.readVarInt(), capacity = buf.readVarInt(), count = buf.readVarInt();
            if (count < 0 || count > 64) throw new IllegalArgumentException("Too many brew selections");
            var selected = new ArrayList<ResourceLocation>();
            for (int i = 0; i < count; i++) selected.add(buf.readResourceLocation());
            return new Edit(menu, revision, capacity, List.copyOf(selected), buf.readUtf(24));
        }
    }
}
