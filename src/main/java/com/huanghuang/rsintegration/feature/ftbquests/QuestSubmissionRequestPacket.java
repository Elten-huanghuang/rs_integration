package com.huanghuang.rsintegration.feature.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
                RSIntegrationMod.LOGGER.info(
                        "[RSI-FTBQuests] JEI request player={} quest={} preview={} repeat={}",
                        player.getGameProfile().getName(), Long.toHexString(packet.questId),
                        packet.preview, packet.repeatCount);
                try {
                    if (packet.preview) FtbQuestSubmissionService.preview(player, packet.questId,
                            packet.repeatCount);
                    else FtbQuestSubmissionService.execute(player, packet.questId,
                            packet.repeatCount);
                } catch (RuntimeException | LinkageError failure) {
                    RSIntegrationMod.LOGGER.error(
                            "[RSI-FTBQuests] JEI request failed quest={} preview={}",
                            Long.toHexString(packet.questId), packet.preview, failure);
                    Component message = Component.translatable("rsi.plan.failure.internal_error");
                    if (packet.preview) {
                        FtbQuestSubmissionService.sendPreviewFailure(player, packet.questId,
                                packet.repeatCount, null, message);
                    } else {
                        player.sendSystemMessage(message);
                    }
                }
            }
        });
        context.setPacketHandled(true);
    }
}
