package com.huanghuang.rsintegration.feature.foodorder.network;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
    public static final int MAX_TOTAL_ENTRIES = 1_000_000;
    private static final int MAX_TEXT = 32767;

    private final long epoch;
    private final List<FoodOrderEntry> entries;
    private final Component sources;
    private final int totalUneaten;
    private final int totalOrderable;
    private final boolean indexing;
    private final boolean partial;
    private final int offset;
    private final int totalEntries;

    public FoodOrderListPacket(long epoch, List<FoodOrderEntry> entries,
                               Component sources, int totalUneaten,
                               int totalOrderable, boolean indexing,
                               boolean partial) {
        this(epoch, entries, sources, totalUneaten, totalOrderable, indexing, partial,
                0, entries == null ? 0 : entries.size());
    }

    private FoodOrderListPacket(long epoch, List<FoodOrderEntry> entries,
                                Component sources, int totalUneaten,
                                int totalOrderable, boolean indexing,
                                boolean partial, int offset, int totalEntries) {
        int size = entries == null ? 0 : entries.size();
        if (size > MAX_ENTRIES || totalEntries < 0 || totalEntries > MAX_TOTAL_ENTRIES
                || offset < 0 || offset > totalEntries || size > totalEntries - offset
                || (size == 0 && totalEntries != 0)) {
            throw new IllegalArgumentException("点餐列表分片范围无效");
        }
        this.epoch = epoch;
        this.entries = entries == null ? List.of() : List.copyOf(entries);
        this.sources = sources == null ? Component.empty() : sources;
        this.totalUneaten = Math.max(0, totalUneaten);
        this.totalOrderable = Math.max(0, totalOrderable);
        this.indexing = indexing;
        this.partial = partial;
        this.offset = offset;
        this.totalEntries = totalEntries;
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
        for (FoodOrderListPacket packet : split(snapshot,
                RSIntegrationConfig.FOOD_ORDER_MAX_LIST_ENTRIES.get())) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /** 旧列表上限现在只控制单包大小，不再丢弃目录尾部。 */
    public static List<FoodOrderListPacket> split(FoodOrderSnapshot snapshot, int batchSize) {
        int size = snapshot.entries().size();
        int limit = Math.max(1, Math.min(batchSize, MAX_ENTRIES));
        List<FoodOrderListPacket> packets = new ArrayList<>();
        for (int offset = 0; offset < size || (offset == 0 && size == 0); offset += limit) {
            packets.add(new FoodOrderListPacket(snapshot.epoch(),
                    snapshot.entries().subList(offset, Math.min(size, offset + limit)),
                    snapshot.sources(), snapshot.totalUneaten(), snapshot.totalOrderable(),
                    snapshot.indexing(), snapshot.partial(), offset, size));
        }
        return packets;
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
        buffer.writeVarInt(packet.offset);
        buffer.writeVarInt(packet.totalEntries);
    }

    public static FoodOrderListPacket decode(FriendlyByteBuf buffer) {
        long epoch = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            // 必须拒绝无效长度，不能少读条目后把剩余内容误当成元数据。
            throw new IllegalArgumentException("点餐列表分片条目数无效：" + count);
        }
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
        int offset = buffer.readVarInt();
        int totalEntries = buffer.readVarInt();
        return new FoodOrderListPacket(epoch, entries, sources,
                totalUneaten, totalOrderable, indexing, partial, offset, totalEntries);
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

    public int offset() {
        return offset;
    }

    public int totalEntries() {
        return totalEntries;
    }
}
