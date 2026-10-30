package com.huanghuang.rsintegration.feature.foodorder.network;

import com.huanghuang.rsintegration.feature.foodorder.FoodOrderClient;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderEntry;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderKind;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderSnapshot;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 服务端下发的点餐列表。 */
public final class FoodOrderListPacket {

    private static final int MAX_ENTRIES = 5000;
    private static final int MAX_TEXT = 32767;

    private final long epoch;
    private final List<FoodOrderEntry> entries;
    private final Component sources;
    private final int totalUneaten;
    private final int totalOrderable;
    private final boolean indexing;
    private final boolean partial;

    public FoodOrderListPacket(long epoch, List<FoodOrderEntry> entries,
                               Component sources, int totalUneaten,
                               int totalOrderable, boolean indexing,
                               boolean partial) {
        this.epoch = epoch;
        this.entries = entries == null ? List.of() : List.copyOf(entries);
        this.sources = sources == null ? Component.empty() : sources;
        this.totalUneaten = Math.max(0, totalUneaten);
        this.totalOrderable = Math.max(0, totalOrderable);
        this.indexing = indexing;
        this.partial = partial;
    }

    public static FoodOrderListPacket from(FoodOrderSnapshot snapshot) {
        return new FoodOrderListPacket(snapshot.epoch(), snapshot.entries(),
                snapshot.sources(), snapshot.totalUneaten(),
                snapshot.totalOrderable(), snapshot.indexing(),
                snapshot.partial());
    }

    public static void send(ServerPlayer player, FoodOrderSnapshot snapshot) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                FoodOrderListPacket.from(snapshot));
    }

    public static void encode(FoodOrderListPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.epoch);
        buffer.writeVarInt(packet.entries.size());
        for (FoodOrderEntry entry : packet.entries) {
            buffer.writeItem(entry.stack());
            buffer.writeByte(entry.kind().wireId());
            buffer.writeBoolean(entry.recipeId() != null);
            if (entry.recipeId() != null) {
                buffer.writeResourceLocation(entry.recipeId());
            }
            buffer.writeUtf(toJson(entry.detail()), MAX_TEXT);
        }
        buffer.writeUtf(toJson(packet.sources), MAX_TEXT);
        buffer.writeVarInt(packet.totalUneaten);
        buffer.writeVarInt(packet.totalOrderable);
        buffer.writeBoolean(packet.indexing);
        buffer.writeBoolean(packet.partial);
    }

    public static FoodOrderListPacket decode(FriendlyByteBuf buffer) {
        long epoch = buffer.readVarLong();
        int count = Math.min(buffer.readVarInt(), MAX_ENTRIES);
        List<FoodOrderEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            var stack = buffer.readItem();
            FoodOrderKind kind = FoodOrderKind.fromWire(buffer.readByte());
            ResourceLocation recipeId = buffer.readBoolean()
                    ? buffer.readResourceLocation() : null;
            Component detail = fromJson(buffer.readUtf(MAX_TEXT));
            entries.add(new FoodOrderEntry(stack, kind, recipeId, detail));
        }
        Component sources = fromJson(buffer.readUtf(MAX_TEXT));
        int totalUneaten = buffer.readVarInt();
        int totalOrderable = buffer.readVarInt();
        boolean indexing = buffer.readBoolean();
        boolean partial = buffer.readBoolean();
        return new FoodOrderListPacket(epoch, entries, sources,
                totalUneaten, totalOrderable, indexing, partial);
    }

    public static void handle(FoodOrderListPacket packet,
                              Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FoodOrderClient.acceptList(packet)));
        context.get().setPacketHandled(true);
    }

    private static String toJson(Component component) {
        try {
            String json = Component.Serializer.toJson(component);
            return json == null ? "" : json;
        } catch (Throwable throwable) {
            return "";
        }
    }

    private static Component fromJson(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return Component.empty();
        }
        try {
            Component component = Component.Serializer.fromJson(json);
            return component == null ? Component.empty() : component;
        } catch (Throwable throwable) {
            return Component.literal(json);
        }
    }

    public long epoch() {
        return epoch;
    }

    public List<FoodOrderEntry> entries() {
        return entries;
    }

    public Component sources() {
        return sources;
    }

    public int totalUneaten() {
        return totalUneaten;
    }

    public int totalOrderable() {
        return totalOrderable;
    }

    public boolean indexing() {
        return indexing;
    }

    public boolean partial() {
        return partial;
    }
}
