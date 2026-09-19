package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client request to execute one server-authoritative FTB Quest submission. */
public final class QuestSubmissionRequestPacket {

    private final long questId;
    private final boolean preview;
    private final int repeatCount;

    public QuestSubmissionRequestPacket(long questId) {
        this(questId, true, 1);
    }

    public QuestSubmissionRequestPacket(long questId, boolean preview) {
        this(questId, preview, 1);
    }

    public QuestSubmissionRequestPacket(long questId, boolean preview, int repeatCount) {
        this.questId = questId;
        this.preview = preview;
        this.repeatCount = Math.max(1, Math.min(repeatCount, 1024));
    }

    public static void encode(QuestSubmissionRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.questId);
        buf.writeBoolean(packet.preview);
        buf.writeVarInt(packet.repeatCount);
    }

    public static QuestSubmissionRequestPacket decode(FriendlyByteBuf buf) {
        return new QuestSubmissionRequestPacket(buf.readLong(), buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(QuestSubmissionRequestPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player != null) {
                if (packet.preview) FtbQuestSubmissionService.preview(player, packet.questId,
                        packet.repeatCount);
                else FtbQuestSubmissionService.execute(player, packet.questId,
                        packet.repeatCount);
            }
        });
        context.setPacketHandled(true);
    }
}
