package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Tells the submitting client to bookmark one still-missing quest item. */
public record QuestMissingBookmarkPacket(ItemStack stack, long missingCount) {
    public QuestMissingBookmarkPacket {
        stack = stack.copyWithCount(1);
        missingCount = Math.max(0L, missingCount);
    }

    public static void encode(QuestMissingBookmarkPacket packet, FriendlyByteBuf buf) {
        buf.writeItem(packet.stack);
        buf.writeVarLong(packet.missingCount);
    }

    public static QuestMissingBookmarkPacket decode(FriendlyByteBuf buf) {
        return new QuestMissingBookmarkPacket(buf.readItem(), buf.readVarLong());
    }

    public static void handle(QuestMissingBookmarkPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> QuestMissingBookmarkClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }
}
