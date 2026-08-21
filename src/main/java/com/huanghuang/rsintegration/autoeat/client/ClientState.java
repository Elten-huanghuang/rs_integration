package com.huanghuang.rsintegration.autoeat.client;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.autoeat.network.UpdateAutoEatPreferencesPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class ClientState {

    private ClientState() {}

    public static AutoEatMode currentMode = AutoEatMode.DIVERSITY;
    public static ResourceLocation selectedItem;
    public static final Set<ResourceLocation> blacklistedItems = new HashSet<>();
    public static final Set<ResourceLocation> blacklistedEffects = new HashSet<>();

    public static void cycleMode() {
        currentMode = currentMode.next();
        syncPreferences();
    }

    public static void selectItem(ResourceLocation item) {
        selectedItem = item;
        syncPreferences();
    }

    public static void applyPreferences(AutoEatMode mode, ResourceLocation item) {
        currentMode = mode == null ? AutoEatMode.DIVERSITY : mode;
        selectedItem = item;
    }

    private static void syncPreferences() {
        NetworkHandler.CHANNEL.sendToServer(
                new UpdateAutoEatPreferencesPacket(currentMode, selectedItem));
    }

    /** Reset all client state when disconnecting from a server / leaving a world. */
    public static void reset() {
        currentMode = AutoEatMode.DIVERSITY;
        selectedItem = null;
        blacklistedItems.clear();
        blacklistedEffects.clear();
    }
}
