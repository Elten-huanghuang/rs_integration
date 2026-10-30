package com.huanghuang.rsintegration.feature.foodorder.network;

import com.huanghuang.rsintegration.feature.foodorder.FoodOrderClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 下单状态机的结果提示。 */
public final class FoodOrderResultPacket {

    public static final int STARTED = 0;
    public static final int SUCCESS = 1;
    public static final int PARTIAL = 2;
    public static final int FAILED = 3;

    private final int code;
    private final int accepted;
    private final int skipped;
    private final String messageKey;
    private final String extra;

    public FoodOrderResultPacket(int code, int accepted, int skipped,
                                 String messageKey, String extra) {
        this.code = code;
        this.accepted = Math.max(0, accepted);
        this.skipped = Math.max(0, skipped);
        this.messageKey = messageKey == null ? "" : messageKey;
        this.extra = extra == null ? "" : extra;
    }

    public static FoodOrderResultPacket queued(int count) {
        return new FoodOrderResultPacket(STARTED, count, 0,
                "rsi.food_order.result.queued", Integer.toString(count));
    }

    public static FoodOrderResultPacket started(int count) {
        return new FoodOrderResultPacket(STARTED, count, 0,
                "rsi.food_order.result.started", Integer.toString(count));
    }

    public static FoodOrderResultPacket success(int count) {
        return new FoodOrderResultPacket(SUCCESS, count, 0,
                "rsi.food_order.result.success", Integer.toString(count));
    }

    public static FoodOrderResultPacket partial(int count, int skipped) {
        return new FoodOrderResultPacket(PARTIAL, count, skipped,
                "rsi.food_order.result.partial", Integer.toString(skipped));
    }

    public static FoodOrderResultPacket failed(String messageKey, String detail) {
        return new FoodOrderResultPacket(FAILED, 0, 0, messageKey,
                detail == null ? "" : detail);
    }

    public static void encode(FoodOrderResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeByte(packet.code);
        buffer.writeVarInt(packet.accepted);
        buffer.writeVarInt(packet.skipped);
        buffer.writeUtf(packet.messageKey, 128);
        buffer.writeUtf(packet.extra, 256);
    }

    public static FoodOrderResultPacket decode(FriendlyByteBuf buffer) {
        int code = buffer.readByte();
        int accepted = buffer.readVarInt();
        int skipped = buffer.readVarInt();
        String messageKey = buffer.readUtf(128);
        String extra = buffer.readUtf(256);
        return new FoodOrderResultPacket(code, accepted, skipped, messageKey, extra);
    }

    public static void handle(FoodOrderResultPacket packet,
                              Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FoodOrderClient.acceptResult(packet)));
        context.get().setPacketHandled(true);
    }

    public int code() {
        return code;
    }

    public int accepted() {
        return accepted;
    }

    public int skipped() {
        return skipped;
    }

    public String messageKey() {
        return messageKey;
    }

    public String extra() {
        return extra;
    }
}
