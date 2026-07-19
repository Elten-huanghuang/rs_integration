package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server acknowledgement for exactly one accepted side-panel operation. */
public final class RSSidePanelOperationResultPacket {
    public enum ErrorCode {
        NONE, INVALID_REQUEST, NO_NETWORK, NO_PERMISSION, NETWORK_STOPPED,
        ITEM_NOT_FOUND, INSUFFICIENT_AMOUNT, CURSOR_CONFLICT, CURSOR_FULL,
        CURSOR_DESYNC, NOTHING_TRANSFERRED, PARTIAL_TRANSFER, INTERNAL_ERROR
    }

    final long operationId;
    final boolean success;
    final UUID stackId;
    final int actualCount;
    final ErrorCode errorCode;

    public RSSidePanelOperationResultPacket(long operationId, boolean success,
                                            UUID stackId, int actualCount,
                                            ErrorCode errorCode) {
        if (operationId < 0) throw new IllegalArgumentException("operationId must be non-negative");
        if (actualCount < 0) throw new IllegalArgumentException("actualCount must be non-negative");
        this.operationId = operationId;
        this.success = success;
        this.stackId = stackId;
        this.actualCount = actualCount;
        this.errorCode = errorCode == null ? ErrorCode.INTERNAL_ERROR : errorCode;
    }

    public long operationId() { return operationId; }
    public boolean success() { return success; }
    public UUID stackId() { return stackId; }
    public int actualCount() { return actualCount; }
    public ErrorCode errorCode() { return errorCode; }

    void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(operationId);
        buf.writeBoolean(success);
        buf.writeBoolean(stackId != null);
        if (stackId != null) buf.writeUUID(stackId);
        buf.writeVarInt(actualCount);
        buf.writeEnum(errorCode);
    }

    static RSSidePanelOperationResultPacket decode(FriendlyByteBuf buf) {
        long id = buf.readVarLong();
        if (id < 0) throw new IllegalArgumentException("negative operationId");
        boolean success = buf.readBoolean();
        UUID stackId = buf.readBoolean() ? buf.readUUID() : null;
        int count = buf.readVarInt();
        if (count < 0) throw new IllegalArgumentException("negative actualCount");
        ErrorCode code = buf.readEnum(ErrorCode.class);
        if (buf.readableBytes() != 0) {
            throw new IllegalArgumentException("trailing side-panel operation result bytes");
        }
        return new RSSidePanelOperationResultPacket(id, success, stackId, count, code);
    }

    static void handle(RSSidePanelOperationResultPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> RSSidePanelClient.onOperationResult(packet));
        context.setPacketHandled(true);
    }
}
