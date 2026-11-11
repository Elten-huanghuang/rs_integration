package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.batch.CraftStatusSyncPacket;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageReferenceCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;

/** 仅同步本菜单可见的身份；不复制执行图和进度发布器。 */
public record RsMonitorScopePacket(int containerId, MonitorScopeState.Scope scope) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeUUID(scope.session());
        buf.writeVarLong(scope.revision());
        buf.writeBoolean(scope.network() != null);
        if (scope.network() != null) buf.writeNbt(StorageReferenceCodec.encode(scope.network()));
        if (scope.crafts().size() > CraftStatusSyncPacket.MAX_CRAFTS) {
            throw new IllegalArgumentException("too many monitor crafts");
        }
        buf.writeVarInt(scope.crafts().size());
        scope.crafts().forEach(buf::writeUUID);
    }

    public static RsMonitorScopePacket decode(FriendlyByteBuf buf) {
        int menu = buf.readVarInt();
        UUID session = buf.readUUID();
        long revision = buf.readVarLong();
        StorageReference network = buf.readBoolean()
                ? StorageReferenceCodec.decode(buf.readNbt()).orElseThrow() : null;
        int count = buf.readVarInt();
        if (count < 0 || count > CraftStatusSyncPacket.MAX_CRAFTS) {
            throw new IllegalArgumentException("invalid monitor craft count");
        }
        var ids = new ArrayList<UUID>(count);
        for (int i = 0; i < count; i++) ids.add(buf.readUUID());
        return new RsMonitorScopePacket(menu, new MonitorScopeState.Scope(session, revision, network, ids));
    }

    public static void handle(RsMonitorScopePacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> RsMonitorClient.accept(packet)));
        context.setPacketHandled(true);
    }
}
