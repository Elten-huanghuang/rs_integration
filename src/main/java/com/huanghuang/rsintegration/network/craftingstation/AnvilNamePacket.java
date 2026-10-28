package com.huanghuang.rsintegration.network.craftingstation;

import com.huanghuang.rsintegration.feature.craftingstation.AnvilTerminalState;
import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationAccess;
import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 同步 RS 虚拟铁砧中的重命名输入。 */
public final class AnvilNamePacket {
    /** 单字段上限：最多 50 个字符，且 UTF-8 不超过 150 字节（writeUtf 的长度上限）。 */
    private static final int MAX_CHARS = 50;
    private static final int MAX_BYTES = MAX_CHARS * 3;

    private final String name;

    public AnvilNamePacket(String name) {
        this.name = clampName(name);
    }

    public AnvilNamePacket(FriendlyByteBuf buf) {
        this(buf.readUtf(MAX_CHARS));
    }

    /**
     * 按字符数与 UTF-8 字节数双重截断。中文一个字符占 3 字节正好卡在上限，
     * 但代理对（如部分扩展汉字、表情符号）会占 4 字节，直接截断长度会超出 writeUtf 限制。
     */
    public static String clampName(String name) {
        if (name == null) return "";
        StringBuilder builder = new StringBuilder(Math.min(MAX_CHARS, name.length()));
        int bytes = 0;
        for (int index = 0; index < name.length(); ) {
            int codePoint = name.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            int byteCount = utf8Length(codePoint);
            if (builder.length() + charCount > MAX_CHARS || bytes + byteCount > MAX_BYTES) break;
            builder.appendCodePoint(codePoint);
            bytes += byteCount;
            index += charCount;
        }
        return builder.toString();
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name, MAX_CHARS);
    }

    /** 供同包的测试核对截断与编码结果。 */
    String name() {
        return name;
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof GridContainerMenu menu)
                    || menu.getGrid() == null || menu.getGrid().getGridType() != GridType.CRAFTING
                    || CraftingStationAccess.access(menu).rsi$getCraftingStationMode()
                    != CraftingStationMode.ANVIL) return;
            AnvilTerminalState state = (AnvilTerminalState)
                    CraftingStationAccess.access(menu).rsi$getCraftingStationState();
            state.setItemName(name);
            menu.broadcastChanges();
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.ANVIL_NAME,
                AnvilNamePacket.class, AnvilNamePacket::encode,
                AnvilNamePacket::new, AnvilNamePacket::handle);
    }
}
