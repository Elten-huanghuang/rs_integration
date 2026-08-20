package com.huanghuang.rsintegration.villager.tradelock.client;

import com.huanghuang.rsintegration.mixin.jei.BookmarkOverlayAccessor;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockService;
import com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockSnapshotPacket;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Debounces JEI mutations and sends complete snapshots after the play connection is ready. */
public final class VillagerTradeLockClient {
    private static final int DEBOUNCE_TICKS = 5;
    private static boolean dirty;
    private static int ticksUntilSync;

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
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !dirty) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null || RSJeiPlugin.getRuntime() == null) return;
        if (ticksUntilSync-- > 0) return;
        List<ItemStack> snapshot = snapshot(RSJeiPlugin.getRuntime());
        NetworkHandler.CHANNEL.sendToServer(new VillagerTradeLockSnapshotPacket(snapshot));
        dirty = false;
    }

    static List<ItemStack> snapshot(IJeiRuntime runtime) {
        if (runtime == null || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        for (var element : ((BookmarkOverlayAccessor) overlay)
                .rsIntegration$getBookmarkList().getElements()) {
            Optional<ItemStack> item = element.getTypedIngredient()
                    .getIngredient(VanillaTypes.ITEM_STACK);
            item.filter(stack -> !stack.isEmpty()).ifPresent(stack -> {
                if (result.size() < VillagerTradeLockService.MAX_BOOKMARKS) {
                    ItemStack copy = stack.copy();
                    copy.setCount(1);
                    result.add(copy);
                }
            });
        }
        return result;
    }
}
