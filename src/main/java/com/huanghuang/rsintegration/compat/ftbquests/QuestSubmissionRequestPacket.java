package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client request to execute one server-authoritative FTB Quest submission. */
public final class QuestSubmissionRequestPacket {

    private final long questId;
    private final boolean preview;

    public QuestSubmissionRequestPacket(long questId) {
        this(questId, true);
    }

    public QuestSubmissionRequestPacket(long questId, boolean preview) {
        this.questId = questId;
        this.preview = preview;
    }

    public static void encode(QuestSubmissionRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.questId);
        buf.writeBoolean(packet.preview);
    }

    public static QuestSubmissionRequestPacket decode(FriendlyByteBuf buf) {
        return new QuestSubmissionRequestPacket(buf.readLong(), buf.readBoolean());
    }

    public static void handle(QuestSubmissionRequestPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player != null) {
                if (packet.preview) FtbQuestSubmissionService.preview(player, packet.questId);
                else FtbQuestSubmissionService.execute(player, packet.questId);
            }
        });
        context.setPacketHandled(true);
    }
}
