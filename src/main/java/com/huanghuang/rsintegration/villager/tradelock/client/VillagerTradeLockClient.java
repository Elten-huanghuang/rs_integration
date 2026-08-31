package com.huanghuang.rsintegration.villager.tradelock.client;

import com.huanghuang.rsintegration.client.RecipeBrowserBridge;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockService;
import com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.List;

/** Debounces JEI mutations and sends complete snapshots after the play connection is ready. */
public final class VillagerTradeLockClient {
    private static final int DEBOUNCE_TICKS = 5;
    private static boolean dirty;
    private static int ticksUntilSync;
    private static int emiPollTicks;
    private static List<ItemStack> lastSnapshot = List.of();

    private VillagerTradeLockClient() {}

    public static void markDirty() {
        dirty = true;
        ticksUntilSync = DEBOUNCE_TICKS;
    }

    public static void onRuntimeAvailable() {
        markDirty();
    }

    public static void onRuntimeUnavailable() {
        dirty = false;
        ticksUntilSync = 0;
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        markDirty();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        dirty = false;
        ticksUntilSync = 0;
        emiPollTicks = 0;
        lastSnapshot = List.of();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) return;
        if (ModList.get().isLoaded("emi") && ++emiPollTicks >= 10) {
            emiPollTicks = 0;
            List<ItemStack> current = RecipeBrowserBridge.favoriteItems(
                    VillagerTradeLockService.MAX_BOOKMARKS);
            if (!sameFavorites(lastSnapshot, current)) {
                lastSnapshot = current;
                markDirty();
            }
        }
        if (!dirty) return;
        if (ticksUntilSync-- > 0) return;
        List<ItemStack> snapshot = RecipeBrowserBridge.favoriteItems(
                VillagerTradeLockService.MAX_BOOKMARKS);
        NetworkHandler.CHANNEL.sendToServer(new VillagerTradeLockSnapshotPacket(snapshot));
        lastSnapshot = snapshot;
        dirty = false;
    }

    private static boolean sameFavorites(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!ItemStack.isSameItemSameTags(left.get(index), right.get(index))) return false;
        }
        return true;
    }
}
