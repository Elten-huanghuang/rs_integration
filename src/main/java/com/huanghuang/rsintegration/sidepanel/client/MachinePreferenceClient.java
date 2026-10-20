package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoriteKey;
import com.huanghuang.rsintegration.sidepanel.network.MachineAutomationBlockTogglePacket;
import com.huanghuang.rsintegration.sidepanel.network.MachineFavoriteTogglePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Client-neutral machine favorite and automation-block state. */
public final class MachinePreferenceClient {
    private static List<MachineFavoriteKey> favorites = List.of();
    private static List<MachineFavoriteKey> blocked = List.of();

    private MachinePreferenceClient() {}

    public static void update(List<MachineFavoriteKey> synchronizedFavorites,
                              List<MachineFavoriteKey> synchronizedBlocked) {
        favorites = List.copyOf(synchronizedFavorites);
        blocked = List.copyOf(synchronizedBlocked);
    }

    public static List<MachineFavoriteKey> favorites() {
        return favorites;
    }

    public static List<BindingInfo> getFavoriteMachines() {
        List<BindingInfo> result = new ArrayList<>(favorites.size());
        for (MachineFavoriteKey favorite : favorites) {
            for (BindingInfo info : BindingCache.getInstance().getAll()) {
                if (favorite.matches(info) && !isBlocked(info)) {
                    result.add(info);
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    public static boolean isFavorite(BindingInfo info) {
        return info != null && favorites.contains(MachineFavoriteKey.from(info));
    }

    public static boolean isBlocked(BindingInfo info) {
        return info != null && isBlocked(
                info.dim(), info.pos(), info.blockKey());
    }

    public static boolean isBlocked(BindingStorage.BindingEntry entry) {
        return entry != null && isBlocked(
                entry.dim(), entry.pos(), entry.blockKey());
    }

    public static boolean isBlocked(ResourceLocation dim,
                                    BlockPos pos,
                                    String blockKey) {
        return dim != null && pos != null && blockKey != null
                && blocked.contains(new MachineFavoriteKey(dim, pos, blockKey));
    }

    public static void requestFavoriteToggle(BindingInfo info) {
        if (info == null) return;
        MachineFavoriteKey key = MachineFavoriteKey.from(info);
        List<MachineFavoriteKey> optimistic = new ArrayList<>(favorites);
        if (!optimistic.remove(key)) optimistic.add(key);
        favorites = List.copyOf(optimistic);
        RSSidePanelNetworkHandler.CHANNEL.sendToServer(new MachineFavoriteTogglePacket(key));
    }

    public static void requestAutomationBlockToggle(BindingInfo info) {
        if (info == null) return;
        MachineFavoriteKey key = MachineFavoriteKey.from(info);
        List<MachineFavoriteKey> optimistic = new ArrayList<>(blocked);
        if (!optimistic.remove(key)) optimistic.add(key);
        blocked = List.copyOf(optimistic);
        RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                new MachineAutomationBlockTogglePacket(key));
    }

    public static void clear() {
        favorites = List.of();
        blocked = List.of();
    }
}
