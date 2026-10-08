package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 有界客户端缓存；按需刷新，退出服务器时清理，摘要过期时不展示旧数量。 */
@OnlyIn(Dist.CLIENT)
public final class UnifiedDiskTooltipClient {
    private record Identity(UUID world, UUID disk) {}
    private static final class Cached {
        long requested, received;
        boolean pending = true;
        UnifiedDiskSummary summary;
        UnifiedDiskFailure failure;
    }
    private static final Map<Identity, Cached> CACHE = new LinkedHashMap<>();
    private UnifiedDiskTooltipClient() {}

    public static UnifiedDiskTooltip.View view(ItemStack stack) {
        boolean expanded = Screen.hasShiftDown();
        UnifiedDiskItem item = (UnifiedDiskItem) stack.getItem();
        if (!item.isValid(stack)) return new UnifiedDiskTooltip.View(null, false, expanded);
        Identity identity = new Identity(item.worldId(stack), item.getId(stack));
        if (identity.world == null || stack.getTag().getInt("Format") != 1) {
            return new UnifiedDiskTooltip.View(null, true, expanded, UnifiedDiskFailure.invalidIdentity());
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null
                || !UnifiedDiskTooltipPackets.visible(minecraft.player, identity.world, identity.disk)) {
            return new UnifiedDiskTooltip.View(null, true, expanded);
        }
        long now = System.nanoTime();
        Cached cached = CACHE.get(identity);
        if (cached == null) {
            if (CACHE.size() >= 256) CACHE.remove(CACHE.keySet().iterator().next());
            cached = new Cached(); CACHE.put(identity, cached);
        }
        if (cached.requested == 0 || now - cached.requested >= 1_000_000_000L) {
            cached.requested = now;
            NetworkHandler.CHANNEL.sendToServer(new UnifiedDiskTooltipRequestPacket(identity.world, identity.disk));
        }
        if (cached.received == 0 || now - cached.received > 5_000_000_000L) {
            return new UnifiedDiskTooltip.View(null, false, expanded);
        }
        return new UnifiedDiskTooltip.View(cached.summary, !cached.pending && cached.summary == null, expanded, cached.failure);
    }

    public static void accept(UnifiedDiskTooltipResponsePacket packet) {
        Cached cached = CACHE.get(new Identity(packet.world(), packet.disk()));
        if (cached == null) return;
        cached.summary = packet.summary(); cached.failure = packet.failure();
        cached.pending = false; cached.received = System.nanoTime();
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { CACHE.clear(); }
}
